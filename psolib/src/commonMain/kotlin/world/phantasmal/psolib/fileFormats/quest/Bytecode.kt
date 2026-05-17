package world.phantasmal.psolib.fileFormats.quest

import mu.KotlinLogging
import world.phantasmal.core.PwResult
import world.phantasmal.core.Severity
import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.asm.*
import world.phantasmal.psolib.asm.ArgsMode
import world.phantasmal.psolib.asm.dataFlowAnalysis.ControlFlowGraph
import world.phantasmal.psolib.asm.dataFlowAnalysis.getRegisterValue
import world.phantasmal.psolib.asm.dataFlowAnalysis.getStackValue
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.BufferCursor
import world.phantasmal.psolib.cursor.Cursor
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.encoding.decodeShiftJis
import world.phantasmal.psolib.encoding.encodeShiftJis
import kotlin.math.min

private fun readShiftJisFromCursor(
    cursor: Cursor,
    byteLength: Int,
    nullTerminated: Boolean,
    dropRemaining: Boolean,
): String {
    val available = min(byteLength, cursor.bytesLeft)
    if (available <= 0) return ""
    val bytes = cursor.byteArray(available)
    val end = if (nullTerminated) {
        val idx = bytes.indexOf(0)
        if (idx >= 0) idx else bytes.size
    } else {
        bytes.size
    }
    val decoded = decodeShiftJis(bytes.copyOf(end))
    if (!dropRemaining && nullTerminated && end < bytes.size) {
        // Rewind to just past the null terminator so the caller sees the remaining bytes.
        val consumed = end + 1
        cursor.seek(consumed - bytes.size)
    }
    return decoded
}

/**
 * Encode [str] as Shift-JIS, write up to `byteLength` bytes (always null-terminated when there's
 * room), and pad with zeros to exactly `byteLength`. Never splits a multi-byte Shift-JIS sequence.
 */
private fun writeShiftJisInto(
    cursor: world.phantasmal.psolib.cursor.WritableCursor,
    str: String,
    byteLength: Int,
) {
    if (byteLength <= 0) return
    val encoded = encodeShiftJis(str)
    var len = min(byteLength - 1, encoded.size)
    // Avoid an orphaned Shift-JIS lead byte at the boundary.
    if (len > 0) {
        val lead = encoded[len - 1].toInt() and 0xFF
        val isLead = lead in 0x81..0x9F || lead in 0xE0..0xFC
        if (isLead) {
            // Count consecutive lead bytes preceding position to determine parity.
            var leadRun = 0
            var i = len - 1
            while (i >= 0) {
                val b = encoded[i].toInt() and 0xFF
                if (b in 0x81..0x9F || b in 0xE0..0xFC) { leadRun++; i-- } else break
            }
            if (leadRun % 2 == 1) len--
        }
    }
    for (i in 0 until len) cursor.writeByte(encoded[i])
    for (i in 0 until (byteLength - len)) cursor.writeByte(0)
}

private val logger = KotlinLogging.logger {}

/**
 * Opcodes that unconditionally transfer control elsewhere and never fall through to the next
 * instruction.  Mirrors newserv's F_TERMINATOR flag (ret=0x01, exit=0x03, jmp=0x28).
 *
 * For pre-BB versions (V0_V2 and GC_V3), the segment walker stops consuming bytes as soon as
 * it has parsed one of these opcodes. Any trailing bytes before the next label boundary are
 * typically padding or garbage bytes (e.g., 0x9F) that are not valid instructions and must not
 * be interpreted as additional instructions — they produce spurious unknown_xx entries in the
 * parsed IR that don't appear in newserv's disassembly.
 *
 * BB_V4 quests are excluded: in BB, bytes after terminators are valid (though unreachable) known
 * opcodes such as ret/nop that serve as alignment padding. Stopping early in BB would break the
 * byte-for-byte round-trip (parse → disassemble → assemble) because the assembler folds unlabeled
 * trailing instructions back into the preceding labeled segment, producing a different IR.
 */
internal val TERMINATOR_OPCODES = setOf(
    0x01, // ret
    0x03, // exit
    0x28, // jmp  (unconditional)
)

private const val MAX_TOTAL_NOPS = 20
private const val MAX_SEQUENTIAL_NOPS = 10
private const val MAX_STACK_POP_WITHOUT_PRECEDING_PUSH_RATIO = 0.2
private const val MAX_UNKNOWN_LABEL_RATIO = 0.2
private const val MAX_LABEL_VALUES = 20

internal val SEGMENT_PRIORITY = mapOf(
    SegmentType.Instructions to 2,
    SegmentType.String to 1,
    SegmentType.Data to 0,
)

/**
 * These functions are built into the client and can optionally be overridden on BB. Other versions
 * require you to always specify them in the script.
 */
val BUILTIN_FUNCTIONS = setOf(
    60,
    70,
    80,
    90,
    100,
    110,
    120,
    130,
    140,
    800,
    810,
    820,
    830,
    840,
    850,
    860,
    900,
    910,
    920,
    930,
    940,
    950,
    960,
)

/**
 * Parses bytecode into bytecode IR.
 *
 * @param entryLabels Hard entry points (label 0, object script labels, set_floor_handler targets,
 *   etc.) that are unconditionally treated as instruction segment starts.
 * @param npcEntryLabels Soft entry points from NPC scriptLabel fields. These are treated as
 *   instruction starts only when the bytecode label table position they resolve to has not already
 *   been classified as a data or string segment by following explicit dlabel/slabel references
 *   from [entryLabels]. This prevents friendly-NPC scriptLabel values that happen to coincide with
 *   enemy data labels (AttackData, PlayerStats, etc.) from causing those data segments to be
 *   parsed as instructions.
 */
