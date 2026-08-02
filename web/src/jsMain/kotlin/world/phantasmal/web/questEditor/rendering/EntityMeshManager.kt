package world.phantasmal.web.questEditor.rendering

import kotlinx.coroutines.*
import mu.KotlinLogging
import org.khronos.webgl.Float32Array
import world.phantasmal.cell.cell
import world.phantasmal.cell.flatMap
import world.phantasmal.cell.map
import world.phantasmal.cell.mutateDeferred
import world.phantasmal.cell.observeNow
import world.phantasmal.core.disposable.DisposableSupervisedScope
import world.phantasmal.core.disposable.Disposer
import world.phantasmal.psolib.fileFormats.quest.EntityType
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.ChallengeModeSeedSimulation
import world.phantasmal.psolib.fileFormats.quest.ChallengeModeSimulatedMonster
import world.phantasmal.psolib.fileFormats.quest.DatCmRandomSpawnEntry
import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.fileFormats.quest.QuestNpc
import world.phantasmal.psolib.fileFormats.quest.getNpcTypeForChallengeMonsterIndex
import world.phantasmal.web.core.euler
import world.phantasmal.web.core.loading.LoadingCache
import world.phantasmal.web.core.rendering.disposeObject3DResources
import world.phantasmal.web.externals.three.*
import world.phantasmal.web.questEditor.loading.EntityAssetLoader
import world.phantasmal.web.questEditor.models.*
import world.phantasmal.web.questEditor.stores.AreaStore
import world.phantasmal.web.questEditor.stores.PlaybackVisualizationStore
import world.phantasmal.web.questEditor.stores.QuestEditorStore
import world.phantasmal.web.questEditor.stores.QuestEditorUiStore
import world.phantasmal.webui.DisposableContainer
import world.phantasmal.webui.obj

private val logger = KotlinLogging.logger {}

/**
 * Rendering a seeded Challenge preview constructs temporary entity models and initializes their
 * cells. When invoked by a Cell observer, that work must wait until the current dependency change
 * has finished.
 */
internal fun deferChallengeSpawnRender(render: () -> Unit) {
    mutateDeferred(render)
}

