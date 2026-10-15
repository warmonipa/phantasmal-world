package world.phantasmal.web.core.rendering.conversion

import org.khronos.webgl.get
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.fileFormats.ninja.XvrTexture
import world.phantasmal.web.externals.three.CompressedTexture
import world.phantasmal.web.externals.three.DataTexture
import world.phantasmal.web.externals.three.LinearMipmapLinearFilter
import world.phantasmal.web.externals.three.NearestFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class XvrTextureConversionTests {
    @Test
    fun decodes_dxt1_four_color_block() {
        val image = decodeDxt(dxt1Block(c0 = 0xF800, c1 = 0x001F, row0 = 0xE4), 8)

        assertPixel(image, 0, 255, 0, 0, 255)
        assertPixel(image, 1, 0, 0, 255, 255)
        assertPixel(image, 2, 170, 0, 85, 255)
        assertPixel(image, 3, 85, 0, 170, 255)
    }

    @Test
    fun decodes_dxt1_transparent_texels() {
        // c0 <= c1 selects the 3-color mode where code 3 is transparent black.
        val image = decodeDxt(dxt1Block(c0 = 0x001F, c1 = 0xF800, row0 = 0xFF), 8)

        assertPixel(image, 0, 0, 0, 0, 0)
    }

    @Test
    fun decodes_dxt3_explicit_alpha() {
        val buf = Buffer.withSize(16).zero()
        buf.setUByte(0, 0x5Fu) // Pixel 0 alpha 0xF, pixel 1 alpha 0x5.
        buf.setUShort(8, 0xFFFFu) // White.
        val image = decodeDxt(xvr(Pair(0, 7), 4, 4, buf), 16)

        assertPixel(image, 0, 255, 255, 255, 255)
        assertPixel(image, 1, 255, 255, 255, 85)
    }

    @Test
    fun uses_stored_mipmap_chain() {
        // 8x8 + 4x4 + 2x2 + 1x1 levels, each at least one 8-byte block.
        val texture = xvrTextureToThree(xvr(Pair(1, 6), 8, 8, Buffer.withSize(56).zero()))

        assertTrue(texture is CompressedTexture)
        assertEquals(4, texture.asDynamic().mipmaps.length.unsafeCast<Int>())
        assertSame(LinearMipmapLinearFilter, texture.asDynamic().minFilter)
    }

    @Test
    fun generates_mipmaps_for_dxt_without_stored_mipmaps() {
        val texture = xvrTextureToThree(xvr(Pair(0, 6), 8, 8, Buffer.withSize(32).zero()))

        assertTrue(texture is DataTexture)
        assertTrue(texture.generateMipmaps)
        assertSame(LinearMipmapLinearFilter, texture.asDynamic().minFilter)
    }

    @Test
    fun explicit_min_filter_uses_base_level_only() {
        val texture = xvrTextureToThree(
            xvr(Pair(1, 6), 8, 8, Buffer.withSize(56).zero()),
            magFilter = NearestFilter,
            minFilter = NearestFilter,
        )

        assertTrue(texture is CompressedTexture)
        assertEquals(1, texture.asDynamic().mipmaps.length.unsafeCast<Int>())
        assertSame(NearestFilter, texture.asDynamic().minFilter)
    }

    private fun dxt1Block(c0: Int, c1: Int, row0: Int): XvrTexture {
        val buf = Buffer.withSize(8).zero()
        buf.setUShort(0, c0.toUShort())
        buf.setUShort(2, c1.toUShort())
        buf.setUByte(4, row0.toUByte())
        return xvr(Pair(0, 6), 4, 4, buf)
    }

    private fun xvr(format: Pair<Int, Int>, width: Int, height: Int, data: Buffer) =
        XvrTexture(id = 0, format, width, height, data.size, data)

    private fun assertPixel(
        image: org.khronos.webgl.Uint8Array,
        index: Int,
        r: Int,
        g: Int,
        b: Int,
        a: Int,
    ) {
        val actual = (0 until 4).map { image[4 * index + it].toInt() and 0xFF }
        assertEquals(listOf(r, g, b, a), actual)
    }
}
