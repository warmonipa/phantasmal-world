package world.phantasmal.web.questEditor.stores

import kotlinx.coroutines.launch
import mu.KotlinLogging
import world.phantasmal.cell.*
import world.phantasmal.cell.list.ListCell
import world.phantasmal.cell.list.emptyListCell
import world.phantasmal.cell.list.filtered
import world.phantasmal.cell.list.flatMapToList
import world.phantasmal.core.externals.browser.FileSystemDirectoryHandle
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.dataFlowAnalysis.FloorMapping
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.fileFormats.quest.ChallengeModeSeedSimulation
import world.phantasmal.psolib.fileFormats.quest.simulateChallengeModeSeed
import world.phantasmal.web.core.PwToolType
import world.phantasmal.web.core.commands.Command
import world.phantasmal.web.core.stores.UiStore
import world.phantasmal.web.core.undo.UndoManager
import world.phantasmal.web.core.undo.UndoStack
import world.phantasmal.web.externals.three.Euler
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.questEditor.QuestRunner
import world.phantasmal.web.questEditor.loading.FreeRoamAreaInfo
import world.phantasmal.web.questEditor.loading.QuestLoader
import world.phantasmal.web.questEditor.models.*
import world.phantasmal.webui.stores.Store

private val logger = KotlinLogging.logger {}

class FreeRoamQuestResult(
    val questModel: QuestModel,
    val binName: String?,
    val datFilesByFloor: Map<Int, Triple<String, String, String>>,
)

