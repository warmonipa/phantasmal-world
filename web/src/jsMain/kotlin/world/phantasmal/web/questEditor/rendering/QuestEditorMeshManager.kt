package world.phantasmal.web.questEditor.rendering

import mu.KotlinLogging
import world.phantasmal.cell.Cell
import world.phantasmal.cell.and
import world.phantasmal.cell.cell
import world.phantasmal.cell.flatMap
import world.phantasmal.cell.map
import world.phantasmal.cell.list.emptyListCell
import world.phantasmal.cell.list.filteredCell
import world.phantasmal.cell.list.mapToList
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptNpcClass
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptNpcSpawn
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptSpatialInteraction
import world.phantasmal.psolib.asm.dataFlowAnalysis.scriptNpcTemplate
import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleSpawn
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.fileFormats.quest.QuestNpc
import world.phantasmal.psolib.fileFormats.particle.GLOBAL_PARTICLE_EFFECT_COUNT
import world.phantasmal.web.questEditor.asm.SymbolChatTriggerInfo
import world.phantasmal.web.questEditor.loading.AreaAssetLoader
import world.phantasmal.web.questEditor.loading.EntityAssetLoader
import world.phantasmal.web.questEditor.loading.ParticleAssetLoader
import world.phantasmal.web.questEditor.loading.SymbolChatColliRepository
import world.phantasmal.web.questEditor.models.QuestModel
import world.phantasmal.web.questEditor.models.QuestNpcModel
import world.phantasmal.web.questEditor.models.lobbyEventSeasonOk
import world.phantasmal.web.questEditor.stores.*

private val questEditorMeshLogger = KotlinLogging.logger {}

internal fun particleTemplateMapIds(
    spawn: ParticleSpawn,
    floorToMapId: Map<Int, Int>,
    visibleFloorIds: Set<Int>,
    currentMapId: Int,
): Set<Int> = if (spawn.particleId < GLOBAL_PARTICLE_EFFECT_COUNT) {
    setOf(currentMapId)
} else {
    spawn.executionFloorIds.asSequence()
        .filter { it in visibleFloorIds }
        .mapNotNullTo(mutableSetOf()) { floorToMapId[it] }
        .ifEmpty { setOf(currentMapId) }
}

/**
 * Selects fixed quest particle emitters for one editor floor view.
 *
 * A DAT emitter belongs to its entity floor; an opcode emitter belongs to its execution paths.
 * An unresolved opcode is not shown in an arbitrary floor because the client clears the
 * particle system during floor transitions; treating unknown as global would be false.
 */
internal fun particleSpawnsForFloorView(
    spawns: List<ParticleSpawn>,
    visibleFloorIds: Set<Int>,
): List<ParticleSpawn> = spawns.filter { spawn ->
    spawn.executionFloorIds.any { it in visibleFloorIds }
}

