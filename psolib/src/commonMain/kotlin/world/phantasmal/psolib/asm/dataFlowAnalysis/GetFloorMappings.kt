package world.phantasmal.psolib.asm.dataFlowAnalysis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.*
import world.phantasmal.psolib.fileFormats.quest.findEpisodeByMapId
import world.phantasmal.psolib.fileFormats.quest.getAreaIdByMapId
import world.phantasmal.psolib.fileFormats.quest.getMapId
import world.phantasmal.psolib.fileFormats.quest.Version

private val logger = KotlinLogging.logger {}

/** The client allocates its floor designation/entity tables for logical floor IDs 0 through 17. */
internal const val CLIENT_LOGICAL_FLOOR_COUNT = 0x12

/**
 * Where the client obtains entities for a designated floor.
 *
 * This describes runtime loading only. Quest DAT records remain part of the editable QST even
 * when the client selects a template or no runtime entities for that floor.
 */
@Serializable
enum class FloorDataSource {
    QuestDat,
    OfflineTemplate,
    OnlineTemplate,
    None,
}

/**
 * How the effective client map and map variation were obtained.
 */
@Serializable
enum class FloorMapSource {
    EpisodeDefault,
    ExplicitDesignation,
}

/**
 * The effective map configuration used by the client for one quest floor.
 *
 * [floorId] is the logical floor stored in quest DAT records. [mapId], [mapVariation], and
 * [objectSetVariation] correspond to the client's `floor_map_designation` fields (`map`,
 * `map_variant`, and `objset`). [mapAreaId] and [mapEpisode] are editor-derived values used to
 * find Phantasmal area models and assets.
 */
@Serializable
data class FloorMapping(
    val floorId: Int,
    val mapId: Int,
    /** Legacy serialized name retained for stored editor data; this is an actual map area. */
    @SerialName("areaId")
    val mapAreaId: Int,
    /** Legacy serialized name retained for stored editor data; matches client `map_variant`. */
    @SerialName("variantId")
    val mapVariation: Int,
    val mapEpisode: Episode? = null,
    val objectSetVariation: Int = 0,
    val dataSource: FloorDataSource = FloorDataSource.QuestDat,
    val mapSource: FloorMapSource = FloorMapSource.ExplicitDesignation,
    /**
     * True when mutually exclusive control-flow paths can leave this floor with different
     * designations. The remaining fields are a preview, not a guaranteed runtime result.
     */
    val runtimeAmbiguous: Boolean = false,
)

private data class FloorDesignation(
    val floorId: Int,
    val dataSource: FloorDataSource,
    val mapUpdate: FloorMapUpdate,
)

private data class ResolvedFloorDesignation(
    val instruction: Instruction,
    val designation: FloorDesignation,
)

private data class FloorRuntimeState(
    val mapId: Int?,
    val mapVariation: Int,
    val objectSetVariation: Int,
    val dataSource: FloorDataSource,
)

private data class FloorRuntimePosition(
    val block: BasicBlock,
    val returnBlocks: List<BasicBlock>,
)

private sealed interface FloorMapUpdate {
    data object KeepCurrent : FloorMapUpdate

    data class Replace(
        val mapId: Int,
        val mapVariation: Int,
        val objectSetVariation: Int,
    ) : FloorMapUpdate
}

/**
 * Resolves the effective client map configuration for quest floors that are known to be used.
 *
 * Used floors come from [usedFloorIds], `set_floor_handler`, and explicit map-designation
 * opcodes. Each used floor starts with the client's episode-default mapping. Statically resolved
 * `map_designate`, `map_designate_ex`, and `bb_map_designate` instructions then update the
 * effective state according to their type:
 *
 * - 0: use quest DAT and overwrite map/variation (`objset` is forced to 0);
 * - 1: use the offline template without changing the current map assignment;
 * - 2: use the online template and overwrite map/variation/`objset` when supported;
 * - 3: load no entities and do not change the current map assignment.
 *
 * This is a static approximation: discovered instructions are applied in normalized segment
 * traversal order. When mutually exclusive paths produce different final states, the preview
 * mapping is marked [FloorMapping.runtimeAmbiguous] instead of being presented as guaranteed.
 */