class EntityMeshManager(
    private val questEditorStore: QuestEditorStore,
    private val questEditorUiStore: QuestEditorUiStore,
    private val playbackVisualizationStore: PlaybackVisualizationStore,
    private val renderContext: QuestRenderContext,
    private val entityAssetLoader: EntityAssetLoader,
    private val areaStore: AreaStore,
    private val enableSectionLabels: Boolean = false, // Only enable section labels for one manager
) : DisposableContainer() {
    private val scope = addDisposable(DisposableSupervisedScope(this::class, Dispatchers.Main))

    private val labelManager: LabelManager? = if (enableSectionLabels) {
        addDisposable(LabelManager(
            questEditorStore, questEditorUiStore, playbackVisualizationStore,
            renderContext, areaStore,
        ))
    } else null

    private val selectionViz = addDisposable(SelectionVisualizationManager(
        questEditorStore, renderContext,
        labelManager?.sectionIdRenderer ?: SectionIdRenderer(),
    ))

    /**
     * Contains one [EntityInstanceContainer] per [EntityType] and model.
     */
    private val entityMeshCache = addDisposable(
        LoadingCache<TypeAndModel, EntityInstanceContainer>(
            { (type, model, ultimate, renderVariant) ->
                val mesh = entityAssetLoader.loadInstancedMesh(
                    type,
                    model,
                    ultimate,
                    renderVariant,
                )
                renderContext.entities.add(mesh)
                EntityInstanceContainer(mesh, modelChanged = { entity ->
                    // When an entity's model changes, add it again. At this point it has already
                    // been removed from its previous EntityInstancedMesh.
                    add(entity)
                })
            },
            EntityInstanceContainer::dispose,
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

    /**
     * Challenge mode random monster spawn points.
     */
    private val challengeMonsterSpawnContainer =
        if (enableSectionLabels) {
            addDisposable(
                ChallengeMonsterSpawnContainer().also {
                    renderContext.helpers.add(it.mesh)
                }
            )
        } else {
            null
        }

    private val challengeMonsterMeshCache =
        if (enableSectionLabels) {
            addDisposable(
                LoadingCache<ChallengeMonsterMeshKey, EntityInstanceContainer>(
                    { key ->
                        val mesh = entityAssetLoader.loadInstancedMesh(
                            key.type,
                            model = null,
                            ultimate = key.ultimate,
                        )
                        renderContext.entities.add(mesh)
                        EntityInstanceContainer(mesh, modelChanged = {})
                    },
                    EntityInstanceContainer::dispose,
                )
            )
        } else {
            null
        }
    private var challengeMonsterLoadingJob: Job? = null

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

    private val groundHeightCalc: ((Double, Double, world.phantasmal.web.questEditor.models.SectionModel) -> Double)? =
        if (enableSectionLabels) { x: Double, z: Double, _: world.phantasmal.web.questEditor.models.SectionModel ->
            calculateGroundHeight(x, z)
        } else null

    init {
        // Only the NPC manager (enableSectionLabels=true) should set the global ground height
        // calculator, to avoid the object manager overwriting it with its own instance.
        if (groundHeightCalc != null) {
            NpcDisplaySettings.setGroundHeightCalculator(groundHeightCalc)
        }

        observeNow(questEditorStore.highlightedEntity) { entity ->
            // getEntityInstance can return null at this point because the entity mesh might not be
            // loaded yet.
            markHighlighted(entity?.let(::getEntityInstance))
        }

        observeNow(questEditorStore.selectedEntity) { entity ->
            // getEntityInstance can return null at this point because the entity mesh might not be
            // loaded yet.
            markSelected(entity?.let(::getEntityInstance))

            // Update range circle / collision rect / SCL_TAMA for selected entity
            selectionViz.updateForSelectedEntity(entity)
        }

        // Observe quest, area, and area variant changes to update challenge mode spawn points
        // Wait for area variant to match current area before creating spawns
        if (challengeMonsterSpawnContainer != null) {
            val challengeDataRevision =
                questEditorStore.currentQuest.flatMap { quest -> quest?.cmDataRevision ?: cell(0) }
            val challengeSimulationAndEvents = map(
                questEditorStore.challengeSeedSimulation,
                questEditorStore.selectedEvents,
                challengeDataRevision,
            ) { simulation, events, revision ->
                ChallengeSimulationAndEvents(
                    simulation,
                    events.asSequence()
                        .filter {
                            it.cmWaveSettings.value != null || it.challengeSourceEventId != null
                        }
                        .map {
                            ChallengeEventSelection(
                                floorId = it.floorId,
                                sourceEventId = it.challengeSourceEventId ?: it.id.value,
                                roomId = it.sectionId.value,
                                waveNumber = it.challengeSourceEventId?.let { _ -> it.wave.value.id },
                            )
                        }
                        .toSet(),
                    revision,
                )
            }
            val challengePreviewSelection = map(
                challengeSimulationAndEvents,
                questEditorStore.selectedChallengeLogicalFloor,
                questEditorStore.selectedChallengeRoomId,
                questEditorUiStore.ultimate,
            ) { simulationAndEvents, logicalFloor, roomId, ultimate ->
                ChallengePreviewSelection(
                    simulationAndEvents.simulation,
                    logicalFloor,
                    roomId,
                    simulationAndEvents.selectedEvents,
                    ultimate,
                    simulationAndEvents.challengeDataRevision,
                )
            }
            observeNow(
                map(
                    questEditorStore.currentQuest,
                    questEditorStore.currentArea,
                    questEditorStore.currentAreaVariant,
                    challengePreviewSelection,
                ) { quest, area, areaVariant, selection ->
                    ChallengeSpawnRenderState(
                        quest,
                        area,
                        areaVariant,
                        selection,
                    )
                }
            ) { state ->
                deferChallengeSpawnRender {
                    updateChallengeMonsterSpawns(
                        state.quest,
                        state.area,
                        state.areaVariant,
                        state.selection,
                    )
                }
            }
        }
    }

    override fun dispose() {
        removeAll()
        renderContext.entities.clear()
        disposeObject3DResources(warpLines)
        if (enableSectionLabels && NpcDisplaySettings.groundHeightCalculator === groundHeightCalc) {
            NpcDisplaySettings.setGroundHeightCalculator(null)
        }
        // Dispose challenge mode spawn container
        challengeMonsterLoadingJob?.cancel("Disposed.")
        challengeMonsterSpawnContainer?.clearInstances()
        super.dispose()
    }

    fun add(entity: QuestEntityModel<*, *>) {
        loadingEntities.getOrPut(entity) {
            scope.launch {
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
                    loadingEntities.remove(entity)
                }
            }
        }
    }

    fun remove(entity: QuestEntityModel<*, *>) {
        loadingEntities.remove(entity)?.cancel("Removed.")

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

        // Clear SCL_TAMA visualization if the removed entity is the currently selected one
        selectionViz.clearIfSelected(entity)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun removeAll() {
        loadingEntities.values.forEach { it.cancel("Removed.") }
        loadingEntities.clear()

        for (meshContainerDeferred in entityMeshCache.values) {
            if (meshContainerDeferred.isCompleted) {
                meshContainerDeferred.getCompleted().clearInstances()
            }
        }

        destinationInstanceContainer.clearInstances()
        // Note: Don't clear challengeMonsterSpawnContainer here - it's managed by updateChallengeMonsterSpawns()
        // which observes area changes directly. Clearing it here causes spawns to disappear when NPCs are loaded.
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

    private val groundRaycaster = Raycaster()
    private val groundRayOrigin = Vector3()
    private val groundRayDirection = Vector3(0.0, -1.0, 0.0)

    private fun calculateGroundHeight(x: Double, z: Double): Double {
        groundRayOrigin.set(x, 1000.0, z)
        groundRaycaster.set(groundRayOrigin, groundRayDirection)
        val intersections = groundRaycaster.intersectObject(renderContext.collisionGeometry, recursive = true)

        // Find the first valid ground intersection
        val groundIntersection = intersections.find { intersection ->
            // Same logic as pickGround - don't allow steep terrain
            intersection.face?.normal?.let { n -> n.y > 0.75 } ?: false
        }

        return groundIntersection?.point?.y ?: 0.0
    }

    /**
     * Updates challenge mode random monster spawn point visualizations for the current area.
     */
    private fun updateChallengeMonsterSpawns(
        quest: QuestModel?,
        area: AreaModel?,
        areaVariant: AreaVariantModel?,
        selection: ChallengePreviewSelection,
    ) {
        val spawnContainer = challengeMonsterSpawnContainer ?: return
        spawnContainer.clearInstances()
        clearChallengeMonsterMeshes()

        if (quest == null || area == null) {
            return
        }

        // Check if quest has challenge mode data
        if (quest.cmRandomSpawns.value.isEmpty()) {
            return
        }

        // Use the area variant passed from the cell observer (already consistent with quest/area).
        if (areaVariant == null) {
            logger.debug { "Area variant not available, skipping CM spawn creation" }
            return
        }

        // Validate that area variant matches the current area
        if (areaVariant.area.id != area.id) {
            // Area variant hasn't updated yet, skip spawn creation for now
            return
        }

        val sections = areaVariant.sections.value
        var totalSpawnsCreated = 0
        var spawnsSkipped = 0

        if (selection.simulation != null) {
            renderChallengeMonsterMeshes(
                quest,
                area,
                areaVariant,
                sections,
                selection,
            )
            return
        }

        val entriesToRender = challengeSpawnsToRender(
            quest,
            simulation = null,
            logicalFloor = selection.logicalFloor,
            roomId = selection.roomId,
            selectedEvents = selection.selectedEvents,
        )

        // Without a seed there is no monster type yet, so render candidate arrows.
        for (spawn in entriesToRender) {
            if (quest.entityBelongsToMap(
                    entityFloorId = spawn.floorId,
                    mapEpisode = areaVariant.episode,
                    mapAreaId = area.id,
                    mapVariation = areaVariant.id,
                )
            ) {
                val sectionId = spawn.roomId

                // Find section in the currently displayed variant
                val section = sections.find { it.id == sectionId }

                if (section == null) {
                    logger.debug { "CM spawn section $sectionId not found in variant ${areaVariant.id} of area ${area.id}" }
                    spawnsSkipped++
                    continue
                }

                val spawnModel = ChallengeMonsterSpawnModel(
                    spawn.floorId,
                    spawn.roomId,
                    spawn.entry,
                    section,
                )
                spawnContainer.addInstance(spawnModel)
                totalSpawnsCreated++
            }
        }

        if (spawnsSkipped > 0) {
            logger.debug { "CM spawns in variant ${areaVariant.id}: $totalSpawnsCreated shown, $spawnsSkipped skipped" }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun clearChallengeMonsterMeshes() {
        challengeMonsterLoadingJob?.cancel("Challenge preview changed.")
        challengeMonsterLoadingJob = null
        challengeMonsterMeshCache?.values?.forEach { deferred ->
            if (deferred.isCompleted) {
                deferred.getCompleted().clearInstances()
            }
        }
    }

    private fun renderChallengeMonsterMeshes(
        quest: QuestModel,
        area: AreaModel,
        areaVariant: AreaVariantModel,
        sections: List<SectionModel>,
        selection: ChallengePreviewSelection,
    ) {
        val simulation = selection.simulation ?: return
        val previews = mutableListOf<ChallengeMonsterPreview>()

        for (monster in challengeMonstersToRender(
            simulation,
            selection.logicalFloor,
            selection.roomId,
            selection.selectedEvents,
        )) {
            if (!quest.entityBelongsToMap(
                    entityFloorId = monster.floorId,
                    mapEpisode = areaVariant.episode,
                    mapAreaId = area.id,
                    mapVariation = areaVariant.id,
                )
            ) {
                continue
            }

            val section = sections.find { it.id == monster.roomId } ?: continue
            val npcType = getNpcTypeForChallengeMonsterIndex(monster.monsterTypeIndex)
            if (npcType == null) {
                addChallengeArrow(monster, section)
                continue
            }

            val npc = QuestNpc(
                npcType,
                areaVariant.episode,
                monster.floorId,
                monster.waveNumber.toShort(),
            ).apply {
                sectionId = monster.roomId.toShort()
                setPosition(monster.location.x, monster.location.y, monster.location.z)
                data.setInt(32, monster.location.angleX)
                data.setInt(36, monster.location.angleY)
                data.setInt(40, monster.location.angleZ)
            }
            val model = QuestNpcModel(npc, monster.waveNumber)
            model.setSection(section, keepRelativeTransform = true)
            val transform = ChallengeMonsterSpawnModel(
                monster.floorId,
                monster.roomId,
                monster.location,
                section,
            )
            model.setWorldPosition(transform.position.value)
            val rotation = transform.rotation.value
            model.setWorldRotation(euler(rotation.x, rotation.y, rotation.z))
            previews += ChallengeMonsterPreview(monster, section, npcType, model)
        }

        val cache = challengeMonsterMeshCache ?: return
        challengeMonsterLoadingJob = scope.launch {
            previews.groupBy { it.type }.forEach { (type, typedPreviews) ->
                typedPreviews.chunked(MAX_CHALLENGE_MONSTERS_PER_MESH)
                    .forEachIndexed { batch, previewBatch ->
                        try {
                            val container = cache.get(
                                ChallengeMonsterMeshKey(type, selection.ultimate, batch),
                            )
                            previewBatch.forEach { container.addInstance(it.model) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            logger.error(e) { "Couldn't render Challenge Mode preview for $type." }
                            previewBatch.forEach { addChallengeArrow(it.monster, it.section) }
                        }
                    }
            }
        }
    }

    private fun addChallengeArrow(
        monster: ChallengeModeSimulatedMonster,
        section: SectionModel,
    ) {
        challengeMonsterSpawnContainer?.addInstance(
            ChallengeMonsterSpawnModel(
                monster.floorId,
                monster.roomId,
                monster.location,
                section,
            )
        )
    }

    /**
     * Called before each render to update text scales for constant screen size.
     */
    fun beforeRender() {
        labelManager?.beforeRender()
    }

    private data class TypeAndModel(
        val type: EntityType,
        val model: Int?,
        val ultimate: Boolean,
        val renderVariant: Int?,
    )

    private data class ChallengeSpawnRenderState(
        val quest: QuestModel?,
        val area: AreaModel?,
        val areaVariant: AreaVariantModel?,
        val selection: ChallengePreviewSelection,
    )

    private data class ChallengePreviewSelection(
        val simulation: ChallengeModeSeedSimulation?,
        val logicalFloor: Int?,
        val roomId: Int?,
        val selectedEvents: Set<ChallengeEventSelection>,
        val ultimate: Boolean,
        val challengeDataRevision: Int,
    )

    private data class ChallengeSimulationAndEvents(
        val simulation: ChallengeModeSeedSimulation?,
        val selectedEvents: Set<ChallengeEventSelection>,
        val challengeDataRevision: Int,
    )

    private data class ChallengeMonsterMeshKey(
        val type: NpcType,
        val ultimate: Boolean,
        val batch: Int,
    )

    private data class ChallengeMonsterPreview(
        val monster: ChallengeModeSimulatedMonster,
        val section: SectionModel,
        val type: NpcType,
        val model: QuestNpcModel,
    )

    private fun entityRenderVariant(entity: QuestEntityModel<*, *>): Int? =
        if (entity is QuestObjectModel && entity.type == ObjectType.ForestDoor) {
            entity.entity.forestDoorDigit
        } else {
            null
        }

    companion object {
        private const val MAX_CHALLENGE_MONSTERS_PER_MESH = 300
    }
}

internal data class ChallengeSpawnToRender(
    val floorId: Int,
    val roomId: Int,
    val entry: DatCmRandomSpawnEntry,
)

internal data class ChallengeEventSelection(
    val floorId: Int,
    val sourceEventId: Int,
    val roomId: Int,
    /** Null for a source Event2 configuration; set for a materialized Event1 card. */
    val waveNumber: Int? = null,
)

/** Pure selection step used by the renderer and its seed-mode regression tests. */
internal fun challengeSpawnsToRender(
    quest: QuestModel,
    simulation: ChallengeModeSeedSimulation?,
    logicalFloor: Int? = null,
    roomId: Int? = null,
    selectedEvents: Set<ChallengeEventSelection> = emptySet(),
): List<ChallengeSpawnToRender> =
    if (simulation == null) {
        quest.cmRandomSpawns.value
            .filter {
                (logicalFloor == null || it.floorId == logicalFloor) &&
                        (roomId == null || it.roomId == roomId) &&
                        (selectedEvents.isEmpty() || selectedEvents.any { event ->
                            event.floorId == it.floorId && event.roomId == it.roomId
                        })
            }
            .flatMap { spawn ->
                spawn.entries.map { entry ->
                    ChallengeSpawnToRender(spawn.floorId, spawn.roomId, entry)
                }
            }
    } else {
        challengeMonstersToRender(simulation, logicalFloor, roomId, selectedEvents)
            .map { monster ->
                ChallengeSpawnToRender(monster.floorId, monster.roomId, monster.location)
            }
    }

internal fun challengeMonstersToRender(
    simulation: ChallengeModeSeedSimulation,
    logicalFloor: Int?,
    roomId: Int?,
    selectedEvents: Set<ChallengeEventSelection> = emptySet(),
): List<ChallengeModeSimulatedMonster> =
    simulation.monsters.filter {
        (logicalFloor == null || it.floorId == logicalFloor) &&
                (roomId == null || it.roomId == roomId) &&
                (selectedEvents.isEmpty() || selectedEvents.any { event ->
                    event.floorId == it.floorId &&
                            event.sourceEventId == it.sourceEventId &&
                            (event.waveNumber == null || event.waveNumber == it.waveNumber)
                })
    }
