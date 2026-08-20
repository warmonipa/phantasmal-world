package world.phantasmal.web.questEditor.rendering

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mu.KotlinLogging
import world.phantasmal.cell.cell
import world.phantasmal.cell.flatMap
import world.phantasmal.cell.map
import world.phantasmal.cell.mutateDeferred
import world.phantasmal.cell.observeNow
import world.phantasmal.core.disposable.DisposableSupervisedScope
import world.phantasmal.web.core.euler
import world.phantasmal.web.core.loading.LoadingCache
import world.phantasmal.psolib.fileFormats.quest.ChallengeModeSeedSimulation
import world.phantasmal.psolib.fileFormats.quest.ChallengeModeSimulatedMonster
import world.phantasmal.psolib.fileFormats.quest.DatCmRandomSpawnEntry
import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.fileFormats.quest.QuestNpc
import world.phantasmal.psolib.fileFormats.quest.getNpcTypeForChallengeMonsterIndex
import world.phantasmal.web.questEditor.loading.EntityMeshLoader
import world.phantasmal.web.questEditor.models.AreaModel
import world.phantasmal.web.questEditor.models.AreaVariantModel
import world.phantasmal.web.questEditor.models.ChallengeMonsterSpawnModel
import world.phantasmal.web.questEditor.models.QuestModel
import world.phantasmal.web.questEditor.models.QuestNpcModel
import world.phantasmal.web.questEditor.models.SectionModel
import world.phantasmal.web.questEditor.stores.QuestEditorRenderState
import world.phantasmal.web.questEditor.stores.QuestEditorUiStore
import world.phantasmal.webui.DisposableContainer

private val logger = KotlinLogging.logger {}

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

/** Defers temporary preview-model construction until the current Cell mutation has completed. */
internal fun deferChallengeSpawnRender(render: () -> Unit) {
    mutateDeferred(render)
}

