package world.phantasmal.web.questEditor.stores

import mu.KotlinLogging
import world.phantasmal.cell.Cell
import world.phantasmal.cell.mutableCell
import world.phantasmal.web.core.PwToolType
import world.phantasmal.web.core.stores.UiStore
import world.phantasmal.web.questEditor.models.LobbyEventFilter
import world.phantasmal.web.questEditor.models.NpcPlacementPolicy
import world.phantasmal.webui.stores.Store

private val logger = KotlinLogging.logger {}

enum class WalkthroughPlayer(val clientId: Int?, val label: String, val color: Int) {
    Off(null, "Off", 0),
    Red(0, "Red", 0xF04444),
    Green(1, "Green", 0x33C96B),
    Yellow(2, "Yellow", 0xF2C94C),
    Blue(3, "Blue", 0x3D7DFF),
}

class QuestEditorUiStore(
    uiStore: UiStore,
    private val npcPlacementPolicy: NpcPlacementPolicy,
) : Store() {
    private val _devMode = mutableCell(false)
    private val _showCollisionGeometry = mutableCell(true)
    private val _showSectionIds = mutableCell(false)
    private val _showDoorIds = mutableCell(false)
    private val _showEntityDirections = mutableCell(false)
    private val _omnispawn = mutableCell(false)
    private val _showOriginPoint = mutableCell(false)
    private val _showQuestParticles = mutableCell(true)
    private val _showFogBoundaries = mutableCell(false)
    private val _ultimate = mutableCell(true)
    private val _selectedLobbyEvent = mutableCell<LobbyEventFilter>(LobbyEventFilter.None)
    private val _walkthroughPlayer = mutableCell(WalkthroughPlayer.Off)

    val devMode: Cell<Boolean> = _devMode
    val showCollisionGeometry: Cell<Boolean> = _showCollisionGeometry
    val showSectionIds: Cell<Boolean> = _showSectionIds
    val showDoorIds: Cell<Boolean> = _showDoorIds
    val showEntityDirections: Cell<Boolean> = _showEntityDirections
    val spawnMonstersOnGround: Cell<Boolean> = npcPlacementPolicy.spawnOnGround
    val omnispawn: Cell<Boolean> = _omnispawn
    val showOriginPoint: Cell<Boolean> = _showOriginPoint
    val showQuestParticles: Cell<Boolean> = _showQuestParticles
    val showFogBoundaries: Cell<Boolean> = _showFogBoundaries

    /**
     * Whether to render the Ultimate-difficulty visual skins of areas and entities.
     * Independent of the free-roam layout difficulty; this only affects appearance.
     */
    val ultimate: Cell<Boolean> = _ultimate
    val selectedLobbyEvent: Cell<LobbyEventFilter> = _selectedLobbyEvent
    val walkthroughPlayer: Cell<WalkthroughPlayer> = _walkthroughPlayer

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

    fun setShowEntityDirections(show: Boolean) {
        _showEntityDirections.value = show
    }

    fun setSpawnMonstersOnGround(spawn: Boolean) {
        npcPlacementPolicy.setSpawnOnGround(spawn)
    }

    fun setOmnispawn(omnispawn: Boolean) {
        _omnispawn.value = omnispawn
    }

    fun setShowOriginPoint(show: Boolean) {
        _showOriginPoint.value = show
    }

    fun setShowQuestParticles(show: Boolean) {
        _showQuestParticles.value = show
    }

    fun setShowFogBoundaries(show: Boolean) {
        _showFogBoundaries.value = show
    }

    fun setUltimate(ultimate: Boolean) {
        _ultimate.value = ultimate
    }

    fun setSelectedLobbyEvent(filter: LobbyEventFilter) {
        _selectedLobbyEvent.value = filter
    }

    fun setWalkthroughPlayer(player: WalkthroughPlayer) {
        _walkthroughPlayer.value = player
    }
}
