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
            readFile("/quests/towards_the_future.bin"),
            readFile("/quests/towards_the_future.dat"),
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
            readFile("/quests/towards_the_future.bin"),
            readFile("/quests/towards_the_future.dat"),
            lenient = false,
        )
        val lenientRun = parseBinDatToQuestAutoDetect(
            readFile("/quests/towards_the_future.bin"),
            readFile("/quests/towards_the_future.dat"),
            lenient = true,
        )
        assertTrue(strict is Success)
        assertTrue(lenientRun is Success)
        // Lenient must NOT add the fallback-warning problem for a clean quest.
        assertTrue(lenientRun.problems.none { it.message?.contains("falling back to lenient") == true },
            "lenient=true should not trigger the strict-fallback path for a clean quest")
    }

    @Test
    fun auto_detect_dc_v2_quest58() = testAsync {
        val r = parseBinDatToQuestAutoDetect(
            readFile("/quests/q058-dc-e.bin"), readFile("/quests/q058-dc.dat"),
            lenient = false, shiftJis = false,
        )
        assertTrue(r is Success, "$r")
        assertEquals(Version.DC_V2, r.value.quest.version)
        assertNoInvalid(r.value.quest)
    }

    @Test
    fun auto_detect_pc_v2_quest58() = testAsync {
        val r = parseBinDatToQuestAutoDetect(
            readFile("/quests/q058-pc-e.bin"), readFile("/quests/q058-pc.dat"),
            lenient = false, shiftJis = false,
        )
        assertTrue(r is Success, "$r")
        assertEquals(Version.PC_V2, r.value.quest.version)
        assertNoInvalid(r.value.quest)
    }

    @Test
    fun auto_detect_dc_v1_quest58_resolves_to_v0_v2_dialect() = testAsync {
        // DC_V1 bytes are V0_V2 dialect but indistinguishable from DC_V2 from bytes alone.
        // Auto-detect picks the bin.format default (DC_V2). Strongest byte-level guarantee
        // is dialect == V0_V2.
        val r = parseBinDatToQuestAutoDetect(
            readFile("/quests/q058-d1-e.bin"), readFile("/quests/q058-d1.dat"),
            lenient = false, shiftJis = false,
        )
        assertTrue(r is Success, "$r")
        assertEquals(Dialect.V0_V2, r.value.quest.version.dialect)
        assertNoInvalid(r.value.quest)
    }

    @Test
    fun auto_detect_gc_nte_en_quest58_resolves_to_v0_v2_dialect() = testAsync {
        // GC_NTE bytes are byte-identical to DC_V2 (newserv symlinks them). Same situation as DC_V1.
        val r = parseBinDatToQuestAutoDetect(
            readFile("/quests/q058-gcn-e.bin"), readFile("/quests/q058-gcn.dat"),
            lenient = false, shiftJis = false,
        )
        assertTrue(r is Success, "$r")
        assertEquals(Dialect.V0_V2, r.value.quest.version.dialect)
        assertNoInvalid(r.value.quest)
    }

    @Test
    fun explicit_version_gc_nte_en_quest58() = testAsync {
        // With explicit version, the GC_NTE code path strict-parses cleanly.
        val r = parseBinDatToQuestAutoDetect(
            readFile("/quests/q058-gcn-e.bin"), readFile("/quests/q058-gcn.dat"),
            lenient = false, shiftJis = false,
            version = Version.GC_NTE,
        )
        assertTrue(r is Success, "$r")
        assertEquals(Version.GC_NTE, r.value.quest.version)
        assertNoInvalid(r.value.quest)
    }

    private fun assertNoInvalid(quest: Quest) {
        val invalid = quest.bytecodeIr.instructionSegments()
            .sumOf { seg -> seg.instructions.count { !it.valid } }
        assertEquals(0, invalid, "expected zero invalid instructions in ${quest.version} quest")
    }
}