fun getFloorMappings(
    instructionSegments: List<InstructionSegment>,
    usedFloorIds: Set<Int> = emptySet(),
    version: Version = Version.BB_V4,
    createControlFlowGraph: () -> ControlFlowGraph,
): List<FloorMapping> {
    if (instructionSegments.isEmpty() && usedFloorIds.isEmpty()) return emptyList()

    val episode = instructionSegments
        .find { 0 in it.labels }
        ?.let(::getEpisode)
        ?: Episode.I

    val inferredUsedFloors = usedFloorIds
        .filterTo(mutableSetOf(), ::validFloor)

    var cachedControlFlowGraph: ControlFlowGraph? = null
    fun controlFlowGraph(): ControlFlowGraph =
        cachedControlFlowGraph
            ?: createControlFlowGraph().also { cachedControlFlowGraph = it }

    for (segment in instructionSegments) {
        for (instruction in segment.instructions) {
            when (instruction.opcode) {
                OP_MAP_DESIGNATE,
                OP_MAP_DESIGNATE_EX -> {
                    // The first register always contains the logical floor.
                    val baseRegister =
                        (instruction.args.firstOrNull() as? IntArg)?.value ?: continue
                    // A CFG is needed only for register-based designation opcodes.
                    val floorValues = getRegisterValue(controlFlowGraph(), instruction, baseRegister)
                    if (floorValues.size == 1L) {
                        floorValues[0]?.takeIf(::validFloor)?.let(inferredUsedFloors::add)
                    }
                }

                OP_BB_MAP_DESIGNATE -> {
                    (instruction.args.firstOrNull() as? IntArg)
                        ?.value
                        ?.takeIf(::validFloor)
                        ?.let(inferredUsedFloors::add)
                }

                OP_SET_FLOOR_HANDLER_V0_V2,
                OP_SET_FLOOR_HANDLER_V3_V4 -> {
                    (instruction.args.firstOrNull() as? IntArg)
                        ?.value
                        ?.takeIf(::validFloor)
                        ?.let(inferredUsedFloors::add)
                }
            }
        }
    }

    // Client equivalent: init_episode_maps(episode) establishes the default floor -> map table
    // before any map_designate-family opcode mutates it.
    val floorMappings = initEpisodeMaps(episode, inferredUsedFloors)
    val initialFloorMappings = floorMappings.toMap()
    val designationHistory = mutableMapOf<Int, MutableList<ResolvedFloorDesignation>>()

    for (segment in instructionSegments) {
        for (instruction in segment.instructions) {
            when (instruction.opcode) {
                OP_MAP_DESIGNATE,
                OP_MAP_DESIGNATE_EX -> {
                    resolveRegisterDesignation(instruction, version, controlFlowGraph())
                        ?.let { designation ->
                            designationHistory
                                .getOrPut(designation.floorId) { mutableListOf() }
                                .add(ResolvedFloorDesignation(instruction, designation))
                            applyDesignation(designation, floorMappings)
                        }
                }

                OP_BB_MAP_DESIGNATE -> {
                    resolveBbDesignation(instruction, version)
                        ?.let { designation ->
                            designationHistory
                                .getOrPut(designation.floorId) { mutableListOf() }
                                .add(ResolvedFloorDesignation(instruction, designation))
                            applyDesignation(designation, floorMappings)
                        }
                }
            }
        }
    }

    val runtimeAmbiguousFloors = designationHistory
        .filter { (floorId, history) ->
            hasRuntimeAmbiguity(
                history = history,
                initialMapping = initialFloorMappings[floorId],
                controlFlowGraph = controlFlowGraph(),
            )
        }
        .keys

    return floorMappings.values
        .map { mapping ->
            if (mapping.floorId in runtimeAmbiguousFloors) {
                mapping.copy(runtimeAmbiguous = true)
            } else {
                mapping
            }
        }
        .sortedBy(FloorMapping::floorId)
}

