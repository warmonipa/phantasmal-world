package world.phantasmal.web.questEditor.loading

import world.phantasmal.psolib.fileFormats.quest.EntityType
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.QuestObject

/**
 * An object parameter that selects one of the textures bundled in the object's XVM, such as a
 * warp's color or a laser fence's color. Parameter meanings follow newserv's Map.cc.
 */
internal class EntityTextureVariant(
    /** Offset of the parameter in the object's data, so renderers can watch it for edits. */
    val paramOffset: Int,
    private val read: (QuestObject) -> Int,
    private val normalize: (Int) -> Int,
    private val overrides: (Int) -> Map<Int, Int>,
) {
    /** The normalized variant of [obj], usable as a mesh cache key. */
    fun variantOf(obj: QuestObject): Int = normalize(read(obj))

    /** Maps the texture indices the model references to the ones [variant] selects. */
    fun textureIndexOverrides(variant: Int): Map<Int, Int> = overrides(normalize(variant))
}

internal fun entityTextureVariant(type: EntityType): EntityTextureVariant? = when (type) {
    ObjectType.ForestDoor -> FOREST_DOOR_DIGIT

    // param6: 0 = blue, 1 = red. The warp lines reference the red texture (2); blue is 3.
    ObjectType.Teleporter,
    ObjectType.QuestWarp,
    ObjectType.Epilogue,
    -> warpColor(redTexture = 2, blueTexture = 3)

    // Same param6 colors. The EP2 warp lines reference the red texture (8); blue is 9.
    ObjectType.TeleporterEp2 -> warpColor(redTexture = 8, blueTexture = 9)

    // param1: <=0 = red, 1 = cyan, 2 = green, >=3 = magenta. Textures 0-3 are in that order.
    ObjectType.LaserFence,
    ObjectType.LaserSquareFence,
    ObjectType.LaserFenceEx,
    ObjectType.LaserSquareFenceEx,
    -> fenceColor(paramOffset = 40, isFloat = true, textures = listOf(0, 1, 2, 3))

    // param6 picks the same four colors for the switch screen.
    ObjectType.ForestLaserFenceSwitch ->
        fenceColor(paramOffset = 60, isFloat = false, textures = listOf(0, 1, 2, 3))

    // Ruins switch and fence, param5 clamped to [0, 3]. Only the 4x2 fence's XVM bundles the
    // colored laser textures.
    ObjectType.RuinsFenceSwitch ->
        fenceColor(paramOffset = 56, isFloat = false, textures = listOf(1, 7, 8, 9))
    ObjectType.RuinsLaserFence4x2 ->
        fenceColor(paramOffset = 56, isFloat = false, textures = listOf(0, 4, 5, 6))

    else -> null
}

/**
 * The first ten textures in 128.xvm match the PSO world-font digit order 0-9.
 */
internal fun forestDoorDigitTextureIndex(digit: Int): Int = digit.mod(10)

private val FOREST_DOOR_DIGIT = EntityTextureVariant(
    paramOffset = 52,
    read = { it.forestDoorDigit },
    normalize = ::forestDoorDigitTextureIndex,
    overrides = { digit -> mapOf(0 to digit) },
)

private const val WARP_BLUE = 0
private const val WARP_RED = 1

private fun warpColor(redTexture: Int, blueTexture: Int) = EntityTextureVariant(
    paramOffset = 60,
    read = { it.data.getInt(60) },
    normalize = { if (it == 0) WARP_BLUE else WARP_RED },
    overrides = { color -> if (color == WARP_BLUE) mapOf(redTexture to blueTexture) else emptyMap() },
)

private fun fenceColor(paramOffset: Int, isFloat: Boolean, textures: List<Int>) =
    EntityTextureVariant(
        paramOffset,
        read = {
            // The client truncates float params.
            if (isFloat) it.data.getFloat(paramOffset).toInt() else it.data.getInt(paramOffset)
        },
        normalize = { it.coerceIn(0, textures.lastIndex) },
        overrides = { color -> mapOf(textures[0] to textures[color]) },
    )
