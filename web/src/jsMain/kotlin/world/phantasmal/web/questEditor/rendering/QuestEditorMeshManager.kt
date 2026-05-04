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
        ) { quest, area, areaVariant ->
            if (quest != null && area != null) {
                if (areaVariant != null) {
                    // Load the specific variant
                    // Use variant's episode to handle cross-episode maps (e.g., EP4 quest using EP2 Lab)
                    loadAreaMeshes(areaVariant.episode, areaVariant)
                } else {
                    // For areas without variants (like Lab, Pioneer2), load the default variant
                    val defaultVariant = area.areaVariants.firstOrNull()
                    loadAreaMeshes(defaultVariant?.episode ?: quest.episode, defaultVariant)
                }
            } else {
                loadAreaMeshes(null, null)
            }
        }

        observeNow(
            questEditorStore.currentQuest,
            questEditorStore.currentArea,
            questEditorStore.currentFloorIds,
            questEditorStore.selectedEventsSectionWaves,
        ) { quest, area, floorIds, selectedSectionWaves ->
            loadNpcMeshes(
                if (quest != null && area != null) {
                    quest.npcs.filteredCell {
                        val areaMatch = if (floorIds != null) {
                            it.areaId in floorIds
                        } else {
                            it.areaId == area.id
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
        ) { quest, area, floorIds ->
            loadObjectMeshes(
                if (quest != null && area != null) {
                    quest.objects.filteredCell {
                        val areaMatch = if (floorIds != null) {
                            it.areaId in floorIds
                        } else {
                            it.areaId == area.id
                        }
                        it.sectionInitialized and areaMatch
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
        ) { quest, area, floorIds, bbox ->
            val spawns = quest?.particleSpawns
            loadParticleMarkers(
                if (spawns == null || area == null) {
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
                        // whose XZ position falls clearly outside the floor's collision bbox;
                        // those were "intended for another floor" but bled through analysis.
                        if (bbox != null) {
                            // Probe with the spawn's world-space XYZ but clamp Y to the bbox's
                            // Y-range, since particles legitimately spawn above ceilings or
                            // below floors. This makes the test effectively "is XZ inside?".
                            val px = spawn.x.toDouble()
                            val pz = spawn.z.toDouble()
                            val py = spawn.y.toDouble().coerceIn(bbox.min.y, bbox.max.y)
                            // Slack covers spawns near edges and bbox imprecision from missing
                            // collision triangles.
                            val slack = COORD_FILTER_SLACK
                            val inside = px >= bbox.min.x - slack && px <= bbox.max.x + slack &&
                                pz >= bbox.min.z - slack && pz <= bbox.max.z + slack &&
                                py >= bbox.min.y && py <= bbox.max.y
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
         * checking script particle XZ coordinates. Covers spawns near edges, missing collision
         * triangles, and effects placed slightly beyond the playable area.
         */
        private const val COORD_FILTER_SLACK = 100.0
    }
}
