package world.phantasmal.web.questEditor.widgets

import org.w3c.dom.HTMLInputElement
import world.phantasmal.web.questEditor.controllers.MonsterRandomnessController
import world.phantasmal.web.questEditor.models.QuestEventModel
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonsterRandomnessWidgetTests : WebTestSuite {
    @Test
    fun seed_controls_follow_controller_state_in_the_dom() = testAsync {
        components.questEditorStore.setCurrentQuest(createQuestModel())
        val controller = disposer.add(MonsterRandomnessController(components.questEditorStore))
        val widget = disposer.add(MonsterRandomnessWidget(controller))

        val inputs = widget.element.querySelectorAll("input")
        val checkbox = inputs.item(0) as HTMLInputElement
        val seed = inputs.item(1) as HTMLInputElement

        assertEquals("checkbox", checkbox.type)
        assertFalse(checkbox.checked)
        assertTrue(seed.disabled)
        assertEquals("00000000", seed.value)

        controller.setSeedHex("FFFFFFFF")
        controller.setSimulateSeed(true)

        assertTrue(checkbox.checked)
        assertFalse(seed.disabled)
        assertEquals("FFFFFFFF", seed.value)

        controller.nextSeed()
        assertEquals("00000000", seed.value, "The 32-bit JoinGame seed wraps around.")
    }

    @Test
    fun invalid_random_tables_are_visible_in_the_seed_preview() = testAsync {
        val event = QuestEventModel(
            id = 1,
            floorId = 0,
            sectionId = 1,
            waveId = 1,
            delay = 0,
            unknown = 0,
            actions = mutableListOf(),
            cmWaveSettings = 0x010101,
        )
        components.questEditorStore.setCurrentQuest(createQuestModel(events = listOf(event)))
        components.questEditorStore.setChallengeSeedSimulationEnabled(true)
        val controller = disposer.add(MonsterRandomnessController(components.questEditorStore))
        val widget = disposer.add(MonsterRandomnessWidget(controller))

        val problems = widget.element.querySelector(".pw-quest-editor-mr-problems")!!
        assertFalse((problems as org.w3c.dom.HTMLElement).hidden)
        assertTrue("Random location table is missing" in problems.textContent.orEmpty())
        assertTrue("Random enemy definition table is missing" in problems.textContent.orEmpty())
        assertTrue("Random enemy weight table is missing" in problems.textContent.orEmpty())
    }
}
