package world.phantasmal.psolib.asm.dataFlowAnalysis

import mu.KotlinLogging
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.Instruction
import world.phantasmal.psolib.asm.InstructionSegment
import world.phantasmal.psolib.asm.IntArg
import world.phantasmal.psolib.asm.OP_NPC_CRP_ID_V3_V3_V4
import world.phantasmal.psolib.asm.OP_NPC_CRP_V3_V3_V4
import world.phantasmal.psolib.asm.OP_NPC_CRPPK_V3_V3_V4
import world.phantasmal.psolib.asm.OP_NPC_CRPTALK_ID_V3_V3_V4
import world.phantasmal.psolib.asm.OP_NPC_CRPTALK_V3_V3_V4
import world.phantasmal.psolib.asm.OP_NPC_TALK_PL_V3_V3_V4
import world.phantasmal.psolib.asm.OP_AT_COORDS_TALK
import world.phantasmal.psolib.asm.OP_AT_COORDS_TALK_EX
import world.phantasmal.psolib.asm.OP_SET_OBJ_PARAM
import world.phantasmal.psolib.asm.OP_SET_OBJ_PARAM_EX
import world.phantasmal.psolib.fileFormats.quest.Version
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

enum class ScriptNpcSpawnKind {
    Follower,
    Attacker,
    LocalTalk,
    ProximityTalk,
}

enum class ScriptNpcInteractionKind {
    Target,
    Talk,
}

data class ScriptNpcInteraction(
    val label: Int,
    val kind: ScriptNpcInteractionKind,
) {
    /** Registration opcode used for client-specific walkthrough reachability. */
    var sourceInstruction: Instruction? = null
        internal set

    /** Floors on which the registration instruction can execute for any client. */
    var executionFloorIds: Set<Int> = emptySet()
        internal set
}

enum class ScriptNpcCreationOpcode(
    val code: Int,
    val mnemonic: String,
    val kind: ScriptNpcSpawnKind,
) {
    NpcCrp(0x66, "npc_crp", ScriptNpcSpawnKind.Follower),
    NpcCrppk(0x7C, "npc_crppk", ScriptNpcSpawnKind.Attacker),
    NpcCrptalk(0x7D, "npc_crptalk", ScriptNpcSpawnKind.LocalTalk),
    NpcCrpId(0x7F, "npc_crp_id", ScriptNpcSpawnKind.Follower),
    NpcCrptalkId(0xCE, "npc_crptalk_id", ScriptNpcSpawnKind.LocalTalk),
    NpcTalkPl(0x79, "npc_talk_pl", ScriptNpcSpawnKind.ProximityTalk),

    ;

    fun mnemonicFor(version: Version): String =
        if (version == Version.GC_V3 || version == Version.BB_V4) "${mnemonic}_v3" else mnemonic
}

/**
 * One statically resolved NPC creation site performed by a V2, V3, or V4 quest VM.
 *
 * This is an editor location preview, not runtime NPC state. Following, movement, stopping, and
 * removal are deliberately outside this model.
 */
data class ScriptNpcSpawn(
    val opcode: ScriptNpcCreationOpcode,
    val x: Int,
    val y: Int,
    val z: Int,
    val angle: Int,
    val templateIndex: Int,
    val ownerSlot: Int? = null,
    val npcSlot: Int? = null,
    val state: Int? = null,
    val visibilityRadius: Int? = null,
    val controllerToken: Int? = null,
    val executionFloorIds: Set<Int>,
    val interactions: Set<ScriptNpcInteraction> = emptySet(),
) {
    val kind: ScriptNpcSpawnKind get() = opcode.kind

    /** Creation opcode used for client-specific walkthrough reachability. */
    var sourceInstruction: Instruction? = null
        internal set
}

enum class ScriptNpcClass {
    HUmar,
    HUnewearl,
    HUcast,
    RAmar,
    RAcast,
    RAcaseal,
    FOmarl,
    FOnewm,
    FOnewearl,
}

