package world.phantasmal.psolib.fileFormats.ninja

import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.cursor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NjModelTests {
    @Test
    fun parses_vertex_colors_material_diffuse_and_texture_flags() {
        val buf = Buffer.withSize(128).zero()
        buf.setInt(0, 24) // Vertex list offset.
        buf.setInt(4, 48) // Polygon list offset.

        // Vertex chunk NJD_CV_D8 with one vertex.
        var o = 24
        buf.setUByte(o, 35u); buf.setUByte(o + 1, 0u)
        buf.setShort(o + 2, 5) // 5 * 4 bytes follow.
        buf.setUShort(o + 4, 0u) // Index offset.
        buf.setUShort(o + 6, 1u) // Vertex count.
        buf.setFloat(o + 8, 1f) // Position x.
        buf.setInt(o + 20, 0x80402010.toInt()) // ARGB color.
        buf.setUByte(o + 24, 255u) // End.

        o = 48
        // Material chunk with diffuse, normal alpha blending.
        buf.setUByte(o, 17u); buf.setUByte(o + 1, ((4 shl 3) or 5).toUByte())
        buf.setShort(o + 2, 2) // 2 * 2 bytes follow.
        buf.setUByte(o + 4, 0x30u) // B
        buf.setUByte(o + 5, 0x20u) // G
        buf.setUByte(o + 6, 0x10u) // R
        buf.setUByte(o + 7, 0xFFu) // A
        o += 8
        // Tiny chunk with flip U and clamp V.
        buf.setUByte(o, 8u); buf.setUByte(o + 1, ((1 shl 7) or (1 shl 4)).toUByte())
        buf.setUShort(o + 2, 3u) // Texture ID.
        o += 4
        // Strip chunk with per-vertex colors, double sided.
        buf.setUByte(o, 70u); buf.setUByte(o + 1, (1 shl 4).toUByte())
        buf.setShort(o + 2, 11) // 11 * 2 bytes follow.
        buf.setShort(o + 4, 1) // One strip.
        buf.setShort(o + 6, 3) // Three indices.
        for (i in 0 until 3) {
            buf.setUShort(o + 8 + 6 * i, 0u)
            buf.setInt(o + 10 + 6 * i, 0xFF00FF00.toInt())
        }
        buf.setUByte(o + 26, 255u) // End.

        val model = parseNjModel(buf.cursor(), mutableMapOf())

        assertEquals(0x80402010.toInt(), assertNotNull(model.vertices[0]).color)

        val strip = model.meshes.single()
        assertTrue(strip.doubleSide)
        assertEquals(3, strip.textureId)
        assertTrue(strip.flipU)
        assertFalse(strip.flipV)
        assertFalse(strip.clampU)
        assertTrue(strip.clampV)
        assertEquals(0xFF00FF00.toInt(), strip.vertices[0].color)

        val diffuse = assertNotNull(strip.diffuse)
        assertEquals(0x10 / 255f, diffuse.r)
        assertEquals(0x20 / 255f, diffuse.g)
        assertEquals(0x30 / 255f, diffuse.b)
        assertEquals(1f, diffuse.a)
    }
}