fun parseBytecode(
    bytecode: Buffer,
    labelOffsets: IntArray,
    entryLabels: Set<Int>,
    stringEncoding: BytecodeStringEncoding,
    lenient: Boolean,
    version: Version = Version.BB_V4,
    npcEntryLabels: Set<Int> = emptySet(),
): PwResult<BytecodeIr> {
    val cursor = BufferCursor(bytecode)
    val labelHolder = LabelHolder(labelOffsets)
    val result = PwResult.build<BytecodeIr>(logger)
    val offsetToSegment = mutableMapOf<Int, Segment>()

    // First pass: parse from hard entry points. This establishes correct Data/String
    // classifications for labels referenced by dlabel/slabel instructions.
    val filteredEntryLabels = entryLabels.filter { labelHolder.hasLabel(it) }.associateWith { SegmentType.Instructions }
    findAndParseSegments(
        cursor,
        labelHolder,
        // Skip any entry labels not registered in the bytecode label table to avoid
        // spurious parse attempts from stale or out-of-range label values.
        filteredEntryLabels,
        offsetToSegment,
        lenient,
        stringEncoding,
        version,
    )

    // Second pass: add NPC script labels only for positions not already classified as
    // Data or String. If a label's offset is already a data/string segment (established by
    // an explicit dlabel/slabel reference in the first pass), the NPC field is storing a
    // coincidental numeric value rather than a real code pointer, and must be ignored.
    if (npcEntryLabels.isNotEmpty()) {
        val npcInstructionLabels = npcEntryLabels.filter { label ->
            val info = labelHolder.getInfo(label) ?: return@filter false
            val existing = offsetToSegment[info.offset]
            existing == null || existing is InstructionSegment
        }.associateWith { SegmentType.Instructions }

        if (npcInstructionLabels.isNotEmpty()) {
            // Collect the byte offsets of only those NPC entry labels that point to positions
            // currently UNCLAIMED (not yet parsed by the first pass).  An offset that already
            // has an InstructionSegment was discovered by the primary entry-point traversal and
            // is therefore a legitimate code region — we must not subject it to the demote
            // heuristic.  Only freshly-unclaimed offsets are candidates for misclassification:
            // they arise when an NPC's scriptLabel field happens to hold a numeric value that
            // coincides with a label whose underlying bytes are data, not code.
            val npcUnclaimedOffsets = npcInstructionLabels.keys.mapNotNull { label ->
                val info = labelHolder.getInfo(label) ?: return@mapNotNull null
                if (offsetToSegment[info.offset] == null) info.offset else null
            }.toHashSet()

            findAndParseSegments(
                cursor,
                labelHolder,
                npcInstructionLabels,
                offsetToSegment,
                lenient,
                stringEncoding,
                version,
            )

            // Demote any instruction segment that was freshly created for an unclaimed NPC
            // entry offset and that fails the isLikelyInstructionSegment heuristic.  Such
            // segments correspond to NPC scriptLabel values that happen to coincide with
            // data/padding regions — the bytes decode as a plausible-looking instruction stream
            // but contain truly-unknown opcodes that are not present in the PSO opcode table.
            for (npcOffset in npcUnclaimedOffsets) {
                val segment = offsetToSegment[npcOffset] as? InstructionSegment ?: continue
                if (!isLikelyInstructionSegment(segment.instructions, labelHolder) {}) {
                    val endOffset = labelHolder.getInfo(segment.labels.first())?.next?.offset ?: cursor.size
                    cursor.seekStart(npcOffset)
                    parseDataSegment(offsetToSegment, cursor, endOffset, segment.labels)
                }
            }
        }
    }

    // Final narrow demote: any tiny InstructionSegment (< 16 bytes) whose VERY FIRST
    // instruction is unknown_xx (newserv also can't decode it) is reclassified as data.
    // Catches event-quest data blobs reached via stale or coincidental label values
    // (e.g. q312-bb-{e,j}). Narrow enough to avoid regressing legitimate code segments.
    val createdOffsets = offsetToSegment.keys.toList()
    for (off in createdOffsets) {
        val segment = offsetToSegment[off] as? InstructionSegment ?: continue
        val firstInsn = segment.instructions.firstOrNull() ?: continue
        val firstIsUnknown = !firstInsn.opcode.known ||
            (firstInsn.opcode.mnemonic.startsWith("unknown_") &&
                firstInsn.opcode.code != 0xDE && firstInsn.opcode.code != 0xFB)
        if (!firstIsUnknown) continue
        val firstLabel = segment.labels.firstOrNull() ?: continue
        val endOffset = labelHolder.getInfo(firstLabel)?.next?.offset ?: cursor.size
        if (endOffset - off >= 16) continue
        cursor.seekStart(off)
        parseDataSegment(offsetToSegment, cursor, endOffset, segment.labels)
    }

    val segments: MutableList<Segment> = mutableListOf()

    // Put segments in an array and try to parse leftover segments as instructions segments. When a
    // segment can't be parsed as instructions, fall back to parsing it as a data segment.
    var offset = 0

    while (offset < cursor.size) {
        var segment: Segment? = offsetToSegment[offset]

        // If we have a segment, add it. Otherwise create a new data segment.
        if (segment == null) {
            val labels = labelHolder.getLabels(offset)
            var endOffset: Int

            if (labels == null) {
                endOffset = cursor.size

                for (label in labelHolder.labels) {
                    if (label.offset > offset) {
                        endOffset = label.offset
                        break
                    }
                }
            } else {
                val info = labelHolder.getInfo(labels[0])!!
                endOffset = info.next?.offset ?: cursor.size
            }

            cursor.seekStart(offset)

            val isInstructionsSegment = tryParseInstructionsSegment(
                offsetToSegment,
                labelHolder,
                cursor,
                endOffset,
                labels?.toMutableList() ?: mutableListOf(),
                stringEncoding,
                version,
            )

            if (!isInstructionsSegment) {
                cursor.seekStart(offset)

                parseDataSegment(
                    offsetToSegment,
                    cursor,
                    endOffset,
                    labels?.toMutableList() ?: mutableListOf()
                )
            }

            segment = offsetToSegment[offset]

            check(endOffset > offset) {
                "Next offset $endOffset was smaller than or equal to current offset ${offset}."
            }
            checkNotNull(segment) { "Couldn't create segment for offset ${offset}." }
        }

        segments.add(segment)

        offset += segment.size(stringEncoding, version)
    }

    // Attach each label from the label table to a segment in the final IR.
    //
    // Three cases per label:
    //
    //   (a) Its offset is the start of a segment in `segments`. Just add the label.
    //
    //   (b) Its offset is inside a segment in `segments` (or its offset matches an
    //       "orphan" segment in offsetToSegment that the linear walker overshot and
    //       never added to `segments`). Split the parent at the offset so the label
    //       anchors a real sub-segment in the IR. Without this the label is lost from
    //       the IR — and on the next write, lost from the bytecode entirely: writeBytecode
    //       iterates segment.labels to populate labelOffsets, so any unattached label
    //       gets -1 and the next parse can't see it. Each round-trip then bleeds more
    //       labels (case observed in 博士のVR: labels 252/278/404 dropped on trip 1,
    //       380/412 on trip 2, with thread_stg(278) becoming a dangling reference).
    //
    //   (c) Split fails: either the offset is out of range (corrupt label table entry),
    //       or it lands mid-instruction, or the parent is a StringSegment (atomic by
    //       design — splitting mid-character corrupts the value; newserv handles this
    //       case by rendering the bytes as raw string content but we currently don't).
    //       Fall back to recording a problem; the label is still lost from the IR but
    //       at least surfaces in problems. Severity is Info when in-range (the historical
    //       "label-into-data" pattern, e.g. quest 230 dialog strings reached via labels
    //       660/15 whose offsets land inside an instruction segment) and Warning when
    //       out-of-range (genuinely corrupt).
    val finalSegments = segments.toHashSet()
    for ((label, labelOffset) in labelHolder.labels) {
        val existing = offsetToSegment[labelOffset]

        if (existing != null && existing in finalSegments) {
            if (label !in existing.labels) {
                existing.labels.add(label)
                existing.labels.sort()
            }
            continue
        }

        val inRange = labelOffset in 0 until cursor.size
        val didSplit = inRange && trySplitSegmentForLabel(
            segments, offsetToSegment, finalSegments, labelOffset, label, stringEncoding, version,
        )

        if (!didSplit) {
            result.addProblem(
                if (inRange) Severity.Info else Severity.Warning,
                "Label $label doesn't point to anything.",
                "Label $label with offset $labelOffset doesn't point to anything.",
            )
        }
    }

    // Sanity check parsed byte code.
    if (cursor.size != offset) {
        result.addProblem(
            Severity.Error,
            "The script code is corrupt.",
            "Expected to parse ${cursor.size} bytes but parsed $offset instead.",
        )

        if (!lenient) {
            return result.failure()
        }
    }

    val ir = BytecodeIr(segments)
    normalizeStackArgs(ir)
    return result.success(ir)
}

