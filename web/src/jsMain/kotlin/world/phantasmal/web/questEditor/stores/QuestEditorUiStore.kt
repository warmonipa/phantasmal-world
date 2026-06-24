package world.phantasmal.web.questEditor.stores

import mu.KotlinLogging
import world.phantasmal.cell.Cell
import world.phantasmal.cell.mutableCell
import world.phantasmal.web.core.PwToolType
import world.phantasmal.web.core.stores.UiStore
import world.phantasmal.web.questEditor.models.LobbyEventFilter
import world.phantasmal.web.questEditor.models.NpcDisplaySettings
import world.phantasmal.webui.stores.Store

private val logger = KotlinLogging.logger {}

class QuestEditorUiStore(
    uiStore: UiStore,
) : Store() {
    private val _devMode = mutableCell(false)
    private val _showCollisionGeometry = mutableCell(true)
    private val _showSectionIds = mutableCell(false)
    private val _showDoorIds = mutableCell(false)
    private val _spawnMonstersOnGround = mutableCell(false)
    private val _omnispawn = mutableCell(false)
    private val _showOriginPoint = mutableCell(false)
    private val _showScriptParticles = mutableCell(false)
    private val _ultimate = mutableCell(true)
    private val _selectedLobbyEvent = mutableCell<LobbyEventFilter>(LobbyEventFilter.None)

    val devMode: Cell<Boolean> = _devMode
    val showCollisionGeometry: Cell<Boolean> = _showCollisionGeometry
    val showSectionIds: Cell<Boolean> = _showSectionIds
    val showDoorIds: Cell<Boolean> = _showDoorIds
    val spawnMonstersOnGround: Cell<Boolean> = _spawnMonstersOnGround
    val omnispawn: Cell<Boolean> = _omnispawn
    val showOriginPoint: Cell<Boolean> = _showOriginPoint
    val showScriptParticles: Cell<Boolean> = _showScriptParticles

    /**
     * Whether to render the Ultimate-difficulty visual skins of areas and entities.
     * Independent of the free-roam layout difficulty; this only affects appearance.
     */
    val ultimate: Cell<Boolean> = _ultimate
    val selectedLobbyEvent: Cell<LobbyEventFilter> = _selectedLobbyEvent

    init {
        addDisposables(
            uiStore.onGlobalKeyDown(PwToolType.QuestEditor, "Ctrl-Alt-Shift-D") {
                _devMode.value = !_devMode.value

                logger.info { "Dev mode ${if (devMode.value) "on" else "off"}." }
            },
        )
    }

    fun setShowCollisionGeometry(show: Boolean) {
        _showCollisionGeometry.value = show
    }

    fun setShowSectionIds(show: Boolean) {
        _showSectionIds.value = show
    }

    fun setShowDoorIds(show: Boolean) {
        _showDoorIds.value = show
    }

    fun setSpawnMonstersOnGround(spawn: Boolean) {
        _spawnMonstersOnGround.value = spawn
        NpcDisplaySettings.setSpawnOnGround(spawn)
    }

    fun setOmnispawn(omnispawn: Boolean) {
        _omnispawn.value = omnispawn
    }

    fun setShowOriginPoint(show: Boolean) {
        _showOriginPoint.value = show
    }

    fun setShowScriptParticles(show: Boolean) {
        _showScriptParticles.value = show
    }

    fun setUltimate(ultimate: Boolean) {
        _ultimate.value = ultimate
    }

    fun setSelectedLobbyEvent(filter: LobbyEventFilter) {
        _selectedLobbyEvent.value = filter
    }
}