class QuestEditorMeshManager(
    areaAssetLoader: AreaAssetLoader,
    entityAssetLoader: EntityAssetLoader,
    particleAssetLoader: ParticleAssetLoader,
    questEditorStore: QuestEditorRenderAccess,
    questEditorUiStore: QuestEditorUiStore,
    playbackVisualizationStore: PlaybackVisualizationStore,
    viewportStore: ViewportStore,
    areaStore: AreaStore,
    renderContext: QuestRenderContext,
    symbolChatColliRepository: SymbolChatColliRepository,
    symbolChatTriggers: Cell<List<SymbolChatTriggerInfo>>,
    readSegmentData: (Int) -> Buffer?,
) : QuestMeshManager(
    areaAssetLoader,
    entityAssetLoader,
    particleAssetLoader,
    questEditorStore,
    questEditorUiStore,
    playbackVisualizationStore,
    areaStore,
    renderContext,
) {
    private data class WalkthroughInputs(
        val quest: QuestModel,
        val scriptNpcs: List<ScriptNpcSpawn>,
        val spatialInteractions: List<ScriptSpatialInteraction>,
    )

    private data class WalkthroughEnvironment(
        val pathfinder: WalkthroughPathfinder?,
    )

    private val questParticleSpawns = questEditorStore.currentQuest.flatMap { quest ->
        quest?.particleSpawns ?: cell(emptyList())
    }
    private val walkthroughInputs = questEditorStore.currentQuest.flatMap { quest ->
        if (quest == null) cell(null)
        else map(
            quest.walkthroughRevision,
            quest.scriptNpcSpawns,
            quest.scriptSpatialInteractions,
        ) { _, scriptNpcs, interactions ->
            WalkthroughInputs(quest, scriptNpcs, interactions)
        }
    }
    private val walkthroughEnvironment = renderContext.collisionGeometryObject.map { collisionGeometry ->
        WalkthroughEnvironment(
            collisionGeometry?.let(CollisionWalkthroughPathfinder::create),
        )
    }

    private val symbolChatTriggerManager = addDisposable(
        SymbolChatTriggerManager(
            triggers = symbolChatTriggers,
            readSegmentData = readSegmentData,
            symbolChatColliRepository = symbolChatColliRepository,
            renderContext = renderContext,
        )
    )
    private val symbolChatBillboardManager = addDisposable(
        SymbolChatBillboardManager(questEditorStore, symbolChatColliRepository, renderContext)
    )
    private val gotoIndicatorManager = addDisposable(GotoIndicatorManager(renderContext))
    private val walkthroughRenderer = addDisposable(WalkthroughRouteRenderer(renderContext))

    init {
        observeNow(
            questEditorStore.currentQuest,
            questEditorStore.currentArea,
            questEditorStore.currentAreaVariant,
            questEditorUiStore.ultimate,
        ) { quest, area, areaVariant, ultimate ->
            if (quest != null && area != null) {
                if (areaVariant != null) {
                    // Load the specific variant
                    // Use variant's episode to handle cross-episode maps (e.g., EP4 quest using EP2 Lab)
                    loadAreaMeshes(areaVariant.episode, areaVariant, ultimate)
                } else {
                    // For areas without variants (like Lab, Pioneer2), load the default variant
                    val defaultVariant = area.areaVariants.firstOrNull()
                    loadAreaMeshes(defaultVariant?.episode ?: quest.episode, defaultVariant, ultimate)
                }
            } else {
                loadAreaMeshes(null, null, ultimate)
            }
        }

        observeNow(
            questEditorStore.currentQuest,
            questEditorStore.currentArea,
            questEditorStore.currentFloorIds,
            questEditorStore.selectedEventsSectionWaves,
            // Re-add NPC meshes when the Ultimate skin toggles; EntityMeshManager reads the
            // current ultimate value when it (re)loads each mesh.
            questEditorUiStore.ultimate,
        ) { quest, area, floorIds, selectedSectionWaves, _ ->
            loadNpcMeshes(
                if (quest != null && area != null) {
                    val datNpcs = quest.npcs.filteredCell {
                        val areaMatch = if (floorIds != null) {
                            it.floorId in floorIds
                        } else {
                            it.floorId == area.id
                        }

                        val matchesSelectedEvent = if (selectedSectionWaves.isEmpty()) {
                            true
                        } else {
                            selectedSectionWaves.contains(Pair(it.sectionId.value, it.wave.value.id))
                        }

                        it.sectionInitialized and areaMatch and matchesSelectedEvent
                    }
                    val visibleFloorIds = floorIds ?: setOf(area.id)
                    mapToList(datNpcs, quest.scriptNpcSpawns) { editableNpcs, scriptSpawns ->
                        editableNpcs + scriptNpcPreviewModels(
                            scriptSpawns,
                            visibleFloorIds,
                            quest,
                        )
                    }
                } else {
                    emptyListCell()
                }
            )
        }

        observeNow(
            questEditorStore.currentQuest,
            questEditorStore.currentArea,
            questEditorStore.currentFloorIds,
            questEditorUiStore.selectedLobbyEvent,
            // Re-add object meshes when the Ultimate skin toggles.
            questEditorUiStore.ultimate,
        ) { quest, area, floorIds, selectedLobbyEvent, _ ->
            loadObjectMeshes(
                if (quest != null && area != null) {
                    quest.objects.filteredCell {
                        val areaMatch = if (floorIds != null) {
                            it.floorId in floorIds
                        } else {
                            it.floorId == area.id
                        }
                        val seasonOk = lobbyEventSeasonOk(it.type.lobbyEvent, selectedLobbyEvent)
                        it.sectionInitialized and (areaMatch && seasonOk)
                    }
                } else {
                    emptyListCell()
                }
            )
        }

        observeNow(
            questEditorStore.currentQuest,
            questEditorStore.currentArea,
            questEditorStore.currentFloorIds,
            questEditorUiStore.showQuestParticles,
            questParticleSpawns,
        ) { quest, area, floorIds, showQuestParticles, particleSpawns ->
            if (quest == null || area == null || !showQuestParticles) {
                loadParticleMarkers(emptyList())
            } else {
                // The runtime emitter has no floor field. DAT objects supply an exact floor;
                // opcode emitters are scoped by the script path that creates them. An unresolved
                // opcode is not assigned to an arbitrary floor.
                val visibleFloorIds = floorIds ?: setOf(area.id)
                val mapId = quest.floorMappings.firstOrNull { mapping ->
                    if (floorIds != null) mapping.floorId in floorIds else mapping.mapAreaId == area.id
                }?.mapId ?: area.id
                val floorToMapId = quest.floorMappings.associate { it.floorId to it.mapId }
                loadParticleMarkers(
                    spawns = particleSpawnsForFloorView(particleSpawns, visibleFloorIds),
                    // A map-local template is selected when the emitter is created. Its source
                    // floors recover the corresponding map-specific resource table.
                    resolveTemplateMapIds = {
                        particleTemplateMapIds(it, floorToMapId, visibleFloorIds, mapId)
                    },
                    resolveEntityPosition = { entityId ->
                        when (entityId) {
                            in 0x1000..0x3FFF -> quest.npcs.value
                                .firstOrNull {
                                    it.floorId in visibleFloorIds &&
                                            (it.entity.id + 0x1000) and 0xFFFF == entityId
                                }
                                ?.worldPosition?.value
                            in 0x4000..0xFFFF -> quest.objects.value
                                .firstOrNull {
                                    it.floorId in visibleFloorIds &&
                                            (it.entity.id.toInt() + 0x4000) and 0xFFFF == entityId
                                }
                                ?.worldPosition?.value
                            // Player IDs 0..11 have no fixed position in a quest file.
                            else -> null
                        }
                    },
                )
            }
        }

        observeNow(questEditorUiStore.showCollisionGeometry) {
            renderContext.collisionGeometryVisible = it
            renderContext.renderGeometryVisible = !it
        }

        observeNow(viewportStore.gotoIndicatorPosition) { pos ->
            gotoIndicatorManager.setPosition(pos)
        }

        observeNow(
            walkthroughInputs,
            questEditorStore.currentArea,
            questEditorStore.currentFloorIds,
            questEditorUiStore.walkthroughPlayer,
            walkthroughEnvironment,
        ) { inputs, area, floorIds, player, environment ->
            if (inputs == null || area == null || environment.pathfinder == null) {
                walkthroughRenderer.setRoute(WalkthroughRoute(emptyList(), emptyList()), player.color)
            } else {
                val route = planWalkthroughRoute(
                    quest = inputs.quest,
                    visibleFloorIds = floorIds ?: setOf(area.id),
                    clientId = player.clientId,
                    scriptNpcSpawns = inputs.scriptNpcs,
                    scriptSpatialInteractions = inputs.spatialInteractions,
                    pathfinder = environment.pathfinder,
                )
                if (route.diagnostics.isNotEmpty()) {
                    questEditorMeshLogger.debug {
                        "Walkthrough diagnostics:\n${route.diagnostics.joinToString("\n")}"
                    }
                }
                walkthroughRenderer.setRoute(route, player.color)
            }
        }
    }

    override fun beforeRender() {
        super.beforeRender()
        symbolChatBillboardManager.beforeRender()
        gotoIndicatorManager.update()
    }

}

