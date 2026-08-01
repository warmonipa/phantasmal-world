package world.phantasmal.web.questEditor.rendering

import kotlinx.browser.document
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield
import org.w3c.dom.HTMLCanvasElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import world.phantasmal.psolib.fileFormats.fog.FogEntry
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.web.externals.three.Camera
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.questEditor.loading.FogAssetLoader
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import world.phantasmal.web.test.createQuestObjectModel

class FogPreviewManagerTests : WebTestSuite {
    @Test
    fun only_selected_fog_object_provides_a_preview_index() {
        assertEquals(17, selectedFogIndex(ObjectType.FogCollision, 17))
        assertEquals(0x1011, selectedFogIndex(ObjectType.FogCollisionSW, 0x1011))
        assertNull(selectedFogIndex(ObjectType.EventCollision, 17))
        assertNull(selectedFogIndex(null, 17))
    }

    @Test
    fun override_bit_is_removed_from_selected_fog_index() {
        assertEquals(17, normalizeFogIndex(0x1011))
    }

    @Test
    fun animated_distances_use_client_30hz_phases() {
        val entry = fogEntry(
            start = -10f,
            animationSpeed = 1f,
            endPulseDistance = 50f,
            startPulseDistance = 25f,
            endPulsePhase = 90,
            startPulsePhase = 90,
        )

        val values = fogRenderValues(entry, frame = 0.0)

        assertEquals(0xFF2020, values.color)
        assertEquals(15.0, values.near, 0.000001)
        assertEquals(150.0, values.far, 0.000001)
        assertEquals(true, values.opacity in 0.08..0.78)
    }

    @Test
    fun higher_density_produces_a_darker_preview() {
        val thin = fogRenderValues(fogEntry(density = 0.001f), frame = 0.0)
        val dense = fogRenderValues(fogEntry(density = 0.02f), frame = 0.0)

        assertTrue(dense.opacity > thin.opacity)
    }

    @Test
    fun fog_is_rendered_only_as_a_selected_local_volume_and_clears_on_deselect() = testAsync {
        val renderContext = disposer.add(QuestRenderContext(
            document.createElement("canvas") as HTMLCanvasElement,
            Camera(),
        ))
        val fogObject = createQuestObjectModel(ObjectType.FogCollision).apply {
            entity.data.setFloat(40, 12f)
            entity.data.setInt(52, 17)
            entity.data.setInt(56, 1)
            setWorldPosition(Vector3(3.0, 4.0, 5.0))
        }
        val store = components.questEditorStore
        store.setCurrentQuest(createQuestModel(objects = listOf(fogObject)))
        store.setSelectedEntity(fogObject)
        val loadStarted = CompletableDeferred<Unit>()
        val globalFogSentinel = js("({ marker: 'global fog must remain untouched' })")
        renderContext.scene.asDynamic().fog = globalFogSentinel
        val manager = disposer.add(FogPreviewManager(
            renderContext = renderContext,
            fogAssetLoader = FogAssetLoader(components.assetLoader),
            questEditorStore = store,
            nowMs = { 0.0 },
            loadEntries = {
                loadStarted.complete(Unit)
                List(256) { fogEntry() }
            },
        ))
        loadStarted.await()
        yield()

        manager.beforeRender()

        assertSame(globalFogSentinel, renderContext.scene.asDynamic().fog)
        val volume = renderContext.helpers.children.single()
        assertEquals("Selected Fog Volume", volume.name)
        assertEquals(3.0, volume.position.x)
        assertEquals(4.0, volume.position.y)
        assertEquals(5.0, volume.position.z)
        assertEquals(12.0, volume.scale.x)
        assertEquals(12.0, volume.scale.y)
        assertEquals(12.0, volume.scale.z)

        store.setSelectedEntity(null)
        manager.beforeRender()

        assertTrue(renderContext.helpers.children.isEmpty())
        assertSame(globalFogSentinel, renderContext.scene.asDynamic().fog)
    }

    private fun fogEntry(
        start: Float = 0f,
        density: Float = 0.01f,
        animationSpeed: Float = 0f,
        endPulseDistance: Float = 0f,
        startPulseDistance: Float = 0f,
        endPulsePhase: Int = 0,
        startPulsePhase: Int = 0,
    ) = FogEntry(
        type = 2,
        color = 0x00FF2020,
        end = 100f,
        start = start,
        density = density,
        animationSpeed = animationSpeed,
        endPulseDistance = endPulseDistance,
        startPulseDistance = startPulseDistance,
        transitionDistance = 640f,
        endPulsePhase = endPulsePhase,
        startPulsePhase = startPulsePhase,
        transitionPulseDistance = 0,
    )
}
