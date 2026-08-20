package world.phantasmal.web.questEditor.rendering

import kotlinx.coroutines.*
import mu.KotlinLogging
import org.khronos.webgl.Float32Array
import world.phantasmal.cell.observeNow
import world.phantasmal.core.disposable.DisposableSupervisedScope
import world.phantasmal.core.disposable.Disposer
import world.phantasmal.psolib.fileFormats.quest.EntityType
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.web.core.loading.LoadingCache
import world.phantasmal.web.core.rendering.disposeObject3DResources
import world.phantasmal.web.externals.three.*
import world.phantasmal.web.questEditor.loading.EntityMeshLoader
import world.phantasmal.web.questEditor.models.*
import world.phantasmal.web.questEditor.stores.QuestEntitySelectionState
import world.phantasmal.web.questEditor.stores.QuestEditorUiStore
import world.phantasmal.webui.DisposableContainer
import world.phantasmal.webui.obj

private val logger = KotlinLogging.logger {}

class EntityMeshManager(
    private val questEditorStore: QuestEntitySelectionState,
    private val questEditorUiStore: QuestEditorUiStore,
    private val renderContext: QuestRenderContext,
    private val entityMeshLoader: EntityMeshLoader,
) : DisposableContainer() {
    private val scope = addDisposable(DisposableSupervisedScope(this::class, Dispatchers.Main))

    private val directionIndicators = addDisposable(
        EntityDirectionIndicatorContainer(questEditorUiStore.showEntityDirections).also {
            renderContext.helpers.add(it.mesh)
        }
    )

    /**
     * Contains one [EntityInstanceContainer] per [EntityType] and model.
     */
    private val entityMeshCache = addDisposable(
        LoadingCache<TypeAndModel, EntityInstanceContainer>(
            { (type, model, ultimate, renderVariant) ->
                val mesh = entityMeshLoader.loadInstancedMesh(
                    type,
                    model,
                    ultimate,
                    renderVariant,
                )
                renderContext.entities.add(mesh)
                EntityInstanceContainer(mesh, modelChanged = { entity ->
                    // When an entity's model changes, add it again. At this point it has already
                    // been removed from its previous EntityInstancedMesh.
                    detachMarkersFor(entity)
                    add(entity)
                })
            },
            { container ->
                renderContext.entities.remove(container.mesh)
                container.dispose()
            },
        )
    )

    /**
     * Warp destinations.
     */
    private val destinationInstanceContainer = addDisposable(
        DestinationInstanceContainer().also {
            renderContext.entities.add(it.mesh)
        }
    )

    // Lines between warps and their destination.
    private val warpLineBufferAttribute = Float32BufferAttribute(Float32Array(6), 3)
    private val warpLines =
        LineSegments(
            BufferGeometry().setAttribute("position", warpLineBufferAttribute),
            LineBasicMaterial(obj {
                color = DestinationInstanceContainer.COLOR
            })
        ).also {
            it.visible = false
            it.frustumCulled = false
            renderContext.helpers.add(it)
        }
    private var warpLineDisposer = addDisposable(Disposer())

    /**
     * Entity meshes that are currently being loaded.
     */
    private val loadingEntities = mutableMapOf<QuestEntityModel<*, *>, Job>()

    private var highlightedEntityInstance: EntityInstance? = null
    private var selectedEntityInstance: EntityInstance? = null

    /**
     * Bounding box around the highlighted entity.
     */
    private val highlightedBox = BoxHelper(color = Color(.7, .7, .7)).apply {
        visible = false
        renderContext.scene.add(this)
    }

    /**
     * Bounding box around the selected entity.
     */
    private val selectedBox = BoxHelper(color = Color(.9, .9, .9)).apply {
        visible = false
        renderContext.scene.add(this)
    }

    init {
        observeNow(questEditorStore.highlightedEntity) { entity ->
            // getEntityInstance can return null at this point because the entity mesh might not be
            // loaded yet.
            markHighlighted(entity?.let(::getEntityInstance))
        }

        observeNow(questEditorStore.selectedEntity) { entity ->
            // getEntityInstance can return null at this point because the entity mesh might not be
            // loaded yet.
            markSelected(entity?.let(::getEntityInstance))
        }

    }

    override fun dispose() {
        removeAll()
        renderContext.helpers.remove(directionIndicators.mesh)
        renderContext.entities.remove(destinationInstanceContainer.mesh)
        renderContext.helpers.remove(warpLines)
        renderContext.scene.remove(highlightedBox)
        renderContext.scene.remove(selectedBox)
        disposeObject3DResources(warpLines)
        disposeObject3DResources(highlightedBox)
        disposeObject3DResources(selectedBox)
        super.dispose()
    }

    fun add(entity: QuestEntityModel<*, *>) {
        if (directionIndicators.getInstance(entity) == null) {
            directionIndicators.addInstance(entity)
        }

        if (entity !in loadingEntities) {
            val loadingJob = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val entityInstancedMesh = entityMeshCache.get(
                        TypeAndModel(
                            type = entity.type,
                            model = (entity as? QuestObjectModel)?.model?.value,
                            ultimate = questEditorUiStore.ultimate.value,
                            renderVariant = entityRenderVariant(entity),
                        )
                    )

                    val instance = entityInstancedMesh.addInstance(entity)

                    if (entity == questEditorStore.selectedEntity.value) {
                        markSelected(instance)
                    } else if (entity == questEditorStore.highlightedEntity.value) {
                        markHighlighted(instance)
                    }

                    if (entity is QuestObjectModel && entity.hasDestination) {
                        destinationInstanceContainer.addInstance(entity)
                    }
                } catch (e: CancellationException) {
                    logger.trace(e) {
                        "Mesh loading for entity of type ${entity.type} cancelled."
                    }
                } catch (e: Throwable) {
                    logger.error(e) {
                        "Couldn't load mesh for entity of type ${entity.type}."
                    }
                } finally {
                    if (loadingEntities[entity] === coroutineContext.job) {
                        loadingEntities.remove(entity)
                    }
                }
            }
            loadingEntities[entity] = loadingJob
            loadingJob.start()
        }
    }

    fun remove(entity: QuestEntityModel<*, *>) {
        loadingEntities.remove(entity)?.cancel("Removed.")

        detachMarkersFor(entity)
        directionIndicators.removeInstance(entity)

        entityMeshCache.getIfPresentNow(
            TypeAndModel(
                entity.type,
                (entity as? QuestObjectModel)?.model?.value,
                questEditorUiStore.ultimate.value,
                entityRenderVariant(entity),
            )
        )?.removeInstance(entity)

        if (entity is QuestObjectModel && entity.hasDestination) {
            destinationInstanceContainer.removeInstance(entity)
        }

    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun removeAll() {
        loadingEntities.values.forEach { it.cancel("Removed.") }
        loadingEntities.clear()

        markSelected(null)
        markHighlighted(null)
        entityMeshCache.loadedValues.forEach(EntityInstanceContainer::clearInstances)

        destinationInstanceContainer.clearInstances()
        directionIndicators.clearInstances()
    }

    private fun detachMarkersFor(entity: QuestEntityModel<*, *>) {
        if (selectedEntityInstance?.entity === entity) {
            markSelected(null)
        }
        if (highlightedEntityInstance?.entity === entity) {
            markHighlighted(null)
        }
    }

    private fun markHighlighted(instance: EntityInstance?) {
        if (instance == selectedEntityInstance) {
            highlightedEntityInstance?.follower = null
            highlightedEntityInstance = null
            highlightedBox.visible = false
        } else {
            attachBoxHelper(
                highlightedBox,
                highlightedEntityInstance,
                instance,
            )
            highlightedEntityInstance = instance
        }
    }

    private fun markSelected(instance: EntityInstance?) {
        if (instance == highlightedEntityInstance) {
            highlightedBox.visible = false
            highlightedEntityInstance = null
        }

        attachBoxHelper(selectedBox, selectedEntityInstance, instance)
        attachWarpLine(instance)
        selectedEntityInstance = instance
    }

    private fun attachBoxHelper(
        box: BoxHelper,
        oldInstance: EntityInstance?,
        newInstance: EntityInstance?,
    ) {
        box.visible = newInstance != null

        if (oldInstance == newInstance) return

        oldInstance?.follower = null

        if (newInstance != null) {
            box.setFromObject(newInstance.mesh)
            newInstance.follower = box
            box.visible = true
        }
    }

    private fun attachWarpLine(newInstance: EntityInstance?) {
        warpLineDisposer.disposeAll()
        warpLines.visible = false

        if (newInstance != null &&
            newInstance.entity is QuestObjectModel &&
            newInstance.entity.hasDestination
        ) {
            warpLineDisposer.add(newInstance.entity.worldPosition.observeNow {
                warpLineBufferAttribute.setXYZ(0, it.x, it.y, it.z)
                warpLineBufferAttribute.needsUpdate = true
            })
            warpLineDisposer.add(newInstance.entity.destinationPosition.observeNow {
                warpLineBufferAttribute.setXYZ(1, it.x, it.y, it.z)
                warpLineBufferAttribute.needsUpdate = true
            })
            warpLines.visible = true
        }
    }

    private fun getEntityInstance(entity: QuestEntityModel<*, *>): EntityInstance? =
        entityMeshCache.getIfPresentNow(
            TypeAndModel(
                entity.type,
                (entity as? QuestObjectModel)?.model?.value,
                questEditorUiStore.ultimate.value,
                entityRenderVariant(entity),
            )
        )?.getInstance(entity)

    private data class TypeAndModel(
        val type: EntityType,
        val model: Int?,
        val ultimate: Boolean,
        val renderVariant: Int?,
    )

    private fun entityRenderVariant(entity: QuestEntityModel<*, *>): Int? =
        if (entity is QuestObjectModel && entity.type == ObjectType.ForestDoor) {
            entity.entity.forestDoorDigit
        } else {
            null
        }

}
