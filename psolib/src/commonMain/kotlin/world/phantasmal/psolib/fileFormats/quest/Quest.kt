package world.phantasmal.psolib.fileFormats.quest

import mu.KotlinLogging
import world.phantasmal.core.*
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.BytecodeIr
import world.phantasmal.psolib.asm.InstructionSegment
import world.phantasmal.psolib.asm.OP_SET_EPISODE_V3_V4
import world.phantasmal.psolib.asm.dataFlowAnalysis.ControlFlowGraph
import world.phantasmal.psolib.asm.dataFlowAnalysis.FloorMapping
import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleSpawn
import world.phantasmal.psolib.asm.dataFlowAnalysis.getFloorMappings
import world.phantasmal.psolib.asm.dataFlowAnalysis.getParticleSpawns
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.compression.prs.prsCompress
import world.phantasmal.psolib.compression.prs.prsDecompress
import world.phantasmal.psolib.cursor.Cursor
import world.phantasmal.psolib.cursor.cursor

private val logger = KotlinLogging.logger {}

/**
 * Groups challenge mode specific data from the DAT file.
 */
data class QuestChallengeData(
    val cmRandomSpawns: List<DatCmRandomSpawn> = emptyList(),
    val cmMonsterMappings: List<DatCmMonsterMapping> = emptyList(),
    val cmConfigPool: List<DatCmConfigPool> = emptyList(),
)

class Quest(
    var id: Int,
    var language: Int,
    var name: String,
    var shortDescription: String,
    var longDescription: String,
    var episode: Episode,
    val objects: MutableList<QuestObject>,
    val npcs: MutableList<QuestNpc>,
    val events: List<DatEvent>,
    /**
     * (Partial) raw DAT data that can't be parsed yet by Phantasmal.
     */
    val datUnknowns: List<DatUnknown>,
    val challengeData: QuestChallengeData = QuestChallengeData(),
    var bytecodeIr: BytecodeIr,
    val shopItems: UIntArray,
    var floorMappings: List<FloorMapping> = emptyList(),
    var bytecodeOffset: Int? = null,
    /** Whether DC/GC text fields use Shift-JIS encoding (Japanese). */
    var shiftJis: Boolean = false,
    /** BIN format detected during parsing. Used to restore the correct version on save. */
    var binFormat: BinFormat = BinFormat.BB,
    /** Quest version detected during parsing (e.g. BB_V4, GC_V3). */
    var version: Version = Version.BB_V4,
    /**
     * `particle_v3` script invocations whose arguments could be statically resolved.
     */
    val particleSpawns: List<ParticleSpawn> = emptyList(),
)

/**
 * Core quest parsing from already-decompressed BIN/DAT cursors.
 *
 * Both cursors must be positioned at offset 0 and must not be shared across
 * concurrent calls.  The caller is responsible for creating fresh cursors for
 * each invocation (e.g. via [Buffer.cursor]).
 */
