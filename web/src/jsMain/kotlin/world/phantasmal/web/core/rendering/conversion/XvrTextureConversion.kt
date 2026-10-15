package world.phantasmal.web.core.rendering.conversion

import org.khronos.webgl.Uint16Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import world.phantasmal.psolib.fileFormats.ninja.XvrTexture
import world.phantasmal.web.externals.three.CompressedPixelFormat
import world.phantasmal.web.externals.three.CompressedTexture
import world.phantasmal.web.externals.three.DataTexture
import world.phantasmal.web.externals.three.LinearFilter
import world.phantasmal.web.externals.three.LinearMipmapLinearFilter
import world.phantasmal.web.externals.three.Mipmap
import world.phantasmal.web.externals.three.MirroredRepeatWrapping
import world.phantasmal.web.externals.three.PixelFormat
import world.phantasmal.web.externals.three.RGBAFormat
import world.phantasmal.web.externals.three.RGBA_S3TC_DXT1_Format
import world.phantasmal.web.externals.three.RGBA_S3TC_DXT3_Format
import world.phantasmal.web.externals.three.RGBFormat
import world.phantasmal.web.externals.three.Texture
import world.phantasmal.web.externals.three.TextureDataType
import world.phantasmal.web.externals.three.TextureFilter
import world.phantasmal.web.externals.three.UnsignedByteType
import world.phantasmal.web.externals.three.UnsignedShort5551Type
import world.phantasmal.web.externals.three.UnsignedShort565Type
import world.phantasmal.webui.obj

/**
 * Anisotropic filtering level for textures loaded without access to a renderer. three.js clamps it
 * to the maximum the GPU supports.
 */
const val DEFAULT_TEXTURE_ANISOTROPY = 8

/**
 * Set in the first XVR format field when the texture data contains a full mipmap chain down to
 * 1 x 1 after the base level.
 */
private const val XVR_FLAG_MIPMAPS = 1

/**
 * @param minFilter When null, textures are trilinearly filtered. Mipmaps stored in the XVR are
 * used when present, otherwise they are generated on the GPU (DXT data is decoded to RGBA first,
 * because compressed textures can't have mipmaps generated for them).
 */
fun xvrTextureToThree(
    xvr: XvrTexture,
    magFilter: TextureFilter = LinearFilter,
    minFilter: TextureFilter? = null,
    anisotropy: Int = 1,
): Texture {
    val mipmapped = minFilter == null
    val effectiveMinFilter = minFilter ?: LinearMipmapLinearFilter

    return when (xvr.format.second) {
        // D3DFMT_R5G6B5
        2 -> createDataTexture(
            Uint16Array(xvr.data.arrayBuffer, 0, xvr.width * xvr.height),
            xvr.width,
            xvr.height,
            RGBFormat,
            UnsignedShort565Type,
            magFilter,
            effectiveMinFilter,
            generateMipmaps = mipmapped,
            anisotropy,
        )
        // D3DFMT_A1R5G5B5
        3 -> {
            val originalData = Uint16Array(xvr.data.arrayBuffer, 0, xvr.width * xvr.height)
            val data = Uint16Array(originalData.length)

            // Change bit order from ARGB 1555 to RGBA 5551.
            for (i in 0 until originalData.length) {
                val x = originalData[i].toInt()
                data[i] = ((x shl 1) or (x ushr 15)).toShort()
            }

            createDataTexture(
                data,
                xvr.width,
                xvr.height,
                RGBAFormat,
                UnsignedShort5551Type,
                magFilter,
                effectiveMinFilter,
                generateMipmaps = mipmapped,
                anisotropy,
            )
        }
        // D3DFMT_DXT1
        6 -> createDxtTexture(
            xvr,
            RGBA_S3TC_DXT1_Format,
            DXT1_BLOCK_SIZE,
            magFilter,
            minFilter,
            anisotropy,
        )
        // D3DFMT_DXT2
        // Although DXT2 nominally has premultiplied alpha, PSO's DXT2 texels have color channels
        // exceeding their alpha, so they are straight alpha and decoded like DXT3.
        7 -> createDxtTexture(
            xvr,
            RGBA_S3TC_DXT3_Format,
            DXT3_BLOCK_SIZE,
            magFilter,
            minFilter,
            anisotropy,
        )
        // 1 -> D3DFMT_A8R8G8B8
        // 4 -> D3DFMT_A4R4G4B4
        // 5 -> D3DFMT_P8
        // 8 -> D3DFMT_DXT3
        // 9 -> D3DFMT_DXT4
        // 10 -> D3DFMT_DXT5
        // 11 -> D3DFMT_A8R8G8B8
        // 12 -> D3DFMT_R5G6B5
        // 13 -> D3DFMT_A1R5G5B5
        // 14 -> D3DFMT_A4R4G4B4
        // 15 -> D3DFMT_YUY2
        // 16 -> D3DFMT_V8U8
        // 17 -> D3DFMT_A8
        // 18 -> D3DFMT_X1R5G5B5
        // 19 -> D3DFMT_X8R8G8B8
        else -> error("Format ${xvr.format.first}, ${xvr.format.second} not supported.")
    }
}

