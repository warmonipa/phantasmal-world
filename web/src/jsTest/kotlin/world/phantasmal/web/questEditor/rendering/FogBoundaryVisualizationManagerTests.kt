package world.phantasmal.web.questEditor.rendering

import kotlinx.browser.document
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield
import org.w3c.dom.HTMLCanvasElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import world.phantasmal.psolib.fileFormats.fog.FogEntry
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.web.externals.three.Camera
import world.phantasmal.web.externals.three.Mesh
import world.phantasmal.web.externals.three.MeshBasicMaterial
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.questEditor.loading.FogAssetLoader
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import world.phantasmal.web.test.createQuestObjectModel

class FogBoundaryVisualizationManagerTests : WebTestSuite {
    @Test
    fun identifies_fog_collision_objects() {
        assertTrue(isFogCollision(ObjectType.FogCollision))
        assertTrue(isFogCollision(ObjectType.FogCollisionSW))
        assertFalse(isFogCollision(ObjectType.EventCollision))
        assertFalse(isFogCollision(null))
    }

    @Test
    fun normalizes_only_valid_fog_indices() {
        assertEquals(17, normalizeFogIndex(17))
        assertEquals(17, normalizeFogIndex(0x1011))
        assertNull(normalizeFogIndex(-1))
        assertNull(normalizeFogIndex(0x100))
        assertNull(normalizeFogIndex(0x1100))
    }

    @Test
    fun disabled_boundaries_do_not_load_or_render() = testAsync {
        val renderContext = disposer.add(QuestRenderContext(
            document.createElement("canvas") as HTMLCanvasElement,
            Camera(),
        ))
        val fogObject = createFogObject(ObjectType.FogCollision, radius = 12f, fogIndex = 17)
        components.questEditorStore.setCurrentQuest(createQuestModel(objects = listOf(fogObject)))
        components.questEditorStore.setSelectedEntity(fogObject)
        var loadCalled = false
        val manager = disposer.add(FogBoundaryVisualizationManager(
            renderContext,
            FogAssetLoader(components.assetLoader),
            components.questEditorStore,
            components.questEditorUiStore,
            loadEntries = {
                loadCalled = true
                fogEntries()
            },
        ))

        manager.beforeRender()
        yield()

        assertFalse(loadCalled)
        assertTrue(renderContext.helpers.children.isEmpty())
    }

    @Test
    fun renders_all_current_area_fog_boundaries_and_updates_them() = testAsync {
        val renderContext = disposer.add(QuestRenderContext(
            document.createElement("canvas") as HTMLCanvasElement,
            Camera(),
        ))
        val fogA = createFogObject(ObjectType.FogCollision, radius = 12f, fogIndex = 17).apply {
            setWorldPosition(Vector3(3.0, 4.0, 5.0))
        }
        val fogB = createFogObject(ObjectType.FogCollisionSW, radius = 24f, fogIndex = 18).apply {
            setWorldPosition(Vector3(30.0, 40.0, 50.0))
        }
        val eventCollision = createQuestObjectModel(ObjectType.EventCollision).apply {
            entity.data.setFloat(40, 50f)
        }
        val fogInAnotherArea = createFogObject(
            ObjectType.FogCollision,
            radius = 36f,
            fogIndex = 17,
            floorId = 1,
        )
        val store = components.questEditorStore
        store.setCurrentQuest(createQuestModel(
            objects = listOf(fogA, fogB, eventCollision, fogInAnotherArea),
        ))
        store.setSelectedEntity(fogA)
        components.questEditorUiStore.setShowFogBoundaries(true)
        val loadStarted = CompletableDeferred<Unit>()
        val globalFogSentinel = js("({ marker: 'global fog must remain untouched' })")
        renderContext.scene.asDynamic().fog = globalFogSentinel
        val manager = disposer.add(FogBoundaryVisualizationManager(
            renderContext,
            FogAssetLoader(components.assetLoader),
            store,
            components.questEditorUiStore,
            loadEntries = {
                loadStarted.complete(Unit)
                fogEntries()
            },
        ))

        manager.beforeRender()
        loadStarted.await()
        yield()
        manager.beforeRender()

        assertEquals(2, renderContext.helpers.children.size)
        val boundaryA = renderContext.helpers.children.single { it.position.x == 3.0 }
        val boundaryB = renderContext.helpers.children.single { it.position.x == 30.0 }
        assertEquals("Fog Boundary", boundaryA.name)
        assertEquals(12.0, boundaryA.scale.x)
        assertEquals(24.0, boundaryB.scale.x)
        assertEquals(0x112233, fillMaterial(boundaryA).color.asDynamic().getHex() as Int)
        assertEquals(0x445566, fillMaterial(boundaryB).color.asDynamic().getHex() as Int)
        assertTrue(fillMaterial(boundaryA).opacity > fillMaterial(boundaryB).opacity)
        assertSame(globalFogSentinel, renderContext.scene.asDynamic().fog)

        fogA.entity.data.setFloat(40, 20f)
        fogA.entity.data.setInt(52, 18)
        fogA.setWorldPosition(Vector3(6.0, 7.0, 8.0))
        store.setSelectedEntity(fogB)
        manager.beforeRender()

        assertEquals(6.0, boundaryA.position.x)
        assertEquals(20.0, boundaryA.scale.x)
        assertEquals(0x445566, fillMaterial(boundaryA).color.asDynamic().getHex() as Int)
        assertTrue(fillMaterial(boundaryB).opacity > fillMaterial(boundaryA).opacity)
    }