/**
 * Visual configuration of a stock NPC template, read from the `PlayerVisualConfig` part of a
 * 112-byte `npcplayerchar.dat` record. Style indices are 0-based, exactly as stored in the file.
 *
 * The client builds the NPC from the player-character models of [ScriptNpcTemplate.characterClass]
 * using these values; they are not the Pioneer 2 "NpcHUmar"-style static NPC models.
 */
data class ScriptNpcAppearance(
    /** Section ID (0-9) that selects the section ID texture. */
    val sectionId: Int,
    val costume: Int,
    val skin: Int,
    val face: Int,
    val head: Int,
    val hair: Int,
    val hairRed: Int,
    val hairGreen: Int,
    val hairBlue: Int,
)

/** Stock entry from the 64-record NPC player-template table shared by V2, V3, and V4. */
data class ScriptNpcTemplate(
    val index: Int,
    val name: String,
    val characterClass: ScriptNpcClass,
    val appearance: ScriptNpcAppearance,
)

private val SCRIPT_NPC_TEMPLATE_NAMES = listOf(
    "NOL", "CICIL", "CICIL", "MARACA", "ELLY", "SHINO", "DONOPH", "MOME",
    "ALICIA", "ASH", "ASH", "SUE", "KIREEK", "BERNIE", "GILLIAM", "ELENOR",
    "ALICIA", "MONTAGUE", "RUPIKA", "MATHA", "ANNA", "TONZLAR", "TOBOKKE", "GEKIGASKY",
    "TYPE:O", "TYPE:W", "GIZEL", "DACCI", "HOPKINS", "DORONBO", "KROE", "MUJO",
    "RACTON", "LIONEL", "ZOKE", "SUE", "NADJA", "ELENOR", "KIREEK", "BERNIE",
    "CHRIS", "RENEE", "KAREN", "BEIRON", "NAKA", "LEO", "HOUND", "MADELEINE",
    "VALLETTA", "BOGARDE", "ULT", "TYPE:I", "TYPE:V", "TACHIBANA", "OSMAN", "VIVIENNE",
    "BP", "SHINTARO", "KEN", "TAKUYA", "SOKON", "UKON", "CANTONA", "HASE",
)

private val SCRIPT_NPC_TEMPLATE_CLASSES = listOf(
    1, 6, 6, 0, 8, 5, 0, 3, 6, 0, 0, 1, 2, 3, 4, 5,
    6, 7, 8, 6, 1, 3, 0, 3, 4, 4, 0, 3, 7, 3, 1, 7,
    0, 2, 3, 1, 6, 5, 2, 3, 0, 1, 8, 3, 0, 0, 2, 1,
    1, 3, 5, 5, 5, 0, 3, 8, 6, 7, 4, 0, 0, 3, 7, 0,
)

/**
 * Per template, in `npcplayerchar.dat` field order: section ID, costume, skin, face, head, hair,
 * hair red, hair green, hair blue (record offsets 0x30 and 0x38..0x47).
 */
