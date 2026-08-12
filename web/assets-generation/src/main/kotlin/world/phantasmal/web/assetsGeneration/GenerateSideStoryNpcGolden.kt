package world.phantasmal.web.assetsGeneration

import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptNpcCreationOpcode
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptNpcSpawn
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.fileFormats.quest.Version
import world.phantasmal.psolib.fileFormats.quest.parseQstToQuest
import java.io.File
import java.security.MessageDigest

internal val SIDE_STORY_QUEST_RESOURCES: List<String> = buildList {
    for (questId in 1..26) {
        add("/ephinea/ship-config/quest/episode_1/story/side_story/quest${questId}_e.qst")
    }
    add("/ephinea/ship-config/quest/episode_2/story/side_story/quest27_e.qst")
    add("/ephinea/ship-config/quest/episode_2/story/side_story/quest486_e.qst")
    for (questId in listOf(30, 31, 32, 33, 34, 36)) {
        add("/ephinea/ship-config/quest/episode_4/story/side_story/quest${questId}_e.qst")
    }
}

fun main(args: Array<String>) {
    require(args.size == 1) { "Expected the golden TSV output path." }
    File(args.single()).writeText(generateSideStoryNpcGolden())
}

internal fun generateSideStoryNpcGolden(): String = buildString {
    val rawOpcodeCounts = ScriptNpcCreationOpcode.entries.associateWith { 0 }.toMutableMap()
    appendLine(
        listOf(
            "resource",
            "qst_sha256",
            "quest_name",
            "spawn_index",
            "opcode",
            "kind",
            "x",
            "y",
            "z",
            "angle",
            "template_index",
            "template_name",
            "owner_slot",
            "npc_slot",
            "state",
            "visibility_radius",
            "controller_token",
            "execution_floors",
            "interactions",
        ).joinToString("\t")
    )

    for (resource in SIDE_STORY_QUEST_RESOURCES) {
        val qst = Buffer.fromResource(resource)
        val questData = parseQstToQuest(qst.cursor(), lenient = false).unwrap()
        check(questData.version == Version.BB_V4) {
            "$resource was detected as ${questData.version}, expected BB_V4."
        }
        val quest = questData.quest
        for (segment in quest.bytecodeIr.instructionSegments()) {
            for (instruction in segment.instructions) {
                val opcode = ScriptNpcCreationOpcode.entries.firstOrNull {
                    it.code == instruction.opcode.code
                } ?: continue
                rawOpcodeCounts[opcode] = rawOpcodeCounts.getValue(opcode) + 1
            }
        }
        val spawns = quest.scriptNpcSpawns.sortedWith(SCRIPT_NPC_SPAWN_ORDER)
        val common = listOf(
            resource,
            sha256(qst.byteArray),
            tsvField(quest.name),
        )

        if (spawns.isEmpty()) {
            appendLine((common + listOf("0", "NONE") + List(14) { "-" }).joinToString("\t"))
        } else {
            spawns.forEachIndexed { index, spawn ->
                appendLine(
                    (common + listOf(
                        (index + 1).toString(),
                        spawn.opcode.mnemonicFor(Version.BB_V4),
                        spawn.kind.name,
                        spawn.x.toString(),
                        spawn.y.toString(),
                        spawn.z.toString(),
                        spawn.angle.toString(),
                        spawn.templateIndex.toString(),
                        spawnTemplateName(spawn),
                        spawn.ownerSlot?.toString() ?: "-",
                        spawn.npcSlot?.toString() ?: "-",
                        spawn.state?.toString() ?: "-",
                        spawn.visibilityRadius?.toString() ?: "-",
                        spawn.controllerToken?.toString() ?: "-",
                        spawn.executionFloorIds.sorted().joinToString(","),
                        spawn.interactions
                            .sortedWith(compareBy({ it.label }, { it.kind.ordinal }))
                            .joinToString(",") { interaction ->
                                "${interaction.kind}:0x${interaction.label.toString(16).uppercase()}"
                            }
                            .ifEmpty { "-" },
                    )).joinToString("\t")
                )
            }
        }
    }

    check(rawOpcodeCounts == EXPECTED_NEWSERV_RAW_OPCODE_COUNTS) {
        "The Side Story creation-opcode inventory changed: $rawOpcodeCounts"
    }
}

/**
 * Independently counted in newserv's BB/QEdit disassembly of the 33 QSTs it accepts. Ephinea's
 * quest3_e.qst has duplicate chunks that newserv rejects; psolib parses it and finds no such opcode.
 */
private val EXPECTED_NEWSERV_RAW_OPCODE_COUNTS = mapOf(
    ScriptNpcCreationOpcode.NpcCrp to 14,
    ScriptNpcCreationOpcode.NpcCrppk to 35,
    ScriptNpcCreationOpcode.NpcCrptalk to 15,
    ScriptNpcCreationOpcode.NpcCrpId to 97,
    ScriptNpcCreationOpcode.NpcCrptalkId to 67,
    ScriptNpcCreationOpcode.NpcTalkPl to 3,
)

private val SCRIPT_NPC_SPAWN_ORDER = compareBy<ScriptNpcSpawn>(
    { it.opcode.mnemonicFor(Version.BB_V4) },
    { it.executionFloorIds.sorted().joinToString(",") },
    { it.templateIndex },
    { it.x },
    { it.y },
    { it.z },
    { it.angle },
    { it.ownerSlot },
    { it.npcSlot },
    { it.state },
    { it.visibilityRadius },
    { it.controllerToken },
)

private fun spawnTemplateName(spawn: ScriptNpcSpawn): String =
    world.phantasmal.psolib.asm.dataFlowAnalysis.scriptNpcTemplate(spawn.templateIndex)?.name
        ?: error("Unknown NPC template ${spawn.templateIndex}.")

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

private fun tsvField(value: String): String {
    require('\t' !in value && '\n' !in value && '\r' !in value) {
        "TSV field contains a control character: $value"
    }
    return value
}
