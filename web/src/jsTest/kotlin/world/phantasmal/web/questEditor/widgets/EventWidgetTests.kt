package world.phantasmal.web.questEditor.widgets

import world.phantasmal.web.questEditor.controllers.EventsController
import world.phantasmal.web.questEditor.models.QuestEventModel
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import kotlin.test.Test
import kotlin.test.assertEquals

class EventWidgetTests : WebTestSuite {
    @Test
    fun selecting_an_event_scrolls_its_card_into_view() = testAsync {
        val event = QuestEventModel(
            id = 100,
            floorId = 0,
            sectionId = 7,
            waveId = 4,
            delay = 0,
            unknown = 0,
            actions = mutableListOf(),
        )
        val store = components.questEditorStore
        store.setCurrentQuest(createQuestModel(events = listOf(event)))
        val controller = disposer.add(
            EventsController(store, components.playbackVisualizationStore),
        )
        var scrollCount = 0
        val widget = disposer.add(EventWidget(controller, event))
        val element = widget.element
        element.asDynamic().scrollIntoView = { _: dynamic ->
            scrollCount++
        }

        store.setSelectedEvent(event)

        assertEquals(1, scrollCount)
    }
}