private fun parseBinDatFromDecompressed(
    binData: Cursor,
    datData: Cursor,
    lenient: Boolean,
    shiftJis: Boolean,
    version: Version,
): PwResult<Quest> {
    val result = PwResult.build<Quest>(logger)

    val bin = parseBin(binData, shiftJis)

    val dat = parseDat(datData)
    val objects = dat.objs.mapTo(mutableListOf()) { QuestObject(it.floorId, it.data) }
    // Initialize NPCs with random episode and correct it later.
    val npcs = dat.npcs.mapTo(mutableListOf()) { QuestNpc(Episode.I, it.floorId, it.data) }

    // Extract episode and map designations from byte code.
    var episode = Episode.I
    var floorMappings = emptyList<FloorMapping>()
    var particleSpawns: List<ParticleSpawn> = emptyList()


    val (hardEntryLabels, npcEntryLabels) = extractScriptEntryPoints(objects, npcs)
    val parseBytecodeResult = parseBytecode(
        bin.bytecode,
        bin.labelOffsets,
        hardEntryLabels,
        bin.stringEncoding,
        lenient,
        version,
        npcEntryLabels = npcEntryLabels,
    )

    result.addResult(parseBytecodeResult)

    if (parseBytecodeResult !is Success) {
        return result.failure()
    }

    val bytecodeIr = parseBytecodeResult.value

    if (bytecodeIr.segments.isEmpty()) {
        result.addProblem(Severity.Warning, "File contains no instruction labels.")
    } else {
        val instructionSegments = bytecodeIr.instructionSegments()

        var label0Segment: InstructionSegment? = null

        for (segment in instructionSegments) {
            if (0 in segment.labels) {
                label0Segment = segment
                break
            }
        }

        if (label0Segment != null) {
            episode = getEpisode(result, label0Segment)

            for (npc in npcs) {
                npc.episode = episode
            }

            // Build the CFG once and reuse it across all bytecode analyses.
            var cachedControlFlowGraph: ControlFlowGraph? = null
            val createControlFlowGraph = {
                cachedControlFlowGraph
                    ?: ControlFlowGraph.create(bytecodeIr)
                        .also { cachedControlFlowGraph = it }
            }

            // Resolve the effective client mapping for every floor referenced by DAT data or
            // quest bytecode. Floors without an explicit designation use the client's
            // episode-default floor table.
            val usedFloorIds = buildSet {
                objects.forEach { add(it.floorId) }
                npcs.forEach { add(it.floorId) }
                dat.events.forEach { add(it.floorId) }
            }
            floorMappings = getFloorMappings(
                instructionSegments = instructionSegments,
                usedFloorIds = usedFloorIds,
                version = version,
                createControlFlowGraph = createControlFlowGraph,
            )

            // Extract `particle_v3` spawn sites from all instruction segments.
            particleSpawns = getParticleSpawns(instructionSegments, createControlFlowGraph)

            // Resolve the actual map area used to interpret each NPC. The DAT floor remains intact.
            if (floorMappings.isNotEmpty()) {
                for (npc in npcs) {
                    /*
                     * Match the mapping's logical floor to NPC.floorId.
                     *
                     * Reason:
                     * - FloorMapping.floorId represents the original logical floor.
                     * - NPC.floorId stores the original DAT floor ID.
 * - FloorMapping.mapAreaId is derived from mapId and may differ
                     *   from floorId when multiple floors share the same map.
                     *
                     * Example:
                     *   Floor 17 -> Tower (mapId 35), variant 0
                     *   Floor 16 -> Tower (mapId 35), variant 1
                     */
                    val mapping = floorMappings.find { it.floorId == npc.floorId }
                    if (mapping != null) {
                        npc.mapAreaId = mapping.mapAreaId
                        // NPC type IDs are interpreted using the effective map's episode. An EP4
                        // quest may designate an EP2 map (for example Lost SON HOPKINS uses Lab).
                        npc.episode = mapping.mapEpisode ?: episode
                    }
                }
            }
        } else {
            result.addProblem(Severity.Warning, "No instruction segment for label 0 found.")
        }
    }

    return result.success(Quest(
        id = bin.questId,
        language = bin.language,
        name = bin.questName,
        shortDescription = bin.shortDescription,
        longDescription = bin.longDescription,
        episode,
        objects,
        npcs,
        events = dat.events,
        datUnknowns = dat.unknowns,
        challengeData = QuestChallengeData(
            cmRandomSpawns = dat.cmRandomSpawns,
            cmMonsterMappings = dat.cmMonsterMappings,
            cmConfigPool = dat.cmConfigPool,
        ),
        bytecodeIr,
        shopItems = bin.shopItems,
        floorMappings,
        bytecodeOffset = bin.bytecodeOffset,
        shiftJis = bin.shiftJis,
        binFormat = bin.format,
        version = version,
        particleSpawns = particleSpawns,
    ))
}

/**
 * High level quest parsing function that delegates to [parseBin] and [parseDat].
 */
fun parseBinDatToQuest(
    binCursor: Cursor,
    datCursor: Cursor,
    lenient: Boolean = false,
    compressed: Boolean = true,
    shiftJis: Boolean = false,
    version: Version = Version.BB_V4,
): PwResult<Quest> {
    val result = PwResult.build<Quest>(logger)

    // Decompress and parse files.
    val binData: Cursor

    if (compressed) {
        val binDecompressed = prsDecompress(binCursor)
        result.addResult(binDecompressed)

        if (binDecompressed !is Success) {
            return result.failure()
        }

        binData = binDecompressed.value
    } else {
        binData = binCursor
    }

    val datData: Cursor

    if (compressed) {
        val datDecompressed = prsDecompress(datCursor)
        result.addResult(datDecompressed)

        if (datDecompressed !is Success) {
            return result.failure()
        }

        datData = datDecompressed.value
    } else {
        datData = datCursor
    }

    val innerResult = parseBinDatFromDecompressed(binData, datData, lenient, shiftJis, version)
    result.addResult(innerResult)

    return if (innerResult is Success) result.success(innerResult.value) else result.failure()
}

