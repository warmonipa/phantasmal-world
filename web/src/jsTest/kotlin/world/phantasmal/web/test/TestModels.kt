package world.phantasmal.web.test

import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.BytecodeIr
import world.phantasmal.psolib.asm.dataFlowAnalysis.FloorMapping
import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.QuestNpc
import world.phantasmal.psolib.fileFormats.quest.QuestObject
import world.phantasmal.psolib.fileFormats.quest.Version
import world.phantasmal.web.questEditor.models.QuestEventModel
import world.phantasmal.web.questEditor.models.QuestModel
import world.phantasmal.web.questEditor.models.QuestNpcModel
import world.phantasmal.web.questEditor.models.QuestObjectModel
import world.phantasmal.web.questEditor.models.NpcPlacementPolicy

fun WebTestContext.createQuestModel(
    id: Int = 1,
    name: String = "Test",
    shortDescription: String = name,
    longDescription: String = name,
    episode: Episode = Episode.I,
    floorMappings: List<FloorMapping> = emptyList(),
    npcs: List<QuestNpcModel> = emptyList(),
    objects: List<QuestObjectModel> = emptyList(),
    events: List<QuestEventModel> = emptyList(),
    bytecodeIr: BytecodeIr = BytecodeIr(emptyList()),
    version: Version = Version.BB_V4,
    npcPlacementPolicy: NpcPlacementPolicy = components.npcPlacementPolicy,
): QuestModel =
    QuestModel(
        id,
        language = 1,
        name,
        shortDescription,
        longDescription,
        episode,
        npcPlacementPolicy,
        npcs.toMutableList(),
        objects.toMutableList(),
        events.toMutableList(),
        datUnknowns = emptyList(),
        cmRandomSpawns = mutableListOf(),
        cmMonsterMappings = mutableListOf(),
        cmConfigPool = mutableListOf(),
        bytecodeIr,
        UIntArray(0),
        floorMappings,
        components.areaStore::getVariant,
        version = version,
    )

fun WebTestContext.createQuestNpcModel(
    type: NpcType,
    episode: Episode,
    floorId: Int = 0,
    placementPolicy: NpcPlacementPolicy = components.npcPlacementPolicy,
): QuestNpcModel =
    QuestNpcModel(
        QuestNpc(type, episode, floorId = floorId, wave = 0),
        waveId = 0,
        placementPolicy,
    )

fun createQuestObjectModel(type: ObjectType, floorId: Int = 0): QuestObjectModel =
    QuestObjectModel(QuestObject(type, floorId = floorId))
