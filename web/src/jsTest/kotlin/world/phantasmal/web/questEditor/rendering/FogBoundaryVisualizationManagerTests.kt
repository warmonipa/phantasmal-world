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
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.fileFormats.fog.FogEntry
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.web.externals.three.Camera
import world.phantasmal.web.externals.three.Fog
import world.phantasmal.web.externals.three.Mesh
import world.phantasmal.web.externals.three.MeshBasicMaterial
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.questEditor.loading.FogAssetLoader
import world.phantasmal.web.questEditor.models.AreaModel
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import world.phantasmal.web.test.createQuestObjectModel

class FogBoundaryVisualizationManagerTests : WebTestSuite {
    @Test
    fun reads_fog_triggers_as_newserv_defines_them() {
        val plain = createFogObject(ObjectType.FogCollision, radius = 12f, fogIndex = 17).apply {
            entity.data.setInt(56, 1)
        }
        fogTrigger(plain, null)!!.let {
            assertEquals(12.0, it.radius)
            assertEquals(17, it.fogIndex)
            assertTrue(it.instantTransition)
            assertNull(it.switchFlag)
            assertNull(it.poisonPower)
        }

        val switched = createFogObject(ObjectType.FogCollisionSW, radius = 12f, fogIndex = 0x1029)
            .apply {
                entity.data.setFloat(48, 1f)
                entity.data.setInt(60, 242)
            }
        fogTrigger(switched, null)!!.let {
            assertEquals(0x1029, it.rawFogNumber)
            assertEquals(41, it.fogIndex)
            assertEquals(242, it.switchFlag)
            assertTrue(it.activeWhileSwitchOn)
            assertFalse(it.instantTransition)
        }

        val poison = createFogObject(ObjectType.Poison, radius = 50f, fogIndex = 8).apply {
            entity.data.setFloat(44, 10f)
            entity.data.setFloat(48, 0f)
            entity.data.setInt(60, 3)
        }
        val ruins = AreaModel(8, "Ruins 1", false, 8, emptyList(), Episode.I)
        fogTrigger(poison, ruins)!!.let {
            assertEquals(8, it.fogIndex)
            assertEquals(10f, it.poisonPower)
            assertEquals(3, it.switchFlag)
            assertFalse(it.activeWhileSwitchOn)
        }
        // On the Vol Opt floor 0x0160 is TObjWarpBoss03, not fog.
        val volOpt = AreaModel(13, "Vol Opt", true, 13, emptyList(), Episode.I)
        assertNull(fogTrigger(poison, volOpt))

        val heat = createFogObject(ObjectType.Heat, radius = 50f, fogIndex = 137).apply {
            entity.data.setFloat(44, 3f)
            entity.data.setInt(60, 5)
        }
        fogTrigger(heat, null)!!.let {
            assertEquals(137, it.fogIndex)
            assertEquals(3f, it.poisonPower)
            assertEquals(5, it.switchFlag)
        }

        assertNull(fogTrigger(createQuestObjectModel(ObjectType.EventCollision), null))
    }