class BinDatQuestData(
    val quest: Quest,
    val compressed: Boolean,
)

/**
 * Detects whether the BIN data is PRS-compressed or raw by checking the first 4 bytes.
 * An uncompressed BIN starts with its bytecode offset:
 *   468  = DC/GC (V0_V2 and V3)
 *   916  = PC NTE (shorter header variant — 4 bytes less than standard PC)
 *   920  = PC V2 (standard)
 *   4652 = Blue Burst
 */
private fun looksLikeUncompressedBin(binCursor: Cursor): Boolean {
    if (binCursor.bytesLeft < 4) return false
    val firstInt = binCursor.int()
    binCursor.seekStart(0)
    return firstInt == 468 || firstInt == 916 || firstInt == 920 || firstInt == 4652
}

/**
 * Ordered list of [Version] candidates for a given [BinFormat].
 *
 * The first entry is the most-likely version and acts as the tie-breaking
 * rank: when two candidates produce identical parse quality scores the one
 * that appears earlier in this list wins.
 */
private fun versionsFor(format: BinFormat): List<Version> = when (format) {
    BinFormat.DC_GC -> listOf(Version.GC_V3, Version.DC_V2, Version.GC_NTE, Version.DC_V1, Version.DC_NTE)
    BinFormat.PC -> listOf(Version.PC_V2, Version.PC_NTE)
    BinFormat.BB -> listOf(Version.BB_V4)
}

private data class CandidateScore(
    val version: Version,
    val rankIndex: Int,
    val threw: Boolean,
    val invalidCount: Int,
    val unknownCount: Int,
    val totalNops: Int,
    val parseResult: PwResult<Quest>?,
)

/**
 * Attempts to parse already-decompressed BIN/DAT data for [version], converting any thrown
 * exception to a failed score.
 */
private fun scoreCandidate(
    binBuffer: Buffer,
    datBuffer: Buffer,
    lenient: Boolean,
    shiftJis: Boolean,
    version: Version,
    rankIndex: Int,
): CandidateScore {
    return try {
        val r = parseBinDatFromDecompressed(
            binBuffer.cursor(),
            datBuffer.cursor(),
            lenient,
            shiftJis,
            version,
        )
        if (r is Success) {
            val segs = r.value.bytecodeIr.instructionSegments()
            val invalidCount = segs.sumOf { seg -> seg.instructions.count { !it.valid } }
            val unknownCount = segs.sumOf { seg -> seg.instructions.count { it.opcode.mnemonic.startsWith("unknown_") } }
            val totalNops = segs.sumOf { seg -> seg.instructions.count { it.opcode.mnemonic == "nop" } }
            CandidateScore(version, rankIndex, threw = false, invalidCount, unknownCount, totalNops, r)
        } else {
            CandidateScore(version, rankIndex, threw = false, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, r)
        }
    } catch (e: Exception) {
        CandidateScore(version, rankIndex, threw = true, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, null)
    }
}

/**
 * Comparator that picks the candidate with the best (lowest) quality score.
 *
 * Priority: did not throw > fewest invalid instructions > fewest unknown opcodes >
 * lowest rank index (i.e. position in [versionsFor]).
 *
 * totalNops was intentionally removed as a tiebreaker: misaligned V0_V2 parses can
 * produce zero nops by accident, causing DC_V1 to beat GC_V3 even for GC quests.
 * rankIndex is a deterministic tiebreaker — versionsFor() puts GC_V3 first for
 * BinFormat.DC_GC, so when (threw, invalidCount, unknownCount) tie, GC_V3 wins.
 */
private val CANDIDATE_COMPARATOR: Comparator<CandidateScore> =
    compareBy({ if (it.threw) 1 else 0 }, { it.invalidCount }, { it.unknownCount }, { it.rankIndex })

/**
 * Attempts to parse BIN/DAT data, catching any exceptions and converting them to a [Failure].
 */
