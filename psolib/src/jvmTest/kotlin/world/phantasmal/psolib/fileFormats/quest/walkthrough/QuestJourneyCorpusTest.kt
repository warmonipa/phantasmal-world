package world.phantasmal.psolib.fileFormats.quest.walkthrough

import world.phantasmal.core.Success
import world.phantasmal.psolib.asm.dataFlowAnalysis.ControlFlowGraph
import world.phantasmal.psolib.asm.dataFlowAnalysis.analyzeWalkthroughScript
import world.phantasmal.psolib.asm.dataFlowAnalysis.scanScriptFloorTransitions
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.fileFormats.quest.Quest
import world.phantasmal.psolib.fileFormats.quest.activeScriptLabelOrNull
import world.phantasmal.psolib.fileFormats.quest.getQuestScriptSpatialInteractions
import world.phantasmal.psolib.fileFormats.quest.parseQstToQuest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Plans every client's journey for every test quest and checks known walkthroughs. */
class QuestJourneyCorpusTest {
    private val root = File("src/commonTest/resources/quests")

    @Test
    fun journeys_cover_the_corpus() {
        val missingArrivals = mutableListOf<String>()
        val unknownOrderQuests = sortedSetOf<String>()
        val files = root.walkTopDown().filter { it.isFile && it.name.endsWith(".qst") }.toList()
        assertTrue(files.isNotEmpty())

        for (file in files) {
            val quest = load(file)
            for (clientId in 0..3) {
                val journey = try {
                    plan(quest, clientId)
                } catch (e: Throwable) {
                    fail("${file.relativeTo(root)} client $clientId: $e")
                }
                val clientFloors = playerSetsForClient(quest.objects, clientId)
                    .mapTo(mutableSetOf()) { it.floorId }
                for (visit in journey.visits) {
                    if (visit.floorId in clientFloors && visit.arrival == null) {
                        missingArrivals += "${file.nameWithoutExtension} client $clientId floor ${visit.floorId}"
                    }
                }
                if (journey.visits.any { !it.orderKnown }) {
                    unknownOrderQuests += file.nameWithoutExtension
                }
            }
        }

        // Floors with the client's Player Sets always yield an arrival, falling back to the other
        // Return flag where quests (often those that disable telepipes) omit return sets.
        assertEquals(emptyList(), missingArrivals)
        assertEquals(EXPECTED_UNKNOWN_ORDER, unknownOrderQuests.toSet())
    }

    @Test
    fun known_quests_have_the_expected_floor_order() {
        val expected = mapOf(
            "ep1/gov/1-2 torrential woods.qst" to listOf(0, 1, 2, 0),
            "ep1/gov/2-1 infernal cavern.qst" to listOf(0, 3, 4, 0),
            "ep2/lab/6-1 spaceship 1.qst" to listOf(0, 3, 0),
            "ep2/lab/5-2 vr temple 2.qst" to listOf(0, 1, 2, 0),
            "ep2/lab/6-2 spaceship 2.qst" to listOf(0, 3, 4, 0),
            "ep4/lost son hopkins.qst" to listOf(0, 6, 7, 8, 9, 0),
            "chl/ep1/1c2_e.qst" to listOf(3, 4, 5, 6, 7),
        )
        for ((path, floors) in expected) {
            val quest = load(File(root, path))
            for (clientId in 0..3) {
                assertEquals(
                    floors,
                    plan(quest, clientId).visits.map { it.floorId },
                    "$path client $clientId",
                )
            }
        }
    }

    @Test
    fun scripted_relocations_are_resolved() {
        val quest = load(File(root, "solo/ep1/22 soul of steel.qst"))
        val cfg = ControlFlowGraph.create(quest.bytecodeIr)
        val relocations = scriptEntries(quest).flatMap { (label, floorId) ->
            analyzeWalkthroughScript(quest.bytecodeIr, label, floorId, 0, cfg).relocations
        }
        assertTrue(relocations.isNotEmpty())
    }

    private fun load(file: File): Quest =
        (parseQstToQuest(Buffer.fromByteArray(file.readBytes()).cursor(), lenient = true)
            as Success).value.quest

    private fun scriptEntries(quest: Quest): List<Pair<Int, Int>> = buildList {
        add(0 to 0)
        for (obj in quest.objects) obj.activeScriptLabel?.let { add(it to obj.floorId) }
        for (npc in quest.npcs) npc.activeScriptLabelOrNull()?.let { add(it to npc.floorId) }
        for (interaction in getQuestScriptSpatialInteractions(
            quest.bytecodeIr, quest.objects, quest.npcs,
        )) {
            for (floorId in interaction.executionFloorIds) add(interaction.event.label to floorId)
        }
    }

    private fun plan(quest: Quest, clientId: Int): QuestJourney {
        val cfg = ControlFlowGraph.create(quest.bytecodeIr)
        val analyses = scriptEntries(quest).map { (label, floorId) ->
            analyzeWalkthroughScript(quest.bytecodeIr, label, floorId, clientId, cfg)
        }
        return planQuestJourney(
            quest.episode,
            quest.objects,
            quest.floorMappings,
            analyses,
            quest.events.any { it.isChallengeMode } || usesChallengeModeScript(quest.bytecodeIr),
            clientId,
            scanScriptFloorTransitions(
                quest.bytecodeIr, clientId, cfg,
                excluded = analyses.flatMapTo(mutableSetOf()) { it.reachableInstructionFloors.keys },
            ),
        )
    }

    private companion object {
        /** `aol cup -maximum attack-` offers two courses from a Pioneer 2 menu; one is played. */
        val EXPECTED_UNKNOWN_ORDER = setOf("aol cup -maximum attack- (e)")
    }
}
