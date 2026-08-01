package world.phantasmal.psolib.fileFormats.fog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.test.LibTestSuite

class FogEntryTests : LibTestSuite {
    @Test
    fun parses_client_layout() {
        val data = Buffer.withSize(FOG_ENTRY_SIZE, Endianness.Little).apply {
            setInt(0x00, 2)
            setInt(0x04, 0x00FF2020)
            setFloat(0x08, 9000f)
            setFloat(0x0C, -100f)
            setFloat(0x10, 0.01f)
            setFloat(0x18, 4f)
            setFloat(0x20, 5000f)
            setFloat(0x28, 2000f)
            setFloat(0x30, 640f)
            setUByte(0x3A, 10u)
            setUByte(0x3C, 20u)
            setUByte(0x3E, 30u)
        }

        val entry = parseFogEntry(data.cursor())

        assertEquals(2, entry.type)
        assertEquals(0x00FF2020, entry.color)
        assertEquals(9000f, entry.end)
        assertEquals(-100f, entry.start)
        assertEquals(0.01f, entry.density, absoluteTolerance = 0.000001f)
        assertEquals(4f, entry.animationSpeed)
        assertEquals(5000f, entry.endPulseDistance)
        assertEquals(2000f, entry.startPulseDistance)
        assertEquals(640f, entry.transitionDistance)
        assertEquals(10, entry.endPulsePhase)
        assertEquals(20, entry.startPulsePhase)
        assertEquals(30, entry.transitionPulseDistance)
    }

    @Test
    fun rejects_wrong_table_size() {
        val data = Buffer.withSize(FOG_ENTRY_COUNT * FOG_ENTRY_SIZE - 1)
        assertFailsWith<IllegalArgumentException> { parseFogEntryList(data.cursor()) }
    }
}