private fun tryParseBinDat(
    binCursor: Cursor,
    datCursor: Cursor,
    lenient: Boolean,
    compressed: Boolean,
    shiftJis: Boolean,
): PwResult<BinDatQuestData> =
    try {
        val result = parseBinDatToQuest(binCursor, datCursor, lenient, compressed, shiftJis)

        if (result is Success) {
            Success(BinDatQuestData(result.value, compressed), result.problems)
        } else {
            result as Failure
        }
    } catch (e: Exception) {
        PwResult.build<BinDatQuestData>(logger)
            .addProblem(Severity.Error, "Couldn't parse file.", cause = e)
            .failure()
    }

/**
 * Auto-detects whether BIN/DAT files are PRS-compressed or raw, then parses.
 *
 * When [version] is null the function tries all candidate versions for the detected
 * [BinFormat] (or [binFormatHint] if provided) and picks the one that produces the
 * fewest parse errors (strict mode). If every candidate fails strict parsing the
 * default candidate for the format is retried with [lenient] = true and a warning
 * is added.
 *
 * When [version] is non-null only that version is attempted (no ranking).
 *
 * [binFormatHint] overrides the BIN-header-based format detection when the caller
 * already knows the platform from an outer container (e.g. the QST header).  This
 * prevents a mis-match when a BIN file has an unexpected bytecode offset
 * (e.g. a PC-format BIN shipped inside a BB QST).
 */
fun parseBinDatToQuestAutoDetect(
    binCursor: Cursor,
    datCursor: Cursor,
    lenient: Boolean = false,
    shiftJis: Boolean = false,
    version: Version? = null,
    binFormatHint: BinFormat? = null,
): PwResult<BinDatQuestData> {
    val compressed = !looksLikeUncompressedBin(binCursor)

    // --- Decompress once ---
    val binBuffer: Buffer
    val datBuffer: Buffer
    val decompressProblems = mutableListOf<Problem>()

    if (compressed) {
        val binDecompressed = prsDecompress(binCursor)
        decompressProblems.addAll(binDecompressed.problems)
        if (binDecompressed !is Success) {
            // Compression detection may be wrong — try the other mode with the original cursors.
            binCursor.seekStart(0)
            datCursor.seekStart(0)
            val retryResult = tryParseBinDat(binCursor, datCursor, lenient, compressed = false, shiftJis)
            if (retryResult is Success) return retryResult

            return PwResult.build<BinDatQuestData>(logger)
                .addProblem(Severity.Error, "Parsing as compressed (auto-detected) failed: decompression error.")
                .addResult(retryResult)
                .failure()
        }
        binBuffer = binDecompressed.value.buffer()

        val datDecompressed = prsDecompress(datCursor)
        decompressProblems.addAll(datDecompressed.problems)
        if (datDecompressed !is Success) {
            return PwResult.build<BinDatQuestData>(logger)
                .addProblem(Severity.Error, "DAT decompression failed.")
                .failure()
        }
        datBuffer = datDecompressed.value.buffer()
    } else {
        binBuffer = binCursor.buffer()
        datBuffer = datCursor.buffer()
    }

    // --- Determine BinFormat: prefer the caller-supplied hint, else detect from bytes ---
    val binFormat = binFormatHint ?: run {
        val c = binBuffer.cursor()
        if (c.bytesLeft < 4) BinFormat.BB else when (c.int()) {
            468 -> BinFormat.DC_GC
            916, 920 -> BinFormat.PC  // 916 = PC NTE (shorter header variant)
            else -> BinFormat.BB
        }
    }

    // --- Version candidates ---
    val candidates = if (version != null) listOf(version) else versionsFor(binFormat)

    val scores = candidates.mapIndexed { idx, v ->
        scoreCandidate(binBuffer, datBuffer, lenient, shiftJis, v, idx)
    }

    val winner = scores.minWithOrNull(CANDIDATE_COMPARATOR)!!

    val winnerResult = winner.parseResult

    if (!winner.threw && winnerResult is Success) {
        val result = PwResult.build<BinDatQuestData>(logger)
        for (p in decompressProblems) result.addProblem(p)
        result.addResult(winnerResult)
        return result.success(BinDatQuestData(winnerResult.value, compressed))
    }

    // All candidates failed.
    if (lenient) {
        // Caller already requested lenient; scoring already used it. If all candidates still
        // threw, this is a genuine parse failure — do not retry.
        val result = PwResult.build<BinDatQuestData>(logger)
        for (p in decompressProblems) result.addProblem(p)
        if (winnerResult != null) result.addResult(winnerResult)
        return result.failure()
    }

    // All candidates failed strict — fall back to lenient with the format default.
    val fallbackVersion = candidates.first()
    val fallbackResult = try {
        parseBinDatFromDecompressed(
            binBuffer.cursor(),
            datBuffer.cursor(),
            lenient = true,
            shiftJis,
            fallbackVersion,
        )
    } catch (e: Exception) {
        PwResult.build<Quest>(logger)
            .addProblem(Severity.Error, "Couldn't parse file.", cause = e)
            .failure()
    }

    val result = PwResult.build<BinDatQuestData>(logger)
    for (p in decompressProblems) result.addProblem(p)
    result.addProblem(
        Severity.Warning,
        "No version candidate strict-parsed; falling back to lenient $fallbackVersion.",
    )

    if (fallbackResult !is Success) {
        result.addResult(fallbackResult)
        return result.failure()
    }

    result.addResult(fallbackResult)
    return result.success(BinDatQuestData(fallbackResult.value, compressed))
}