private val SCRIPT_NPC_TEMPLATE_APPEARANCES = intArrayOf(
    3, 1, 1, 1, 0, 8, 231, 202, 146,  // 0
    6, 6, 0, 0, 0, 7, 159, 154, 103,  // 1
    6, 6, 0, 0, 0, 4, 136, 138, 87,  // 2
    1, 2, 2, 0, 0, 6, 208, 79, 41,  // 3
    0, 0, 1, 3, 0, 3, 146, 75, 24,  // 4
    5, 0, 5, 0, 1, 0, 0, 0, 0,  // 5
    6, 4, 1, 2, 0, 0, 239, 238, 236,  // 6
    2, 6, 0, 3, 0, 5, 239, 249, 246,  // 7
    9, 2, 0, 0, 0, 0, 0, 90, 255,  // 8
    0, 0, 1, 0, 0, 0, 133, 73, 41,  // 9
    0, 0, 1, 0, 0, 0, 133, 73, 41,  // 10
    7, 5, 3, 0, 0, 0, 255, 90, 178,  // 11
    3, 0, 4, 0, 0, 0, 0, 0, 0,  // 12
    7, 1, 1, 0, 0, 0, 255, 188, 66,  // 13
    1, 0, 3, 0, 0, 0, 0, 0, 0,  // 14
    3, 0, 0, 0, 0, 0, 0, 0, 0,  // 15
    9, 2, 0, 0, 0, 0, 0, 90, 255,  // 16
    8, 0, 1, 0, 0, 0, 44, 28, 89,  // 17
    0, 4, 1, 0, 0, 0, 96, 154, 253,  // 18
    3, 0, 0, 3, 0, 9, 255, 195, 255,  // 19
    6, 6, 0, 2, 0, 2, 205, 255, 149,  // 20
    4, 8, 3, 3, 0, 3, 248, 64, 72,  // 21
    7, 8, 1, 4, 0, 5, 18, 58, 179,  // 22
    1, 8, 3, 3, 0, 3, 255, 205, 41,  // 23
    9, 0, 7, 0, 4, 0, 0, 0, 0,  // 24
    7, 0, 8, 0, 4, 0, 0, 0, 0,  // 25
    9, 7, 1, 3, 0, 1, 72, 121, 80,  // 26
    0, 7, 2, 2, 0, 2, 255, 152, 143,  // 27
    0, 6, 1, 2, 0, 6, 255, 255, 255,  // 28
    1, 7, 1, 2, 0, 1, 255, 213, 198,  // 29
    5, 6, 0, 2, 0, 5, 204, 255, 170,  // 30
    5, 4, 1, 2, 0, 5, 44, 28, 89,  // 31
    5, 7, 1, 3, 0, 5, 85, 73, 41,  // 32
    1, 0, 2, 0, 2, 0, 0, 0, 0,  // 33
    3, 5, 1, 1, 0, 1, 225, 240, 215,  // 34
    7, 5, 3, 0, 0, 0, 255, 90, 178,  // 35
    0, 0, 0, 3, 0, 4, 196, 190, 168,  // 36
    3, 0, 0, 0, 0, 0, 0, 0, 0,  // 37
    3, 0, 4, 0, 0, 0, 0, 0, 0,  // 38
    7, 1, 1, 0, 0, 0, 255, 188, 66,  // 39
    7, 1, 1, 1, 0, 2, 113, 209, 222,  // 40
    7, 0, 0, 0, 0, 5, 124, 255, 234,  // 41
    9, 0, 1, 0, 0, 1, 239, 215, 131,  // 42
    7, 5, 1, 1, 0, 1, 255, 188, 66,  // 43
    3, 1, 1, 3, 0, 3, 42, 65, 213,  // 44
    4, 3, 1, 4, 0, 0, 56, 76, 21,  // 45
    2, 0, 7, 0, 0, 0, 0, 0, 0,  // 46
    4, 7, 2, 4, 0, 4, 115, 65, 35,  // 47
    5, 7, 0, 3, 0, 6, 203, 189, 178,  // 48
    0, 2, 1, 1, 0, 0, 153, 137, 117,  // 49
    5, 0, 7, 0, 2, 0, 0, 0, 0,  // 50
    3, 0, 8, 0, 0, 0, 0, 0, 0,  // 51
    6, 0, 8, 0, 3, 0, 0, 0, 0,  // 52
    5, 8, 0, 2, 0, 4, 9, 177, 210,  // 53
    2, 8, 3, 1, 0, 2, 255, 61, 0,  // 54
    2, 8, 0, 4, 0, 4, 201, 255, 22,  // 55
    6, 5, 0, 4, 0, 7, 251, 9, 16,  // 56
    6, 2, 2, 3, 0, 1, 227, 28, 89,  // 57
    2, 0, 8, 0, 0, 0, 0, 0, 0,  // 58
    3, 7, 1, 1, 0, 2, 205, 169, 136,  // 59
    4, 7, 2, 3, 0, 3, 247, 52, 31,  // 60
    7, 7, 2, 0, 0, 4, 21, 102, 212,  // 61
    6, 7, 0, 0, 0, 5, 0, 0, 0,  // 62
    9, 6, 1, 0, 0, 1, 128, 94, 78,  // 63
)