    @Test
    fun normalizes_only_valid_fog_indices() {
        assertEquals(17, normalizeFogIndex(17))
        assertEquals(17, normalizeFogIndex(0x1011))
        assertEquals(255, normalizeFogIndex(0x10FF))
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
        val globalFogSentinel = js("({ marker: 'global fog is restored after the preview' })")
        val backgroundSentinel = js("({ marker: 'background is restored after the preview' })")
        renderContext.scene.asDynamic().fog = globalFogSentinel
        renderContext.scene.asDynamic().background = backgroundSentinel
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

        assertEquals(2, boundaries(renderContext).size)
        val boundaryA = boundaries(renderContext).single { it.position.x == 3.0 }
        val boundaryB = boundaries(renderContext).single { it.position.x == 30.0 }
        assertEquals("Fog Boundary", boundaryA.name)
        assertEquals(12.0, boundaryA.scale.x)
        assertEquals(24.0, boundaryB.scale.x)
        assertEquals(0x112233, fillMaterial(boundaryA).color.asDynamic().getHex() as Int)
        assertEquals(0x112233, outlineMaterial(boundaryA).color.asDynamic().getHex() as Int)
        assertEquals(0x445566, fillMaterial(boundaryB).color.asDynamic().getHex() as Int)
        assertEquals(0x445566, outlineMaterial(boundaryB).color.asDynamic().getHex() as Int)
        assertTrue(fillMaterial(boundaryA).opacity > fillMaterial(boundaryB).opacity)
        // The selected trigger's fog entry is previewed as scene fog with a matching clear colour.
        val previewFog = renderContext.scene.fog.unsafeCast<Fog>()
        assertEquals(0x112233, previewFog.color.asDynamic().getHex() as Int)
        assertEquals(10.0, previewFog.near)
        assertEquals(100.0, previewFog.far)
        assertEquals(0x112233, renderContext.scene.background.getHex() as Int)
        assertFalse(fillMaterial(boundaryA).asDynamic().fog as Boolean)

        fogA.entity.data.setFloat(40, 20f)
        fogA.entity.data.setInt(52, 18)
        fogA.setWorldPosition(Vector3(6.0, 7.0, 8.0))
        store.setSelectedEntity(fogB)
        manager.beforeRender()

        assertEquals(6.0, boundaryA.position.x)
        assertEquals(20.0, boundaryA.scale.x)
        assertEquals(0x445566, fillMaterial(boundaryA).color.asDynamic().getHex() as Int)
        assertTrue(fillMaterial(boundaryB).opacity > fillMaterial(boundaryA).opacity)
        assertEquals(0x445566, renderContext.scene.fog.color.getHex() as Int)

        store.setSelectedEntity(null)
        manager.beforeRender()

        assertSame(globalFogSentinel, renderContext.scene.asDynamic().fog)
        assertSame(backgroundSentinel, renderContext.scene.asDynamic().background)
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
        assertEquals(1, boundaries(renderContext).size)

        components.questEditorUiStore.setShowFogBoundaries(false)
        manager.beforeRender()

        assertTrue(renderContext.helpers.children.isEmpty())

        fogObject.entity.data.setFloat(40, 20f)
        components.questEditorUiStore.setShowFogBoundaries(true)
        manager.beforeRender()
        yield()
        manager.beforeRender()

        assertEquals(2, loadCount)
        assertEquals(20.0, boundaries(renderContext).single().scale.x)
    }

    @Test
    fun labels_name_the_fog_entry_and_conditions() {
        val plain = createFogObject(ObjectType.FogCollision, radius = 12f, fogIndex = 17)
        assertEquals(
            listOf("Fog 17 · end 70 · #000000"),
            fogLabelLines(fogTrigger(plain, null)!!, fogEntry(end = 70f)),
        )

        val forced = createFogObject(ObjectType.FogCollisionSW, radius = 12f, fogIndex = 0x1029)
            .apply {
                entity.data.setFloat(48, 1f)
                entity.data.setInt(56, 1)
                entity.data.setInt(60, 242)
            }
        assertEquals(
            listOf("Fog 41 (0x1029) · end 500 · #131501", "on while SW 242 on · instant"),
            fogLabelLines(fogTrigger(forced, null)!!, fogEntry(end = 500f, color = 0x131501)),
        )

        val poison = createFogObject(ObjectType.Poison, radius = 12f, fogIndex = 8).apply {
            entity.data.setFloat(44, 10f)
            entity.data.setFloat(48, 0f)
            entity.data.setInt(56, 0)
            entity.data.setInt(60, 3)
        }
        assertEquals(
            listOf("Fog 8 · end 7500 · #80FFAA", "Poison 10 · on while SW 3 off"),
            fogLabelLines(fogTrigger(poison, null)!!, fogEntry(end = 7500f, color = 0x80FFAA)),
        )

        val invalid = createFogObject(ObjectType.FogCollision, radius = 12f, fogIndex = 0x1100)
        assertEquals(
            listOf("Fog 0x1100 (no entry)"),
            fogLabelLines(fogTrigger(invalid, null)!!, null),
        )
    }