private fun hasRuntimeAmbiguity(
    history: List<ResolvedFloorDesignation>,
    initialMapping: FloorMapping?,
    controlFlowGraph: ControlFlowGraph,
): Boolean {
    if (history.isEmpty()) return false

    val designationsByBlock = history.groupBy { resolved ->
        controlFlowGraph.getBlockForInstruction(resolved.instruction)
    }.mapValues { (block, designations) ->
        designations.sortedBy { block.indexOfInstruction(it.instruction) }
    }

    val nextInSegment = mutableMapOf<BasicBlock, BasicBlock>()
    for (i in controlFlowGraph.blocks.indices) {
        val block = controlFlowGraph.blocks[i]
        val next = controlFlowGraph.blocks.getOrNull(i + 1) ?: continue
        if (next.segment === block.segment) nextInSegment[block] = next
    }

    // ControlFlowGraph connects every callee return to every caller continuation. Those edges are
    // useful for context-insensitive analyses, but would mix mutually exclusive call sites here.
    // Build return-free predecessors and handle calls with an explicit return stack below.
    val semanticPredecessors = mutableMapOf<BasicBlock, MutableSet<BasicBlock>>()
    for (block in controlFlowGraph.blocks) {
        val successors = when (block.branchType) {
            BranchType.Return -> emptyList()
            BranchType.Call -> block.to + listOfNotNull(nextInSegment[block])
            else -> block.to
        }
        for (successor in successors) {
            semanticPredecessors.getOrPut(successor) { mutableSetOf() }.add(block)
        }
    }

    // Only entry paths which can reach a designation for this floor are relevant. Starting every
    // quest function would introduce false default states from unrelated handlers.
    val relevantAncestors = mutableSetOf<BasicBlock>()
    val ancestorsToVisit = ArrayDeque<BasicBlock>()
    ancestorsToVisit.addAll(designationsByBlock.keys)
    while (ancestorsToVisit.isNotEmpty()) {
        val block = ancestorsToVisit.removeFirst()
        if (!relevantAncestors.add(block)) continue
        ancestorsToVisit.addAll(semanticPredecessors[block].orEmpty())
    }

    val entryBlocks = relevantAncestors.filter { block ->
        semanticPredecessors[block].orEmpty().none { it in relevantAncestors }
    }.ifEmpty {
        // A closed loop has no graph root. Seed its designation blocks conservatively.
        designationsByBlock.keys.toList()
    }

    val initialState = initialMapping.toRuntimeState()
    val entryStates = mutableMapOf<FloorRuntimePosition, MutableSet<FloorRuntimeState>>()
    val worklist = ArrayDeque<FloorRuntimePosition>()
    for (entryBlock in entryBlocks) {
        val position = FloorRuntimePosition(entryBlock, emptyList())
        entryStates.getOrPut(position) { mutableSetOf() }.add(initialState)
        worklist.addLast(position)
    }

    val terminalStates = mutableSetOf<FloorRuntimeState>()
    while (worklist.isNotEmpty()) {
        val position = worklist.removeFirst()
        val block = position.block
        var outputStates = entryStates.getValue(position).toSet()
        for (resolved in designationsByBlock[block].orEmpty()) {
            outputStates = outputStates
                .mapTo(mutableSetOf()) { state ->
                    state.apply(resolved.designation)
                }
        }

        fun enqueue(successor: FloorRuntimePosition) {
            val successorStates = entryStates.getOrPut(successor) { mutableSetOf() }
            if (successorStates.addAll(outputStates)) {
                worklist.addLast(successor)
            }
        }

        when (block.branchType) {
            BranchType.Return -> {
                val returnBlock = position.returnBlocks.lastOrNull()
                if (returnBlock == null) {
                    terminalStates.addAll(outputStates)
                } else {
                    enqueue(FloorRuntimePosition(returnBlock, position.returnBlocks.dropLast(1)))
                }
            }
            BranchType.Call -> {
                val returnBlock = nextInSegment[block]
                if (block.to.isEmpty()) {
                    if (returnBlock == null) {
                        terminalStates.addAll(outputStates)
                    } else {
                        enqueue(FloorRuntimePosition(returnBlock, position.returnBlocks))
                    }
                } else if (returnBlock != null && position.returnBlocks.size >= 64) {
                    // Match the existing interprocedural analysis depth guard: continue after an
                    // excessively deep call instead of growing an unbounded recursion stack.
                    enqueue(FloorRuntimePosition(returnBlock, position.returnBlocks))
                } else {
                    val returnBlocks = if (returnBlock == null) {
                        position.returnBlocks
                    } else {
                        position.returnBlocks + returnBlock
                    }
                    for (callee in block.to) {
                        enqueue(FloorRuntimePosition(callee, returnBlocks))
                    }
                }
            }
            else -> {
                if (block.to.isEmpty()) {
                    terminalStates.addAll(outputStates)
                } else {
                    for (successor in block.to) {
                        enqueue(FloorRuntimePosition(successor, position.returnBlocks))
                    }
                }
            }
        }
    }

    return terminalStates.size > 1
}