private const val APPEARANCE_FIELDS = 9

val SCRIPT_NPC_TEMPLATES: List<ScriptNpcTemplate> =
    SCRIPT_NPC_TEMPLATE_NAMES.mapIndexed { index, name ->
        val a = index * APPEARANCE_FIELDS
        val v = SCRIPT_NPC_TEMPLATE_APPEARANCES
        ScriptNpcTemplate(
            index,
            name,
            ScriptNpcClass.entries[SCRIPT_NPC_TEMPLATE_CLASSES[index]],
            ScriptNpcAppearance(v[a], v[a + 1], v[a + 2], v[a + 3], v[a + 4], v[a + 5], v[a + 6], v[a + 7], v[a + 8]),
        )
    }

fun scriptNpcTemplate(index: Int): ScriptNpcTemplate? = SCRIPT_NPC_TEMPLATES.getOrNull(index)

/**
 * Finds reachable, positioned NPC creation sites in a V2, V3, or V4 quest script.
 *
 * Position, angle, and template must resolve to constants. Runtime-only owner, slot, state,
 * radius, and controller values are retained when known but do not prevent a preview.
 *
 * Stack-only creations (`npc_crt_V3` and `npc_crtpk_V3`) are intentionally excluded because the
 * opcode does not provide a world position that the editor can render. Slot lifecycle opcodes are
 * also excluded from this creation-position view; they affect runtime behavior, not the creation
 * point shown by the editor.
 */
