package world.phantasmal.psolib.fileFormats.ninja

import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.cursor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class XjTests {
    @Test
    fun parses_vertex_colors_and_strip_tables() {
        val buf = Buffer.withSize(200).zero()
        // Header.
        buf.setInt(4, 44) // Vertex info table offset.
        buf.setInt(8, 1) // Vertex info count.
        buf.setInt(12, 96) // Opaque strip table offset.
        buf.setInt(16, 1)
        buf.setInt(20, 116) // Transparent strip table offset.
        buf.setInt(24, 1)
        // Vertex info table: type 7 (position, normal, color, uv).
        buf.setShort(44, 7)
        buf.setInt(48, 60) // Vertex table offset.
        buf.setInt(52, 36) // Vertex size.
        buf.setInt(56, 1) // Vertex count.
        // Vertex.
        buf.setFloat(60, 1f)
        buf.setFloat(76, 1f) // Normal y.
        buf.setInt(84, 0x80402010.toInt()) // ARGB color.
        buf.setFloat(88, .5f) // U.
        buf.setFloat(92, .25f) // V.
        // Strip tables, each referencing the same empty material table and index list.
        for (offset in listOf(96, 116)) {
            buf.setInt(offset, 136) // Material table offset.
            buf.setInt(offset + 4, 0) // Material count.
            buf.setInt(offset + 8, 136) // Index list offset.
            buf.setInt(offset + 12, 0) // Index count.
        }

        val model = parseXjModel(buf.cursor())

        val vertex = model.vertices.single()
        assertEquals(0x80402010.toInt(), vertex.color)
        assertNotNull(vertex.normal)
        assertEquals(.5f, vertex.uv?.x)
        assertEquals(.25f, vertex.uv?.y)

        assertEquals(2, model.meshes.size)
        assertFalse(model.meshes[0].transparent)
        assertTrue(model.meshes[1].transparent)
    }
}
