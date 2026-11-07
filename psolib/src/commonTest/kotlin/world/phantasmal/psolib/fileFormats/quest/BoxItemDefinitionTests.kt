package world.phantasmal.psolib.fileFormats.quest

import kotlin.test.*

class BoxItemDefinitionTests {
    @Test
    fun challenge_stage_2_exact_items_decode_to_inventory_codes() {
        // Exact words extracted from chl02.dat, floor 3/section 30 and floor 6/section 5.
        val saber = BoxItemDefinition(0, 0x0A000000, 0)
        assertEquals(0x000100, saber.itemCode)
        assertEquals(10, saber.read(BoxItemField.Grind))
        assertEquals(0x010200, BoxItemDefinition(0x01010000, 0, 0).itemCode)
        assertEquals(0x010202, BoxItemDefinition(0x01010200, 0, 0).itemCode)
        assertEquals(0x010201, BoxItemDefinition(0x01010100, 0, 0).itemCode)
        assertEquals(0x020100, BoxItemDefinition(0x0201FFFF, -1, -1).itemCode)
        assertEquals(0x040000, BoxItemDefinition(0x0401FFFF, 0x007B0000, -1).itemCode)
    }

    @Test
    fun every_category_uses_its_own_code_layout() {
        val golden = listOf(
            0x000600 to 0x00050000, // handgun: category is offset by one
            0x010102 to 0x01000200, // armor
            0x010302 to 0x01020200, // unit
            0x020100 to 0x02010000, // Mag: no category offset
            0x030001 to 0x03000100, // tool
            0x030212 to 0x03020012, // Megid: technique number in the low byte
            0x040000 to 0x04000000,
        )
        for ((code, packed) in golden) {
            assertEquals(code, BoxItemDefinition(packed, 0, 0).itemCode)
            assertEquals(packed, BoxItemDefinition(0, 0, 0).withItemCode(code).data1)
        }
    }

    @Test
    fun edits_preserve_unrelated_and_ignored_bits() {
        val original = BoxItemDefinition(0xA005028B.toInt(), 0x0AAB01EC, 0x051E0228)
        val changed = original.withField(BoxItemField.Grind, 15)
        assertEquals(original.data1, changed.data1)
        assertEquals(0x0FAB01EC, changed.data2)
        assertEquals(original.data3, changed.data3)
        assertEquals(-20, original.read(BoxItemField.Percent1))
        assertEquals(0x0AAB01E2, original.withField(BoxItemField.Percent1, -30).data2)
        assertEquals(0xA005038B.toInt(), original.withItemCode(0x000603).data1)
    }

    @Test
    fun technique_levels_and_meseta_use_human_units() {
        val megid = BoxItemDefinition(0x03021D12, 0, 0)
        assertEquals(0x030212, megid.itemCode)
        assertEquals(30, megid.read(BoxItemField.TechniqueLevel))
        assertEquals(0x03020412, megid.withField(BoxItemField.TechniqueLevel, 5).data1)
        val anti = megid.withItemCode(0x030210)
        assertEquals(1, anti.read(BoxItemField.TechniqueLevel))
        assertFailsWith<IllegalArgumentException> { anti.withField(BoxItemField.TechniqueLevel, 8) }
        val meseta = BoxItemDefinition(0x04000000, 0xFFFF1234.toInt(), 99)
        assertEquals(655350, meseta.read(BoxItemField.Meseta))
        assertEquals(0x007B1234, meseta.withField(BoxItemField.Meseta, 1230).data2)
        assertFailsWith<IllegalArgumentException> { meseta.withField(BoxItemField.Meseta, 1231) }
    }

    @Test
    fun category_changes_clear_fields_with_different_meanings() {
        assertEquals(BoxItemDefinition(0x01000200, 0, 0),
            BoxItemDefinition(0, 0x0A000000, -1).withItemCode(0x010102))
        assertFalse(BoxItemField.Slots in BoxItemDefinition(0x01010000, 0, 0).fields)
        assertTrue(BoxItemDefinition(0x02010000, 0, 0).fields.isEmpty())
        assertFalse(BoxItemField.Attribute1 in BoxItemDefinition(0x006F0000, 0, 0).fields)
    }
}
