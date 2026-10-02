package world.phantasmal.web.questEditor.asm

import world.phantasmal.psolib.asm.dataFlowAnalysis.FloorMapping
import world.phantasmal.web.shared.messages.AsmRange
import world.phantasmal.web.shared.messages.Label
import world.phantasmal.web.shared.messages.ServerNotification
import world.phantasmal.web.test.WebTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals

class AsmAnalyserTests : WebTestSuite {
    @Test
    fun floor_context_changes_discard_old_results_and_equal_sets_keep_the_generation() = test {
        val analyser = AsmAnalyser()
        analyser.setAsm(listOf("0:", "    ret"))
        val oldGeneration = analyser.asmGeneration
        analyser.updateUsedFloorIds(setOf(7))
        val generation = analyser.asmGeneration
        analyser.updateUsedFloorIds(setOf(7))
        assertEquals(generation, analyser.asmGeneration)

        analyser.receiveMessage(ServerNotification.FloorMappings(
            oldGeneration, listOf(FloorMapping(6, 6, 6, 0)),
        ))
        assertEquals(emptyList(), analyser.floorMappings.value)

        val mappings = listOf(FloorMapping(7, 7, 7, 0))
        analyser.receiveMessage(ServerNotification.FloorMappings(generation, mappings))
        assertEquals(mappings, analyser.floorMappings.value)
    }

    @Test
    fun notifications_about_a_previously_set_script_are_ignored() = test {
        val analyser = AsmAnalyser()
        analyser.setAsm(listOf("0:", "    ret"))
        val staleGeneration = analyser.asmGeneration
        analyser.setAsm(listOf("1:", "    ret"))

        analyser.receiveMessage(
            ServerNotification.Labels(staleGeneration, listOf(Label(0, AsmRange(1, 1, 1, 3)))),
        )

        assertEquals(emptyList(), analyser.labels.value)

        analyser.receiveMessage(
            ServerNotification.Labels(
                analyser.asmGeneration,
                listOf(Label(1, AsmRange(1, 1, 1, 3))),
            ),
        )

        assertEquals(listOf(1), analyser.labels.value.map { it.name })
    }
}
