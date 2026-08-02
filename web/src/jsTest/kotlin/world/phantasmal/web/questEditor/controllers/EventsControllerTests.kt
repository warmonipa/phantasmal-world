package world.phantasmal.web.questEditor.controllers

import world.phantasmal.psolib.asm.dataFlowAnalysis.FloorMapping
import world.phantasmal.psolib.fileFormats.quest.ChallengeModeSeedSimulation
import world.phantasmal.psolib.fileFormats.quest.ChallengeModeSimulatedWave
import world.phantasmal.psolib.fileFormats.quest.DatCmConfigPool
import world.phantasmal.psolib.fileFormats.quest.DatCmConfigPoolEntry
import world.phantasmal.psolib.fileFormats.quest.DatCmMonsterMapping
import world.phantasmal.psolib.fileFormats.quest.DatCmMonsterMappingEntry
import world.phantasmal.psolib.fileFormats.quest.DatCmRandomSpawn
import world.phantasmal.psolib.fileFormats.quest.DatCmRandomSpawnEntry
import world.phantasmal.web.questEditor.models.QuestEventActionModel
import world.phantasmal.web.questEditor.models.QuestEventModel
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertSame

class EventsControllerTests : WebTestSuite {
    @Test
    fun seed_toggle_switches_between_source_event2_and_read_only_event1_list() = testAsync {
        val source = QuestEventModel(
            id = 100,
            floorId = 0,
            sectionId = 1,
            waveId = 4,
            delay = 10,
            unknown = 20,
            actions = mutableListOf(QuestEventActionModel.Door.Unlock(8)),
            cmWaveSettings = 0x0001_0101,
        )
        val quest = createQuestModel(events = listOf(source))
        quest.addCmRandomSpawn(DatCmRandomSpawn(
            0, 1, mutableListOf(DatCmRandomSpawnEntry(0f, 0f, 0f, 0, 0, 0, 0, 0)),
        ))
        quest.addCmConfigPool(DatCmConfigPool(0, mutableListOf(
            DatCmConfigPoolEntry(0f, 0f, 0f, 0f, 0f, 0, 0, 1, 0, 0, 0),
        )))
        quest.addCmMonsterMapping(DatCmMonsterMapping(0, mutableListOf(
            DatCmMonsterMappingEntry(0, 1, 1, 0),
        )))
        val store = components.questEditorStore
        store.setCurrentQuest(quest)
        store.setCurrentArea(quest.areaVariants.value.first().area)
        val ctrl = disposer.add(EventsController(store, components.playbackVisualizationStore))

        assertSame(source, ctrl.events.value.single())
        assertTrue(ctrl.enabled.value)

        store.setChallengeSeedSimulationEnabled(true)

        val generated = ctrl.events.value.single()
        assertEquals(100, generated.id.value)
        assertEquals(100, generated.challengeSourceEventId)
        assertEquals(null, generated.cmWaveSettings.value)
        assertFalse(ctrl.enabled.value, "Materialized Event1 preview must be read-only.")

        store.setChallengeSeedSimulationEnabled(false)

        assertSame(source, ctrl.events.value.single())
        assertTrue(ctrl.enabled.value)
    }

    @Test
    fun seed_preview_replaces_event2_configuration_with_standard_event1_chain() = testAsync {
        val fixed = QuestEventModel(7, 0, 1, 1, 5, 0, mutableListOf())
        val source = QuestEventModel(
            id = 100,
            floorId = 1,
            sectionId = 11,
            waveId = 4,
            delay = 10,
            unknown = 20,
            actions = mutableListOf(QuestEventActionModel.Door.Unlock(8)),
            cmWaveSettings = 0x0002_0302,
        )
        val simulation = ChallengeModeSeedSimulation(
            seed = 0x12345678u,
            waves = listOf(
                ChallengeModeSimulatedWave(
                    1, 100, 4, 11, 12, emptyList(),
                    materializedEventId = 100,
                    triggeredEventId = 10104,
                ),
                ChallengeModeSimulatedWave(
                    1, 100, 5, 11, 18, emptyList(),
                    materializedEventId = 10104,
                    triggeredEventId = null,
                ),
            ),
        )

        val displayed = materializeChallengeEventModels(simulation, listOf(fixed, source))

        assertTrue(displayed[0] === fixed, "Fixed Event1 entries must remain unchanged.")
        assertEquals(listOf(100, 10104), displayed.drop(1).map { it.id.value })
        assertEquals(listOf(4, 5), displayed.drop(1).map { it.wave.value.id })
        assertEquals(listOf(12, 18), displayed.drop(1).map { it.delay.value })
        assertTrue(displayed.drop(1).all { it.cmWaveSettings.value == null })
        assertTrue(displayed.drop(1).all { it.challengeSourceEventId == 100 })
        assertEquals(
            10104,
            (displayed[1].actions.value.single() as QuestEventActionModel.TriggerEvent).eventId.value,
        )
        assertEquals(
            8,
            (displayed[2].actions.value.single() as QuestEventActionModel.Door.Unlock).doorId.value,
        )
    }