    @Test
    fun disabling_removes_all_boundaries() = testAsync {
        val renderContext = disposer.add(QuestRenderContext(
            document.createElement("canvas") as HTMLCanvasElement,
            Camera(),
        ))
        val fogObject = createFogObject(ObjectType.FogCollision, radius = 12f, fogIndex = 17)
        components.questEditorStore.setCurrentQuest(createQuestModel(objects = listOf(fogObject)))
        components.questEditorUiStore.setShowFogBoundaries(true)
        val loadStarted = CompletableDeferred<Unit>()
        var loadCount = 0
        val manager = disposer.add(FogBoundaryVisualizationManager(
            renderContext,
            FogAssetLoader(components.assetLoader),
            components.questEditorStore,
            components.questEditorUiStore,
            loadEntries = {
                loadCount++
                loadStarted.complete(Unit)
                fogEntries()
            },
        ))

        manager.beforeRender()
        loadStarted.await()
        yield()
        manager.beforeRender()
        assertEquals(1, renderContext.helpers.children.size)

        components.questEditorUiStore.setShowFogBoundaries(false)
        manager.beforeRender()

        assertTrue(renderContext.helpers.children.isEmpty())

        fogObject.entity.data.setFloat(40, 20f)
        components.questEditorUiStore.setShowFogBoundaries(true)
        manager.beforeRender()
        yield()
        manager.beforeRender()

        assertEquals(2, loadCount)
        assertEquals(20.0, renderContext.helpers.children.single().scale.x)
    }

    private fun createFogObject(
        type: ObjectType,
        radius: Float,
        fogIndex: Int,
        floorId: Int = 0,
    ) =
        createQuestObjectModel(type, floorId).apply {
            entity.data.setFloat(40, radius)
            entity.data.setInt(52, fogIndex)
        }

    private fun fillMaterial(boundary: world.phantasmal.web.externals.three.Object3D) =
        (boundary.children[0] as Mesh).material.unsafeCast<MeshBasicMaterial>()

    private fun fogEntries(): List<FogEntry> = List(256) { index ->
        FogEntry(
            type = 1,
            color = when (index) {
                17 -> 0x00112233
                18 -> 0x00445566
                else -> 0
            },
            end = 100f,
            start = 10f,
            density = 0.01f,
            animationSpeed = 0f,
            endPulseDistance = 0f,
            startPulseDistance = 0f,
            transitionDistance = 640f,
            endPulsePhase = 0,
            startPulsePhase = 0,
            transitionPulseDistance = 0,
        )
    }
}
