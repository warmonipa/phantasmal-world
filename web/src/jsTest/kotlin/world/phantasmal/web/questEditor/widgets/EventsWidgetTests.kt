package world.phantasmal.web.questEditor.widgets

import org.w3c.dom.HTMLElement
import world.phantasmal.web.questEditor.controllers.EventsController
import world.phantasmal.web.questEditor.models.QuestEventModel
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventsWidgetTests : WebTestSuite {
    @Test
    fun seed_controls_are_visible_for_challenge_events() = testAsync {
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
        val quest = createQuestModel(events = listOf(event))
        components.questEditorStore.setCurrentQuest(quest)
        components.questEditorStore.setCurrentArea(quest.areaVariants.value.first().area)
        val controller = disposer.add(EventsController(
            components.questEditorStore,
            components.playbackVisualizationStore,
        ))
        val widget = disposer.add(EventsWidget(controller))

        val toolbar = widget.element.querySelector(".pw-quest-editor-events-seed-toolbar") as HTMLElement
        val text = toolbar.textContent.orEmpty()
        assertFalse(toolbar.hidden)
        assertTrue("Simulate seed" in text)
        assertTrue("Seed:" in text)
        assertTrue("Next run" in text)
    }

    @Test
    fun seed_controls_are_hidden_for_ordinary_events() = testAsync {
        val event = QuestEventModel(
            id = 1,
            floorId = 0,
            sectionId = 1,
            waveId = 1,
            delay = 0,
            unknown = 0,
            actions = mutableListOf(),
        )
        val quest = createQuestModel(events = listOf(event))
        components.questEditorStore.setCurrentQuest(quest)
        components.questEditorStore.setCurrentArea(quest.areaVariants.value.first().area)
        val controller = disposer.add(EventsController(
            components.questEditorStore,
            components.playbackVisualizationStore,
        ))
        val widget = disposer.add(EventsWidget(controller))

        val toolbar = widget.element.querySelector(".pw-quest-editor-events-seed-toolbar") as HTMLElement
        assertTrue(toolbar.hidden)
    }
}