class QuestEditorStore(
    private val questLoader: QuestLoader,
    uiStore: UiStore,
    private val areaStore: AreaStore,
    private val undoManager: UndoManager,
    private val viewportStore: ViewportStore,
    override val npcPlacementPolicy: NpcPlacementPolicy,
    initializeNewQuest: Boolean,
) : Store(), QuestEditorRenderAccess {
    private val _currentQuest = mutableCell<QuestModel?>(null)
    private val _currentArea = mutableCell<AreaModel?>(null)
    private val _currentAreaVariant = mutableCell<AreaVariantModel?>(null)
    private val _currentFloorIds = mutableCell<Set<Int>?>(null)
    private val _effectiveScriptEpisode = mutableCell<Episode>(Episode.I)
    private val _focusedEvent = mutableCell<QuestEventModel?>(null)
    private val _selectedEvents = mutableCell<Set<QuestEventModel>>(emptySet())
    private val _highlightedEntity = mutableCell<QuestEntityModel<*, *>?>(null)
    private val _selectedEntity = mutableCell<QuestEntityModel<*, *>?>(null)
    private val mainUndo = UndoStack(undoManager)
    private val _sectionsUpdated = mutableCell(0) // Trigger to update sections
    private var sectionLoadToken = 0
    private val _selectedSection = mutableCell<SectionModel?>(null)
    private val _challengeSeedSimulationEnabled = mutableCell(false)
    private val _challengeSeed = mutableCell(0)
    private val _selectedChallengeLogicalFloor = mutableCell<Int?>(null)
    private val _selectedChallengeRoomId = mutableCell<Int?>(null)

    private val runner = QuestRunner()
    override val currentQuest: Cell<QuestModel?> = _currentQuest
    override val currentArea: Cell<AreaModel?> = _currentArea
    override val currentAreaVariant: Cell<AreaVariantModel?> = _currentAreaVariant
    val effectiveScriptEpisode: Cell<Episode> = _effectiveScriptEpisode
    /** Episode that owns the currently displayed map, which can differ from the quest episode. */
    val currentMapEpisode: Cell<Episode?> =
        map(currentQuest, currentAreaVariant, _effectiveScriptEpisode) { quest, variant, scriptEp ->
            variant?.episode ?: if (quest?.floorMappingsFromScript == true) scriptEp else quest?.episode
        }
    /** When set, entity filtering uses these specific floor IDs instead of area+variant lookup. */
    override val currentFloorIds: Cell<Set<Int>?> = _currentFloorIds
    private val currentFloorMappingRevision: Cell<Int> =
        currentQuest.flatMap { quest -> quest?.floorMappingRevision ?: cell(0) }
    /**
     * True when the currently selected logical floor has an explicit script mapping whose
     * map variation does not exist in [areaStore]'s catalog. Renderers must omit map geometry
     * instead of silently substituting Map 1.
     */
    override val currentFloorHasUnsupportedVariant: Cell<Boolean> =
        map(currentQuest, currentFloorIds, currentAreaVariant, currentFloorMappingRevision) {
                quest, floorIds, variant, _ ->
            if (quest == null || variant != null || floorIds == null || floorIds.size != 1) {
                false
            } else {
                val floorId = floorIds.single()
                val mapping = quest.floorMappings.find { it.floorId == floorId }
                if (mapping == null) {
                    false
                } else {
                    val mapEpisode = mapping.mapEpisode ?: quest.effectiveEpisode
                    areaStore.getVariant(mapEpisode, mapping.mapAreaId, mapping.mapVariation) == null
                }
            }
        }

    val currentAreaNpcs: ListCell<QuestNpcModel> =
        flatMapToList(currentQuest, currentArea, currentFloorIds) { quest, area, floorIds ->
            filterEntitiesByFloor(quest, area, floorIds, quest?.npcs) { it.floorId }
        }

    override val currentAreaObjects: ListCell<QuestObjectModel> =
        flatMapToList(currentQuest, currentArea, currentFloorIds) { quest, area, floorIds ->
            filterEntitiesByFloor(quest, area, floorIds, quest?.objects) { it.floorId }
        }

    val currentAreaEvents: ListCell<QuestEventModel> =
        flatMapToList(currentQuest, currentArea, currentFloorIds) { quest, area, floorIds ->
            filterEntitiesByFloor(quest, area, floorIds, quest?.events) { it.floorId }
        }

    val selectedEvent: Cell<QuestEventModel?> = _focusedEvent
    override val selectedEvents: Cell<Set<QuestEventModel>> = _selectedEvents

    /**
     * Atomically updates both event selection cells, enforcing the invariant that
     * [focused] is null or contained in [events].
     */
    private fun updateEventSelection(events: Set<QuestEventModel>, focused: QuestEventModel?) {
        require(focused == null || events.any { it === focused }) {
            "Focused event must be null or contained in the selection set."
        }
        _selectedEvents.value = events
        _focusedEvent.value = focused
    }

    /**
     * Get section and wave info from selected events for NPC filtering.
     * Only NPCs that match both the section and wave of selected events will be shown.
     */
    override val selectedEventsSectionWaves: Cell<Set<Pair<Int, Int>>> = selectedEvents.map { events ->
        events.map { event -> Pair(event.sectionId.value, event.wave.value.id) }.toSet()
    }

    /**
     * Get all sections for the current area variant for goto section functionality
     */
    val currentAreaSections: Cell<List<SectionModel>> =
        map(currentQuest, currentAreaVariant, _sectionsUpdated) { quest, areaVariant, _ ->
            if (quest != null && areaVariant != null) {
                (areaStore.getLoadedSections(areaVariant.episode, areaVariant) ?: emptyList())
                    .sortedBy { it.id }
            } else {
                emptyList()
            }
        }

    /**
     * The entity the user is currently hovering over.
     */
    override val highlightedEntity: Cell<QuestEntityModel<*, *>?> = _highlightedEntity

    /**
     * The entity the user has selected, typically by clicking it.
     */
    override val selectedEntity: Cell<QuestEntityModel<*, *>?> = _selectedEntity

    val questEditingEnabled: Cell<Boolean> = currentQuest.isNotNull() and !runner.running

    val canUndo: Cell<Boolean> = questEditingEnabled and undoManager.canUndo
    val firstUndo: Cell<Command?> = undoManager.firstUndo
    val canRedo: Cell<Boolean> = questEditingEnabled and undoManager.canRedo
    val firstRedo: Cell<Command?> = undoManager.firstRedo

    /**
     * True if there have been changes since the last save.
     */
    val canSaveChanges: Cell<Boolean> = !undoManager.allAtSavePoint

    override val selectedSection: Cell<SectionModel?> = _selectedSection
    val challengeSeedSimulationEnabled: Cell<Boolean> = _challengeSeedSimulationEnabled
    val challengeSeed: Cell<Int> = _challengeSeed
    val challengeLogicalFloors: Cell<List<Int>> =
        map(currentQuest, currentArea, currentAreaVariant, currentFloorMappingRevision) {
                quest, area, areaVariant, _ ->
            when {
                quest == null || area == null -> emptyList()
                quest.floorMappings.isEmpty() -> listOf(area.id)
                areaVariant == null ->
                    quest.getFloorIdsForArea(quest.episode, area.id).orEmpty().sorted()
                else ->
                    quest.getFloorIdsForVariant(
                        areaVariant.episode,
                        area.id,
                        areaVariant.id,
                    ).orEmpty().sorted()
            }
        }
    override val selectedChallengeLogicalFloor: Cell<Int?> =
        map(challengeLogicalFloors, _selectedChallengeLogicalFloor) { floors, selected ->
            selected?.takeIf { it in floors } ?: floors.firstOrNull()
        }
    override val selectedChallengeRoomId: Cell<Int?> = _selectedChallengeRoomId
    override val challengeSeedSimulation: Cell<ChallengeModeSeedSimulation?> =
        currentQuest.flatMap { quest ->
            if (quest == null) {
                cell(null)
            } else {
                map(_challengeSeedSimulationEnabled, _challengeSeed, quest.cmDataRevision) { enabled, seed, _ ->
                    if (enabled) simulateChallengeModeSeed(convertQuestFromModel(quest), seed.toUInt()) else null
                }
            }
        }

    init {
        observeNow(uiStore.currentTool) { tool ->
            if (tool == PwToolType.QuestEditor) {
                makeMainUndoCurrent()
            }
        }

        if (initializeNewQuest) {
            scope.launch { setCurrentQuest(getDefaultQuest(Episode.I)) }
        }

        // Clear entity selection when wave filters change and selected entities are no longer visible.
        observeNow(selectedEventsSectionWaves) { sectionWaves ->
            mutateDeferred {
                clearIncompatibleEntitySelections(sectionWaves)
            }
        }
    }

    fun setChallengeSeedSimulationEnabled(enabled: Boolean) {
        _challengeSeedSimulationEnabled.value = enabled
    }

    fun setChallengeSeed(seed: Int) {
        _challengeSeed.value = seed
    }

    fun setSelectedChallengeLogicalFloor(floorId: Int) {
        if (floorId in challengeLogicalFloors.value) {
            mutate {
                _selectedChallengeLogicalFloor.value = floorId
                _selectedChallengeRoomId.value = null
            }
        }
    }

    fun setSelectedChallengeRoomId(roomId: Int?) {
        _selectedChallengeRoomId.value = roomId
    }

    /**
     * Resolves a floor ID to its area and variant using the quest's floor mappings.
     * Returns null if the quest has no floor mappings or no mapping exists for the given floor ID.
     */
    private fun resolveAreaVariantForFloor(
        quest: QuestModel,
        floorId: Int,
    ): Pair<AreaModel?, AreaVariantModel?>? {
        if (quest.floorMappings.isEmpty()) return null
        val mapping = quest.floorMappings.find { it.floorId == floorId } ?: return null
        val mapEpisode = mapping.mapEpisode ?: quest.episode
        val area = areaStore.getArea(mapEpisode, mapping.mapAreaId)
        val variant = areaStore.getVariant(
            mapEpisode,
            mapping.mapAreaId,
            mapping.mapVariation,
        )
        return Pair(area, variant)
    }

    private fun <T> filterEntitiesByFloor(
        quest: QuestModel?,
        area: AreaModel?,
        floorIds: Set<Int>?,
        entities: ListCell<T>?,
        getAreaId: (T) -> Int,
    ): ListCell<T> {
        if (quest == null || area == null || entities == null) return emptyListCell()

        return if (floorIds != null) {
            entities.filtered { getAreaId(it) in floorIds }
        } else {
            // Fallback for quests without floor mappings (e.g. free roam).
            entities.filtered { getAreaId(it) == area.id }
        }
    }

    override fun dispose() {
        runner.stop()
        super.dispose()
    }

    fun makeMainUndoCurrent() {
        undoManager.setCurrent(mainUndo)
    }

    fun undo() {
        if (!questEditingEnabled.value) return
        undoManager.undo()
    }

    fun redo() {
        if (!questEditingEnabled.value) return
        undoManager.redo()
    }

    suspend fun setCurrentQuest(quest: QuestModel?) {
        require(quest == null || quest.npcPlacementPolicy === npcPlacementPolicy) {
            "The Quest Model must use this Quest Editor's NPC placement policy."
        }
        undoManager.reset()

        runner.stop()

        if (quest == null) {
            mutate {
                _highlightedEntity.value = null
                _selectedEntity.value = null
                updateEventSelection(emptySet(), null)
                _selectedSection.value = null
                _currentArea.value = null
                _currentAreaVariant.value = null
                _currentFloorIds.value = null
                _effectiveScriptEpisode.value = Episode.I
                _currentQuest.value = null
            }
        } else {
            // Resolve before mutating so all cells update atomically.
            val (initialArea, initialVariant, initialFloorId) = resolveInitialAreaAndVariant(quest)
            val initialFloorIds = initialFloorId?.let { setOf(it) }
            val initialRevision = quest.floorMappingRevision.value

            mutate {
                _highlightedEntity.value = null
                _selectedEntity.value = null
                updateEventSelection(emptySet(), null)
                _selectedSection.value = null
                _effectiveScriptEpisode.value = quest.effectiveEpisode
                _currentQuest.value = quest
                _currentArea.value = initialArea
                _currentAreaVariant.value = initialVariant
                _currentFloorIds.value = initialFloorIds
            }

            // Load section data (suspend, must be outside mutate).
            updateQuestEntitySections(quest, initialRevision)

            if (disposed || currentQuest.value !== quest || quest.floorMappingRevision.value != initialRevision) {
                return
            }

            // Ensure all entities have their section initialized.
            quest.npcs.value.forEach(QuestNpcModel::setSectionInitialized)
            quest.objects.value.forEach(QuestObjectModel::setSectionInitialized)

            // Trigger section loading for dropdown immediately after quest is loaded
            _sectionsUpdated.value += 1

            // Auto-select the section closest to the origin only if user hasn't navigated away.
            autoSelectClosestSection(quest, initialFloorIds, initialVariant)
        }
    }

    /**
     * Resolves the initial area, variant, and floor ID to display when a quest is first loaded.
     * For quests with floor mappings, prefers floor 0, then falls back to the first floor with
     * entities, then the first available floor.
     * For standard quests, uses area 0.
     *
     * @return Triple of (area, variant, floorId). floorId is null only for quests without floor mappings.
     */
    private fun resolveInitialAreaAndVariant(quest: QuestModel): Triple<AreaModel?, AreaVariantModel?, Int?> {
        if (quest.floorMappings.isNotEmpty()) {
            // For quests with floor mappings, find the mapping for floor 0 (starting area)
            val floor0Mapping = quest.floorMappings.find { it.floorId == 0 }
            if (floor0Mapping != null) {
                val resolved = resolveAreaVariantForFloor(quest, 0)
                if (resolved != null) {
                    return Triple(resolved.first, resolved.second, 0)
                }
            }

            // Fallback: find the first floor with entities
            for (mapping in quest.floorMappings) {
                val hasEntities =
                    quest.npcs.value.any { it.floorId == mapping.floorId } ||
                    quest.objects.value.any { it.floorId == mapping.floorId }
                if (hasEntities) {
                    val resolved = resolveAreaVariantForFloor(quest, mapping.floorId)
                    if (resolved != null) {
                        return Triple(resolved.first, resolved.second, mapping.floorId)
                    }
                }
            }

            // Last fallback: first available floor
            val first = quest.floorMappings.firstOrNull()
            if (first != null) {
                val resolved = resolveAreaVariantForFloor(quest, first.floorId)
                if (resolved != null) {
                    return Triple(resolved.first, resolved.second, first.floorId)
                }
            }

            return Triple(null, null, null)
        }

        val area = areaStore.getArea(quest.episode, 0)
        return Triple(area, area?.areaVariants?.getOrNull(0), null)
    }

    /**
     * Auto-selects the section closest to the origin for the given area variant if the user
     * is still viewing that exact quest, floor, and variant with no section selected.
     */
    private fun autoSelectClosestSection(
        quest: QuestModel,
        expectedFloorIds: Set<Int>?,
        variant: AreaVariantModel?,
    ) {
        if (variant == null) return
        if (disposed ||
            currentQuest.value !== quest ||
            _currentFloorIds.value != expectedFloorIds ||
            _currentAreaVariant.value !== variant ||
            _selectedSection.value != null
        ) {
            return
        }
        val sections = areaStore.getLoadedSections(variant.episode, variant)
        if (sections != null && sections.isNotEmpty()) {
            _selectedSection.value =
                sections.minByOrNull { it.position.distanceTo(Vector3(0.0, 0.0, 0.0)) }
        }
    }

    suspend fun getDefaultQuest(episode: Episode): QuestModel =
        convertQuestToModel(questLoader.loadDefaultQuest(episode), areaStore::getVariant, npcPlacementPolicy)

    suspend fun getCityQuest(episode: Episode): QuestModel =
        convertQuestToModel(questLoader.loadCityQuest(episode), areaStore::getVariant, npcPlacementPolicy)

    suspend fun getLobbyQuest(variant: Int, objectData: Buffer? = null): QuestModel =
        convertQuestToModel(
            questLoader.loadLobbyQuest(variant, objectData),
            areaStore::getVariant,
            npcPlacementPolicy,
            floorMappingsFromScript = false,
        )

    suspend fun getFreeRoamQuest(
        gameDirHandle: FileSystemDirectoryHandle,
        info: FreeRoamAreaInfo,
        v1: Int = 0,
        v2: Int = 0,
    ): FreeRoamQuestResult {
        val result = questLoader.loadFreeRoamQuest(gameDirHandle, info, v1, v2)
        val questModel = convertQuestToModel(
            result.quest, areaStore::getVariant, npcPlacementPolicy, floorMappingsFromScript = false,
        )
        return FreeRoamQuestResult(questModel, result.binName, result.datFilesByFloor)
    }

    fun <T> setQuestProperty(
        quest: QuestModel,
        setter: (QuestModel, T) -> Unit,
        value: T,
    ) {
        setter(quest, value)
    }

    fun setCurrentArea(area: AreaModel?) {
        val event = selectedEvent.value

        if (area != null && event != null) {
            val eventIsVisible = _currentFloorIds.value
                ?.let { event.floorId in it }
                ?: (event.floorId == area.id)
            if (!eventIsVisible) setSelectedEvent(null)
        }

        _highlightedEntity.value = null
        _selectedEntity.value = null
        _currentArea.value = area
    }

    fun setCurrentAreaVariant(variant: AreaVariantModel?) {
        _currentAreaVariant.value = variant

        // Load sections for the new area variant if quest is loaded
        if (variant != null) {
            currentQuest.value?.let {
                requestSectionLoading(variant.episode, variant)
            }
        }
    }

    fun setCurrentFloorIds(floorIds: Set<Int>?) {
        _currentFloorIds.value = floorIds
    }

    /**
     * Switch to the floor containing [floorId]: sets area, variant, and floorIds.
     */
    private fun switchToFloor(quest: QuestModel, floorId: Int) {
        val resolved = resolveAreaVariantForFloor(quest, floorId)
        if (resolved != null) {
            _currentArea.value = resolved.first
            _currentAreaVariant.value = resolved.second
            _currentFloorIds.value = setOf(floorId)
        } else {
            val ep = if (quest.floorMappingsFromScript) quest.effectiveEpisode else quest.episode
            val area = areaStore.getArea(ep, floorId)
            _currentArea.value = area
            _currentAreaVariant.value = area?.areaVariants?.getOrNull(0)
            _currentFloorIds.value = if (area != null) setOf(floorId) else null
        }
    }

    private fun clearSelectionForCrossFloorSwitch(targetFloorId: Int) {
        _selectedSection.value = null
        _highlightedEntity.value = null
        _selectedEntity.value = null
        val selected = _selectedEvents.value
        if (selected.any { it.floorId != targetFloorId }) {
            updateEventSelection(emptySet(), null)
        }
    }

    fun addEvent(quest: QuestModel, index: Int, event: QuestEventModel) {
        mutate {
            quest.addEvent(index, event)
            setSelectedEvent(event)
        }
    }

    fun removeEvent(quest: QuestModel, event: QuestEventModel) {
        mutate {
            setSelectedEvent(null)
            quest.removeEvent(event)
        }
    }

    fun setSelectedEvent(event: QuestEventModel?) {
        event?.let {
            val wave = event.wave.value

            highlightedEntity.value?.let { entity ->
                if (entity is QuestNpcModel && entity.wave.value != wave) {
                    setHighlightedEntity(null)
                }
            }

            selectedEntity.value?.let { entity ->
                if (entity is QuestNpcModel && entity.wave.value != wave) {
                    setSelectedEntity(null)
                }
            }

            // Cross-area navigation: switch to the event's floor if different.
            val quest = currentQuest.value

            if (quest != null && _currentFloorIds.value?.let { event.floorId !in it } != false) {
                mutate {
                    switchToFloor(quest, event.floorId)
                }
            }
        }

        if (event != null) {
            updateEventSelection(setOf(event), event)
        } else {
            updateEventSelection(emptySet(), null)
        }
    }

    /**
     * Toggle event selection for multi-selection with Ctrl+click
     */
    fun toggleEventSelection(event: QuestEventModel) {
        val oldSelection = _selectedEvents.value
        val wasInSelection = oldSelection.any { it === event }

        val newSet = if (wasInSelection) {
            oldSelection.filterNot { it === event }.toSet()
        } else {
            oldSelection + event
        }

        val newFocused = when {
            newSet.isEmpty() -> null
            !wasInSelection -> event
            _focusedEvent.value === event -> newSet.firstOrNull()
            else -> _focusedEvent.value
        }

        updateEventSelection(newSet, newFocused)
    }

    fun <T> setEventProperty(
        event: QuestEventModel,
        setter: (QuestEventModel, T) -> Unit,
        value: T,
    ) {
        mutate {
            // Preserve multi-selection state when editing properties
            ensureEventInSelection(event)
            setter(event, value)
            _currentQuest.value?.bumpCmRevision()
        }
    }

    fun addEventAction(event: QuestEventModel, action: QuestEventActionModel) {
        mutate {
            // Preserve multi-selection state when adding actions
            ensureEventInSelection(event)
            event.addAction(action)
        }
    }

    fun addEventAction(event: QuestEventModel, index: Int, action: QuestEventActionModel) {
        mutate {
            // Preserve multi-selection state when adding actions
            ensureEventInSelection(event)
            event.addAction(index, action)
        }
    }

    fun removeEventAction(event: QuestEventModel, action: QuestEventActionModel) {
        mutate {
            // Preserve multi-selection state when removing actions
            ensureEventInSelection(event)
            event.removeAction(action)
        }
    }

    /**
     * Ensures the event is in the selection without clearing multi-selection.
     * If multi-selection is active and the event is already selected, just update focused.
     * Otherwise, delegate to setSelectedEvent (replaces the entire selection).
     */
    private fun ensureEventInSelection(event: QuestEventModel) {
        val currentSelection = _selectedEvents.value
        val eventId = event.id.value

        if (currentSelection.any { it.id.value == eventId }) {
            _focusedEvent.value = event
        } else {
            setSelectedEvent(event)
        }
    }

    fun <Action : QuestEventActionModel, T> setEventActionProperty(
        event: QuestEventModel,
        action: Action,
        setter: (Action, T) -> Unit,
        value: T,
    ) {
        mutate {
            // Preserve multi-selection state when editing action properties
            ensureEventInSelection(event)
            setter(action, value)
        }
    }

    override fun setHighlightedEntity(entity: QuestEntityModel<*, *>?) {
        _highlightedEntity.value = entity
    }

    override fun setSelectedEntity(entity: QuestEntityModel<*, *>?) {
        mutate {
            entity?.let {
                currentQuest.value?.let { quest ->
                    switchToFloor(quest, entity.floorId)
                }
            }
            _selectedEntity.value = entity
        }
    }

    /** Events whose floor, section, and wave match a viewport enemy. */
    internal fun matchingEventsForViewportEntity(
        entity: QuestEntityModel<*, *>,
    ): List<QuestEventModel> {
        val enemy = (entity as? QuestNpcModel)?.takeIf { it.type.enemy } ?: return emptyList()
        return currentQuest.value?.events?.value?.filter { it.wave.value == enemy.wave.value }
            ?: emptyList()
    }

    /** Selects a viewport entity and every event matching a clicked enemy's wave. */
    fun selectViewportEntity(entity: QuestEntityModel<*, *>) {
        selectViewportEntity(entity, matchingEventsForViewportEntity(entity))
    }

    internal fun selectViewportEntity(
        entity: QuestEntityModel<*, *>,
        matchingEvents: List<QuestEventModel>,
    ) {
        mutate {
            setSelectedEntity(entity)

            val focusedEvent = matchingEvents.firstOrNull() ?: return@mutate
            setSelectedEvent(focusedEvent)
            updateEventSelection(matchingEvents.toSet(), focusedEvent)
            selectSectionForEvent(focusedEvent)
        }
    }

    fun addEntity(quest: QuestModel, entity: QuestEntityModel<*, *>) {
        mutate {
            quest.addEntity(entity)
            setSelectedEntity(entity)
        }
    }

    fun removeEntity(quest: QuestModel, entity: QuestEntityModel<*, *>) {
        require(entity.isEditable) { "Script-created NPC previews cannot be removed." }
        mutate {
            if (entity == _selectedEntity.value) {
                _selectedEntity.value = null
            }

            quest.removeEntity(entity)
        }
    }

    fun setEntityPosition(entity: QuestEntityModel<*, *>, sectionId: Int?, position: Vector3) {
        require(entity.isEditable) { "Script-created NPC previews cannot be edited." }
        mutate {
            setSelectedEntity(entity)
            sectionId?.let { setEntitySection(entity, it) }
            entity.setPosition(position)
        }
    }

    fun setEntityWorldPosition(entity: QuestEntityModel<*, *>, sectionId: Int?, position: Vector3) {
        require(entity.isEditable) { "Script-created NPC previews cannot be edited." }
        mutate {
            setSelectedEntity(entity)
            sectionId?.let { setEntitySection(entity, it) }
            entity.setWorldPosition(position)
        }
    }

    fun setEntityRotation(entity: QuestEntityModel<*, *>, rotation: Euler) {
        require(entity.isEditable) { "Script-created NPC previews cannot be edited." }
        mutate {
            setSelectedEntity(entity)
            entity.setRotation(rotation)
        }
    }

    fun setEntityWorldRotation(entity: QuestEntityModel<*, *>, rotation: Euler) {
        require(entity.isEditable) { "Script-created NPC previews cannot be edited." }
        mutate {
            setSelectedEntity(entity)
            entity.setWorldRotation(rotation)
        }
    }

    fun <Entity : QuestEntityModel<*, *>, T> setEntityProperty(
        entity: Entity,
        setter: (Entity, T) -> Unit,
        value: T,
    ) {
        require(entity.isEditable) { "Script-created NPC previews cannot be edited." }
        mutate {
            setSelectedEntity(entity)
            setter(entity, value)
        }
    }

    fun setEntityProp(entity: QuestEntityModel<*, *>, prop: QuestEntityPropModel, value: Any) {
        require(entity.isEditable) { "Script-created NPC previews cannot be edited." }
        mutate {
            setSelectedEntity(entity)
            prop.setValue(value)
        }
    }

    /**
     * Synchronously commits updated script-derived floor mappings and effective episode,
     * resolves the final active floor (falling back to Floor 0 when the previous floor does not
     * exist in the new episode, honoring any explicit mapping on Floor 0), cleans up stale
     * cross-floor selections and detached entity sections, and triggers section loading for the
     * active variant.
     *
     * Returns the new `floorMappingRevision` when an async entity-section reload is needed, or
     * `null` when mappings and effective episode were unchanged.
     */
    fun commitFloorMappings(
        quest: QuestModel,
        floorMappings: List<FloorMapping>,
        targetFloorId: Int? = null,
        effectiveEpisode: Episode? = null,
    ): Int? {
        if (disposed || currentQuest.value !== quest || !quest.floorMappingsFromScript) return null

        val nextEpisode = effectiveEpisode ?: quest.effectiveEpisode
        val previousFloorId = _currentFloorIds.value?.singleOrNull() ?: _currentArea.value?.id
        val previousVariant = _currentAreaVariant.value
        val requestedFloorId = targetFloorId ?: previousFloorId
        val resolvedFloorId = when {
            requestedFloorId == null -> null
            floorMappings.any { it.floorId == requestedFloorId } -> requestedFloorId
            areaStore.getArea(nextEpisode, requestedFloorId) != null -> requestedFloorId
            else -> 0
        }
        val floorActuallyChanged = resolvedFloorId != null && resolvedFloorId != previousFloorId
        val unchanged = quest.floorMappings == floorMappings && quest.effectiveEpisode == nextEpisode

        if (unchanged) {
            if (floorActuallyChanged) {
                mutate {
                    clearSelectionForCrossFloorSwitch(resolvedFloorId)
                    switchToFloor(quest, resolvedFloorId)
                }
                val newVariant = _currentAreaVariant.value
                if (newVariant != null && newVariant !== previousVariant) {
                    requestSectionLoading(newVariant.episode, newVariant)
                }
            }
            return null
        }

        val previousVariantsByFloor = buildMap {
            if (quest.floorMappings.isNotEmpty()) {
                putAll(quest.floorToVariantMap)
            } else {
                for (v in quest.areaVariants.value) {
                    put(v.area.id, v)
                }
            }
        }

        var revision = 0
        mutate {
            _effectiveScriptEpisode.value = nextEpisode
            quest.setFloorMappings(floorMappings, nextEpisode)
            revision = quest.floorMappingRevision.value
            _selectedSection.value = null
            if (floorActuallyChanged) {
                clearSelectionForCrossFloorSwitch(resolvedFloorId)
            }
            if (resolvedFloorId != null) {
                switchToFloor(quest, resolvedFloorId)
            }
            detachStaleEntitySections(quest, previousVariantsByFloor)
        }

        val newVariant = _currentAreaVariant.value
        if (newVariant != null) {
            requestSectionLoading(newVariant.episode, newVariant)
        }
        return revision
    }

    suspend fun reloadEntitySections(quest: QuestModel, expectedRevision: Int) {
        updateQuestEntitySections(quest, expectedRevision)
    }

    suspend fun setFloorMappings(
        quest: QuestModel,
        floorMappings: List<FloorMapping>,
        targetFloorId: Int? = null,
        effectiveEpisode: Episode? = null,
    ) {
        val revision = commitFloorMappings(quest, floorMappings, targetFloorId, effectiveEpisode) ?: return
        reloadEntitySections(quest, revision)
    }

    private fun detachStaleEntitySections(
        quest: QuestModel,
        previousVariantsByFloor: Map<Int, AreaVariantModel>,
    ) {
        val currentVariantsByFloor = buildMap {
            if (quest.floorMappings.isNotEmpty()) {
                putAll(quest.floorToVariantMap)
            } else {
                for (v in quest.areaVariants.value) {
                    put(v.area.id, v)
                }
            }
        }
        fun detachIfChanged(entity: QuestEntityModel<*, *>) {
            val prev = previousVariantsByFloor[entity.floorId]
            val next = currentVariantsByFloor[entity.floorId]
            if (next == null || prev !== next) {
                entity.detachSectionKeepingRelativeTransform()
            }
        }
        quest.npcs.value.forEach(::detachIfChanged)
        quest.objects.value.forEach(::detachIfChanged)
    }

    fun setEntitySectionId(entity: QuestEntityModel<*, *>, sectionId: Int) {
        require(entity.isEditable) { "Script-created NPC previews cannot be edited." }
        mutate {
            setSelectedEntity(entity)
            entity.setSectionId(sectionId)
        }
    }

    fun setEntitySection(entity: QuestEntityModel<*, *>, section: SectionModel) {
        require(entity.isEditable) { "Script-created NPC previews cannot be edited." }
        mutate {
            setSelectedEntity(entity)
            entity.setSection(section)
        }
    }

    /**
     * Sets [QuestEntityModel.sectionId] and [QuestEntityModel.section] if there's a section with
     * [sectionId] as ID.
     */
    private fun setEntitySection(entity: QuestEntityModel<*, *>, sectionId: Int) {
        currentQuest.value?.let { quest ->
            val variant = quest.floorToVariantMap[entity.floorId]
            val variants = if (variant != null) {
                listOf(variant)
            } else {
                quest.areaVariants.value.filter { it.area.id == entity.floorId }
            }

            for (areaVariant in variants) {
                val section = areaStore.getLoadedSections(areaVariant.episode, areaVariant)
                    ?.find { it.id == sectionId }

                if (section != null) {
                    entity.setSection(section)
                    return@let
                }
            }

            // If section not found in any variant, just set the ID
            entity.setSectionId(sectionId)
        }
    }

    fun executeAction(command: Command) {
        pushAction(command)
        command.execute()
    }

    fun pushAction(command: Command) {
        require(questEditingEnabled.value) {
            val reason = when {
                currentQuest.value == null -> " (no current quest)"
                runner.running.value -> " (QuestRunner is running)"
                else -> ""
            }
            "Quest editing is disabled at the moment$reason."
        }
        mainUndo.push(command)
    }

    /**
     * Selects the section associated with the given event, or clears the section selection
     * if the event is null or no matching section is found.
     */
    fun selectSectionForEvent(event: QuestEventModel?) {
        if (event != null) {
            val quest = currentQuest.value
            val variant = currentAreaVariant.value
            if (quest != null && variant != null) {
                // Use getLoadedSections to get sections that have been loaded
                // This ensures we get the correct section even if it was loaded asynchronously
                val sections = currentAreaSections.value
                val eventSection = sections.find { it.id == event.sectionId.value }
                _selectedSection.value = eventSection
            } else {
                _selectedSection.value = null
            }
        } else {
            _selectedSection.value = null
        }
    }

    fun setSelectedSection(section: SectionModel?) {
        _selectedSection.value = section
    }

    fun captureSavePoint(): () -> Unit = undoManager.captureSavePoint()

    /**
     * Request async loading of sections for a specific area variant
     */
    fun requestSectionLoading(episode: Episode, areaVariant: AreaVariantModel) {
        val quest = currentQuest.value ?: return
        val expectedFloorIds = _currentFloorIds.value
        val token = ++sectionLoadToken
        scope.launch {
            try {
                val sections = areaStore.getSections(episode, areaVariant)
                if (disposed ||
                    currentQuest.value !== quest ||
                    _currentAreaVariant.value !== areaVariant ||
                    _currentFloorIds.value != expectedFloorIds ||
                    sectionLoadToken != token
                ) {
                    return@launch
                }
                // Batch both cell updates atomically so observers see a consistent state.
                mutate {
                    _sectionsUpdated.value += 1
                    if (_selectedSection.value == null && sections.isNotEmpty()) {
                        val closest = sections.minByOrNull {
                            it.position.distanceTo(Vector3(0.0, 0.0, 0.0))
                        }
                        _selectedSection.value = closest
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Error loading sections for area variant ${areaVariant.id}" }
            }
        }
    }

    /**
     * True if the event exists in the current area and quest editing is enabled.
     */
    fun canGoToEvent(eventId: Cell<Int>): Cell<Boolean> =
        map(questEditingEnabled, currentAreaEvents, eventId) { en, evts, id ->
            en && evts.any { it.id.value == id }
        }

    fun goToEvent(eventId: Int) {
        currentAreaEvents.value.find { it.id.value == eventId }?.let { event ->
            setSelectedEvent(event)
        }
    }

    /**
     * Navigate camera to a specific section by section ID.
     */
    fun goToSection(sectionId: Int) {
        currentAreaVariant.value?.let { areaVariant ->
            val sections = areaStore.getLoadedSections(areaVariant.episode, areaVariant)
            sections?.find { it.id == sectionId }?.let { section ->
                // Set target camera position without using observers to avoid circular dependencies
                viewportStore.setTargetCameraPosition(section.position.clone())
            }
        }
    }

    /**
     * Navigate camera to the section of a specific event.
     */
    fun goToEventSection(event: QuestEventModel) {
        goToSection(event.sectionId.value)
    }

    /**
     * Navigate camera target to an arbitrary world-space position. Also leaves a persistent
     * indicator at the destination so the user can see where they were sent.
     */
    fun goToWorldPosition(x: Double, y: Double, z: Double) {
        val pos = Vector3(x, y, z)
        viewportStore.setTargetCameraPosition(pos.clone())
        viewportStore.setGotoIndicatorPosition(pos)
    }

    /**
     * Clear entity selections that are incompatible with the current wave filters.
     * Called asynchronously to avoid circular dependencies.
     */
    private fun clearIncompatibleEntitySelections(sectionWaves: Set<Pair<Int, Int>>) {
        if (sectionWaves.isEmpty()) return

        if (!isEntityCompatibleWithWaveFilters(_selectedEntity.value, sectionWaves)) {
            _selectedEntity.value = null
        }
        if (!isEntityCompatibleWithWaveFilters(_highlightedEntity.value, sectionWaves)) {
            _highlightedEntity.value = null
        }
    }

    private fun isEntityCompatibleWithWaveFilters(
        entity: QuestEntityModel<*, *>?,
        sectionWaves: Set<Pair<Int, Int>>,
    ): Boolean {
        if (entity == null) return true
        val waveId = (entity as? QuestNpcModel)?.wave?.value?.id ?: return true
        return Pair(entity.sectionId.value, waveId) in sectionWaves
    }

    private suspend fun updateQuestEntitySections(
        quest: QuestModel,
        expectedRevision: Int = quest.floorMappingRevision.value,
    ) {
        val variantsSnapshot = quest.areaVariants.value.toList()
        for (variant in variantsSnapshot) {
            val sections = areaStore.getSections(variant.episode, variant)
            if (disposed ||
                currentQuest.value !== quest ||
                quest.floorMappingRevision.value != expectedRevision
            ) {
                return
            }
            variant.setSections(sections)
            setSectionOnQuestEntities(quest, quest.npcs.value, variant, sections)
            setSectionOnQuestEntities(quest, quest.objects.value, variant, sections)
        }
    }

    private fun setSectionOnQuestEntities(
        quest: QuestModel,
        entities: List<QuestEntityModel<*, *>>,
        variant: AreaVariantModel,
        sections: List<SectionModel>,
    ) {

        entities.forEach { entity ->
            if (quest.entityBelongsToMap(
                    entityFloorId = entity.floorId,
                    mapEpisode = variant.episode,
                    mapAreaId = variant.area.id,
                    mapVariation = variant.id,
                )
            ) {
                val section = sections.find { it.id == entity.sectionId.value }

                if (section == null) {
                    logger.warn { "Section ${entity.sectionId.value} not found." }
                    entity.detachSectionKeepingRelativeTransform()
                } else {
                    entity.setSection(section, keepRelativeTransform = true)
                }
            }
        }
    }

}