/**
 * Splits the segment in [segments] containing [labelOffset] so that [label] anchors a
 * sub-segment. On success, replaces the parent in [segments] with two halves, updates
 * [offsetToSegment] and [finalSegments], and returns true.
 *
 * Returns false (caller's responsibility to log the lost label) when:
 *   - no segment in [segments] contains [labelOffset] (shouldn't happen for in-range
 *     offsets after the linear walker has filled the buffer, but kept for safety);
 *   - the parent is an [InstructionSegment] and [labelOffset] doesn't align with any
 *     instruction's start (the label points into the middle of an opcode's args, which
 *     can't be split without corrupting the instruction);
 *   - the parent is a [StringSegment] (atomic — splitting mid-character corrupts the
 *     decoded value).
 *
 * The split preserves the parent's original [Segment.labels] on the "before" half and
 * gives the "after" half a fresh labels list containing just [label]; any further labels
 * pointing into the same range will be re-processed by the caller and may split the
 * "after" half again.
 */
private fun trySplitSegmentForLabel(
    segments: MutableList<Segment>,
    offsetToSegment: MutableMap<Int, Segment>,
    finalSegments: MutableSet<Segment>,
    labelOffset: Int,
    label: Int,
    stringEncoding: BytecodeStringEncoding,
    version: Version,
): Boolean {
    // Locate the parent: walk segments accumulating start offsets until labelOffset
    // falls within [start, start+size).
    var start = 0
    var parentIdx = -1
    var parent: Segment? = null
    for ((i, s) in segments.withIndex()) {
        val end = start + s.size(stringEncoding, version)
        if (labelOffset in start until end) {
            parentIdx = i
            parent = s
            break
        }
        start = end
    }
    if (parent == null || parentIdx < 0) return false

    val splitWithinParent = labelOffset - start
    if (splitWithinParent == 0) {
        // Already at the parent's start — just attach the label.
        if (label !in parent.labels) {
            parent.labels.add(label)
            parent.labels.sort()
        }
        offsetToSegment[labelOffset] = parent
        return true
    }

    fun replaceParent(before: Segment, after: Segment): Boolean {
        segments[parentIdx] = before
        segments.add(parentIdx + 1, after)
        finalSegments.remove(parent)
        finalSegments.add(before)
        finalSegments.add(after)
        offsetToSegment[start] = before
        offsetToSegment[labelOffset] = after
        return true
    }

    return when (parent) {
        is InstructionSegment -> {
            // Find the instruction whose start aligns with the labelOffset.
            var acc = 0
            var splitIdx = -1
            for ((i, ins) in parent.instructions.withIndex()) {
                if (acc == splitWithinParent) {
                    splitIdx = i
                    break
                }
                if (acc > splitWithinParent) break
                acc += ins.getSize(stringEncoding, version)
            }
            if (splitIdx <= 0) return false // label points mid-instruction or before first

            val before = InstructionSegment(
                parent.labels,
                parent.instructions.subList(0, splitIdx).toMutableList(),
                parent.srcLoc,
            )
            val after = InstructionSegment(
                mutableListOf(label),
                parent.instructions.subList(splitIdx, parent.instructions.size).toMutableList(),
                SegmentSrcLoc(),
            )
            replaceParent(before, after)
        }
        is DataSegment -> {
            val beforeBuf = parent.data.copy(0, splitWithinParent)
            val afterBuf = parent.data.copy(splitWithinParent, parent.data.size - splitWithinParent)
            val before = DataSegment(parent.labels, beforeBuf, parent.srcLoc)
            val after = DataSegment(mutableListOf(label), afterBuf, SegmentSrcLoc())
            replaceParent(before, after)
        }
        is StringSegment -> false
    }
}

private fun findAndParseSegments(
    cursor: Cursor,
    labelHolder: LabelHolder,
    labels: Map<Int, SegmentType>,
    offsetToSegment: MutableMap<Int, Segment>,
    lenient: Boolean,
    stringEncoding: BytecodeStringEncoding,
    version: Version,
) {
    var newLabels = labels
    var startSegmentCount: Int
    // Instruction segments which we've been able to fully analyze for label references so far.
    val analyzedSegments = mutableSetOf<InstructionSegment>()

    // Iteratively parse segments from label references.
    do {
        startSegmentCount = offsetToSegment.size

        // Parse segments of which the type is known.
        for ((label, type) in newLabels) {
            parseSegment(offsetToSegment, labelHolder, cursor, label, type, lenient, stringEncoding, version)
        }

        // Find label references.
        val sortedSegments = offsetToSegment.entries
            .filter { (_, s) -> s is InstructionSegment }
            .sortedBy { it.key }
            .map { (_, s) -> s as InstructionSegment }

        val cfg = ControlFlowGraph.create(sortedSegments)

        newLabels = mutableMapOf()

        for (segment in sortedSegments) {
            if (segment in analyzedSegments) continue

            var foundAllLabels = true

            for (instructionIdx in segment.instructions.indices) {
                if (!collectLabelReferencesFromInstruction(
                        cfg,
                        newLabels,
                        segment,
                        instructionIdx,
                    )
                ) {
                    foundAllLabels = false
                }
            }

            if (foundAllLabels) {
                analyzedSegments.add(segment)
            }
        }
    } while (offsetToSegment.size > startSegmentCount)
}

/**
 * Processes a single instruction's parameters to collect label references.
 * Returns true if all label references could be resolved.
 */
private fun collectLabelReferencesFromInstruction(
    cfg: ControlFlowGraph,
    newLabels: MutableMap<Int, SegmentType>,
    segment: InstructionSegment,
    instructionIdx: Int,
): Boolean {
    val instruction = segment.instructions[instructionIdx]
    var i = 0
    var foundAllLabels = true

    while (i < instruction.opcode.params.size) {
        val param = instruction.opcode.params[i]

        when (param.type) {
            is ILabelType -> {
                if (!getArgLabelValues(
                        cfg,
                        newLabels,
                        segment,
                        instructionIdx,
                        i,
                        SegmentType.Instructions,
                    )
                ) {
                    foundAllLabels = false
                }
            }

            is ILabelVarType -> {
                // Never on the stack.
                // Eat all remaining arguments.
                while (i < instruction.args.size) {
                    val label = (instruction.args[i] as IntArg).value
                    if (label == 76) {
                        println("DEBUG: label 76 added via ILabelVarType from opcode=${instruction.opcode.mnemonic} in segment labels=${segment.labels} instructionIdx=$instructionIdx")
                    }
                    val oldType = newLabels[label]

                    if (oldType == null ||
                        SEGMENT_PRIORITY.getValue(SegmentType.Instructions) >
                        SEGMENT_PRIORITY.getValue(oldType)
                    ) {
                        newLabels[label] = SegmentType.Instructions
                    }

                    i++
                }
            }

            is DLabelType -> {
                if (!getArgLabelValues(
                        cfg,
                        newLabels,
                        segment,
                        instructionIdx,
                        i,
                        SegmentType.Data
                    )
                ) {
                    foundAllLabels = false
                }
            }

            is SLabelType -> {
                if (!getArgLabelValues(
                        cfg,
                        newLabels,
                        segment,
                        instructionIdx,
                        i,
                        SegmentType.String
                    )
                ) {
                    foundAllLabels = false
                }
            }

            is RegType -> if (param.type.registers != null) {
                for (j in param.type.registers.indices) {
                    val registerParam = param.type.registers[j]

                    // Never on the stack.
                    if (registerParam.type is ILabelType) {
                        val firstRegister = instruction.args[0].value as Int
                        val labelValues = getRegisterValue(
                            cfg,
                            instruction,
                            firstRegister + j,
                        )

                        if (labelValues.size <= MAX_LABEL_VALUES) {
                            for (label in labelValues) {
                                if (label == 76) {
                                    println("DEBUG: label 76 added via RegType ILabelType from opcode=${instruction.opcode.mnemonic} in segment labels=${segment.labels} instructionIdx=$instructionIdx reg=${firstRegister + j}")
                                }
                                newLabels[label] = SegmentType.Instructions
                            }
                        } else {
                            foundAllLabels = false
                        }
                    }
                }
            }

            else -> {}
        }

        i++
    }

    return foundAllLabels
}

