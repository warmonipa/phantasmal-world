package world.phantasmal.web.questEditor.widgets

import org.w3c.dom.HTMLElement
import world.phantasmal.core.Success
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.assemble
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptNpcCreationOpcode
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptNpcInteraction
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptNpcInteractionKind
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptNpcSpawn
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.fileFormats.quest.QuestNpc
import world.phantasmal.psolib.fileFormats.quest.Version
import world.phantasmal.web.questEditor.controllers.EntityInfoController
import world.phantasmal.web.questEditor.controllers.EntityInfoPropModel
import world.phantasmal.web.questEditor.controllers.EventsController
import world.phantasmal.web.questEditor.models.QuestEventModel
import world.phantasmal.web.questEditor.models.QuestNpcModel
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import world.phantasmal.web.test.createQuestNpcModel
import world.phantasmal.web.test.createQuestObjectModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EntityInfoWidgetTests : WebTestSuite {
    @Test
    fun event_id_button_activates_events_and_scrolls_to_the_exact_event_card() = testAsync {
        val objectModel = createQuestObjectModel(ObjectType.EventCollision)
        val event = QuestEventModel(100, 0, 7, 4, 0, 0, mutableListOf())
        val store = components.questEditorStore
        store.setCurrentQuest(createQuestModel(objects = listOf(objectModel), events = listOf(event)))
        store.setSelectedEntity(objectModel)
        var activationCount = 0
        val entityController = disposer.add(EntityInfoController(
            components.areaStore,
            store,
            components.questEditorUiStore,
            components.asmStore,
            onActivateEventsWidget = {
                assertTrue(store.selectedEvent.value == null)
                activationCount++
            },
        ))
        entityController.props.value
            .filterIsInstance<EntityInfoPropModel.I32>()
            .single { it.showGoToEvent }
            .setValue(100)
        val eventsController = disposer.add(
            EventsController(store, components.playbackVisualizationStore),
        )
        var scrollCount = 0
        val eventWidget = disposer.add(EventWidget(eventsController, event))
        eventWidget.element.asDynamic().scrollIntoView = { _: dynamic -> scrollCount++ }
        val entityWidget = disposer.add(EntityInfoWidget(entityController))
        val button = assertNotNull(
            entityWidget.element.querySelector("[title='Go to event']") as? HTMLElement,
        )

        button.click()

        assertEquals(1, activationCount)
        assertEquals(event, store.selectedEvent.value)
        assertEquals(1, scrollCount)
    }

    @Test
    fun npc_script_navigation_activates_the_editor_and_navigates_to_the_label() = testAsync {
        val bytecode = assemble(listOf("0:", "ret", "310:", "ret"), Version.BB_V4)
        assertTrue(bytecode is Success)
        val npc = createQuestNpcModel(
            world.phantasmal.psolib.fileFormats.quest.NpcType.Principal,
            Episode.I,
        )
        val store = components.questEditorStore
        store.setCurrentQuest(createQuestModel(npcs = listOf(npc), bytecodeIr = bytecode.value))
        store.setSelectedEntity(npc)
        var activationCount = 0
        val controller = disposer.add(EntityInfoController(
            components.areaStore,
            store,
            components.questEditorUiStore,
            components.asmStore,
            onActivateAsmEditor = { activationCount++ },
        ))
        val scriptProp = controller.props.value
            .filterIsInstance<EntityInfoPropModel.F32>()
            .single { it.isScriptLabel }
        scriptProp.setValue(310.0001220703125)
        val widget = disposer.add(EntityInfoWidget(controller))
        val button = assertNotNull(
            widget.element.querySelector("[title='Go to script label']") as? HTMLElement,
        )

        button.click()

        assertEquals(1, activationCount)
        val range = assertNotNull(components.asmStore.takePendingGoToLabelRange())
        assertTrue(range.startLineNo > 1, "Expected label 310, not the first script line: $range")
        assertTrue("Click to open script" !in (widget.element.textContent ?: ""))
    }

    @Test
    fun npc_label_missing_from_the_quest_script_does_not_open_the_script_at_line_one() = testAsync {
        val bytecode = assemble(listOf("0:", "ret"), Version.BB_V4)
        assertTrue(bytecode is Success)
        val npc = createQuestNpcModel(NpcType.MaleFat, Episode.I)
        val store = components.questEditorStore
        store.setCurrentQuest(createQuestModel(npcs = listOf(npc), bytecodeIr = bytecode.value))
        store.setSelectedEntity(npc)
        var activationCount = 0
        val controller = disposer.add(EntityInfoController(
            components.areaStore,
            store,
            components.questEditorUiStore,
            components.asmStore,
            onActivateAsmEditor = { activationCount++ },
        ))
        val scriptProp = controller.props.value
            .filterIsInstance<EntityInfoPropModel.F32>()
            .single { it.isScriptLabel }
        scriptProp.setValue(310.0)
        val widget = disposer.add(EntityInfoWidget(controller))
        val button = assertNotNull(
            widget.element.querySelector("button[title='Go to script label']") as? HTMLElement,
        )

        assertTrue(scriptProp.canGoToScriptLabel.value)
        assertFalse(button.hasAttribute("disabled"))
        button.click()

        assertEquals(0, activationCount)
        assertEquals(null, components.asmStore.takePendingGoToLabelRange())
    }

    @Test
    fun script_npc_metadata_and_interaction_navigation_are_rendered() = testAsync {
        val bytecode = assemble(listOf("320:", "ret"), Version.BB_V4)
        assertTrue(bytecode is Success)
        val npc = QuestNpcModel(
            QuestNpc(NpcType.NpcRAmar, Episode.I, floorId = 0, wave = 0),
            waveId = 0,
            scriptSpawn = ScriptNpcSpawn(
                opcode = ScriptNpcCreationOpcode.NpcCrptalk,
                x = 113,
                y = 0,
                z = 64,
                angle = 60,
                templateIndex = 27,
                executionFloorIds = setOf(0),
                interactions = setOf(
                    ScriptNpcInteraction(320, ScriptNpcInteractionKind.Target),
                ),
            ),
        )
        val store = components.questEditorStore
        store.setCurrentQuest(createQuestModel(bytecodeIr = bytecode.value))
        store.setSelectedEntity(npc)
        var activationCount = 0
        val controller = disposer.add(EntityInfoController(
            components.areaStore,
            store,
            components.questEditorUiStore,
            components.asmStore,
            onActivateAsmEditor = { activationCount++ },
        ))
        val widget = disposer.add(EntityInfoWidget(controller))
        val text = widget.element.textContent ?: ""

        assertTrue("npc_crptalk_v3 (0x7D)" in text)
        assertTrue("NPC (Script)" in text)
        assertTrue("DACCI (27)" in text)
        assertTrue("Target 0x140" in text)
        assertTrue("Wave:" in text)
        assertTrue("Script label:" in text)
        val button = assertNotNull(
            widget.element.querySelector("[title='Go to script label']") as? HTMLElement,
        )

        button.click()

        assertEquals(1, activationCount)
        assertNotNull(components.asmStore.takePendingGoToLabelRange())
    }

    @Test
    fun script_navigation_button_activates_the_editor_and_navigates_to_the_label() = testAsync {
        val bytecode = assemble(listOf("0:", "ret"), Version.BB_V4)
        assertTrue(bytecode is Success)
        val objectModel = createQuestObjectModel(ObjectType.ScriptCollision)
        val store = components.questEditorStore
        store.setCurrentQuest(createQuestModel(
            objects = listOf(objectModel),
            bytecodeIr = bytecode.value,
        ))
        store.setSelectedEntity(objectModel)
        var activationCount = 0
        val controller = disposer.add(EntityInfoController(
            components.areaStore,
            store,
            components.questEditorUiStore,
            components.asmStore,
            onActivateAsmEditor = { activationCount++ },
        ))
        val widget = disposer.add(EntityInfoWidget(controller))
        val button = assertNotNull(
            widget.element.querySelector("[title='Go to script label']") as? HTMLElement,
        )

        button.click()

        assertEquals(1, activationCount)
        assertNotNull(components.asmStore.takePendingGoToLabelRange())
    }
}
