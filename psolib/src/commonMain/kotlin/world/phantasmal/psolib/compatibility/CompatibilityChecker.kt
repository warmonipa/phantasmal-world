package world.phantasmal.psolib.compatibility

import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.*
import world.phantasmal.psolib.fileFormats.quest.Quest
import world.phantasmal.psolib.fileFormats.quest.QuestNpc
import world.phantasmal.psolib.fileFormats.quest.QuestObject
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.getNormalBossTeleporterDestinationFloor
import world.phantasmal.psolib.fileFormats.quest.isBossArea

/**
 * PSO Quest compatibility checker.
 * Validates if a quest is compatible with different PSO versions.
 */
class CompatibilityChecker(
    private val floorDataProvider: FloorDataProvider = NoRestrictionFloorDataProvider,
) {
    /**
     * Check compatibility of a quest with a specific PSO version.
     */
    fun checkCompatibility(
        version: PSOVersion,
        quest: Quest,
    ): CompatibilityResult =
        checkCompatibilityInternal(version, quest, collectAllLabels(quest.bytecodeIr))

    private fun checkCompatibilityInternal(
        version: PSOVersion,
        quest: Quest,
        allLabels: Set<Int>,
    ): CompatibilityResult {
        val builder = CompatibilityResultBuilder(version)

        checkBytecodeCompatibility(version, quest, allLabels, builder)
        checkNpcCompatibility(version, quest, allLabels, builder)
        checkObjectCompatibility(version, quest, builder)

        return builder.build()
    }

    private fun collectAllLabels(bytecodeIr: BytecodeIr): Set<Int> =
        bytecodeIr.segments.flatMapTo(mutableSetOf()) { it.labels }

    /**
     * Check compatibility with all PSO versions.
     */
    fun checkAllVersions(quest: Quest): Map<PSOVersion, CompatibilityResult> {
        val allLabels = collectAllLabels(quest.bytecodeIr)
        return PSOVersion.entries.associateWith { version ->
            checkCompatibilityInternal(version, quest, allLabels)
        }
    }

    /**
     * Context for bytecode instruction checks. Reduces parameter threading by bundling shared state.
     */
    private inner class CheckContext(
        val version: PSOVersion,
        val allLabels: Set<Int>,
        val referencedDataLabels: MutableSet<Int>,
        val builder: CompatibilityResultBuilder,
    ) {
        fun checkInstruction(instruction: Instruction, episode: Episode) {
            val opcode = instruction.opcode
            val lineNo = instruction.srcLoc?.mnemonic?.lineNo
            val location = lineNo?.let { ProblemLocation.ScriptLine(it) }

            checkOpcodeCompatibility(opcode, lineNo, location)

            if (opcode == OP_SET_EPISODE_V3_V4) {
                checkEpisodeParameter(instruction, location)
            }

            checkArguments(opcode, instruction, lineNo, location)
        }

        private fun checkOpcodeCompatibility(
            opcode: Opcode,
            lineNo: Int?,
            location: ProblemLocation?,
        ) {
            if (opcode.mnemonic.startsWith("unknown_", ignoreCase = true)) {
                builder.addWarning(
                    ProblemType.UNKNOWN_OPCODE,
                    "Unknown opcode \"${opcode.mnemonic}\"" + (lineNo?.let { " at line $it" } ?: ""),
                    location
                )
            }

            // BB-only opcodes are prefixed with "bb_" by convention in opcodes.yml.
            if (opcode.mnemonic.startsWith("bb_", ignoreCase = true) && version != PSOVersion.BLUE_BURST) {
                builder.addError(
                    ProblemType.OPCODE_VERSION_MISMATCH,
                    "Opcode not supported \"${opcode.mnemonic}\"" + (lineNo?.let { " at line $it" } ?: ""),
                    location
                )
            }

            if (opcode.code in CONVERT_OPCODES && (version == PSOVersion.DC_V1 || version == PSOVersion.DC_V2)) {
                builder.addWarning(
                    ProblemType.CONVERSION_WARNING,
                    "Opcode '${opcode.mnemonic}' may have conversion issues in DC V1/V2",
                    location
                )
            }

            if (opcode.code == OP_CALL_IMAGE_DATA.code) {
                builder.addWarning(
                    ProblemType.SPECIAL_OPCODE_WARNING,
                    "Opcode 0xF8EE (call_image_data) detected - may cause compatibility issues",
                    location
                )
            }
        }

        private fun checkArguments(
            opcode: Opcode,
            instruction: Instruction,
            lineNo: Int?,
            location: ProblemLocation?,
        ) {
            instruction.args.forEachIndexed { argIndex, arg ->
                if (arg is StringArg) {
                    checkStringBrackets(arg.value, lineNo)
                }

                if (arg is IntArg && !arg.isRegRef && opcode.params.isNotEmpty()) {
                    val param = opcode.params[argIndex.coerceAtMost(opcode.params.lastIndex)]
                    when (param.type) {
                        is DLabelType -> {
                            referencedDataLabels.add(arg.value)
                            checkLabelExists("Data label", arg.value, location)
                        }
                        is ILabelType, ILabelVarType ->
                            checkLabelExists("Label", arg.value, location)
                        is SLabelType ->
                            checkLabelExists("String label", arg.value, location)
                        else -> {}
                    }
                }
            }
        }

        private fun checkLabelExists(
            labelKind: String,
            labelValue: Int,
            location: ProblemLocation?,
        ) {
            if (labelValue !in allLabels) {
                builder.addError(ProblemType.LABEL_NOT_FOUND, "$labelKind $labelValue not found", location)
            }
        }

        private fun checkEpisodeParameter(
            instruction: Instruction,
            location: ProblemLocation?,
        ) {
            if (instruction.args.isEmpty()) return

            val episodeArg = instruction.args[0]
            if (episodeArg !is IntArg) return

            val episodeValue = episodeArg.value

            when {
                // DC V1 only supports Episode 1 (value 0).
                version == PSOVersion.DC_V1 && episodeValue != 0 -> {
                    builder.addError(
                        ProblemType.EPISODE_NOT_SUPPORTED,
                        "Episode parameter $episodeValue not supported in ${version.displayName} (only Episode 1 supported)",
                        location
                    )
                }

                // DC V2, PC, and GC Ep1&2 support Episodes 1 and 2 (values 0 and 1), not Episode 4.
                version in NO_EP4_VERSIONS && episodeValue == 2 -> {
                    builder.addError(
                        ProblemType.EPISODE_NOT_SUPPORTED,
                        "Episode 4 not supported in ${version.displayName}",
                        location
                    )
                }
            }
        }

        private fun checkStringBrackets(
            str: String,
            lineNo: Int?,
        ) {
            var depth = 0
            for (char in str) {
                when (char) {
                    '<' -> depth++
                    '>' -> depth--
                }
                // Catch reversed brackets (e.g., ">foo<") — depth should never go negative.
                if (depth < 0) break
            }

            if (depth != 0) {
                builder.addWarning(
                    ProblemType.INVALID_ARGUMENT,
                    "Unmatched brackets in string parameter" + (lineNo?.let { " at line $it" } ?: ""),
                    lineNo?.let { ProblemLocation.ScriptLine(it) }
                )
            }
        }
    }

    private fun checkBytecodeCompatibility(
        version: PSOVersion,
        quest: Quest,
        allLabels: Set<Int>,
        builder: CompatibilityResultBuilder,
    ) {
        val bytecodeIr = quest.bytecodeIr
        val segments = bytecodeIr.segments

        // Check if label 0 exists
        val hasLabel0 = segments.any { segment ->
            segment is InstructionSegment && 0 in segment.labels
        }

        if (!hasLabel0) {
            builder.addError(
                ProblemType.MISSING_LABEL_0,
                "Label 0 does not exist (quest entry point required)"
            )
        }

        val dataLabels = mutableSetOf<Int>()
        val referencedDataLabels = mutableSetOf<Int>()

        segments.filterIsInstance<DataSegment>().forEach { segment ->
            dataLabels.addAll(segment.labels)
        }

        val ctx = CheckContext(version, allLabels, referencedDataLabels, builder)

        // Check each instruction segment
        segments.filterIsInstance<InstructionSegment>().forEach { segment ->
            segment.instructions.forEach { instruction ->
                ctx.checkInstruction(instruction, quest.episode)
            }
        }

        // Check for unused data labels
        checkUnusedDataLabels(dataLabels, referencedDataLabels, builder)
    }

    private fun checkUnusedDataLabels(
        dataLabels: Set<Int>,
        referencedLabels: Set<Int>,
        builder: CompatibilityResultBuilder,
    ) {
        val unusedLabels = dataLabels - referencedLabels
        unusedLabels.forEach { label ->
            builder.addWarning(
                ProblemType.UNUSED_DATA_LABEL,
                "Data label $label is defined but never referenced",
                ProblemLocation.DataLabel(label)
            )
        }
    }

    private fun checkNpcCompatibility(
        version: PSOVersion,
        quest: Quest,
        allLabels: Set<Int>,
        builder: CompatibilityResultBuilder,
    ) {
        val episode = quest.episode

        // Entity limits are enforced per logical DAT floor.
        val npcsByFloor = quest.npcs.groupBy { it.floorId }

        quest.npcs.forEachIndexed { index, npc ->
            checkNpc(version, npc, index, episode, allLabels, builder)
        }

        npcsByFloor.forEach { (floorId, npcs) ->
            if (npcs.size > MAX_ENTITIES_PER_AREA) {
                builder.addWarning(
                    ProblemType.TOO_MANY_MONSTERS,
                    "Floor $floorId has too many NPCs (${npcs.size} > $MAX_ENTITIES_PER_AREA)",
                    ProblemLocation.Floor(floorId)
                )
            }
        }
    }

    private fun checkNpc(
        version: PSOVersion,
        npc: QuestNpc,
        index: Int,
        episode: Episode,
        allLabels: Set<Int>,
        builder: CompatibilityResultBuilder,
    ) {
        // NPC type and resource validity depend on the resolved map area, not the DAT floor.
        val effectiveAreaId = npc.mapAreaId
        val location = ProblemLocation.Monster(index, npc.floorId)
        val skin = npc.skin

        // Check NPC script label for lobby area (area 0: Pioneer II in EP1/EP4, Lab in EP2) only.
        // NPCs (not enemies) can have action labels that trigger scripts.
        if (effectiveAreaId == PIONEER2_AREA_ID && skin !in DefaultLabels.ENEMY_IDS) {
            val scriptLabel = npc.scriptLabel
            if (scriptLabel > 0) {
                // Determine if this label is built-in or requires script definition
                val isBaseLabel = DefaultLabels.isBaseLabel(scriptLabel, episode)
                val isExtraLabel = DefaultLabels.isExtraLabel(scriptLabel, episode)

                when {
                    // Base labels are built-in for all versions
                    isBaseLabel -> {
                        // No check needed, built-in for all versions
                    }

                    // Extra labels are built-in for GC Ep1&2 (ver=3) and BB.
                    // BB carries the same extended lobby NPC table as GC.
                    // DC V1/V2 and PC do not have these built-in and require script definition.
                    isExtraLabel -> {
                        if (version != PSOVersion.GC_EP12 && version != PSOVersion.BLUE_BURST) {
                            // Extra label in non-GC version - must be defined in script
                            if (scriptLabel !in allLabels) {
                                builder.addWarning(
                                    ProblemType.NPC_ACTION_LABEL_NOT_FOUND,
                                    "Label $scriptLabel not found for NPC #$index on floor ${npc.floorId}",
                                    location
                                )
                            }
                        }
                        // GC has these built-in, no check needed
                    }

                    // Custom label (not in any default table) - must be defined in script
                    else -> {
                        if (scriptLabel !in allLabels) {
                            builder.addWarning(
                                ProblemType.NPC_ACTION_LABEL_NOT_FOUND,
                                "Label $scriptLabel not found for NPC #$index on floor ${npc.floorId}",
                                location
                            )
                        }
                    }
                }
            }
        }

        // Check Skin 51 (special NPC skin)
        if (skin == SKIN_SPECIAL_NPC) {
            checkSkin51(version, npc, index, episode, builder)
        }

        // Check floor-specific monster restrictions
        if (effectiveAreaId <= MAX_FIELD_AREA_ID) {
            val allowedMonsters = floorDataProvider.getFloorMonsters(effectiveAreaId, version.verId)
            if (allowedMonsters != null && allowedMonsters.isNotEmpty() && skin !in allowedMonsters) {
                builder.addWarning(
                    ProblemType.MONSTER_FLOOR_MISMATCH,
                    "Monster skin $skin may not spawn correctly on this floor",
                    location
                )
            }
        }
    }

    private fun checkSkin51(
        version: PSOVersion,
        npc: QuestNpc,
        index: Int,
        episode: Episode,
        builder: CompatibilityResultBuilder,
    ) {
        val location = ProblemLocation.Monster(index, npc.floorId)

        when {
            version == PSOVersion.DC_V1 || version == PSOVersion.DC_V2 -> {
                builder.addWarning(
                    ProblemType.SKIN_NOT_SUPPORTED,
                    "Skin $SKIN_SPECIAL_NPC not supported in ${version.displayName}",
                    location
                )
            }

            episode == Episode.II -> {
                builder.addWarning(
                    ProblemType.SKIN_NOT_SUPPORTED,
                    "Skin $SKIN_SPECIAL_NPC may not work properly in Episode 2",
                    location
                )
            }

            else -> {
                // Get subtype from rotation.x field
                val subtype = npc.data.getInt(NPC_ROTATION_X_OFFSET)
                when {
                    subtype > MAX_SKIN_51_SUBTYPE -> {
                        builder.addError(
                            ProblemType.SKIN_51_INVALID_SUBTYPE,
                            "Skin $SKIN_SPECIAL_NPC invalid subtype $subtype (must be 0-$MAX_SKIN_51_SUBTYPE)",
                            location
                        )
                    }

                    !floorDataProvider.isValidNPC51(npc.mapAreaId, subtype) -> {
                        builder.addError(
                            ProblemType.SKIN_51_INVALID_SUBTYPE,
                            "Skin $SKIN_SPECIAL_NPC subtype $subtype not valid for map area ${npc.mapAreaId}",
                            location
                        )
                    }
                }
            }
        }
    }

    private fun checkObjectCompatibility(
        version: PSOVersion,
        quest: Quest,
        builder: CompatibilityResultBuilder,
    ) {
        // Entity limits are enforced per logical DAT floor.
        val objectsByFloor = quest.objects.groupBy { it.floorId }

        quest.objects.forEachIndexed { index, obj ->
            checkObject(version, quest, obj, index, builder)
        }

        objectsByFloor.forEach { (floorId, objects) ->
            if (objects.size > MAX_ENTITIES_PER_AREA) {
                builder.addWarning(
                    ProblemType.TOO_MANY_OBJECTS,
                    "Floor $floorId has too many objects (${objects.size} > $MAX_ENTITIES_PER_AREA)",
                    ProblemLocation.Floor(floorId)
                )
            }
        }
    }

    private fun checkObject(
        version: PSOVersion,
        quest: Quest,
        obj: QuestObject,
        index: Int,
        builder: CompatibilityResultBuilder,
    ) {
        val location = ProblemLocation.Object(index, obj.floorId)

        // Get object skin/type ID
        val skinId = obj.data.getShort(0).toInt()

        // Check floor-specific object restrictions
        if (obj.floorId <= MAX_FIELD_AREA_ID) {
            val allowedObjects = floorDataProvider.getFloorObjects(obj.floorId, version.verId)
            if (allowedObjects != null && allowedObjects.isNotEmpty() && skinId !in allowedObjects) {
                builder.addWarning(
                    ProblemType.OBJECT_FLOOR_MISMATCH,
                    "Object skin $skinId may not work correctly on this floor",
                    location
                )
            }
        }

        if (
            version == PSOVersion.BLUE_BURST &&
            obj.type == ObjectType.BossTeleporter &&
            quest.events.none { it.isChallengeMode }
        ) {
            checkNormalBossTeleporter(quest, obj, location, builder)
        }
    }

    private fun checkNormalBossTeleporter(
        quest: Quest,
        obj: QuestObject,
        location: ProblemLocation.Object,
        builder: CompatibilityResultBuilder,
    ) {
        val destinationFloor = getNormalBossTeleporterDestinationFloor(quest.episode, obj.floorId)
            ?: return
        val sourceMapping = quest.floorMappings.find { it.floorId == obj.floorId }
        val sourceMapEpisode = sourceMapping?.mapEpisode ?: quest.episode
        val sourceAreaId = sourceMapping?.mapAreaId ?: obj.floorId
        val mapNativeDestination =
            getNormalBossTeleporterDestinationFloor(sourceMapEpisode, sourceAreaId)

        if (mapNativeDestination != null && mapNativeDestination != destinationFloor) {
            builder.addWarning(
                ProblemType.BOSS_TELEPORTER_SOURCE_MISMATCH,
                "In BB normal mode, Boss Teleporter on logical floor ${obj.floorId} targets " +
                    "floor $destinationFloor, but the effective map belongs to the floor " +
                    "$mapNativeDestination Boss group.",
                location,
            )
        }

        val targetMapping = quest.floorMappings.find { it.floorId == destinationFloor }
        val targetMapEpisode = targetMapping?.mapEpisode ?: quest.episode
        val targetAreaId = targetMapping?.mapAreaId ?: destinationFloor
        if (!isBossArea(targetMapEpisode, targetAreaId)) {
            builder.addWarning(
                ProblemType.BOSS_TELEPORTER_TARGET_NOT_BOSS,
                "In BB normal mode, Boss Teleporter on logical floor ${obj.floorId} targets " +
                    "floor $destinationFloor, whose effective map is not a Boss area.",
                location,
            )
        }
    }

    companion object {
        private const val MAX_ENTITIES_PER_AREA = 400
        private const val SKIN_SPECIAL_NPC = 51
        private const val MAX_FIELD_AREA_ID = 49
        private const val NPC_ROTATION_X_OFFSET = 32

        // Area 0 is Pioneer II in EP1/EP4 and Lab in EP2 — both use the same lobby NPC tables.
        private const val PIONEER2_AREA_ID = 0

        private val NO_EP4_VERSIONS = setOf(PSOVersion.DC_V2, PSOVersion.PC, PSOVersion.GC_EP12)

        // V3 opcodes that may have conversion issues in V1.
        private val CONVERT_OPCODES = setOf(
            OP_NPC_CRP_V3_V3_V4.code, OP_P_MOVE_V3_V3_V4.code, OP_NPC_TALK_PL_V3_V3_V4.code,
            OP_NPC_CRPPK_V3_V3_V4.code, OP_NPC_CRPTALK_V3_V3_V4.code, OP_NPC_CRP_ID_V3_V3_V4.code,
            OP_CAM_PAN_V3_V3_V4.code, OP_POS_PIPE_V3_V3_V4.code, OP_PL_WALK_V3_V3_V4.code,
            OP_PARTICLE_V3_V3_V4.code, OP_PARTICLE_ID_V3_V3_V4.code, OP_NPC_CRPTALK_ID_V3_V3_V4.code,
        )

    }
}
