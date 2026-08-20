package world.phantasmal.web.questEditor.rendering

import kotlinx.browser.document
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.w3c.dom.HTMLCanvasElement
import world.phantasmal.psolib.fileFormats.quest.EntityType
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.web.externals.three.BoxHelper
import world.phantasmal.web.externals.three.InstancedMesh
import world.phantasmal.web.externals.three.MeshBasicMaterial
import world.phantasmal.web.externals.three.PlaneGeometry
import world.phantasmal.web.externals.three.PerspectiveCamera
import world.phantasmal.web.questEditor.loading.EntityAssetLoader
import world.phantasmal.web.questEditor.loading.EntityMeshLoader
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestObjectModel
import kotlin.js.unsafeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntityMeshManagerTests : WebTestSuite {
    @Test
    fun a_cancelled_old_load_cannot_unregister_its_replacement() = testAsync {
        val context = disposer.add(
            QuestRenderContext(
                document.createElement("canvas").unsafeCast<HTMLCanvasElement>(),
                PerspectiveCamera(),
            )
        )
        val loader = ControlledEntityMeshLoader()
        val manager = disposer.add(
            EntityMeshManager(
                components.questEditorStore,
                components.questEditorUiStore,
                context,
                loader,
            )
        )
        val entity = createQuestObjectModel(ObjectType.Probe)

        manager.add(entity)
        loader.loadStarted.await()
        manager.removeAll()
        manager.add(entity)
        yield()

        manager.remove(entity)
        loader.completeLoad()
        withTimeout(5_000) {
            while (loader.mesh !in context.entities.children) yield()
        }

        assertEquals(0, loader.mesh.count)
        assertFalse(selectionMarkers(context).any { it.visible })
    }

    @Test
    fun removing_all_entities_detaches_markers_and_disposal_removes_owned_scene_nodes() =
        testAsync {
            val context = disposer.add(
                QuestRenderContext(
                    document.createElement("canvas").unsafeCast<HTMLCanvasElement>(),
                    PerspectiveCamera(),
                )
            )
            val assetLoader = disposer.add(EntityAssetLoader(components.assetLoader))
            val manager = disposer.add(
                EntityMeshManager(
                    components.questEditorStore,
                    components.questEditorUiStore,
                    context,
                    assetLoader,
                )
            )
            val entity = createQuestObjectModel(ObjectType.Probe)

            manager.add(entity)
            components.questEditorStore.setSelectedEntity(entity)
            awaitVisibleSelectionMarker(context)

            manager.removeAll()

            assertFalse(selectionMarkers(context).any { it.visible })

            manager.add(entity)
            awaitVisibleSelectionMarker(context)
            manager.remove(entity)
            assertFalse(selectionMarkers(context).any { it.visible })

            manager.add(entity)
            awaitVisibleSelectionMarker(context)
            entity.setModel(1)
            assertFalse(selectionMarkers(context).any { it.visible })
            awaitVisibleSelectionMarker(context)

            assertTrue(context.entities.children.isNotEmpty())
            assertTrue(context.helpers.children.isNotEmpty())

            disposer.remove(manager)

            assertTrue(context.entities.children.isEmpty())
            assertTrue(context.helpers.children.isEmpty())
            assertEquals(emptyList(), selectionMarkers(context))
        }

    @Test
    fun deselecting_a_hovered_entity_restores_its_highlight_marker() = testAsync {
        val context = disposer.add(
            QuestRenderContext(
                document.createElement("canvas").unsafeCast<HTMLCanvasElement>(),
                PerspectiveCamera(),
            )
        )
        val assetLoader = disposer.add(EntityAssetLoader(components.assetLoader))
        val manager = disposer.add(
            EntityMeshManager(
                components.questEditorStore,
                components.questEditorUiStore,
                context,
                assetLoader,
            )
        )
        val entity = createQuestObjectModel(ObjectType.Probe)

        manager.add(entity)
        components.questEditorStore.setHighlightedEntity(entity)
        components.questEditorStore.setSelectedEntity(entity)
        awaitVisibleSelectionMarker(context)

        components.questEditorStore.setSelectedEntity(null)

        assertEquals(1, selectionMarkers(context).count { it.visible })
    }

    private suspend fun awaitVisibleSelectionMarker(context: QuestRenderContext) {
        withTimeout(5_000) {
            while (selectionMarkers(context).none { it.visible }) yield()
        }
    }

    private fun selectionMarkers(context: QuestRenderContext): List<BoxHelper> =
        context.scene.children.filterIsInstance<BoxHelper>()

    private class ControlledEntityMeshLoader : EntityMeshLoader {
        val loadStarted = CompletableDeferred<Unit>()
        val mesh = InstancedMesh(PlaneGeometry(), MeshBasicMaterial(), 10).apply { count = 0 }
        private val loadResult = CompletableDeferred<InstancedMesh>()

        override suspend fun loadInstancedMesh(
            type: EntityType,
            model: Int?,
            ultimate: Boolean,
            renderVariant: Int?,
        ): InstancedMesh {
            loadStarted.complete(Unit)
            return loadResult.await()
        }

        fun completeLoad() {
            loadResult.complete(mesh)
        }
    }
}
