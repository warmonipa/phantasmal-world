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

    @Test
    fun entity_rejected_by_a_full_mesh_is_added_after_its_model_changes() = testAsync {
        val context = disposer.add(
            QuestRenderContext(
                document.createElement("canvas").unsafeCast<HTMLCanvasElement>(),
                PerspectiveCamera(),
            )
        )
        val loader = SingleInstanceMeshLoader()
        val manager = disposer.add(
            EntityMeshManager(
                components.questEditorStore,
                components.questEditorUiStore,
                context,
                loader,
            )
        )
        val occupant = createQuestObjectModel(ObjectType.Probe)
        val rejected = createQuestObjectModel(ObjectType.Probe)

        manager.add(occupant)
        awaitCondition { loader.meshFor(0)?.count == 1 }
        manager.add(rejected)
        // Let the rejected entity's load finish against the full model-0 mesh.
        repeat(10) { yield() }
        assertEquals(1, loader.meshFor(0)?.count)

        rejected.setModel(1)

        awaitCondition { loader.meshFor(1)?.count == 1 }
    }

    @Test
    fun removed_rejected_entity_is_not_added_after_its_model_changes() = testAsync {
        val context = disposer.add(
            QuestRenderContext(
                document.createElement("canvas").unsafeCast<HTMLCanvasElement>(),
                PerspectiveCamera(),
            )
        )
        val loader = SingleInstanceMeshLoader()
        val manager = disposer.add(
            EntityMeshManager(
                components.questEditorStore,
                components.questEditorUiStore,
                context,
                loader,
            )
        )
        val occupant = createQuestObjectModel(ObjectType.Probe)
        val rejected = createQuestObjectModel(ObjectType.Probe)

        manager.add(occupant)
        awaitCondition { loader.meshFor(0)?.count == 1 }
        manager.add(rejected)
        repeat(10) { yield() }

        manager.remove(rejected)
        rejected.setModel(1)
        repeat(10) { yield() }

        assertEquals(null, loader.meshFor(1))
    }

    @Test
    fun model_change_during_a_mesh_load_uses_the_latest_model() = testAsync {
        val context = disposer.add(
            QuestRenderContext(
                document.createElement("canvas").unsafeCast<HTMLCanvasElement>(),
                PerspectiveCamera(),
            )
        )
        val loader = GatedMeshLoader()
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
        awaitCondition { loader.meshFor(0)?.count == 1 }

        val model1Gate = loader.gate(1)
        entity.setModel(1)
        awaitCondition { loader.loadStarted(1) }
        entity.setModel(2)
        model1Gate.complete(Unit)

        awaitCondition { loader.meshFor(2)?.count == 1 }
        assertEquals(0, loader.meshFor(0)?.count)
        assertEquals(0, loader.meshFor(1)?.count ?: 0)

        manager.remove(entity)

        assertEquals(0, loader.meshFor(2)?.count)
        assertEquals(0, loader.meshFor(1)?.count ?: 0)
    }

    private suspend fun awaitVisibleSelectionMarker(context: QuestRenderContext) {
        withTimeout(5_000) {
            while (selectionMarkers(context).none { it.visible }) yield()
        }
    }

    private fun selectionMarkers(context: QuestRenderContext): List<BoxHelper> =
        context.scene.children.filterIsInstance<BoxHelper>()

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) yield()
        }
    }

    /** Gives every model its own mesh; loads of gated models wait until their gate completes. */
    private class GatedMeshLoader : EntityMeshLoader {
        private val meshes = mutableMapOf<Int?, InstancedMesh>()
        private val gates = mutableMapOf<Int?, CompletableDeferred<Unit>>()
        private val startedLoads = mutableSetOf<Int?>()

        fun meshFor(model: Int?): InstancedMesh? = meshes[model]

        fun gate(model: Int?): CompletableDeferred<Unit> =
            CompletableDeferred<Unit>().also { gates[model] = it }

        fun loadStarted(model: Int?): Boolean = model in startedLoads

        override suspend fun loadInstancedMesh(
            type: EntityType,
            model: Int?,
            ultimate: Boolean,
            renderVariant: Int?,
        ): InstancedMesh {
            startedLoads.add(model)
            gates[model]?.await()
            return meshes.getOrPut(model) {
                InstancedMesh(PlaneGeometry(), MeshBasicMaterial(), 10).apply { count = 0 }
            }
        }
    }

    /** Gives every model its own mesh with room for exactly one instance. */
    private class SingleInstanceMeshLoader : EntityMeshLoader {
        private val meshes = mutableMapOf<Int?, InstancedMesh>()

        fun meshFor(model: Int?): InstancedMesh? = meshes[model]

        override suspend fun loadInstancedMesh(
            type: EntityType,
            model: Int?,
            ultimate: Boolean,
            renderVariant: Int?,
        ): InstancedMesh =
            meshes.getOrPut(model) {
                InstancedMesh(PlaneGeometry(), MeshBasicMaterial(), 1).apply { count = 0 }
            }
    }

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