    @Test
    fun overlapping_identical_triggers_share_one_label() = testAsync {
        val renderContext = disposer.add(QuestRenderContext(
            document.createElement("canvas") as HTMLCanvasElement,
            Camera(),
        ))
        fun dense(x: Double) =
            createFogObject(ObjectType.FogCollision, radius = 12f, fogIndex = 17).apply {
                setWorldPosition(Vector3(x, 0.0, 0.0))
            }
        // 0 and 20 overlap, 20 and 40 overlap: one cluster. 100 is separate.
        val a = dense(0.0)
        val b = dense(20.0)
        val c = dense(40.0)
        val far = dense(100.0)
        val store = components.questEditorStore
        store.setCurrentQuest(createQuestModel(objects = listOf(a, b, c, far)))
        components.questEditorUiStore.setShowFogBoundaries(true)
        val loadStarted = CompletableDeferred<Unit>()
        val manager = disposer.add(FogBoundaryVisualizationManager(
            renderContext,
            FogAssetLoader(components.assetLoader),
            store,
            components.questEditorUiStore,
            loadEntries = {
                loadStarted.complete(Unit)
                List(256) { fogEntry(end = 70f) }
            },
        ))

        manager.beforeRender()
        loadStarted.await()
        yield()
        manager.beforeRender()

        assertEquals(listOf(0.0, 100.0), labels(renderContext).map { it.position.x }.sorted())
        // Labels are sized in world units relative to the boundary (clamped to a minimum), so they
        // zoom with the map instead of keeping a fixed screen size.
        assertEquals(listOf(10.0, 10.0), labels(renderContext).map { it.scale.y })
        far.entity.data.setFloat(40, 400f)
        far.setWorldPosition(Vector3(1000.0, 0.0, 0.0))
        manager.beforeRender()
        assertEquals(48.0, labels(renderContext).single { it.position.x == 1000.0 }.scale.y)
        far.setWorldPosition(Vector3(100.0, 0.0, 0.0))
        far.entity.data.setFloat(40, 12f)
        manager.beforeRender()

        store.setSelectedEntity(c)
        manager.beforeRender()

        assertEquals(listOf(40.0, 100.0), labels(renderContext).map { it.position.x }.sorted())
    }

    @Test
    fun stacked_triggers_differing_only_in_switch_flag_are_drawn_once() = testAsync {
        val renderContext = disposer.add(QuestRenderContext(
            document.createElement("canvas") as HTMLCanvasElement,
            Camera(),
        ))
        // Penumbral Surge #4 stacks one 2000-radius trigger per switch flag 247..250.
        val stacked = listOf(247, 248, 249, 250).mapIndexed { i, flag ->
            createFogObject(ObjectType.FogCollisionSW, radius = 2000f, fogIndex = 0x100F).apply {
                entity.data.setFloat(48, -1f)
                entity.data.setInt(56, 1)
                entity.data.setInt(60, flag)
                setWorldPosition(Vector3(-82.0 + i * 3, 150.0, -490.0 + i * 3))
            }
        }
        val store = components.questEditorStore
        store.setCurrentQuest(createQuestModel(objects = stacked))
        components.questEditorUiStore.setShowFogBoundaries(true)
        val loadStarted = CompletableDeferred<Unit>()
        val manager = disposer.add(FogBoundaryVisualizationManager(
            renderContext,
            FogAssetLoader(components.assetLoader),
            store,
            components.questEditorUiStore,
            loadEntries = {
                loadStarted.complete(Unit)
                List(256) { fogEntry(end = 600f, color = 0x303500) }
            },
        ))

        manager.beforeRender()
        loadStarted.await()
        yield()
        manager.beforeRender()

        assertEquals(1, boundaries(renderContext).count { it.visible })
        assertEquals(1, labels(renderContext).size)
        assertEquals(
            listOf("Fog 15 (0x100F) · end 600 · #303500", "on while SW 247, 248, 249, 250 off · instant"),
            fogLabelLines(
                fogTrigger(stacked[0], null)!!,
                fogEntry(end = 600f, color = 0x303500),
                listOf(247, 248, 249, 250),
            ),
        )

        // Selecting another copy shows that copy instead, still once.
        store.setSelectedEntity(stacked[2])
        manager.beforeRender()

        val visible = boundaries(renderContext).filter { it.visible }
        assertEquals(1, visible.size)
        assertEquals(stacked[2].worldPosition.value.x, visible.single().position.x)
        assertEquals(1, labels(renderContext).size)
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

    private fun boundaries(renderContext: QuestRenderContext) =
        renderContext.helpers.children.filter { it.name == "Fog Boundary" }

    private fun labels(renderContext: QuestRenderContext) =
        renderContext.helpers.children.filter { it.name == "Fog Boundary Label" }

    private fun outlineMaterial(boundary: world.phantasmal.web.externals.three.Object3D) =
        (boundary.children[1] as Mesh).material.unsafeCast<MeshBasicMaterial>()

    private fun fogEntry(end: Float, color: Int = 0) = FogEntry(
        type = 1,
        color = color,
        end = end,
        start = 0f,
        density = 0.01f,
        animationSpeed = 0f,
        endPulseDistance = 0f,
        startPulseDistance = 0f,
        transitionDistance = 640f,
        endPulsePhase = 0,
        startPulsePhase = 0,
        transitionPulseDistance = 0,
    )

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