/**
 * Returns immediate arguments or stack arguments.
 */
private fun getArgLabelValues(
    cfg: ControlFlowGraph,
    labels: MutableMap<Int, SegmentType>,
    instructionSegment: InstructionSegment,
    instructionIdx: Int,
    paramIdx: Int,
    segmentType: SegmentType,
): Boolean {
    val instruction = instructionSegment.instructions[instructionIdx]

    if (instruction.opcode.stack === StackInteraction.Pop) {
        // Post-normalization: args are inlined directly on the Pop instruction.
        if (instruction.args.isNotEmpty()) {
            val arg = instruction.args.getOrNull(paramIdx) as? IntArg ?: return false
            // Register references (arg_pushr) cannot be statically resolved to a label value.
            if (arg.isRegRef) return false
            val value = arg.value
            if (value == 76 && segmentType == SegmentType.Instructions) {
                println("DEBUG: label 76 added via getArgLabelValues (inlined stack-pop) from opcode=${instruction.opcode.mnemonic} in segment labels=${instructionSegment.labels} instructionIdx=$instructionIdx paramIdx=$paramIdx")
            }
            val oldType = labels[value]

            if (
                oldType == null ||
                SEGMENT_PRIORITY.getValue(segmentType) > SEGMENT_PRIORITY.getValue(oldType)
            ) {
                labels[value] = segmentType
            }

            return true
        }

        // Pre-normalization: args are on the stack, use data flow analysis.
        val stackValues = getStackValue(
            cfg,
            instruction,
            instruction.opcode.params.size - paramIdx - 1,
        ).first

        if (stackValues.size <= MAX_LABEL_VALUES) {
            for (value in stackValues) {
                if (value == 76 && segmentType == SegmentType.Instructions) {
                    println("DEBUG: label 76 added via getArgLabelValues (stack DFA) from opcode=${instruction.opcode.mnemonic} in segment labels=${instructionSegment.labels} instructionIdx=$instructionIdx paramIdx=$paramIdx")
                }
                val oldType = labels[value]

                if (
                    oldType == null ||
                    SEGMENT_PRIORITY.getValue(segmentType) > SEGMENT_PRIORITY.getValue(oldType)
                ) {
                    labels[value] = segmentType
                }
            }

            return true
        }
    } else {
        val value = (instruction.args[paramIdx] as IntArg).value
        if (value == 76 && segmentType == SegmentType.Instructions) {
            println("DEBUG: label 76 added via getArgLabelValues (direct arg) from opcode=${instruction.opcode.mnemonic} in segment labels=${instructionSegment.labels} instructionIdx=$instructionIdx paramIdx=$paramIdx")
        }
        val oldType = labels[value]

        if (
            oldType == null ||
            SEGMENT_PRIORITY.getValue(segmentType) > SEGMENT_PRIORITY.getValue(oldType)
        ) {
            labels[value] = segmentType
        }

        return true
    }

    return false
}

private fun parseSegment(
    offsetToSegment: MutableMap<Int, Segment>,
    labelHolder: LabelHolder,
    cursor: Cursor,
    label: Int,
    type: SegmentType,
    lenient: Boolean,
    stringEncoding: BytecodeStringEncoding,
    version: Version,
) {
    try {
        val info = labelHolder.getInfo(label)

        if (info == null) {
            if (label !in BUILTIN_FUNCTIONS) {
                logger.warn { "Label $label is not registered in the label table." }
            }

            return
        }

        // Check whether we've already parsed this segment and reparse it if necessary.
        val segment = offsetToSegment[info.offset]

        val labels: MutableList<Int> =
            if (segment == null) {
                mutableListOf(label)
            } else {
                if (label !in segment.labels) {
                    segment.labels.add(label)
                    segment.labels.sort()
                }

                if (SEGMENT_PRIORITY.getValue(type) > SEGMENT_PRIORITY.getValue(segment.type)) {
                    segment.labels
                } else {
                    return
                }
            }

        val endOffset = info.next?.offset ?: cursor.size
        cursor.seekStart(info.offset)

        return when (type) {
            SegmentType.Instructions ->
                parseInstructionsSegment(
                    offsetToSegment,
                    labelHolder,
                    cursor,
                    endOffset,
                    labels,
                    info.next?.label,
                    lenient,
                    stringEncoding,
                    version,
                )

            SegmentType.Data ->
                parseDataSegment(offsetToSegment, cursor, endOffset, labels)

            SegmentType.String ->
                parseStringSegment(offsetToSegment, cursor, endOffset, labels, stringEncoding)
        }
    } catch (e: Exception) {
        if (lenient) {
            logger.error(e) { "Couldn't fully parse byte code segment." }
        } else {
            throw e
        }
    }
}

private fun parseInstructionsSegment(
    offsetToSegment: MutableMap<Int, Segment>,
    labelHolder: LabelHolder,
    cursor: Cursor,
    endOffset: Int,
    labels: MutableList<Int>,
    nextLabel: Int?,
    lenient: Boolean,
    stringEncoding: BytecodeStringEncoding,
    version: Version,
) {
    val instructions = mutableListOf<Instruction>()

    val segment = InstructionSegment(
        labels,
        instructions,
        SegmentSrcLoc()
    )
    offsetToSegment[cursor.position] = segment

    while (cursor.position < endOffset) {
        // Parse the opcode.
        val mainOpcode = cursor.uByte()

        val fullOpcode = when (mainOpcode.toInt()) {
            0xF8, 0xF9 -> ((mainOpcode.toInt() shl 8) or cursor.uByte().toInt())
            else -> mainOpcode.toInt()
        }

        val opcode = codeToOpcode(fullOpcode, version)

        // Parse the arguments.
        var terminatorHit = false
        try {
            val args = parseInstructionArguments(cursor, opcode, version, stringEncoding)
            instructions.add(Instruction(opcode, args, srcLoc = null, valid = true))
            // Stop consuming bytes once we've parsed an unconditional-terminator instruction
            // (ret / exit / jmp) — but only for pre-BB versions.
            //
            // In DC/GC/PC quests, any bytes between the terminator and the next label boundary
            // are alignment padding or garbage (e.g., 0x9F), and parsing them as instructions
            // produces spurious unknown_xx entries that don't appear in newserv's disassembly.
            //
            // BB_V4 is excluded: in BB, post-terminator bytes are valid (though unreachable)
            // known opcodes (ret, nop) used for alignment. Stopping early in BB breaks the
            // byte-for-byte round-trip because the assembler folds unlabeled trailing instructions
            // back into the preceding labeled segment, yielding a different IR.
            if (version != Version.BB_V4 && opcode.code in TERMINATOR_OPCODES) terminatorHit = true
        } catch (e: Exception) {
            if (lenient) {
                logger.error(e) {
                    "Exception occurred while parsing arguments for instruction ${opcode.mnemonic}."
                }
                instructions.add(Instruction(opcode, emptyList(), srcLoc = null, valid = false))
            } else {
                throw e
            }
        }
        if (terminatorHit) break
    }

    // Recurse on label drop-through.
    if (nextLabel != null) {
        var dropThrough = true

        if (version != Version.BB_V4) {
            // For pre-BB versions, we stopped at the first terminator (if any), so the last
            // instruction IS the terminator — no need to scan backwards.
            if (instructions.isNotEmpty() &&
                instructions.last().opcode.code in TERMINATOR_OPCODES
            ) {
                dropThrough = false
            }
        } else {
            // For BB_V4, retain the original backward scan for ret or jmp.
            for (i in instructions.lastIndex downTo 0) {
                val code = instructions[i].opcode.code
                if (code == OP_RET.code || code == OP_JMP.code) {
                    dropThrough = false
                    break
                }
            }
        }

        if (dropThrough) {
            parseSegment(
                offsetToSegment,
                labelHolder,
                cursor,
                nextLabel,
                SegmentType.Instructions,
                lenient,
                stringEncoding,
                version,
            )
        }
    }
}

