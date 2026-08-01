package world.phantasmal.web.questEditor.rendering.input.state

import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleInteractionEvent
import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleSpawn
import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleSpawnOpcode
import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleSpawnOrigin
import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleSpawnSource
import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.web.core.rendering.OrbitalCameraInputManager
import world.phantasmal.web.externals.three.Mesh
import world.phantasmal.web.externals.three.MeshBasicMaterial
import world.phantasmal.web.externals.three.PerspectiveCamera
import world.phantasmal.web.externals.three.SphereGeometry
import world.phantasmal.web.externals.three.Vector2
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.questEditor.models.QuestEventModel
import world.phantasmal.web.questEditor.controllers.EventsController
import world.phantasmal.web.questEditor.rendering.QuestRenderContext
import world.phantasmal.web.questEditor.rendering.input.PointerDownEvt
import world.phantasmal.web.questEditor.rendering.input.PointerUpEvt
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.WebTestContext
import world.phantasmal.web.test.createQuestModel
import world.phantasmal.web.test.createQuestNpcModel
import world.phantasmal.web.test.createQuestObjectModel
import world.phantasmal.web.questEditor.widgets.EventWidget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StateContextTests : WebTestSuite {
    @Test
    fun viewport_enemy_activates_events_and_scrolls_to_its_matching_card() = testAsync {
        val store = components.questEditorStore
        val enemy = createQuestNpcModel(NpcType.Booma, Episode.I).apply {
            setSectionId(7)
            setWaveId(4)
        }
        val event = event(sectionId = 7, waveId = 4)
        store.setCurrentQuest(createQuestModel(npcs = listOf(enemy), events = listOf(event)))
        val eventsController = disposer.add(
            EventsController(store, components.playbackVisualizationStore),
        )
        var scrollCount = 0
        val eventWidget = disposer.add(EventWidget(eventsController, event))
        eventWidget.element.asDynamic().scrollIntoView = { _: dynamic -> scrollCount++ }
        var activationCount = 0
        val context = createStateContext(onActivateEventsWidget = { activationCount++ })

        context.selectViewportEntity(enemy)

        assertEquals(1, activationCount)
        assertEquals(event, store.selectedEvent.value)
        assertEquals(1, scrollCount)
    }

    @Test
    fun viewport_enemy_activates_events_before_selecting_its_matching_event() = testAsync {
        val store = components.questEditorStore
        val enemy = createQuestNpcModel(NpcType.Booma, Episode.I).apply {
            setSectionId(7)
            setWaveId(4)
        }
        val event = event(sectionId = 7, waveId = 4)
        store.setCurrentQuest(createQuestModel(npcs = listOf(enemy), events = listOf(event)))
        var activationCount = 0
        val context = createStateContext(onActivateEventsWidget = {
            assertNull(store.selectedEvent.value)
            activationCount++
        })

        context.selectViewportEntity(enemy)

        assertEquals(1, activationCount)
        assertEquals(event, store.selectedEvent.value)
    }

    @Test
    fun viewport_entities_without_a_matching_event_do_not_activate_events() = testAsync {
        val store = components.questEditorStore
        val enemy = createQuestNpcModel(NpcType.Booma, Episode.I).apply {
            setSectionId(8)
            setWaveId(4)
        }
        val objectModel = createQuestObjectModel(ObjectType.PlayerSet)
        store.setCurrentQuest(createQuestModel(
            npcs = listOf(enemy),
            objects = listOf(objectModel),
            events = listOf(event(sectionId = 7, waveId = 4)),
        ))
        var activationCount = 0
        val context = createStateContext(onActivateEventsWidget = { activationCount++ })

        context.selectViewportEntity(enemy)
        context.selectViewportEntity(objectModel)

        assertEquals(0, activationCount)
        assertEquals(objectModel, store.selectedEntity.value)
        assertNull(store.selectedEvent.value)
        assertEquals(emptySet(), store.selectedEvents.value)
    }

    @Test
    fun clicking_a_particle_navigates_to_its_primary_script_label() = testAsync {
        var navigatedLabel: Int? = null
        val context = createStateContext(onNavigateToScriptLabel = { navigatedLabel = it })
        val particle = ParticleSpawn(
            origin = ParticleSpawnOrigin.WorldPosition(0, 0, 0),
            particleId = 349,
            lifetimeFrames = 30,
            source = ParticleSpawnSource.Opcode(ParticleSpawnOpcode.ParticleV3),
            hasExtendedDrawRange = false,
            interactionEvents = setOf(
                ParticleInteractionEvent(302, ParticleInteractionEvent.Kind.Talk),
                ParticleInteractionEvent(217, ParticleInteractionEvent.Kind.Talk),
            ),
        )
        val marker = Mesh(SphereGeometry(1.0), MeshBasicMaterial()).apply {
            userData = particle
        }
        context.renderContext.particleMarkers.add(marker)
        context.renderContext.particleMarkers.updateMatrixWorld(true)
        val state = IdleState(context, entityManipulationEnabled = true)
        val pointer = Vector2(0.0, 0.0)

        state.processEvent(PointerDownEvt(1, false, false, pointer, false))
        state.processEvent(PointerUpEvt(0, false, false, pointer, false))

        assertEquals(217, navigatedLabel)
    }

    private fun WebTestContext.createStateContext(
        onNavigateToScriptLabel: (Int) -> Unit = {},
        onActivateEventsWidget: () -> Unit = {},
    ): StateContext {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        val camera = PerspectiveCamera(60.0, 1.0, 0.1, 1000.0).apply {
            position.set(0.0, 0.0, 10.0)
            lookAt(Vector3(0.0, 0.0, 0.0))
            updateProjectionMatrix()
            updateMatrixWorld(true)
        }
        val renderContext = disposer.add(QuestRenderContext(canvas, camera))
        val cameraInputManager = disposer.add(OrbitalCameraInputManager(
            canvas = canvas,
            camera = camera,
            position = Vector3(0.0, 0.0, 10.0),
            screenSpacePanning = false,
        ))
        return StateContext(
            questEditorStore = components.questEditorStore,
            questEditorUiStore = components.questEditorUiStore,
            renderContext = renderContext,
            cameraInputManager = cameraInputManager,
            onNavigateToScriptLabel = onNavigateToScriptLabel,
            onActivateEventsWidget = onActivateEventsWidget,
        )
    }

    private fun event(sectionId: Int, waveId: Int): QuestEventModel = QuestEventModel(
        id = 100,
        floorId = 0,
        sectionId = sectionId,
        waveId = waveId,
        delay = 0,
        unknown = 0,
        actions = mutableListOf(),
    )
}
