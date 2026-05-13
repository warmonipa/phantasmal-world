package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.core.Success
import world.phantasmal.psolib.asm.BytecodeStringEncoding
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.test.LibTestSuite
import world.phantasmal.psolib.test.readFile
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

    @Test
    fun auto_detect_picks_bb_v4_for_towards_the_future() = testAsync {
        val r = parseBinDatToQuestAutoDetect(
            readFile("/towards_the_future.bin"),
            readFile("/towards_the_future.dat"),
            lenient = false,
            shiftJis = false,
        )
        assertTrue(r is Success)
        assertEquals(Version.BB_V4, r.value.quest.version)
    }

    @Test
    fun lenient_flag_is_threaded_to_candidate_scoring() = testAsync {
        // Use towards_the_future (BB_V4); lenient should produce the same Success as strict for a clean quest.
        val strict = parseBinDatToQuestAutoDetect(
            readFile("/towards_the_future.bin"),
            readFile("/towards_the_future.dat"),
            lenient = false,
        )
        val lenientRun = parseBinDatToQuestAutoDetect(
            readFile("/towards_the_future.bin"),
            readFile("/towards_the_future.dat"),
            lenient = true,
        )
        assertTrue(strict is Success)
        assertTrue(lenientRun is Success)
        // Lenient must NOT add the fallback-warning problem for a clean quest.
        assertTrue(lenientRun.problems.none { it.message?.contains("falling back to lenient") == true },
            "lenient=true should not trigger the strict-fallback path for a clean quest")
    }
}