private fun FloorMapping?.toRuntimeState(): FloorRuntimeState =
    if (this == null) {
        FloorRuntimeState(
            mapId = null,
            mapVariation = 0,
            objectSetVariation = 0,
            dataSource = FloorDataSource.QuestDat,
        )
    } else {
        FloorRuntimeState(
            mapId = mapId,
            mapVariation = mapVariation,
            objectSetVariation = objectSetVariation,
            dataSource = dataSource,
        )
    }

private fun FloorRuntimeState.apply(designation: FloorDesignation): FloorRuntimeState =
    when (val mapUpdate = designation.mapUpdate) {
        FloorMapUpdate.KeepCurrent -> copy(dataSource = designation.dataSource)
        is FloorMapUpdate.Replace -> FloorRuntimeState(
            mapId = mapUpdate.mapId,
            mapVariation = mapUpdate.mapVariation,
            objectSetVariation = mapUpdate.objectSetVariation,
            dataSource = designation.dataSource,
        )
    }

private fun resolveRegisterDesignation(
    instruction: Instruction,
    version: Version,
    controlFlowGraph: ControlFlowGraph,
): FloorDesignation? {
    val baseRegister = (instruction.args.firstOrNull() as? IntArg)?.value ?: return null
    val floorId =
        resolveRegister(controlFlowGraph, instruction, baseRegister, "floor ID") ?: return null
    if (!validFloor(floorId)) return null

    val typeRegister = baseRegister + if (instruction.opcode == OP_MAP_DESIGNATE) 1 else 2
    val designationType =
        resolveRegister(controlFlowGraph, instruction, typeRegister, "designation type")
            ?: return null
    val dataSource = floorDataSourceForDesignationType(designationType) ?: return null

    val explicitMapId = if (instruction.opcode == OP_MAP_DESIGNATE_EX) {
        (resolveRegister(controlFlowGraph, instruction, baseRegister + 1, "map ID") ?: return null)
            .takeIf { validMap(version, it) }
            ?: return null
    } else {
        null
    }

    if (designationType == 1 || designationType == 3) {
        return FloorDesignation(
            floorId = floorId,
            dataSource = dataSource,
            mapUpdate = FloorMapUpdate.KeepCurrent,
        )
    }

    val mapId = if (instruction.opcode == OP_MAP_DESIGNATE) {
        // The legacy client handler passes the logical floor as the map ID. This happens to
        // match Episode I's default table, but it must not be translated through the selected
        // episode for cross-episode or nonstandard quests.
        floorId
    } else {
        explicitMapId ?: return null
    }
    if (instruction.opcode == OP_MAP_DESIGNATE && !validMap(version, mapId)) return null

    val variationRegister = baseRegister + if (instruction.opcode == OP_MAP_DESIGNATE) 2 else 3
    val mapVariation = resolveRegister(
        controlFlowGraph,
        instruction,
        variationRegister,
        "map variation",
    ) ?: return null
    val objectSetVariation = when {
        designationType != 2 -> 0
        // DC V2: handler 8C1701C4 -> 8C150438.
        // GC V3: handler 801EDDDC -> 801F3C0C.
        // Both pass the fifth register through for type 2.
        instruction.opcode == OP_MAP_DESIGNATE_EX ->
            resolveRegister(
                controlFlowGraph,
                instruction,
                baseRegister + 4,
                "object-set variation",
            ) ?: return null
        legacyMapDesignateUsesObjectSetVariation(version) ->
            resolveRegister(
                controlFlowGraph,
                instruction,
                baseRegister + 3,
                "object-set variation",
            ) ?: return null
        else -> 0
    }

    return FloorDesignation(
        floorId = floorId,
        dataSource = dataSource,
        mapUpdate = FloorMapUpdate.Replace(
            mapId = mapId,
            mapVariation = mapVariation,
            objectSetVariation = objectSetVariation,
        ),
    )
}