fun getScriptNpcSpawns(
    version: Version,
    episode: Episode,
    instructionSegments: List<InstructionSegment>,
    entityEntryPointFloorIds: Map<Int, Set<Int>> = emptyMap(),
    createCfg: () -> ControlFlowGraph,
): List<ScriptNpcSpawn> {
    if (version !in SUPPORTED_SCRIPT_NPC_VERSIONS) return emptyList()

    var cfg: ControlFlowGraph? = null
    var executionFloors: ExecutionFloors? = null
    val spawns = mutableListOf<ScriptNpcSpawn>()

    for (segment in instructionSegments) {
        for (inst in segment.instructions) {
            val registerCount = when (inst.opcode.code) {
                OP_NPC_CRP_V3_V3_V4.code,
                OP_NPC_CRPTALK_V3_V3_V4.code,
                -> 6

                OP_NPC_CRPPK_V3_V3_V4.code,
                OP_NPC_CRP_ID_V3_V3_V4.code,
                OP_NPC_CRPTALK_ID_V3_V3_V4.code,
                -> 7

                OP_NPC_TALK_PL_V3_V3_V4.code -> 8
                else -> continue
            }

            val firstReg = (inst.args.firstOrNull() as? IntArg)?.value ?: continue
            if (firstReg !in 0..(256 - registerCount)) continue

            val resolvedCfg = cfg ?: createCfg().also { cfg = it }
            val values = (0 until registerCount).map { offset ->
                val value = getRegisterValue(resolvedCfg, inst, firstReg + offset)
                if (value.size == 1L) value[0] else null
            }
            val requiredIndices = when (inst.opcode.code) {
                OP_NPC_CRP_ID_V3_V3_V4.code -> intArrayOf(0, 1, 2, 3, 6)
                OP_NPC_TALK_PL_V3_V3_V4.code -> intArrayOf(0, 1, 2, 4, 5)
                else -> intArrayOf(0, 1, 2, 3, 5)
            }
            if (requiredIndices.any { values[it] == null }) {
                logger.debug {
                    "Couldn't determine the positioned preview arguments for " +
                        "${inst.opcode.mnemonic} in segment " +
                        "with labels ${segment.labels}."
                }
                continue
            }

            val floors = executionFloors ?: computeExecutionFloors(
                resolvedCfg,
                instructionSegments,
                entityEntryPointFloorIds,
                logicalFloorCount(version, episode),
            ).also { executionFloors = it }
            val executionFloorIds = floors.floorsByInstruction[inst] ?: emptySet()
            if (executionFloorIds.isEmpty()) continue

            val spawn = when (inst.opcode.code) {
                OP_NPC_CRP_V3_V3_V4.code -> ScriptNpcSpawn(
                    opcode = ScriptNpcCreationOpcode.NpcCrp,
                    x = values[0]!!,
                    y = values[1]!!,
                    z = values[2]!!,
                    angle = values[3]!!,
                    ownerSlot = values[4].takeIf { version == Version.BB_V4 },
                    state = values[4].takeIf { version != Version.BB_V4 },
                    npcSlot = 1.takeIf { version != Version.BB_V4 },
                    templateIndex = values[5]!!,
                    executionFloorIds = executionFloorIds,
                )

                OP_NPC_CRPPK_V3_V3_V4.code -> ScriptNpcSpawn(
                    opcode = ScriptNpcCreationOpcode.NpcCrppk,
                    x = values[0]!!,
                    y = values[1]!!,
                    z = values[2]!!,
                    angle = values[3]!!,
                    ownerSlot = values[4].takeIf { version == Version.BB_V4 },
                    state = values[4].takeIf { version != Version.BB_V4 },
                    templateIndex = values[5]!!,
                    npcSlot = values[6],
                    executionFloorIds = executionFloorIds,
                )

                OP_NPC_CRPTALK_V3_V3_V4.code -> ScriptNpcSpawn(
                    opcode = ScriptNpcCreationOpcode.NpcCrptalk,
                    x = values[0]!!,
                    y = values[1]!!,
                    z = values[2]!!,
                    angle = values[3]!!,
                    state = values[4],
                    templateIndex = values[5]!!,
                    npcSlot = if (version == Version.BB_V4) 3 else 1,
                    executionFloorIds = executionFloorIds,
                )

                OP_NPC_CRP_ID_V3_V3_V4.code -> ScriptNpcSpawn(
                    opcode = ScriptNpcCreationOpcode.NpcCrpId,
                    x = values[0]!!,
                    y = values[1]!!,
                    z = values[2]!!,
                    angle = values[3]!!,
                    ownerSlot = values[4].takeIf { version == Version.BB_V4 },
                    state = values[4].takeIf { version != Version.BB_V4 },
                    npcSlot = values[5],
                    templateIndex = values[6]!!,
                    executionFloorIds = executionFloorIds,
                )

                OP_NPC_CRPTALK_ID_V3_V3_V4.code -> ScriptNpcSpawn(
                    opcode = ScriptNpcCreationOpcode.NpcCrptalkId,
                    x = values[0]!!,
                    y = values[1]!!,
                    z = values[2]!!,
                    angle = values[3]!!,
                    state = values[4],
                    templateIndex = values[5]!!,
                    npcSlot = values[6],
                    executionFloorIds = executionFloorIds,
                )

                OP_NPC_TALK_PL_V3_V3_V4.code -> ScriptNpcSpawn(
                    opcode = ScriptNpcCreationOpcode.NpcTalkPl,
                    x = values[0]!!,
                    y = values[1]!!,
                    z = values[2]!!,
                    visibilityRadius = values[3],
                    angle = values[4]!!,
                    templateIndex = values[5]!!,
                    state = values[6],
                    controllerToken = values[7].takeIf { version == Version.BB_V4 },
                    npcSlot = if (version == Version.BB_V4) 3 else values[7],
                    executionFloorIds = executionFloorIds,
                )

                else -> continue
            }

            if (scriptNpcTemplate(spawn.templateIndex) != null) {
                spawn.sourceInstruction = inst
                spawns.add(spawn)
            }
        }
    }

    if (spawns.isEmpty()) return emptyList()

    val resolvedExecutionFloors = checkNotNull(executionFloors)
    val interactionRegions = getScriptNpcInteractionRegions(
        checkNotNull(cfg),
        instructionSegments,
        resolvedExecutionFloors,
    )

    return spawns.map { spawn ->
        val interactions = interactionRegions.asSequence()
            .filter { region ->
                region.executionFloorIds.any { it in spawn.executionFloorIds } &&
                    region.contains(spawn.x, spawn.y, spawn.z)
            }
            .mapTo(linkedSetOf()) { it.interaction }

        if (interactions.isEmpty()) spawn else spawn.copy(interactions = interactions).also {
            it.sourceInstruction = spawn.sourceInstruction
        }
    }
}

