package world.phantasmal.web.assetsGeneration

import java.io.File

/**
 * Prepares Episode I Ultimate Sinow assets from Sega's original files.
 *
 * The normal XVM contains Gold in texture slots 0..2 and Beat in 3..5; the Ultimate XVM contains
 * Red in 0..2 and Blue in 3..5. The shared Sinow geometry references 3..5, so the rare variant's
 * original chunks are copied into those slots without colour conversion or DXT re-encoding. Blue
 * reuses Beat's normal geometry so its default pose has retracted blades like Beat, Gold, and Red.
 *
 * Usage: PrepareSinowVariants <npcs-assets-dir>
 */

private const val XVMH = 0x484D5658
private const val XVRT = 0x54525658

private data class IffRegion(val type: Int, val dataOffset: Int, val dataSize: Int)

private fun i32(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

private fun iffRegions(bytes: ByteArray): List<IffRegion> {
    val regions = mutableListOf<IffRegion>()
    var offset = 0

    while (offset + 8 <= bytes.size) {
        val type = i32(bytes, offset)
        val dataSize = i32(bytes, offset + 4)
        val dataOffset = offset + 8
        require(dataSize >= 0 && dataOffset + dataSize <= bytes.size) {
            "Invalid IFF chunk at offset $offset."
        }
        regions.add(IffRegion(type, dataOffset, dataSize))
        offset = dataOffset + dataSize
    }

    require(offset == bytes.size) { "Trailing bytes after the final IFF chunk." }
    return regions
}

private fun prepareOriginalRareTexture(
    dir: File,
    sourceName: String,
    destinationName: String,
    variantName: String,
) {
    val sourceFile = File(dir, sourceName)
    val sourceBytes = sourceFile.readBytes()
    val regions = iffRegions(sourceBytes)
    val header = regions.single { it.type == XVMH }
    val textures = regions.filter { it.type == XVRT }

    require(textures.size == 6) {
        "Expected six textures in ${sourceFile.name}, found ${textures.size}."
    }
    require((0 until 3).all { textures[it].dataSize == textures[it + 3].dataSize }) {
        "Sinow rare and common texture chunk sizes do not match in ${sourceFile.name}."
    }

    val rare = sourceBytes.copyOf()
    for (i in 0 until 3) {
        val source = textures[i]
        val destination = textures[i + 3]
        val destinationId = rare.copyOfRange(
            destination.dataOffset + 8,
            destination.dataOffset + 12,
        )
        sourceBytes.copyInto(
            rare,
            destination.dataOffset,
            source.dataOffset,
            source.dataOffset + source.dataSize,
        )
        // Retain IDs 900033..900035 referenced by the shared geometry.
        destinationId.copyInto(rare, destination.dataOffset + 8)
    }

    require((rare[header.dataOffset].toInt() and 0xFF) == 6)
    File(dir, destinationName).writeBytes(rare)
    println("Wrote $destinationName from the original $variantName textures.")
}

internal fun prepareSinowVariants(dir: File) {
    require(dir.isDirectory) { "NPC assets directory not found: $dir" }

    prepareOriginalRareTexture(dir, "SinowBeat.xvm", "SinowGold.xvm", "Gold")
    prepareOriginalRareTexture(dir, "SinowBeat.ult.xvm", "SinowGold.ult.xvm", "Red")
    File(dir, "SinowGold.ult.nj").writeBytes(File(dir, "SinowGold.nj").readBytes())
    println("Wrote SinowGold.ult.nj from Sinow Gold's retracted geometry.")
    File(dir, "SinowBeat.ult.nj").writeBytes(File(dir, "SinowBeat.nj").readBytes())
    println("Wrote SinowBeat.ult.nj from Sinow Beat's retracted geometry.")
}

fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: PrepareSinowVariants <npcs-assets-dir>" }
    prepareSinowVariants(File(args[0]))
}