private const val DXT1_BLOCK_SIZE = 8
private const val DXT3_BLOCK_SIZE = 16

private fun createDxtTexture(
    xvr: XvrTexture,
    format: CompressedPixelFormat,
    blockSize: Int,
    magFilter: TextureFilter,
    minFilter: TextureFilter?,
    anisotropy: Int,
): Texture {
    val hasMipmaps = (xvr.format.first and XVR_FLAG_MIPMAPS) != 0

    if (minFilter == null && !hasMipmaps) {
        // Without stored mipmaps, decode to RGBA so the GPU can generate them. Minifying a
        // texture without mipmaps causes heavy aliasing on distant surfaces.
        return createDataTexture(
            decodeDxt(xvr, blockSize),
            xvr.width,
            xvr.height,
            RGBAFormat,
            UnsignedByteType,
            magFilter,
            LinearMipmapLinearFilter,
            generateMipmaps = true,
            anisotropy,
        )
    }

    // Here, minFilter == null implies the XVR contains mipmaps.
    val useMipmaps = minFilter == null
    val mipmaps = mutableListOf<Mipmap>()
    var byteOffset = 0
    var width = xvr.width
    var height = xvr.height

    while (true) {
        val byteSize = dxtLevelSize(width, height, blockSize)

        if (byteOffset + byteSize > xvr.data.size) break

        mipmaps.add(obj {
            this.data = Uint8Array(xvr.data.arrayBuffer, byteOffset, byteSize)
            this.width = width
            this.height = height
        })

        byteOffset += byteSize

        if (!useMipmaps || (width == 1 && height == 1)) break

        width = maxOf(1, width / 2)
        height = maxOf(1, height / 2)
    }

    // WebGL requires a complete mipmap chain down to 1 x 1, fall back to the base level otherwise.
    val completeChain = useMipmaps && mipmaps.size > 1 &&
            mipmaps.last().let { it.width == 1 && it.height == 1 }

    if (!completeChain && mipmaps.size > 1) {
        mipmaps.subList(1, mipmaps.size).clear()
    }

    return createCompressedTexture(
        mipmaps.toTypedArray(),
        xvr.width,
        xvr.height,
        format,
        magFilter,
        minFilter ?: if (completeChain) LinearMipmapLinearFilter else LinearFilter,
        anisotropy,
    )
}

private fun dxtLevelSize(width: Int, height: Int, blockSize: Int): Int =
    ((width + 3) / 4) * ((height + 3) / 4) * blockSize

private fun createDataTexture(
    data: Any,
    width: Int,
    height: Int,
    format: PixelFormat,
    type: TextureDataType,
    magFilter: TextureFilter,
    minFilter: TextureFilter,
    generateMipmaps: Boolean,
    anisotropy: Int,
): DataTexture {
    val texture = DataTexture(
        data,
        width,
        height,
        format,
        type,
        wrapS = MirroredRepeatWrapping,
        wrapT = MirroredRepeatWrapping,
        magFilter = magFilter,
        minFilter = minFilter,
        anisotropy = anisotropy,
    )
    texture.generateMipmaps = generateMipmaps
    texture.needsUpdate = true
    return texture
}