private fun resolveBbDesignation(
    instruction: Instruction,
    version: Version,
): FloorDesignation? {
    if (instruction.args.size < 5) {
        logger.warn { "bb_map_designate has ${instruction.args.size} arguments; expected 5." }
        return null
    }

    val floorId = (instruction.args[0] as? IntArg)?.value ?: return null
    val mapId = (instruction.args[1] as? IntArg)?.value ?: return null
    val designationType = (instruction.args[2] as? IntArg)?.value ?: return null
    val mapVariation = (instruction.args[3] as? IntArg)?.value ?: return null
    val objectSetVariation = (instruction.args[4] as? IntArg)?.value ?: return null

    if (!validFloor(floorId)) return null
    val dataSource = floorDataSourceForDesignationType(designationType) ?: return null
    // The BB client validates the map ID before switching on type, including types 1 and 3
    // whose map fields are otherwise ignored.
    if (!validMap(version, mapId)) return null

    val mapUpdate = if (designationType == 0 || designationType == 2) {
        FloorMapUpdate.Replace(
            mapId = mapId,
            mapVariation = mapVariation,
            objectSetVariation = if (designationType == 2) objectSetVariation else 0,
        )
    } else {
        FloorMapUpdate.KeepCurrent
    }
    return FloorDesignation(
        floorId = floorId,
        dataSource = dataSource,
        mapUpdate = mapUpdate,
    )
}

private fun applyDesignation(
    designation: FloorDesignation,
    floorMappings: MutableMap<Int, FloorMapping>,
) {
    when (val mapUpdate = designation.mapUpdate) {
        FloorMapUpdate.KeepCurrent -> {
            val current = floorMappings[designation.floorId] ?: return
            floorMappings[designation.floorId] = current.copy(dataSource = designation.dataSource)
        }

        is FloorMapUpdate.Replace -> {
            floorMappings[designation.floorId] = createFloorMapping(
                floorId = designation.floorId,
                mapId = mapUpdate.mapId,
                mapVariation = mapUpdate.mapVariation,
                objectSetVariation = mapUpdate.objectSetVariation,
                dataSource = designation.dataSource,
                mapSource = FloorMapSource.ExplicitDesignation,
            ) ?: return
        }
    }
}

private fun resolveRegister(
    controlFlowGraph: ControlFlowGraph,
    instruction: Instruction,
    register: Int,
    description: String,
): Int? {
    val values = getRegisterValue(controlFlowGraph, instruction, register)
    if (values.size != 1L) {
        logger.warn {
            "Could not determine $description from R$register for ${instruction.opcode.mnemonic}."
        }
        return null
    }
    return values[0]
}

/**
 * Editor equivalent of the client's `init_episode_maps`.
 *
 * The client initializes all 18 `floor_map_designations` entries from the selected episode's
 * default map table. The editor only materializes entries for floors present in the QST because
 * unused floors cannot affect the rendered quest.
 */
private fun initEpisodeMaps(
    episode: Episode,
    usedFloorIds: Set<Int>,
): MutableMap<Int, FloorMapping> =
    usedFloorIds
        .mapNotNull { createEpisodeDefaultFloorMapping(episode, it) }
        .associateByTo(mutableMapOf(), FloorMapping::floorId)