/** Owns all candidate and seeded Challenge Mode monster preview rendering. */
internal class ChallengePreviewMeshManager(
    private val questEditorStore: QuestEditorRenderState,
    private val questEditorUiStore: QuestEditorUiStore,
    private val renderContext: QuestRenderContext,
    private val entityMeshLoader: EntityMeshLoader,
) : DisposableContainer() {
    private val scope = addDisposable(DisposableSupervisedScope(this::class, Dispatchers.Main))
    private val spawnContainer = addDisposable(
        ChallengeMonsterSpawnContainer().also { renderContext.helpers.add(it.mesh) }
    )
    private val monsterMeshCache = addDisposable(
        LoadingCache<MonsterMeshKey, EntityInstanceContainer>(
            { key ->
                val mesh = entityMeshLoader.loadInstancedMesh(
                    key.type,
                    model = null,
                    ultimate = key.ultimate,
                )
                renderContext.entities.add(mesh)
                EntityInstanceContainer(mesh, modelChanged = {})
            },
            { container ->
                renderContext.entities.remove(container.mesh)
                container.dispose()
            },
        )
    )
    private var loadingJob: Job? = null

    init {
        val challengeDataRevision =
            questEditorStore.currentQuest.flatMap { quest -> quest?.cmDataRevision ?: cell(0) }
        val simulationAndEvents = map(
            questEditorStore.challengeSeedSimulation,
            questEditorStore.selectedEvents,
            challengeDataRevision,
        ) { simulation, events, revision ->
            SimulationAndEvents(
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
        val selection = map(
            simulationAndEvents,
            questEditorStore.selectedChallengeLogicalFloor,
            questEditorStore.selectedChallengeRoomId,
            questEditorUiStore.ultimate,
        ) { state, logicalFloor, roomId, ultimate ->
            PreviewSelection(
                state.simulation,
                logicalFloor,
                roomId,
                state.selectedEvents,
                ultimate,
                state.challengeDataRevision,
            )
        }

        observeNow(
            map(
                questEditorStore.currentQuest,
                questEditorStore.currentArea,
                questEditorStore.currentAreaVariant,
                selection,
            ) { quest, area, areaVariant, previewSelection ->
                RenderState(quest, area, areaVariant, previewSelection)
            }
        ) { state ->
            deferChallengeSpawnRender {
                if (!disposed) {
                    update(state.quest, state.area, state.areaVariant, state.selection)
                }
            }
        }
    }

    override fun dispose() {
        loadingJob?.cancel(CancellationException("Disposed."))
        spawnContainer.clearInstances()
        renderContext.helpers.remove(spawnContainer.mesh)
        super.dispose()
    }

    private fun update(
        quest: QuestModel?,
        area: AreaModel?,
        areaVariant: AreaVariantModel?,
        selection: PreviewSelection,
    ) {
        spawnContainer.clearInstances()
        clearMonsterMeshes()

        if (quest == null || area == null || quest.cmRandomSpawns.value.isEmpty()) return
        if (areaVariant == null) {
            logger.debug { "Area variant not available, skipping CM spawn creation" }
            return
        }
        if (areaVariant.area.id != area.id) return

        val sections = areaVariant.sections.value
        if (selection.simulation != null) {
            renderMonsterMeshes(quest, area, areaVariant, sections, selection)
            return
        }

        var created = 0
        var skipped = 0
        for (spawn in challengeSpawnsToRender(
            quest,
            simulation = null,
            logicalFloor = selection.logicalFloor,
            roomId = selection.roomId,
            selectedEvents = selection.selectedEvents,
        )) {
            if (!quest.entityBelongsToMap(
                    entityFloorId = spawn.floorId,
                    mapEpisode = areaVariant.episode,
                    mapAreaId = area.id,
                    mapVariation = areaVariant.id,
                )
            ) continue

            val section = sections.find { it.id == spawn.roomId }
            if (section == null) {
                skipped++
                continue
            }
            spawnContainer.addInstance(
                ChallengeMonsterSpawnModel(spawn.floorId, spawn.roomId, spawn.entry, section)
            )
            created++
        }

        if (skipped > 0) {
            logger.debug {
                "CM spawns in variant ${areaVariant.id}: $created shown, $skipped skipped"
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun clearMonsterMeshes() {
        loadingJob?.cancel(CancellationException("Challenge preview changed."))
        loadingJob = null
        monsterMeshCache.loadedValues.forEach(EntityInstanceContainer::clearInstances)
    }

    private fun renderMonsterMeshes(
        quest: QuestModel,
        area: AreaModel,
        areaVariant: AreaVariantModel,
        sections: List<SectionModel>,
        selection: PreviewSelection,
    ) {
        val simulation = selection.simulation ?: return
        val previews = mutableListOf<MonsterPreview>()

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
            ) continue

            val section = sections.find { it.id == monster.roomId } ?: continue
            val npcType = getNpcTypeForChallengeMonsterIndex(monster.monsterTypeIndex)
            if (npcType == null) {
                addArrow(monster, section)
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
            val model = QuestNpcModel(
                npc,
                monster.waveNumber,
                quest.npcPlacementPolicy,
            )
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
            previews += MonsterPreview(monster, section, npcType, model)
        }

        loadingJob = scope.launch {
            previews.groupBy { it.type }.forEach { (type, typedPreviews) ->
                typedPreviews.chunked(MAX_MONSTERS_PER_MESH)
                    .forEachIndexed { batch, previewBatch ->
                        try {
                            val container = monsterMeshCache.get(
                                MonsterMeshKey(type, selection.ultimate, batch)
                            )
                            previewBatch.forEach { container.addInstance(it.model) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            logger.error(e) { "Couldn't render Challenge Mode preview for $type." }
                            previewBatch.forEach { addArrow(it.monster, it.section) }
                        }
                    }
            }
        }
    }

    private fun addArrow(monster: ChallengeModeSimulatedMonster, section: SectionModel) {
        spawnContainer.addInstance(
            ChallengeMonsterSpawnModel(
                monster.floorId,
                monster.roomId,
                monster.location,
                section,
            )
        )
    }

    private data class RenderState(
        val quest: QuestModel?,
        val area: AreaModel?,
        val areaVariant: AreaVariantModel?,
        val selection: PreviewSelection,
    )

    private data class PreviewSelection(
        val simulation: ChallengeModeSeedSimulation?,
        val logicalFloor: Int?,
        val roomId: Int?,
        val selectedEvents: Set<ChallengeEventSelection>,
        val ultimate: Boolean,
        val challengeDataRevision: Int,
    )

    private data class SimulationAndEvents(
        val simulation: ChallengeModeSeedSimulation?,
        val selectedEvents: Set<ChallengeEventSelection>,
        val challengeDataRevision: Int,
    )

    private data class MonsterMeshKey(val type: NpcType, val ultimate: Boolean, val batch: Int)

    private data class MonsterPreview(
        val monster: ChallengeModeSimulatedMonster,
        val section: SectionModel,
        val type: NpcType,
        val model: QuestNpcModel,
    )

    companion object {
        private const val MAX_MONSTERS_PER_MESH = 300
    }
}