class QuestData(
    val quest: Quest,
    val version: Version,
    val online: Boolean,
)

/**
 * High level .qst parsing function that delegates to [parseQst], [parseBin] and [parseDat].
 */
fun parseQstToQuest(cursor: Cursor, lenient: Boolean = false): PwResult<QuestData> {
    val result = PwResult.build<QuestData>(logger)

    // Extract contained .dat and .bin files.
    val qstResult = parseQst(cursor)
    result.addResult(qstResult)

    if (qstResult !is Success) {
        return result.failure()
    }

    val version = qstResult.value.version
    val online = qstResult.value.online
    val files = qstResult.value.files
    var datFile: QstContainedFile? = null
    var binFile: QstContainedFile? = null
    var datCount = 0
    var binCount = 0

    for (file in files) {
        val fileName = file.filename.trim().lowercase()

        if (fileName.endsWith(".dat")) {
            if (++datCount > 1) result.addProblem(Severity.Warning, "QST contains multiple DAT files; using last: ${file.filename.trim()}.")
            datFile = file
        } else if (fileName.endsWith(".bin")) {
            if (++binCount > 1) result.addProblem(Severity.Warning, "QST contains multiple BIN files; using last: ${file.filename.trim()}.")
            binFile = file
        }
    }

    if (datFile == null) {
        return result.addProblem(Severity.Error, "File contains no DAT file.").failure()
    }

    if (binFile == null) {
        return result.addProblem(Severity.Error, "File contains no BIN file.").failure()
    }

    val binBase = binFile.filename.trim().substringBeforeLast('.')
    val shiftJis = binBase.endsWith("_j")

    // Derive the expected BinFormat from the QST header version so that the
    // candidate-ranking in parseBinDatToQuestAutoDetect is constrained to the
    // right platform.  This prevents a PC-format BIN embedded in a BB QST from
    // being mis-detected as PC_V2 instead of BB_V4.
    val binFormatFromVersion = when (version) {
        Version.BB_V4 -> BinFormat.BB
        Version.PC_NTE, Version.PC_V2 -> BinFormat.PC
        else -> BinFormat.DC_GC
    }

    val questResult = parseBinDatToQuestAutoDetect(
        binFile.data.cursor(),
        datFile.data.cursor(),
        lenient,
        shiftJis = shiftJis,
        binFormatHint = binFormatFromVersion,
    )
    result.addResult(questResult)

    if (questResult !is Success) {
        return result.failure()
    }

    return result.success(QuestData(
        questResult.value.quest,
        version,
        online,
    ))
}

/**
 * Defaults to episode I.
 */