private fun parseDataSegment(
    offsetToSegment: MutableMap<Int, Segment>,
    cursor: Cursor,
    endOffset: Int,
    labels: MutableList<Int>,
) {
    val startOffset = cursor.position
    val segment = DataSegment(
        labels,
        cursor.buffer(endOffset - startOffset),
        SegmentSrcLoc(),
    )
    offsetToSegment[startOffset] = segment
}

private fun parseStringSegment(
    offsetToSegment: MutableMap<Int, Segment>,
    cursor: Cursor,
    endOffset: Int,
    labels: MutableList<Int>,
    stringEncoding: BytecodeStringEncoding,
) {
    val startOffset = cursor.position
    val byteLength = endOffset - startOffset
    val segment = StringSegment(
        labels,
        when (stringEncoding) {
            BytecodeStringEncoding.ASCII -> cursor.stringAscii(
                byteLength,
                nullTerminated = true,
                dropRemaining = true,
            )

            BytecodeStringEncoding.SHIFT_JIS -> readShiftJisFromCursor(
                cursor,
                byteLength,
                nullTerminated = true,
                dropRemaining = true,
            )

            BytecodeStringEncoding.UTF16 -> cursor.stringUtf16(
                byteLength,
                nullTerminated = true,
                dropRemaining = true,
            )
        },
        byteLength,
        SegmentSrcLoc()
    )
    offsetToSegment[startOffset] = segment
}

private fun parseInstructionArguments(
    cursor: Cursor,
    opcode: Opcode,
    version: Version,
    stringEncoding: BytecodeStringEncoding,
): List<Arg> {
    val args = mutableListOf<Arg>()

    val readInline = !(version.dialect == Dialect.V3_V4 && opcode.argsMode == ArgsMode.Stack)
    if (readInline) {
        var varargCount = 0

        for (param in opcode.params) {
            when (param.type) {
                is ByteType ->
                    args.add(IntArg(cursor.uByte().toInt()))

                is ShortType ->
                    args.add(IntArg(cursor.uShort().toInt()))

                is IntType ->
                    args.add(IntArg(cursor.int()))

                is FloatType ->
                    args.add(FloatArg(cursor.float()))

                // Ensure this case is before the LabelType case because ILabelVarType extends
                // LabelType.
                is ILabelVarType -> {
                    varargCount++
                    val argSize = cursor.uByte()
                    args.addAll(cursor.uShortArray(argSize.toInt()).map { IntArg(it.toInt()) })
                }

                is LabelType -> {
                    args.add(IntArg(cursor.uShort().toInt()))
                }

                is StringType -> {
                    val maxBytes = min(4096, cursor.bytesLeft)
                    args.add(
                        StringArg(
                            when (stringEncoding) {
                                BytecodeStringEncoding.ASCII -> cursor.stringAscii(
                                    maxBytes,
                                    nullTerminated = true,
                                    dropRemaining = false,
                                )

                                BytecodeStringEncoding.SHIFT_JIS -> readShiftJisFromCursor(
                                    cursor,
                                    maxBytes,
                                    nullTerminated = true,
                                    dropRemaining = false,
                                )

                                BytecodeStringEncoding.UTF16 -> cursor.stringUtf16(
                                    maxBytes,
                                    nullTerminated = true,
                                    dropRemaining = false,
                                )
                            },
                        )
                    )
                }

                is RegType -> {
                    args.add(IntArg(cursor.uByte().toInt()))
                }

                is RegVarType -> {
                    varargCount++
                    val argSize = cursor.uByte()
                    args.addAll(cursor.uByteArray(argSize.toInt()).map { IntArg(it.toInt()) })
                }

                else -> error("Parameter type ${param.type} not implemented.")
            }
        }

        val minExpectedArgs = opcode.params.size - varargCount

        check(args.size >= minExpectedArgs) {
            "Expected to parse at least $minExpectedArgs, only parsed ${args.size}."
        }
    }

    return args
}

private fun tryParseInstructionsSegment(
    offsetToSegment: MutableMap<Int, Segment>,
    labelHolder: LabelHolder,
    cursor: Cursor,
    endOffset: Int,
    labels: MutableList<Int>,
    stringEncoding: BytecodeStringEncoding,
    version: Version,
): Boolean {
    val offset = cursor.position

    fun logReason(reason: String, t: Throwable? = null) {
        logger.trace(t) {
            buildString {
                append("Determined that segment ")

                if (labels.isEmpty()) {
                    append("without label")
                } else {
                    if (labels.size == 1) append("with label ")
                    else append("with labels ")

                    labels.joinTo(this)
                }

                append(" at offset ")
                append(offset)
                append(" is not an instructions segment because ")
                append(reason)
                append(".")
            }
        }
    }

    try {
        parseInstructionsSegment(
            offsetToSegment,
            labelHolder,
            cursor,
            endOffset,
            labels,
            nextLabel = null,
            lenient = false,
            stringEncoding,
            version,
        )

        val segment = offsetToSegment[offset]
        val instructions = (segment as InstructionSegment).instructions

        return isLikelyInstructionSegment(instructions, labelHolder) { reason ->
            logReason(reason)
        }
    } catch (e: Exception) {
        logReason("parsing it resulted in an exception", e)
        return false
    }
}

/**
 * Heuristically try to detect whether parsed instructions are likely a real instruction segment
 * (as opposed to data that happened to parse as valid opcodes).
 */
