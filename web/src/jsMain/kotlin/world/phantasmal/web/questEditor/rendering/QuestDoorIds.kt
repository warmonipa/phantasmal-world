package world.phantasmal.web.questEditor.rendering

import kotlin.math.cos
import kotlin.math.sin
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

/** Whether a door or fence is open, closed for good, or open once all of some switch flags are on. */
internal sealed interface GateState {
    data object Open : GateState
    data object Closed : GateState
    data class Needs(val flags: IntRange) : GateState
}

/** The state of this door or fence following newserv's Map.cc, or null for other objects. */
internal fun QuestObjectModel.gateState(): GateState? {
    if (type !in doorObjectTypes) return null
    val raw = entity.data.getInt(52)
    if (type == ObjectType.ForestDoor) {
        return if (raw == -1) GateState.Open else GateState.Needs((raw and 0xFF)..(raw and 0xFF))
    }
    val count = switchCount()
    if (count != null) {
        // Multi-switch doors: negative = always unlocked, and so is a door that needs no flags.
        if (raw < 0 || count == 0) return GateState.Open
        return GateState.Needs(raw until raw + count)
    }
    return when {
        // Negative is always unlocked (or opens by proximity, for Ruins fences); 0x100 behaves the
        // same because of an off-by-one bounds check in the client.
        raw < 0 || raw == 0x100 -> GateState.Open
        raw > 0x100 -> GateState.Closed
        else -> GateState.Needs(raw..raw)
    }
}

/**
 * Half the width this door or fence blocks, across the direction of travel, or null when it
 * can't be crossed by walking past it (rising bridges block by being lowered instead).
 */
internal fun QuestObjectModel.gateHalfWidth(): Double? = when (type) {
    ObjectType.ForestRisingBridge -> null
    ObjectType.LaserFenceEx, ObjectType.LaserSquareFenceEx ->
        entity.data.getFloat(48).toDouble().takeIf { it > 0.0 } ?: SHORT_FENCE_HALF_WIDTH
    // Even model numbers are short fences, odd ones long.
    ObjectType.LaserFence, ObjectType.LaserSquareFence ->
        if (entity.data.getInt(60) % 2 == 0) SHORT_FENCE_HALF_WIDTH else LONG_FENCE_HALF_WIDTH
    ObjectType.RuinsLaserFence4x2, ObjectType.RuinsLaserFence4x4 -> SHORT_FENCE_HALF_WIDTH
    ObjectType.RuinsLaserFence6x2, ObjectType.RuinsLaserFence6x4 -> LONG_FENCE_HALF_WIDTH
    ObjectType.CcaDoor, ObjectType.SeabedDoorWithBlueEdges -> WIDE_DOOR_HALF_WIDTH
    else -> DOOR_HALF_WIDTH
}

/**
 * The horizontal direction players cross this door or fence in. Doors span their local X axis and
 * are crossed along local Z; laser fences string their lasers along local Z (e.g. Penumbral Surge
 * #6 places three fences end to end along that axis) and are crossed along local X.
 */
internal fun QuestObjectModel.gateCrossingDirection(): Pair<Double, Double> {
    val yaw = worldRotation.value.y
    return if (type in laserFenceTypes) cos(yaw) to -sin(yaw) else sin(yaw) to cos(yaw)
}

private val laserFenceTypes: Set<ObjectType> = setOf(
    ObjectType.LaserFence,
    ObjectType.LaserSquareFence,
    ObjectType.LaserFenceEx,
    ObjectType.LaserSquareFenceEx,
)

/** Square fences block on all four sides of the square. */
internal fun QuestObjectModel.isSquareGate(): Boolean =
    type == ObjectType.LaserSquareFence || type == ObjectType.LaserSquareFenceEx

/** The switch flag this object turns on when a player uses it (newserv Map.cc), or null. */
internal fun QuestObjectModel.setSwitchFlag(): Int? {
    val flag = when (type) {
        in switchFlagParam4Types -> entity.data.getInt(52)
        // Attackable collision: param3 is set after enough hits, unless param6 calls a label.
        ObjectType.TargetableObject ->
            if (entity.data.getInt(60) > 0) return null else entity.data.getFloat(48).toInt()
        else -> return null
    }
    return flag.takeIf { it in 0..0xFF }
}

private fun QuestObjectModel.switchCount(): Int? = when (type) {
    ObjectType.Ruins4ButtonDoor -> 4
    ObjectType.Ruins2ButtonDoor -> 2
    in inverseSwitchCountDoorTypes -> 4 - entity.data.getInt(56).coerceIn(0, 4)
    in switchCountDoorTypes -> entity.data.getInt(56).coerceIn(0, 4)
    else -> null
}

private const val SHORT_FENCE_HALF_WIDTH = 15.0
private const val LONG_FENCE_HALF_WIDTH = 30.0
private const val DOOR_HALF_WIDTH = 20.0
private const val WIDE_DOOR_HALF_WIDTH = 25.0

private val switchFlagParam4Types: Set<ObjectType> = setOf(
    ObjectType.ForestSwitch,
    ObjectType.ForestLaserFenceSwitch,
    ObjectType.SwitchNoneDoor,
    ObjectType.FloorPanel1,
    ObjectType.FloorPanel3,
    ObjectType.FloorPanel4,
    ObjectType.RuinsSwitch,
    ObjectType.RuinsFenceSwitch,
    ObjectType.TouchPlateObject,
    ObjectType.BoxDetectObject,
    ObjectType.Sensor,
    ObjectType.RuinsSensor,
)

internal fun QuestObjectModel.isDoorObject(): Boolean = type in doorObjectTypes

private val fenceObjectTypes: Set<ObjectType> = setOf(
    ObjectType.LaserFence,
    ObjectType.LaserSquareFence,
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