internal fun scriptNpcPreviewModels(
    spawns: List<ScriptNpcSpawn>,
    visibleFloorIds: Set<Int>,
    quest: QuestModel,
): List<QuestNpcModel> = spawns.flatMap { spawn ->
    val template = scriptNpcTemplate(spawn.templateIndex) ?: return@flatMap emptyList()
    spawn.executionFloorIds.asSequence()
        .filter { it in visibleFloorIds }
        .map { floorId ->
            val episode = quest.floorMappings
                .firstOrNull { it.floorId == floorId }
                ?.mapEpisode
                ?: quest.episode
            val type = when (template.characterClass) {
                ScriptNpcClass.HUmar -> NpcType.NpcHUmar
                ScriptNpcClass.HUnewearl -> NpcType.NpcHUnewearl
                ScriptNpcClass.HUcast -> NpcType.NpcHUcast
                ScriptNpcClass.RAmar -> NpcType.NpcRAmar
                ScriptNpcClass.RAcast -> NpcType.NpcRAcast
                ScriptNpcClass.RAcaseal -> NpcType.NpcRAcaseal
                ScriptNpcClass.FOmarl -> NpcType.NpcFOmarl
                ScriptNpcClass.FOnewm -> NpcType.NpcFOnewm
                ScriptNpcClass.FOnewearl -> NpcType.NpcFOnewearl
            }
            val npc = QuestNpc(type, episode, floorId, wave = 0).apply {
                setPosition(spawn.x.toFloat(), spawn.y.toFloat(), spawn.z.toFloat())
                // Quest VM angles are degrees; the client converts them with signed integer division.
                data.setInt(36, (spawn.angle.toLong() * 0x10000L / 360L).toInt())
            }
            QuestNpcModel(
                npc,
                waveId = 0,
                quest.npcPlacementPolicy,
                scriptSpawn = spawn,
            ).apply {
                // Script coordinates are already map/world coordinates rather than section-local.
                setSectionInitialized()
            }
        }
        .toList()
}
