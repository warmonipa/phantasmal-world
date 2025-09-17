package world.phantasmal.web.questEditor.stores

import kotlinx.browser.window
import kotlinx.coroutines.launch
import mu.KotlinLogging
import world.phantasmal.cell.*
import world.phantasmal.cell.list.ListCell
import world.phantasmal.cell.list.emptyListCell
import world.phantasmal.cell.list.filtered
import world.phantasmal.cell.list.flatMapToList
import world.phantasmal.psolib.Episode
import world.phantasmal.web.core.PwToolType
import world.phantasmal.web.core.commands.Command
import world.phantasmal.web.core.stores.UiStore
import world.phantasmal.web.core.undo.UndoManager
import world.phantasmal.web.core.undo.UndoStack
import world.phantasmal.web.externals.three.Euler
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.questEditor.QuestRunner
import world.phantasmal.web.questEditor.loading.QuestLoader
import world.phantasmal.web.questEditor.models.*
import world.phantasmal.webui.stores.Store

private val logger = KotlinLogging.logger {}

class QuestEditorStore(
    private val questLoader: QuestLoader,
    uiStore: UiStore,
    private val areaStore: AreaStore,
    private val undoManager: UndoManager,
    initializeNewQuest: Boolean,
) : Store() {
    private val _devMode = mutableCell(false)
    private val _showRoomIds = mutableCell(false) // Room ID display toggle
    private val _spawnMonstersOnGround = mutableCell(false) // Monster ground spawn toggle
    private val _currentQuest = mutableCell<QuestModel?>(null)
    private val _currentArea = mutableCell<AreaModel?>(null)
    private val _selectedEvent = mutableCell<QuestEventModel?>(null)
    private val _selectedEvents = mutableCell<Set<QuestEventModel>>(emptySet())
    private val _highlightedEntity = mutableCell<QuestEntityModel<*, *>?>(null)
    private val _selectedEntity = mutableCell<QuestEntityModel<*, *>?>(null)
    private val mainUndo = UndoStack(undoManager)
    private val _showCollisionGeometry = mutableCell(true)
    private val _mouseWorldPosition = mutableCell<Vector3?>(null)
    private val _targetCameraPosition = mutableCell<Vector3?>(null)
    private val _sectionsUpdated = mutableCell(0) // Trigger to update sections
    
    // Queue to batch cell updates and avoid circular dependencies
    private var updateQueue: (() -> Unit)? = null
    private var isProcessingUpdates = false

    val devMode: Cell<Boolean> = _devMode

    private val runner = QuestRunner()
    val currentQuest: Cell<QuestModel?> = _currentQuest
    val currentArea: Cell<AreaModel?> = _currentArea
    val showRoomIds: Cell<Boolean> = _showRoomIds
    val spawnMonstersOnGround: Cell<Boolean> = _spawnMonstersOnGround
    val currentAreaVariant: Cell<AreaVariantModel?> =
        map(currentArea, currentQuest.flatMapNull { it?.areaVariants }) { area, variants ->
            if (area != null && variants != null) {
                variants.find { it.area.id == area.id } ?: area.areaVariants.first()
            } else {
                null
            }
        }

    val currentAreaEvents: ListCell<QuestEventModel> =
        flatMapToList(currentQuest, currentArea) { quest, area ->
            if (quest != null && area != null) {
                quest.events.filtered { it.areaId == area.id }
            } else {
                emptyListCell()
            }
        }

    val selectedEvent: Cell<QuestEventModel?> = _selectedEvent
    val selectedEvents: Cell<Set<QuestEventModel>> = _selectedEvents

    /**
     * Get all waves from selected events for NPC filtering
     */
    val selectedEventsWaves: Cell<Set<WaveModel>> = selectedEvents.map { events ->
        events.map { it.wave.value }.toSet()
    }

    /**
     * Get all sections for the current area variant for goto section functionality
     */
    val currentAreaSections: Cell<List<SectionModel>> = 
        map(currentQuest, currentAreaVariant, _sectionsUpdated) { quest, areaVariant, _ ->
            if (quest != null && areaVariant != null) {
                // Simply return already loaded sections, or empty list if not loaded
                areaStore.getLoadedSections(quest.episode, areaVariant) ?: emptyList()
            } else {
                emptyList()
            }
        }

    /**
     * The entity the user is currently hovering over.
     */
    val highlightedEntity: Cell<QuestEntityModel<*, *>?> = _highlightedEntity

    /**
     * The entity the user has selected, typically by clicking it.
     */
    val selectedEntity: Cell<QuestEntityModel<*, *>?> = _selectedEntity

    val questEditingEnabled: Cell<Boolean> = currentQuest.isNotNull() and !runner.running

    val canUndo: Cell<Boolean> = questEditingEnabled and undoManager.canUndo
    val firstUndo: Cell<Command?> = undoManager.firstUndo
    val canRedo: Cell<Boolean> = questEditingEnabled and undoManager.canRedo
    val firstRedo: Cell<Command?> = undoManager.firstRedo

    /**
     * True if there have been changes since the last save.
     */
    val canSaveChanges: Cell<Boolean> = !undoManager.allAtSavePoint

    val showCollisionGeometry: Cell<Boolean> = _showCollisionGeometry
    val mouseWorldPosition: Cell<Vector3?> = _mouseWorldPosition
    val targetCameraPosition: Cell<Vector3?> = _targetCameraPosition

    init {
        addDisposables(
            uiStore.onGlobalKeyDown(PwToolType.QuestEditor, "Ctrl-Alt-Shift-D") {
                _devMode.value = !_devMode.value

                logger.info { "Dev mode ${if (devMode.value) "on" else "off"}." }
            },
        )

        observeNow(uiStore.currentTool) { tool ->
            if (tool == PwToolType.QuestEditor) {
                makeMainUndoCurrent()
            }
        }

        // TODO: Re-enable section loading observer after fixing parsing issues
        // Observe area variant changes and update sections
        /*
        observeNow(currentQuest, currentAreaVariant) { quest, areaVariant ->
            if (quest != null && areaVariant != null) {
                console.log("Loading sections for quest episode ${quest.episode} and area variant ${areaVariant.id}")
                // Try to get already loaded sections first
                val loadedSections = areaStore.getLoadedSections(quest.episode, areaVariant)
                if (loadedSections != null) {
                    console.log("Found ${loadedSections.size} loaded sections")
                    _currentAreaSections.value = loadedSections
                } else {
                    console.log("Sections not loaded, triggering async load")
                    // Clear sections first
                    _currentAreaSections.value = emptyList()
                    // Trigger async loading
                    scope.launch {
                        try {
                            val sections = areaStore.getSections(quest.episode, areaVariant)
                            console.log("Loaded ${sections.size} sections asynchronously")
                            _currentAreaSections.value = sections
                        } catch (e: Exception) {
                            console.log("Error loading sections: ${e.message}")
                        }
                    }
                }
            } else {
                _currentAreaSections.value = emptyList()
            }
        }
        */

        if (initializeNewQuest) {
            scope.launch { setCurrentQuest(getDefaultQuest(Episode.I)) }
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
        undoManager.undo()
    }

    fun redo() {
        undoManager.redo()
    }

    suspend fun setCurrentQuest(quest: QuestModel?) {
        undoManager.reset()

        runner.stop()

        _highlightedEntity.value = null
        _selectedEntity.value = null
        _selectedEvent.value = null

        if (quest == null) {
            _currentArea.value = null
            _currentQuest.value = null
        } else {
            _currentArea.value = areaStore.getArea(quest.episode, 0)
            _currentQuest.value = quest

            // Load section data.
            updateQuestEntitySections(quest)

            // Ensure all entities have their section initialized.
            quest.npcs.value.forEach(QuestNpcModel::setSectionInitialized)
            quest.objects.value.forEach(QuestObjectModel::setSectionInitialized)
            
            // Trigger section loading for dropdown immediately after quest is loaded
            console.log("Quest loaded, triggering section loading for goto dropdown")
            _sectionsUpdated.value = _sectionsUpdated.value + 1
        }
    }

    suspend fun getDefaultQuest(episode: Episode): QuestModel =
        convertQuestToModel(questLoader.loadDefaultQuest(episode), areaStore::getVariant)

    fun <T> setQuestProperty(
        quest: QuestModel,
        setter: (QuestModel, T) -> Unit,
        value: T,
    ) {
        setter(quest, value)
    }

    fun setCurrentArea(area: AreaModel?) {
        val event = selectedEvent.value

        if (area != null && event != null && area.id != event.areaId) {
            setSelectedEvent(null)
        }

        _highlightedEntity.value = null
        _selectedEntity.value = null
        _currentArea.value = area
        
        // Trigger section list update when area changes
        console.log("Area changed to ${area?.name}, triggering section list update")
        
        // Load sections for the new area if quest is loaded
        // Use setTimeout to ensure currentAreaVariant has been updated first
        window.setTimeout({
            currentQuest.value?.let { quest ->
                currentAreaVariant.value?.let { areaVariant ->
                    console.log("Requesting section loading for new area variant ${areaVariant.id}")
                    requestSectionLoading(quest.episode, areaVariant)
                }
            }
        }, 50)
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
        console.log("setSelectedEvent called with event: ${event?.id?.value}")
        
        // Simple implementation - just set the selected event
        _selectedEvent.value = event
        
        // Update multi-selection to match single selection
        if (event != null) {
            _selectedEvents.value = setOf(event)
        } else {
            _selectedEvents.value = emptySet()
        }
    }

    /**
     * Toggle event selection for multi-selection with Ctrl+click
     */
    fun toggleEventSelection(event: QuestEventModel) {
        console.log("toggleEventSelection called for event: ${event.id.value}")
        val currentSelection = _selectedEvents.value.toMutableSet()
        
        if (event in currentSelection) {
            console.log("Removing event ${event.id.value} from multi-selection")
            currentSelection.remove(event)
        } else {
            console.log("Adding event ${event.id.value} to multi-selection")
            currentSelection.add(event)
        }
        
        _selectedEvents.value = currentSelection
        console.log("Multi-selection now contains: ${currentSelection.map { it.id.value }}")
        
        // Update single selection to the last selected event (or null if none)
        _selectedEvent.value = if (currentSelection.isEmpty()) null else event
    }

    /**
     * Check if an event is currently selected in multi-selection
     */
    fun isEventSelected(event: QuestEventModel): Boolean = 
        event in _selectedEvents.value

    /**
     * Clear all event selections
     */
    fun clearEventSelection() {
        _selectedEvents.value = emptySet()
        _selectedEvent.value = null
    }

    fun <T> setEventProperty(
        event: QuestEventModel,
        setter: (QuestEventModel, T) -> Unit,
        value: T,
    ) {
        mutate {
            setSelectedEvent(event)
            setter(event, value)
        }
    }

    fun addEventAction(event: QuestEventModel, action: QuestEventActionModel) {
        mutate {
            setSelectedEvent(event)
            event.addAction(action)
        }
    }

    fun addEventAction(event: QuestEventModel, index: Int, action: QuestEventActionModel) {
        mutate {
            setSelectedEvent(event)
            event.addAction(index, action)
        }
    }

    fun removeEventAction(event: QuestEventModel, action: QuestEventActionModel) {
        mutate {
            setSelectedEvent(event)
            event.removeAction(action)
        }
    }

    fun <Action : QuestEventActionModel, T> setEventActionProperty(
        event: QuestEventModel,
        action: Action,
        setter: (Action, T) -> Unit,
        value: T,
    ) {
        mutate {
            setSelectedEvent(event)
            setter(action, value)
        }
    }

    fun setHighlightedEntity(entity: QuestEntityModel<*, *>?) {
        _highlightedEntity.value = entity
    }

    fun setSelectedEntity(entity: QuestEntityModel<*, *>?) {
        entity?.let {
            currentQuest.value?.let { quest ->
                _currentArea.value = areaStore.getArea(quest.episode, entity.areaId)
            }
        }

        _selectedEntity.value = entity
    }

    fun addEntity(quest: QuestModel, entity: QuestEntityModel<*, *>) {
        mutate {
            quest.addEntity(entity)
            setSelectedEntity(entity)
        }
    }

    fun removeEntity(quest: QuestModel, entity: QuestEntityModel<*, *>) {
        mutate {
            if (entity == _selectedEntity.value) {
                _selectedEntity.value = null
            }

            quest.removeEntity(entity)
        }
    }

    fun setEntityPosition(entity: QuestEntityModel<*, *>, sectionId: Int?, position: Vector3) {
        mutate {
            setSelectedEntity(entity)
            sectionId?.let { setEntitySection(entity, it) }
            entity.setPosition(position)
        }
    }

    fun setEntityWorldPosition(entity: QuestEntityModel<*, *>, sectionId: Int?, position: Vector3) {
        mutate {
            setSelectedEntity(entity)
            sectionId?.let { setEntitySection(entity, it) }
            entity.setWorldPosition(position)
        }
    }

    fun setEntityRotation(entity: QuestEntityModel<*, *>, rotation: Euler) {
        mutate {
            setSelectedEntity(entity)
            entity.setRotation(rotation)
        }
    }

    fun setEntityWorldRotation(entity: QuestEntityModel<*, *>, rotation: Euler) {
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
        mutate {
            setSelectedEntity(entity)
            setter(entity, value)
        }
    }

    fun setEntityProp(entity: QuestEntityModel<*, *>, prop: QuestEntityPropModel, value: Any) {
        mutate {
            setSelectedEntity(entity)
            prop.setValue(value)
        }
    }

    suspend fun setMapDesignations(mapDesignations: Map<Int, Int>) {
        currentQuest.value?.let { quest ->
            quest.setMapDesignations(mapDesignations)
            updateQuestEntitySections(quest)
        }
    }

    fun setEntitySectionId(entity: QuestEntityModel<*, *>, sectionId: Int) {
        mutate {
            setSelectedEntity(entity)
            entity.setSectionId(sectionId)
        }
    }

    fun setEntitySection(entity: QuestEntityModel<*, *>, section: SectionModel) {
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
            val variant = quest.areaVariants.value.find { it.area.id == entity.areaId }

            variant?.let {
                val section = areaStore.getLoadedSections(quest.episode, variant)
                    ?.find { it.id == sectionId }

                if (section == null) {
                    entity.setSectionId(sectionId)
                } else {
                    entity.setSection(section)
                }
            }
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

    fun setShowCollisionGeometry(show: Boolean) {
        _showCollisionGeometry.value = show
    }

    fun setShowRoomIds(show: Boolean) {
        _showRoomIds.value = show
    }

    fun setSpawnMonstersOnGround(spawn: Boolean) {
        _spawnMonstersOnGround.value = spawn
        QuestNpcModel.setSpawnOnGround(spawn)
    }

    fun setMouseWorldPosition(position: Vector3?) {
        _mouseWorldPosition.value = position
    }

    fun setTargetCameraPosition(position: Vector3?) {
        _targetCameraPosition.value = position
    }

    fun questSaved() {
        undoManager.savePoint()
    }

    /**
     * Request async loading of sections for a specific area variant
     */
    fun requestSectionLoading(episode: Episode, areaVariant: AreaVariantModel) {
        scope.launch {
            try {
                console.log("Loading sections for episode $episode and area variant ${areaVariant.id}")
                areaStore.getSections(episode, areaVariant)
                console.log("Section loading completed, triggering UI update")
                // Trigger UI update by incrementing the counter
                _sectionsUpdated.value = _sectionsUpdated.value + 1
            } catch (e: Exception) {
                console.log("Error loading sections: ${e.message}")
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
        console.log("goToSection called for section $sectionId")
        currentAreaVariant.value?.let { areaVariant ->
            currentQuest.value?.let { quest ->
                val sections = areaStore.getLoadedSections(quest.episode, areaVariant)
                sections?.find { it.id == sectionId }?.let { section ->
                    console.log("Found section $sectionId at position: ${section.position.x}, ${section.position.y}, ${section.position.z}")
                    // Set target camera position without using observers to avoid circular dependencies
                    _targetCameraPosition.value = section.position.clone()
                } ?: run {
                    console.log("Section $sectionId not found")
                }
            }
        }
    }

    /**
     * Navigate camera to the section of a specific event.
     */
    fun goToEventSection(event: QuestEventModel) {
        goToSection(event.sectionId.value)
    }

    private suspend fun updateQuestEntitySections(quest: QuestModel) {
        quest.areaVariants.value.forEach { variant ->
            val sections = areaStore.getSections(quest.episode, variant)
            variant.setSections(sections)
            setSectionOnQuestEntities(quest.npcs.value, variant, sections)
            setSectionOnQuestEntities(quest.objects.value, variant, sections)
        }
    }

    private fun setSectionOnQuestEntities(
        entities: List<QuestEntityModel<*, *>>,
        variant: AreaVariantModel,
        sections: List<SectionModel>,
    ) {
        entities.forEach { entity ->
            if (entity.areaId == variant.area.id) {
                val section = sections.find { it.id == entity.sectionId.value }

                if (section == null) {
                    logger.warn { "Section ${entity.sectionId.value} not found." }
                    entity.setSectionInitialized()
                } else {
                    entity.setSection(section, keepRelativeTransform = true)
                }
            }
        }
    }
}
