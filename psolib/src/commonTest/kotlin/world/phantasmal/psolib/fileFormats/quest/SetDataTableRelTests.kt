package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.cursor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SetDataTableRelTests {

    /**
     * Build a synthetic minimal REL file matching newserv's documented format:
     * 1 area × 1 layout × 2 entities, with each entry holding 3 strings.
     *
     * Layout in the synthetic buffer:
     *   0x00..0x05  string "obj0\0"            (5 bytes)
     *   0x06..0x0B  string "obj1\0"            (5 bytes)
     *   0x0C..0x11  string "ene0\0"            (5 bytes)
     *   0x12..0x17  string "ene1\0"            (5 bytes)
     *   0x18..0x1D  string "setup\0"           (6 bytes)
     *   0x1E..0x1F  pad
     *   0x20        entities table (2 × 12 = 24 bytes)  → (ptrObj, ptrEnemy, ptrSetup)
     *   0x38        layout table (1 × 8 = 8 bytes)      → (entitiesOff=0x20, count=2)
     *   0x40        root table (1 × 8 = 8 bytes)        → (layoutOff=0x38, count=1)
     *   0x48        u32 root_table_offset = 0x40
     *   0x4C..0x6B  footer (32 bytes), root_offset @ +0x10 = 0x48
     */
    @Test
    fun parse_synthetic_one_area_two_entities() {
        val buf = Buffer.withSize(0x6C, Endianness.Big)

        fun writeStr(at: Int, s: String): Int {
            var p = at
            for (c in s) buf.setUByte(p++, c.code.toUByte())
            buf.setUByte(p++, 0u)
            return p
        }
        // Strings
        writeStr(0x00, "obj0")
        writeStr(0x06, "obj1")
        writeStr(0x0C, "ene0")
        writeStr(0x12, "ene1")
        writeStr(0x18, "setup")

        // Entities table @ 0x20 (2 entries × 12 bytes)
        var p = 0x20
        // entry 0
        buf.setUInt(p, 0x00u); buf.setUInt(p + 4, 0x0Cu); buf.setUInt(p + 8, 0x18u)
        p += 12
        // entry 1
        buf.setUInt(p, 0x06u); buf.setUInt(p + 4, 0x12u); buf.setUInt(p + 8, 0x18u)
        p += 12
        // p should now be 0x38

        // Layout table @ 0x38 (1 entry × 8 bytes): (entitiesOff=0x20, count=2)
        buf.setUInt(0x38, 0x20u); buf.setUInt(0x3C, 2u)

        // Root table @ 0x40 (1 entry × 8 bytes): (layoutOff=0x38, count=1)
        buf.setUInt(0x40, 0x38u); buf.setUInt(0x44, 1u)

        // u32 root_table_offset @ 0x48 = 0x40
        buf.setUInt(0x48, 0x40u)

        // Footer @ 0x4C (32 bytes); root_offset_ptr field is at +0x10
        // 0x4C + 0x10 = 0x5C
        buf.setUInt(0x5C, 0x48u)

        val rel = parseSetDataTableRel(buf.cursor())
        assertEquals(1, rel.areas.size)
        assertEquals(1, rel.areas[0].size)
        assertEquals(2, rel.areas[0][0].size)

        val e0 = rel.get(0, 0, 0)!!
        assertEquals("obj0", e0.objectBasename)
        assertEquals("ene0", e0.enemyBasename)
        assertEquals("setup", e0.areaSetupBasename)

        val e1 = rel.get(0, 0, 1)!!
        assertEquals("obj1", e1.objectBasename)
        assertEquals("ene1", e1.enemyBasename)
        assertEquals("setup", e1.areaSetupBasename)

        // Out-of-range lookups
        assertEquals(null, rel.get(0, 0, 2))
        assertEquals(null, rel.get(0, 1, 0))
        assertEquals(null, rel.get(1, 0, 0))
    }

    @Test
    fun rejects_little_endian_cursor() {
        val buf = Buffer.withSize(0x20, Endianness.Little)
        assertFailsWith<IllegalArgumentException> {
            parseSetDataTableRel(buf.cursor())
        }
    }

    @Test
    fun rejects_too_small_file() {
        val buf = Buffer.withSize(0x10, Endianness.Big)
        assertFailsWith<IllegalArgumentException> {
            parseSetDataTableRel(buf.cursor())
        }
    }
}
