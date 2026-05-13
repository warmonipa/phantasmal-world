package world.phantasmal.psolib.fileFormats.quest

import kotlin.test.Test
import kotlin.test.assertEquals

class VersionTest {
    @Test
    fun v0_v2_versions_have_v0_v2_dialect() {
        assertEquals(Dialect.V0_V2, Version.DC_NTE.dialect)
        assertEquals(Dialect.V0_V2, Version.DC_V1.dialect)
        assertEquals(Dialect.V0_V2, Version.DC_V2.dialect)
        assertEquals(Dialect.V0_V2, Version.PC_NTE.dialect)
        assertEquals(Dialect.V0_V2, Version.PC_V2.dialect)
        assertEquals(Dialect.V0_V2, Version.GC_NTE.dialect)
    }

    @Test
    fun v3_v4_versions_have_v3_v4_dialect() {
        assertEquals(Dialect.V3_V4, Version.GC_V3.dialect)
        assertEquals(Dialect.V3_V4, Version.BB_V4.dialect)
    }

    @Test
    fun version_bits_are_unique_powers_of_two() {
        val bits = Version.entries.map { it.bit }
        assertEquals(Version.entries.size, bits.toSet().size)
        bits.forEach { b -> assertEquals(0, b and (b - 1), "bit $b is not a power of two") }
    }
}
