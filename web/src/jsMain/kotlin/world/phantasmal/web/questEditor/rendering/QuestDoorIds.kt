package world.phantasmal.web.questEditor.rendering

import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.web.questEditor.models.QuestObjectModel

/** Door IDs controlled by this object, using the same native rules as playback labels. */
internal fun QuestObjectModel.controlledDoorIds(): IntRange? {
    if (type !in doorObjectTypes) return null
    val raw = entity.data.getInt(52)
    if (raw == -1) return null
    val first = if (type == ObjectType.ForestDoor) raw and 0xFF else raw
    if (first < 0) return null
    val count = when {
        type == ObjectType.Ruins4ButtonDoor -> 4
        type == ObjectType.Ruins2ButtonDoor -> 2
        // newserv: the Caves and Mines multi-switch doors store 4 minus the number of switch
        // flags in param5; the CCA, Seabed, Temple and Spaceship ones store the number itself.
        type in inverseSwitchCountDoorTypes -> 4 - entity.data.getInt(56).coerceIn(0, 4)
        type in switchCountDoorTypes -> entity.data.getInt(56).coerceIn(0, 4)
        else -> 1
    }
    return first until first + count.coerceAtLeast(1)
}

internal fun QuestObjectModel.isFenceObject(): Boolean = type in fenceObjectTypes

internal fun QuestObjectModel.isDoorObject(): Boolean = type in doorObjectTypes

private val fenceObjectTypes: Set<ObjectType> = setOf(
    ObjectType.LaserFence,
    ObjectType.LaserSquareFence,
    ObjectType.ForestLaserFenceSwitch,
    ObjectType.LaserFenceEx,
    ObjectType.LaserSquareFenceEx,
    ObjectType.RuinsLaserFence4x2,
    ObjectType.RuinsLaserFence6x2,
    ObjectType.RuinsLaserFence4x4,
    ObjectType.RuinsLaserFence6x4,
)

private val doorObjectTypes: Set<ObjectType> = setOf(
    ObjectType.ForestDoor,
    ObjectType.EnergyBarrier,
    ObjectType.ForestRisingBridge,
    ObjectType.Caves4ButtonDoor,
    ObjectType.CavesNormalDoor,
    ObjectType.CavesSwitchDoor,
    ObjectType.MinesDoor,
    ObjectType.MinesSwitchDoor,
    ObjectType.Ruins1Door,
    ObjectType.Ruins2Door,
    ObjectType.Ruins3Door,
    ObjectType.Ruins11ButtonDoor,
    ObjectType.Ruins21ButtonDoor,
    ObjectType.Ruins31ButtonDoor,
    ObjectType.Ruins4ButtonDoor,
    ObjectType.Ruins2ButtonDoor,
    ObjectType.LaserFence,
    ObjectType.LaserSquareFence,
    ObjectType.ForestLaserFenceSwitch,
    ObjectType.LaserFenceEx,
    ObjectType.LaserSquareFenceEx,
    ObjectType.RuinsLaserFence4x2,
    ObjectType.RuinsLaserFence6x2,
    ObjectType.RuinsLaserFence4x4,
    ObjectType.RuinsLaserFence6x4,
    ObjectType.SpaceshipDoor,
    ObjectType.TempleNormalDoor,
    ObjectType.CcaDoor,
    ObjectType.SeabedDoorWithBlueEdges,
    ObjectType.FourSwitchTempleDoor,
    ObjectType.FourButtonSpaceshipDoor,
)

private val inverseSwitchCountDoorTypes: Set<ObjectType> = setOf(
    ObjectType.Caves4ButtonDoor,
    ObjectType.MinesDoor,
)

private val switchCountDoorTypes: Set<ObjectType> = setOf(
    ObjectType.CcaDoor,
    ObjectType.SeabedDoorWithBlueEdges,
    ObjectType.FourSwitchTempleDoor,
    ObjectType.FourButtonSpaceshipDoor,
)