private fun isLikelyInstructionSegment(
    instructions: List<Instruction>,
    labelHolder: LabelHolder,
    logReason: (String) -> Unit,
): Boolean {
    // Heuristically try to detect whether the segment is actually a data segment.
    var prevOpcode: Opcode? = null
    var totalNopCount = 0
    var sequentialNopCount = 0
    var unknownOpcodeCount = 0
    var stackPopCount = 0
    var stackPopWithoutPrecedingPushCount = 0
    var labelCount = 0
    var unknownLabelCount = 0

    for (inst in instructions) {
        if (inst.opcode.code == OP_NOP.code) {
            if (++totalNopCount > MAX_TOTAL_NOPS) {
                logReason("it has more than $MAX_TOTAL_NOPS nop instructions")
                return false
            }

            if (++sequentialNopCount > MAX_SEQUENTIAL_NOPS) {
                logReason("it has more than $MAX_SEQUENTIAL_NOPS sequential nop instructions")
                return false
            }
        } else {
            sequentialNopCount = 0
        }

        // Count an opcode as unknown if it is not in our table, OR if it is listed in the
        // table with an "unknown_xx" placeholder mnemonic (meaning newserv doesn't recognise
        // it as a real instruction either).  The two exceptions are unknown_de and unknown_fb,
        // which are real but undocumented opcodes that legitimately appear in quest scripts.
        if (!inst.opcode.known ||
            (inst.opcode.mnemonic.startsWith("unknown_") &&
                inst.opcode.code != 0xDE && inst.opcode.code != 0xFB)
        ) {
            unknownOpcodeCount++
        }

        if (inst.opcode.stack == StackInteraction.Pop) {
            stackPopCount++

            if (prevOpcode?.stack != StackInteraction.Push) {
                stackPopWithoutPrecedingPushCount++
            }
        }

        for ((index, param) in inst.opcode.params.withIndex()) {
            if (index >= inst.args.size) break

            if (param.type is LabelType) {
                for (arg in inst.getArgs(index)) {
                    labelCount++

                    if (!labelHolder.hasLabel((arg as IntArg).value)) {
                        unknownLabelCount++
                    }
                }
            }
        }

        prevOpcode = inst.opcode
    }

    if (labelCount > 0) {
        val unknownLabelRatio = unknownLabelCount.toDouble() / labelCount

        if (unknownLabelRatio > MAX_UNKNOWN_LABEL_RATIO) {
            logReason(
                "${100 * unknownLabelRatio}% of its label references are to nonexistent labels"
            )
            return false
        }
    }

    if (stackPopCount > 0) {
        val stackPopWithoutPrecedingPushRatio =
            stackPopWithoutPrecedingPushCount.toDouble() / stackPopCount

        if (stackPopWithoutPrecedingPushRatio > MAX_STACK_POP_WITHOUT_PRECEDING_PUSH_RATIO) {
            logReason(
                "${100 * stackPopWithoutPrecedingPushRatio}% of its stack pop instructions don't have a preceding push instruction"
            )
            return false
        }
    }

    if (instructions.isNotEmpty()) {
        // Reject the segment if any truly-unknown opcode is present.
        //
        // "Unknown" here means either (a) the opcode code is not in our table at all
        // (opcode.known == false) or (b) the opcode is listed in the table with an
        // "unknown_xx" placeholder mnemonic, indicating that newserv also doesn't recognise
        // it as a real instruction. The two exceptions are unknown_de and unknown_fb, which
        // are real but undocumented opcodes.
        //
        // A single such opcode is a reliable signal that the bytes are data, not code — every
        // real PSO instruction has a known opcode in our table.
        //
        // This strict zero-tolerance rule is deterministic and doesn't shift with segment
        // boundary changes caused by push normalisation.
        //
        // Observed misclassifications fixed:
        //   q230-bb-e labels 522/28/60/280/41: NPC dialog strings (unknown_4f, which is in
        //     the YAML as a placeholder but not a real instruction)
        //   q026-bb-{e,j} label 100: PlayerStats struct reached via NPC scriptLabel
        //     coincidence (unknown_f4, not in table)
        //   q312-bb-{e,j}: data blobs (unknown_ff, not in table)
        if (unknownOpcodeCount > 0) {
            logReason("contains $unknownOpcodeCount unknown opcode(s) out of ${instructions.size}")
            return false
        }
    }

    return true
}

fun writeBytecode(
    bytecodeIr: BytecodeIr,
    stringEncoding: BytecodeStringEncoding,
    version: Version,
): BytecodeAndLabelOffsets {
    val buffer = Buffer.withCapacity(100 * bytecodeIr.segments.size, Endianness.Little)
    val cursor = buffer.cursor()
    // Keep track of label offsets.
    val largestLabel = bytecodeIr.segments.asSequence().flatMap { it.labels }.maxOrNull() ?: -1
    val labelOffsets = IntArray(largestLabel + 1) { -1 }

    for (segment in bytecodeIr.segments) {
        val segmentStart = cursor.position

        for (label in segment.labels) {
            labelOffsets[label] = cursor.position
        }

        when (segment) {
            is InstructionSegment -> {
                for (instruction in segment.instructions) {
                    val opcode = instruction.opcode
                    val emitPushPrologue =
                        version.dialect == Dialect.V3_V4 && opcode.argsMode == ArgsMode.Stack

                    if (emitPushPrologue) {
                        // Write push instructions before the Pop opcode.
                        writePushInstructions(cursor, instruction, stringEncoding)
                    }

                    if (opcode.size == 2) {
                        cursor.writeByte((opcode.code ushr 8).toByte())
                    }

                    cursor.writeByte(opcode.code.toByte())

                    if (!emitPushPrologue) {
                        writeInlineArgs(cursor, instruction, stringEncoding)
                    }
                }
            }

            is StringSegment -> {
                val size = segment.size(stringEncoding, version)

                when (stringEncoding) {
                    BytecodeStringEncoding.ASCII ->
                        cursor.writeStringAscii(segment.value, size)

                    BytecodeStringEncoding.SHIFT_JIS ->
                        writeShiftJisInto(cursor, segment.value, size)

                    BytecodeStringEncoding.UTF16 ->
                        cursor.writeStringUtf16(segment.value, size)
                }
            }

            is DataSegment -> {
                cursor.writeCursor(segment.data.cursor())
            }
        }

        val actualSize = cursor.position - segmentStart
        val expectedSize = segment.size(stringEncoding, version)

        if (actualSize != expectedSize) {
            // Log and continue rather than crash — a size mismatch here typically indicates
            // incomplete normalization (e.g., variadic Pop with wrong arg count). The written
            // bytecode may be malformed but the save should not abort entirely.
            logger.warn {
                "Segment size mismatch: getSize()=$expectedSize but wrote $actualSize bytes " +
                    "(labels=${segment.labels}, type=${segment.type})."
            }
        }
    }

    return BytecodeAndLabelOffsets(buffer, labelOffsets)
}

private fun writeInlineArgs(
    cursor: BufferCursor,
    instruction: Instruction,
    stringEncoding: BytecodeStringEncoding,
) {
    val opcode = instruction.opcode

    for (i in opcode.params.indices) {
        val param = opcode.params[i]
        val args = instruction.getArgs(i)
        val arg = args.firstOrNull()

        if (arg == null) {
            logger.warn {
                "No argument passed to ${opcode.mnemonic} for parameter ${i + 1}."
            }
            continue
        }

        when (param.type) {
            ByteType -> cursor.writeByte(arg.coerceInt().toByte())
            ShortType -> cursor.writeShort(arg.coerceInt().toShort())
            IntType -> cursor.writeInt(arg.coerceInt())
            FloatType -> cursor.writeFloat(arg.coerceFloat())
            // Ensure this case is before the LabelType case because
            // ILabelVarType extends LabelType.
            ILabelVarType -> {
                cursor.writeByte(args.size.toByte())

                for (a in args) {
                    cursor.writeShort(a.coerceInt().toShort())
                }
            }

            is LabelType -> cursor.writeShort(arg.coerceInt().toShort())

            StringType -> {
                val str = arg.coerceString()

                when (stringEncoding) {
                    BytecodeStringEncoding.ASCII ->
                        cursor.writeStringAscii(str, str.length + 1)

                    BytecodeStringEncoding.SHIFT_JIS ->
                        writeShiftJisInto(cursor, str, encodeShiftJis(str).size + 1)

                    BytecodeStringEncoding.UTF16 ->
                        cursor.writeStringUtf16(str, 2 * str.length + 2)
                }
            }

            is RegType -> {
                cursor.writeByte(arg.coerceInt().toByte())
            }

            RegVarType -> {
                cursor.writeByte(args.size.toByte())

                for (a in args) {
                    cursor.writeByte(a.coerceInt().toByte())
                }
            }

            else -> error(
                "Parameter type ${param.type::class.simpleName} not supported."
            )
        }
    }
}

