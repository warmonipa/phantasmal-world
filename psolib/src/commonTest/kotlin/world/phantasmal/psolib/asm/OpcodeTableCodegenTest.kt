package world.phantasmal.psolib.asm

import world.phantasmal.psolib.fileFormats.quest.Version
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpcodeTableCodegenTest {
    @Test
    fun opcodes_for_each_version_is_nonempty() {
        for (v in Version.entries) {
            val tables = opcodesFor(v)
            val count = tables.byCode.count { it != null } +
                    tables.byCodeF8.count { it != null } +
                    tables.byCodeF9.count { it != null }
            assertTrue(count > 0, "opcodesFor($v) is empty")
        }
    }

    @Test
    fun bb_v4_has_set_floor_handler() {
        val tables = opcodesFor(Version.BB_V4)
        val op = tables.byCode[0x95]
        assertNotNull(op)
        assertEquals("set_floor_handler", op.mnemonic)
    }

    @Test
    fun version_mask_is_subset_of_all_versions() {
        val allBits = Version.entries.fold(0) { acc, v -> acc or v.bit }
        for (op in ALL_OPCODES) {
            assertEquals(op.versionMask, op.versionMask and allBits,
                "${op.mnemonic} has bits outside the Version enum")
            assertTrue(op.versionMask != 0, "${op.mnemonic} has empty versionMask")
        }
    }
}