private fun createCompressedTexture(
    mipmaps: Array<Mipmap>,
    width: Int,
    height: Int,
    format: CompressedPixelFormat,
    magFilter: TextureFilter,
    minFilter: TextureFilter,
    anisotropy: Int,
): CompressedTexture {
    val texture = CompressedTexture(
        mipmaps,
        width,
        height,
        format,
        wrapS = MirroredRepeatWrapping,
        wrapT = MirroredRepeatWrapping,
        magFilter = magFilter,
        minFilter = minFilter,
        anisotropy = anisotropy,
    )
    texture.needsUpdate = true
    return texture
}

/**
 * Decodes the base level of a DXT1 (8-byte blocks) or DXT2/3 (16-byte blocks) texture to
 * 8-bit RGBA.
 */
internal fun decodeDxt(xvr: XvrTexture, blockSize: Int): Uint8Array {
    val width = xvr.width
    val height = xvr.height
    val src = Uint8Array(xvr.data.arrayBuffer, 0, dxtLevelSize(width, height, blockSize))
    val image = Uint8Array(width * height * 4)
    val palette = IntArray(16) // 4 RGBA colors.
    val hasExplicitAlpha = blockSize == DXT3_BLOCK_SIZE
    var offset = 0

    for (blockY in 0 until (height + 3) / 4) {
        for (blockX in 0 until (width + 3) / 4) {
            val alphaOffset = offset
            val colorOffset = if (hasExplicitAlpha) offset + 8 else offset
            offset += blockSize

            val c0 = u8(src, colorOffset) or (u8(src, colorOffset + 1) shl 8)
            val c1 = u8(src, colorOffset + 2) or (u8(src, colorOffset + 3) shl 8)
            rgb565(c0, palette, 0)
            rgb565(c1, palette, 4)

            if (c0 > c1 || hasExplicitAlpha) {
                for (k in 0 until 3) {
                    palette[8 + k] = (2 * palette[k] + palette[4 + k] + 1) / 3
                    palette[12 + k] = (palette[k] + 2 * palette[4 + k] + 1) / 3
                }
                palette[11] = 255
                palette[15] = 255
            } else {
                for (k in 0 until 3) {
                    palette[8 + k] = (palette[k] + palette[4 + k]) / 2
                    palette[12 + k] = 0
                }
                palette[11] = 255
                palette[15] = 0
            }

            for (py in 0 until 4) {
                val y = blockY * 4 + py
                if (y >= height) break

                val codes = u8(src, colorOffset + 4 + py)

                for (px in 0 until 4) {
                    val x = blockX * 4 + px
                    if (x >= width) break

                    val code = (codes ushr (2 * px)) and 3
                    val dst = 4 * (y * width + x)
                    image[dst] = palette[4 * code].toByte()
                    image[dst + 1] = palette[4 * code + 1].toByte()
                    image[dst + 2] = palette[4 * code + 2].toByte()
                    image[dst + 3] = if (hasExplicitAlpha) {
                        val a = (u8(src, alphaOffset + 2 * py + px / 2) ushr (4 * (px % 2))) and 0xF
                        (a * 17).toByte()
                    } else {
                        palette[4 * code + 3].toByte()
                    }
                }
            }
        }
    }

    return image
}

private fun u8(array: Uint8Array, index: Int): Int =
    array[index].toInt() and 0xFF

private fun rgb565(color: Int, palette: IntArray, offset: Int) {
    val r = (color ushr 11) and 0x1F
    val g = (color ushr 5) and 0x3F
    val b = color and 0x1F
    palette[offset] = (r shl 3) or (r ushr 2)
    palette[offset + 1] = (g shl 2) or (g ushr 4)
    palette[offset + 2] = (b shl 3) or (b ushr 2)
    palette[offset + 3] = 255
}
