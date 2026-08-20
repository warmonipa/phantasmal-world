package world.phantasmal.web.questEditor.stores

import world.phantasmal.cell.Cell
import world.phantasmal.cell.list.ListCell
import world.phantasmal.psolib.fileFormats.quest.ChallengeModeSeedSimulation
import world.phantasmal.web.questEditor.models.AreaModel
import world.phantasmal.web.questEditor.models.AreaVariantModel
import world.phantasmal.web.questEditor.models.NpcPlacementPolicy
import world.phantasmal.web.questEditor.models.QuestEntityModel
import world.phantasmal.web.questEditor.models.QuestEventModel
import world.phantasmal.web.questEditor.models.QuestModel
import world.phantasmal.web.questEditor.models.QuestObjectModel
import world.phantasmal.web.questEditor.models.SectionModel

interface QuestDocumentState {
    val currentQuest: Cell<QuestModel?>
}

interface QuestMapState {
    val currentArea: Cell<AreaModel?>
    val currentAreaVariant: Cell<AreaVariantModel?>
    val currentFloorIds: Cell<Set<Int>?>
    val currentAreaObjects: ListCell<QuestObjectModel>
    val selectedSection: Cell<SectionModel?>
}

interface QuestEventSelectionState {
    val selectedEvents: Cell<Set<QuestEventModel>>
    val selectedEventsSectionWaves: Cell<Set<Pair<Int, Int>>>
}

interface QuestEntitySelectionState {
    val highlightedEntity: Cell<QuestEntityModel<*, *>?>
    val selectedEntity: Cell<QuestEntityModel<*, *>?>
}

interface QuestChallengePreviewState {
    val challengeSeedSimulation: Cell<ChallengeModeSeedSimulation?>
    val selectedChallengeLogicalFloor: Cell<Int?>
    val selectedChallengeRoomId: Cell<Int?>
}

interface QuestEntitySelectionActions : QuestEntitySelectionState {
    fun setHighlightedEntity(entity: QuestEntityModel<*, *>?)
    fun setSelectedEntity(entity: QuestEntityModel<*, *>?)
}

/** Read-only state consumed by the Quest Editor rendering subsystem. */
interface QuestEditorRenderState :
    QuestDocumentState,
    QuestMapState,
    QuestEventSelectionState,
    QuestEntitySelectionState,
    QuestChallengePreviewState

/** Rendering state plus the mutations and placement capability owned by the renderer. */
interface QuestEditorRenderAccess : QuestEditorRenderState, QuestEntitySelectionActions {
    val npcPlacementPolicy: NpcPlacementPolicy
}
