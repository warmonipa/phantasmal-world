package world.phantasmal.web.questEditor.rendering

import world.phantasmal.cell.Cell
import world.phantasmal.cell.and
import world.phantasmal.cell.list.emptyListCell
import world.phantasmal.cell.list.filteredCell
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.web.questEditor.asm.SymbolChatTriggerInfo
import world.phantasmal.web.questEditor.loading.AreaAssetLoader
import world.phantasmal.web.questEditor.loading.EntityAssetLoader
import world.phantasmal.web.questEditor.loading.SymbolChatColliRepository
import world.phantasmal.web.questEditor.models.lobbyEventSeasonOk
import world.phantasmal.web.questEditor.stores.*

class QuestEditorMeshManager(
    areaAssetLoader: AreaAssetLoader,
    entityAssetLoader: EntityAssetLoader,
    questEditorStore: QuestEditorStore,
    questEditorUiStore: QuestEditorUiStore,
    playbackVisualizationStore: PlaybackVisualizationStore,
    viewportStore: ViewportStore,
    areaStore: AreaStore,
    renderContext: QuestRenderContext,
    symbolChatColliRepository: SymbolChatColliRepository,
    symbolChatTriggers: Cell<List<SymbolChatTriggerInfo>>,
    readSegmentData: (Int) -> Buffer?,
) : QuestMeshManager(areaAssetLoader, entityAssetLoader, questEditorStore, questEditorUiStore, playbackVisualizationStore, areaStore, renderContext) {
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
                    quest.npcs.filteredCell {
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
            renderContext.collisionGeometryBoundingBox,
            questEditorUiStore.showScriptParticles,
        ) { quest, area, floorIds, bbox, showScriptParticles ->
            val spawns = quest?.particleSpawns
            loadParticleMarkers(
                if (spawns == null || area == null || !showScriptParticles) {
                    emptyList()
                } else {
                    spawns.filter { spawn ->
                        // 1) Empty floorIds = unreachable bytecode (psolib already warned).
                        if (spawn.floorIds.isEmpty()) return@filter false

                        // 2) Floor-id attribution from static analysis.
                        val floorMatches = if (floorIds != null) {
                            spawn.floorIds.any { it in floorIds }
                        } else {
                            area.id in spawn.floorIds
                        }
                        if (!floorMatches) return@filter false

                        // 3) Coord-based geometry filter — script analysis can over-attribute
                        // when a quest dispatches through random/non-floor-deterministic state
                        // (e.g. Endless Episode 2's `r6 mod 3` chain, where floors 1..12 share
                        // a dispatcher that reaches all per-floor thread starters). Drop spawns
                        // whose XZ position falls clearly outside the floor's collision bbox.
                        // Y is intentionally unconstrained: particles legitimately spawn above
                        // ceilings or below floors as visual effects.
                        //
                        // Heuristic, not a guarantee: if two floors' coordinate ranges overlap
                        // (rare in PSO maps), a spawn authored for one may also fall within
                        // the other's bbox.
                        if (bbox != null) {
                            val px = spawn.x.toDouble()
                            val pz = spawn.z.toDouble()
                            val slack = COORD_FILTER_SLACK
                            val inside = px in (bbox.min.x - slack)..(bbox.max.x + slack) &&
                                pz in (bbox.min.z - slack)..(bbox.max.z + slack)
                            if (!inside) return@filter false
                        }

                        true
                    }
                }
            )
        }

        observeNow(questEditorUiStore.showCollisionGeometry) {
            renderContext.collisionGeometryVisible = it
            renderContext.renderGeometryVisible = !it
        }

        observeNow(viewportStore.gotoIndicatorPosition) { pos ->
            gotoIndicatorManager.setPosition(pos)
        }
    }

    override fun beforeRender() {
        super.beforeRender()
        symbolChatBillboardManager.beforeRender()
        gotoIndicatorManager.update()
    }

    private companion object {
        /**
         * Slack (in PSO world units) added to the collision-geometry bounding box when
         * checking script particle XZ coordinates. PSO maps span thousands of units, so 100
         * is roughly a 1% margin — generous enough to catch edge spawns and bbox imprecision
         * from missing collision triangles without leaking spawns from neighboring floors.
         */
        private const val COORD_FILTER_SLACK = 100.0
    }
}