private fun writePushInstructions(
    cursor: BufferCursor,
    instruction: Instruction,
    stringEncoding: BytecodeStringEncoding,
) {
    val opcode = instruction.opcode

    for (i in opcode.params.indices) {
        val paramType = opcode.params[i].type
        val args = instruction.getArgs(i)

        for (arg in args) {
            if (arg is IntArg && arg.isRegRef) {
                // arg_pushr
                cursor.writeByte(OP_ARG_PUSHR_V3_V4.code.toByte())
                cursor.writeByte(arg.value.toByte())
            } else when (paramType) {
                ByteType, is RegType, RegVarType -> {
                    // arg_pushb
                    cursor.writeByte(OP_ARG_PUSHB_V3_V4.code.toByte())
                    cursor.writeByte(arg.coerceInt().toByte())
                }

                ShortType, ILabelVarType, is LabelType -> {
                    // arg_pushw
                    cursor.writeByte(OP_ARG_PUSHW_V3_V4.code.toByte())
                    cursor.writeShort(arg.coerceInt().toShort())
                }

                IntType -> {
                    // arg_pushl
                    cursor.writeByte(OP_ARG_PUSHL_V3_V4.code.toByte())
                    cursor.writeInt(arg.coerceInt())
                }

                FloatType -> {
                    // arg_pushl (floats are pushed as int bits)
                    cursor.writeByte(OP_ARG_PUSHL_V3_V4.code.toByte())
                    cursor.writeInt(arg.coerceFloat().toRawBits())
                }

                StringType -> {
                    // arg_pushs
                    cursor.writeByte(OP_ARG_PUSHS_V3_V4.code.toByte())
                    val str = arg.coerceString()

                    when (stringEncoding) {
                        BytecodeStringEncoding.ASCII ->
                            cursor.writeStringAscii(str, str.length + 1)

                        BytecodeStringEncoding.SHIFT_JIS ->
                            writeShiftJisInto(cursor, str, encodeShiftJis(str).size + 1)

                        BytecodeStringEncoding.UTF16 ->
                            cursor.writeStringUtf16(str, 2 * str.length + 2)
                    }
                }

                else -> error(
                    "Parameter type ${paramType::class.simpleName} not supported for push."
                )
            }
        }
    }
}

private data class PushInfo(
    val segmentIdx: Int,
    val instructionIdx: Int,
    val arg: Arg,
    val opcode: Opcode,
)

private data class PopNormalization(
    val segmentIdx: Int,
    val instructionIdx: Int,
    val args: List<Arg>,
    val pushesToRemove: List<PushInfo>,
)

private data class PushPopRelationships(
    val normalizations: List<PopNormalization>,
    val pushesToRemove: Set<Pair<Int, Int>>,
)

/**
 * Normalizes the bytecode IR by inlining stack arguments into Pop instructions.
 *
 * PSO bytecode uses a push/pop pattern: `arg_push*` instructions push values onto a virtual stack,
 * then a Pop opcode consumes them. This function removes the push instructions and places their
 * arguments directly on the consuming Pop instruction, producing a flat, platform-agnostic IR.
 *
 * This is called unconditionally during [parseBytecode] so the IR is always in normalized form.
 * Previously this was optional (controlled by an `inlineStackArgs` flag passed from the UI), but
 * since all downstream consumers (disassembler, assembler, data flow analysis) benefit from the
 * normalized form, it is now always applied at parse time. On write-back, push instructions are
 * regenerated from the inlined args.
 *
 * [IntArg.isRegRef] preserves `arg_pushr` semantics (register reference vs. literal value) so
 * no information is lost during normalization.
 */
private fun normalizeStackArgs(ir: BytecodeIr) {
    // Collect all instructions across all segments in order.
    val allSegments = ir.segments.filterIsInstance<InstructionSegment>()

    // First pass: identify push/pop relationships.
    val (normalizations, pushesToRemove) = identifyPushPopRelationships(allSegments)

    // Second pass: apply normalizations.
    applyNormalizations(allSegments, pushesToRemove, normalizations)

}

/**
 * First pass: identify push/pop relationships.
 * We track which push instructions to remove and which pop instructions to augment.
 */
private fun identifyPushPopRelationships(
    allSegments: List<InstructionSegment>,
): PushPopRelationships {
    val stack = mutableListOf<PushInfo>()
    var inVaBlock = false
    val normalizations = mutableListOf<PopNormalization>()
    // Track pushes to remove (by segment index and instruction index).
    val pushesToRemove = mutableSetOf<Pair<Int, Int>>()

    for (segIdx in allSegments.indices) {
        val segment = allSegments[segIdx]

        // Reset state at segment boundaries. Each segment is a separate function/label,
        // so pushes and va blocks from a previous segment should not carry over.
        stack.clear()
        inVaBlock = false

        for (instIdx in segment.instructions.indices) {
            val inst = segment.instructions[instIdx]
            val opcode = inst.opcode

            when {
                opcode.code == OP_VA_START_V3_V4.code -> {
                    inVaBlock = true
                }

                opcode.code == OP_VA_END_V3_V4.code -> {
                    inVaBlock = false
                    stack.clear()
                }

                inVaBlock -> {
                    // Inside va_start/va_end blocks, don't normalize.
                    if (opcode.stack == StackInteraction.Push) {
                        stack.add(PushInfo(segIdx, instIdx, inst.args.firstOrNull() ?: IntArg(0), opcode))
                    }
                }

                opcode.stack == StackInteraction.Push -> {
                    if (inst.args.isEmpty()) {
                        logger.warn { "Push instruction ${opcode.mnemonic} at seg=$segIdx inst=$instIdx has no args; defaulting to IntArg(0)" }
                    }
                    val arg = inst.args.firstOrNull() ?: IntArg(0)
                    stack.add(PushInfo(segIdx, instIdx, arg, opcode))
                }

                opcode.stack == StackInteraction.Pop -> {
                    // Variadic Pop opcodes (e.g. switch_jmp) consume all available stack entries;
                    // fixed Pop opcodes consume exactly paramCount entries.
                    val consumeCount = if (opcode.varargs) stack.size else opcode.params.size

                    if (consumeCount > 0 && stack.size >= consumeCount) {
                        // Consume args from stack (stack is LIFO, params are in forward order).
                        val consumed = stack.subList(stack.size - consumeCount, stack.size).toList()

                        val normalizedArgs = mutableListOf<Arg>()

                        for (j in consumed.indices) {
                            val pushInfo = consumed[j]
                            // For variadic params, reuse the last declared param type for extra args.
                            val paramType = opcode.params[j.coerceAtMost(opcode.params.lastIndex)].type
                            normalizedArgs.add(normalizeArg(pushInfo.arg, pushInfo.opcode, paramType))
                        }

                        normalizations.add(
                            PopNormalization(segIdx, instIdx, normalizedArgs, consumed)
                        )

                        for (p in consumed) {
                            pushesToRemove.add(Pair(p.segmentIdx, p.instructionIdx))
                        }

                        repeat(consumeCount) { stack.removeAt(stack.lastIndex) }
                    } else {
                        // Not enough pushes on the stack; skip normalization for this pop.
                        stack.clear()
                    }
                }

                // Unconditional control-flow terminates the current execution path.
                // Any pushes still on the stack are orphaned and must be discarded.
                opcode.code == OP_RET.code || opcode.code == OP_JMP.code -> {
                    stack.clear()
                }

                // Conditional branches and calls: the branch target may consume or push
                // different values, so any pushes accumulated so far cannot safely be
                // matched to a later pop on the fall-through path. Clear to avoid
                // mismatched normalization with hand-edited scripts.
                opcode.code == OP_JMP_ON.code ||
                opcode.code == OP_JMP_OFF.code ||
                opcode.code == OP_JMP_E.code ||
                opcode.code == OP_JMPI_E.code ||
                opcode.code == OP_JMP_NE.code ||
                opcode.code == OP_JMPI_NE.code ||
                opcode.code == OP_UJMP_G.code ||
                opcode.code == OP_UJMPI_G.code ||
                opcode.code == OP_JMP_G.code ||
                opcode.code == OP_JMPI_G.code ||
                opcode.code == OP_UJMP_L.code ||
                opcode.code == OP_UJMPI_L.code ||
                opcode.code == OP_JMP_L.code ||
                opcode.code == OP_JMPI_L.code ||
                opcode.code == OP_UJMP_GE.code ||
                opcode.code == OP_UJMPI_GE.code ||
                opcode.code == OP_JMP_GE.code ||
                opcode.code == OP_JMPI_GE.code ||
                opcode.code == OP_UJMP_LE.code ||
                opcode.code == OP_UJMPI_LE.code ||
                opcode.code == OP_JMP_LE.code ||
                opcode.code == OP_JMPI_LE.code ||
                opcode.code == OP_SWITCH_JMP.code ||
                opcode.code == OP_CALL.code ||
                opcode.code == OP_VA_CALL_V3_V4.code ||
                opcode.code == OP_SWITCH_CALL.code -> {
                    stack.clear()
                }

                else -> Unit
            }
        }
    }

    return PushPopRelationships(normalizations, pushesToRemove)
}