private data class ScriptNpcInteractionRegion(
    val x: Int,
    val y: Int,
    val z: Int,
    val radius: Int,
    val interaction: ScriptNpcInteraction,
    val executionFloorIds: Set<Int>,
) {
    fun contains(otherX: Int, otherY: Int, otherZ: Int): Boolean {
        val dx = otherX.toDouble() - x
        val dy = otherY.toDouble() - y
        val dz = otherZ.toDouble() - z
        val r = abs(radius.toDouble())
        return dx * dx + dy * dy + dz * dz <= r * r
    }
}

private fun getScriptNpcInteractionRegions(
    cfg: ControlFlowGraph,
    instructionSegments: List<InstructionSegment>,
    executionFloors: ExecutionFloors,
): List<ScriptNpcInteractionRegion> {
    val regions = mutableListOf<ScriptNpcInteractionRegion>()

    for (segment in instructionSegments) {
        for (inst in segment.instructions) {
            val kind = when (inst.opcode.code) {
                OP_SET_OBJ_PARAM.code,
                OP_SET_OBJ_PARAM_EX.code,
                -> ScriptNpcInteractionKind.Target

                OP_AT_COORDS_TALK.code,
                OP_AT_COORDS_TALK_EX.code,
                -> ScriptNpcInteractionKind.Talk

                else -> continue
            }
            val firstReg = (inst.args.firstOrNull() as? IntArg)?.value ?: continue
            if (firstReg !in 0..(256 - 5)) continue
            val values = (0 until 5).map { offset ->
                val value = getRegisterValue(cfg, inst, firstReg + offset)
                if (value.size == 1L) value[0] else null
            }
            if (values.any { it == null }) continue
            val executionFloorIds = executionFloors.floorsByInstruction[inst] ?: emptySet()
            if (executionFloorIds.isEmpty()) continue

            regions.add(ScriptNpcInteractionRegion(
                x = values[0]!!,
                y = values[1]!!,
                z = values[2]!!,
                radius = values[3]!!,
                interaction = ScriptNpcInteraction(values[4]!!, kind).also {
                    it.sourceInstruction = inst
                    it.executionFloorIds = executionFloorIds
                },
                executionFloorIds = executionFloorIds,
            ))
        }
    }

    return regions
}

private val SUPPORTED_SCRIPT_NPC_VERSIONS = setOf(
    Version.DC_V2,
    Version.PC_V2,
    Version.GC_V3,
    Version.BB_V4,
)

private fun logicalFloorCount(version: Version, episode: Episode): Int = when (episode) {
    Episode.I -> CLIENT_LOGICAL_FLOOR_COUNT
    Episode.II -> if (version == Version.GC_V3 || version == Version.BB_V4) CLIENT_LOGICAL_FLOOR_COUNT else 0
    Episode.IV -> if (version == Version.BB_V4) CLIENT_LOGICAL_FLOOR_COUNT else 0
}