/**
 * Looks up the map assigned to [floorId] by the client's episode-specific default map table.
 */
private fun getDefaultMapIdForFloor(episode: Episode, floorId: Int): Int? =
    getMapId(episode, floorId)

private fun createEpisodeDefaultFloorMapping(
    episode: Episode,
    floorId: Int,
): FloorMapping? {
    val mapId = getDefaultMapIdForFloor(episode, floorId) ?: return null
    return createFloorMapping(
        floorId = floorId,
        mapId = mapId,
        mapVariation = 0,
        objectSetVariation = 0,
        dataSource = FloorDataSource.QuestDat,
        mapSource = FloorMapSource.EpisodeDefault,
    )
}

private fun createFloorMapping(
    floorId: Int,
    mapId: Int,
    mapVariation: Int,
    objectSetVariation: Int,
    dataSource: FloorDataSource,
    mapSource: FloorMapSource,
): FloorMapping? {
    val mapAreaId = getAreaIdByMapId(mapId) ?: return null
    return FloorMapping(
        floorId = floorId,
        mapId = mapId,
        mapAreaId = mapAreaId,
        mapVariation = mapVariation,
        mapEpisode = findEpisodeByMapId(mapId),
        objectSetVariation = objectSetVariation,
        dataSource = dataSource,
        mapSource = mapSource,
    )
}

private fun floorDataSourceForDesignationType(designationType: Int): FloorDataSource? =
    when (designationType) {
        0 -> FloorDataSource.QuestDat
        1 -> FloorDataSource.OfflineTemplate
        2 -> FloorDataSource.OnlineTemplate
        3 -> FloorDataSource.None
        else -> {
            logger.warn { "Invalid map designation type $designationType; expected 0..3." }
            null
        }
    }

private fun validFloor(floorId: Int): Boolean {
    if (floorId in 0 until CLIENT_LOGICAL_FLOOR_COUNT) return true
    logger.warn {
        "Invalid logical floor ID $floorId; expected 0..${CLIENT_LOGICAL_FLOOR_COUNT - 1}."
    }
    return false
}

/**
 * DC/PC `map_designate` passes its fourth register through for type 2. GC V3 and BB instead
 * force object-set variation to zero; this version split is visible in the respective client
 * handlers:
 *
 * - DC V2 handler `8C16FB18` loads R..R+3; `8C1503D4` stores R+3 for type 2.
 * - GC V3 handler `801EE788` loads R..R+3; `801F3CD8` passes zero for type 0 and type 2.
 */
private fun legacyMapDesignateUsesObjectSetVariation(version: Version): Boolean =
    when (version) {
        Version.DC_NTE,
        Version.DC_V1,
        Version.DC_V2,
        Version.PC_NTE,
        Version.PC_V2 -> true

        Version.GC_NTE,
        Version.GC_V3,
        Version.BB_V4 -> false
    }

private fun validMap(version: Version, mapId: Int): Boolean {
    val mapCount = when (version) {
        Version.DC_NTE,
        Version.DC_V1,
        Version.DC_V2,
        Version.PC_NTE,
        Version.PC_V2 -> 0x12

        Version.GC_NTE,
        Version.GC_V3 -> 0x24

        Version.BB_V4 -> 0x2F
    }

    if (mapId in 0 until mapCount) return true
    logger.warn { "Invalid map ID $mapId for $version; expected 0..${mapCount - 1}." }
    return false
}

/**
 * Extracts the quest episode from function 0's `set_episode` instruction.
 */
internal fun getEpisode(func0Segment: InstructionSegment): Episode {
    val setEpisode = func0Segment.instructions.find { it.opcode == OP_SET_EPISODE_V3_V4 }
        ?: return Episode.I

    if (setEpisode.args.isEmpty()) {
        logger.warn { "set_episode instruction has no arguments, defaulting to Episode I." }
        return Episode.I
    }

    val value = (setEpisode.args[0] as IntArg).value
    return Episode.fromBytecodeValue(value) ?: run {
        logger.warn { "Unknown set_episode value: $value, defaulting to Episode I." }
        Episode.I
    }
}