/**
 * Second pass: apply normalizations.
 * Removes push instructions and inlines their arguments on the corresponding Pop instructions.
 */
private fun applyNormalizations(
    allSegments: List<InstructionSegment>,
    pushesToRemove: Set<Pair<Int, Int>>,
    normalizations: List<PopNormalization>,
) {
    // Remove push instructions in reverse order to preserve indices.
    for (segIdx in allSegments.indices.reversed()) {
        val segment = allSegments[segIdx]

        for (instIdx in segment.instructions.indices.reversed()) {
            if (Pair(segIdx, instIdx) in pushesToRemove) {
                segment.instructions.removeAt(instIdx)
            }
        }
    }

    // Pre-compute per-segment sorted removal indices for O(n log n) adjustment.
    val removedBySegment: Map<Int, List<Int>> = pushesToRemove
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, indices) -> indices.sorted() }

    // Inline args on Pop instructions. Indices have shifted due to removals above,
    // so adjust each Pop's index by the number of pushes removed before it.
    for (norm in normalizations) {
        val segment = allSegments[norm.segmentIdx]
        val sortedRemovals = removedBySegment[norm.segmentIdx] ?: emptyList()
        // Binary search to count elements strictly less than norm.instructionIdx.
        val removedBefore = sortedRemovals.binarySearch(norm.instructionIdx)
            .let { if (it >= 0) it else -(it + 1) }
        val adjustedIdx = norm.instructionIdx - removedBefore

        if (adjustedIdx >= 0 && adjustedIdx < segment.instructions.size) {
            val oldInst = segment.instructions[adjustedIdx]

            if (oldInst.opcode.stack == StackInteraction.Pop) {
                segment.instructions[adjustedIdx] = Instruction(
                    oldInst.opcode,
                    norm.args,
                    oldInst.valid,
                    oldInst.srcLoc,
                )
            }
        }
    }
}

private fun normalizeArg(arg: Arg, pushOpcode: Opcode, paramType: AnyType): Arg {
    return when {
        // arg_pushr targeting a non-register parameter → mark as register reference.
        pushOpcode.code == OP_ARG_PUSHR_V3_V4.code && paramType !is RegType -> {
            IntArg(arg.coerceInt(), isRegRef = true)
        }
        // arg_pushl targeting a float parameter → reinterpret int bits as float.
        // arg_pushb/arg_pushw paths are kept for robustness; in practice PSO always uses
        // arg_pushl for floats. Byte/short values produce denormalized (near-zero) floats,
        // which is almost certainly a sign of malformed bytecode.
        (pushOpcode.code == OP_ARG_PUSHL_V3_V4.code ||
                pushOpcode.code == OP_ARG_PUSHB_V3_V4.code ||
                pushOpcode.code == OP_ARG_PUSHW_V3_V4.code) && paramType == FloatType -> {
            if (pushOpcode.code == OP_ARG_PUSHB_V3_V4.code || pushOpcode.code == OP_ARG_PUSHW_V3_V4.code) {
                logger.warn {
                    "Float parameter pushed via ${pushOpcode.mnemonic} (value=${arg.coerceInt()}); " +
                        "byte/short values produce denormalized floats. Malformed bytecode?"
                }
            }
            FloatArg(Float.fromBits(arg.coerceInt()))
        }
        else -> arg
    }
}

class BytecodeAndLabelOffsets(
    val bytecode: Buffer,
    val labelOffsets: IntArray,
) {
    operator fun component1(): Buffer = bytecode
    operator fun component2(): IntArray = labelOffsets
}

private data class LabelAndOffset(val label: Int, val offset: Int)
private data class OffsetAndIndex(val offset: Int, val index: Int)
private class LabelInfo(val offset: Int, val next: LabelAndOffset?)

private class LabelHolder(labelOffsets: IntArray) {
    /**
     * Mapping of labels to their offset and index into [labels].
     */
    private val labelMap: MutableMap<Int, OffsetAndIndex> = mutableMapOf()

    /**
     * Mapping of offsets to lists of labels.
     */
    private val offsetMap: MutableMap<Int, MutableList<Int>> = mutableMapOf()

    /**
     * Labels and their offset sorted by offset and then label.
     */
    val labels: List<LabelAndOffset>

    init {
        val labels = mutableListOf<LabelAndOffset>()

        // Populate the main label list.
        for (label in labelOffsets.indices) {
            val offset = labelOffsets[label]

            if (offset != -1) {
                labels.add(LabelAndOffset(label, offset))
            }
        }

        // Sort by offset, then label.
        labels.sortWith { a, b ->
            if (a.offset - b.offset != 0) a.offset - b.offset
            else a.label - b.label
        }

        this.labels = labels

        // Populate the label and offset maps.
        for (index in 0 until labels.size) {
            val (label, offset) = labels[index]

            labelMap[label] = OffsetAndIndex(offset, index)

            offsetMap.getOrPut(offset) { mutableListOf() }.add(label)
        }
    }

    fun hasLabel(label: Int): Boolean = label in labelMap

    fun getLabels(offset: Int): List<Int>? = offsetMap[offset]

    fun getInfo(label: Int): LabelInfo? {
        val offsetAndIndex = labelMap[label] ?: return null

        // Find the next label with a different offset.
        var next: LabelAndOffset? = null

        for (i in offsetAndIndex.index + 1 until labels.size) {
            next = labels[i]

            // Skip the label if it points to the same offset.
            if (next.offset > offsetAndIndex.offset) {
                break
            } else {
                next = null
            }
        }

        return LabelInfo(offsetAndIndex.offset, next)
    }
}