private fun getEpisode(rb: PwResultBuilder<*>, func0Segment: InstructionSegment): Episode {
    val setEpisode = func0Segment.instructions.find {
        it.opcode == OP_SET_EPISODE_V3_V4
    }

    if (setEpisode == null) {
        logger.debug { "Function 0 has no set_episode instruction." }
        return Episode.I
    }

    if (setEpisode.args.isEmpty()) {
        rb.addProblem(Severity.Warning, "set_episode instruction in function 0 has no arguments.")
        return Episode.I
    }

    return when (val episode = setEpisode.args[0].value) {
        0 -> Episode.I
        1 -> Episode.II
        2 -> Episode.IV
        else -> {
            rb.addProblem(
                Severity.Warning,
                "Unknown episode $episode in function 0 set_episode instruction."
            )
            Episode.I
        }
    }
}

/**
 * Returns a pair of (hardEntryLabels, npcEntryLabels).
 *
 * Hard entry labels are unconditional instruction entry points: label 0 and any script labels
 * embedded in objects.
 *
 * NPC entry labels come from friendly NPC scriptLabel fields. They are treated as instruction
 * entry points only when they do not coincide with a data or string segment already discovered
 * via the hard entry labels (see [parseBytecode]).
 */
private fun extractScriptEntryPoints(
    objects: List<QuestObject>,
    npcs: List<QuestNpc>,
): Pair<Set<Int>, Set<Int>> {
    val hardEntryPoints = mutableSetOf(0)
    val npcEntryPoints = mutableSetOf<Int>()

    objects.forEach { obj ->
        obj.scriptLabel?.let(hardEntryPoints::add)
        obj.scriptLabel2?.let(hardEntryPoints::add)
    }

    npcs.forEach { npc ->
        // Enemy NPCs store unrelated data at the scriptLabel field offset (it is a combat
        // parameter, not a code label). Only add scriptLabel for non-enemy (friendly) NPCs.
        if (!npc.type.enemy) {
            npcEntryPoints.add(npc.scriptLabel)
        }
    }

    return hardEntryPoints to npcEntryPoints
}

/**
 * Returns a .bin and .dat file in that order.
 */
fun writeQuestToBinDat(quest: Quest, version: Version): Pair<Buffer, Buffer> {
    val dat = writeDat(DatFile(
        objs = quest.objects.mapTo(mutableListOf()) { DatEntity(it.floorId, it.data) },
        npcs = quest.npcs.mapTo(mutableListOf()) { DatEntity(it.floorId, it.data) },
        events = quest.events,
        unknowns = quest.datUnknowns,
        cmRandomSpawns = quest.challengeData.cmRandomSpawns,
        cmMonsterMappings = quest.challengeData.cmMonsterMappings,
        cmConfigPool = quest.challengeData.cmConfigPool,
    ))

    val binFormat = when (version) {
        Version.DC_NTE, Version.DC_V1, Version.DC_V2,
        Version.GC_NTE, Version.GC_V3 -> BinFormat.DC_GC
        Version.PC_NTE, Version.PC_V2 -> BinFormat.PC
        Version.BB_V4 -> BinFormat.BB
    }

    val (bytecode, labelOffsets) = writeBytecode(
        quest.bytecodeIr,
        binStringEncoding(binFormat, quest.shiftJis),
        version,
    )

    val bin = writeBin(BinFile(
        binFormat,
        quest.id,
        quest.language,
        quest.name,
        quest.shortDescription,
        quest.longDescription,
        bytecode,
        labelOffsets,
        quest.shopItems,
        quest.bytecodeOffset,
        shiftJis = quest.shiftJis,
    ))

    return Pair(bin, dat)
}

/**
 * Creates a .qst file from [quest].
 */
fun writeQuestToQst(
    quest: Quest,
    filename: String,
    version: Version,
    online: Boolean,
    compressed: Boolean = true,
): Buffer {
    val (bin, dat) = writeQuestToBinDat(quest, version)

    val baseFilename = (filenameBase(filename) ?: filename).take(11)
    val questName = quest.name.take(if (version == Version.BB_V4) 23 else 31)

    fun maybeCompress(buf: Buffer): Buffer =
        if (compressed) prsCompress(buf.cursor()).buffer() else buf

    return writeQst(QstContent(
        version,
        online,
        files = listOf(
            QstContainedFile(
                id = quest.id,
                filename = "$baseFilename.dat",
                questName = questName,
                data = maybeCompress(dat),
            ),
            QstContainedFile(
                id = quest.id,
                filename = "$baseFilename.bin",
                questName = questName,
                data = maybeCompress(bin),
            ),
        ),
    ))
}
