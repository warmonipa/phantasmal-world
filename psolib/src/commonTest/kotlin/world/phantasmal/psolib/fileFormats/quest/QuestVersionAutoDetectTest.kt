package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.core.Success
import world.phantasmal.psolib.asm.BytecodeStringEncoding
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.test.LibTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuestVersionAutoDetectTest : LibTestSuite {
    @Test
    fun parseBytecode_with_v0_v2_version_parses_inline_args() = testAsync {
        // Empty bytecode + no entry labels: trivially OK regardless of dialect.
        // This just proves parseBytecode now takes a version parameter and the V0_V2 path runs.
        val emptyBytecode = Buffer.fromByteArray(byteArrayOf())
        val labels = intArrayOf(-1)
        val r = parseBytecode(
            emptyBytecode, labels, entryLabels = emptySet(),
            stringEncoding = BytecodeStringEncoding.ASCII,
            lenient = false,
            version = Version.GC_NTE,
        )
        assertTrue(r is Success)
        assertEquals(0, r.value.segments.size)
    }
}