    @Test
    fun addEvent() = testAsync {
        // Setup.
        val store = components.questEditorStore
        val quest = createQuestModel(floorMappings = listOf(FloorMapping(1, 1, 1, 0)))
        store.setCurrentQuest(quest)
        store.setCurrentArea(quest.areaVariants.value.first().area)
        store.makeMainUndoCurrent()

        val ctrl = disposer.add(EventsController(store, components.playbackVisualizationStore))

        // Add an event.
        ctrl.addEvent()

        assertEquals(1, quest.events.value.size)

        // Undo.
        assertTrue(store.canUndo.value)
        assertFalse(store.canRedo.value)

        store.undo()

        assertTrue(quest.events.value.isEmpty())

        // Redo.
        assertFalse(store.canUndo.value)
        assertTrue(store.canRedo.value)

        store.redo()

        assertEquals(1, quest.events.value.size)
        assertTrue(store.canUndo.value)
        assertFalse(store.canRedo.value)
    }

    @Test
    fun addAction() = testAsync {
        // Setup.
        val store = components.questEditorStore
        val quest = createQuestModel(floorMappings = listOf(FloorMapping(1, 1, 1, 0)))
        store.setCurrentQuest(quest)
        store.setCurrentArea(quest.areaVariants.value.first().area)
        store.makeMainUndoCurrent()

        val ctrl = disposer.add(EventsController(store, components.playbackVisualizationStore))

        // Add an event and an action.
        ctrl.addEvent()
        val event = ctrl.events.value.first()
        ctrl.addAction(event, QuestEventActionModel.Door.Unlock.SHORT_NAME)

        // Undo.
        assertTrue(store.canUndo.value)
        assertFalse(store.canRedo.value)

        store.undo()

        assertTrue(event.actions.value.isEmpty())

        // Redo.
        assertTrue(store.canUndo.value) // Can still undo event creation at this point.
        assertTrue(store.canRedo.value)

        store.redo()

        assertEquals(1, event.actions.value.size)
    }

    @Test
    fun canGoToEvent() = testAsync {
        // Setup.
        val store = components.questEditorStore
        // Quest with two events, the first event triggers the second event.
        val quest = createQuestModel(
            floorMappings = listOf(FloorMapping(1, 1, 1, 0)),
            events = listOf(
                QuestEventModel(
                    id = 100,
                    floorId = 1,
                    sectionId = 11,
                    waveId = 1,
                    delay = 50,
                    unknown = 0,
                    actions = mutableListOf(QuestEventActionModel.TriggerEvent(101)),
                ),
                QuestEventModel(
                    id = 101,
                    floorId = 1,
                    sectionId = 11,
                    waveId = 2,
                    delay = 50,
                    unknown = 0,
                    actions = mutableListOf(QuestEventActionModel.Door.Unlock(7)),
                ),
            ),
        )
        store.setCurrentQuest(quest)
        store.setCurrentArea(quest.areaVariants.value.first().area)

        val ctrl = disposer.add(EventsController(store, components.playbackVisualizationStore))

        val canGoToEvent = ctrl.canGoToEvent(
            (ctrl.events[0].actions[0] as QuestEventActionModel.TriggerEvent).eventId
        )

        assertEquals(true, canGoToEvent.value)

        // Let event 100 point to nonexistent event 102.
        ctrl.setActionEventId(
            ctrl.events[0],
            ctrl.events[0].actions[0] as QuestEventActionModel.TriggerEvent,
            102,
        )

        assertEquals(false, canGoToEvent.value)

        // Add event 102.
        ctrl.selectEvent(null) // Deselect so the next event will be added at the end of the list.
        ctrl.addEvent()
        ctrl.setId(ctrl.events.value.last(), 102)

        assertEquals(true, canGoToEvent.value)

        // Remove event 102.
        ctrl.removeEvent(ctrl.events.value.last())

        assertEquals(false, canGoToEvent.value)
    }
}
