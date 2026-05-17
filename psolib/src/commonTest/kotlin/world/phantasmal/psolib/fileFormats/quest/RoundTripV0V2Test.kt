package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.core.Success
import world.phantasmal.psolib.asm.InstructionSegment
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.test.LibTestSuite
import world.phantasmal.psolib.test.readFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoundTripV0V2Test : LibTestSuite {
    @Test fun dc_v1_round_trip() = roundTrip("/quests/q058/q058-d1-e.bin", "/quests/q058/q058-d1.dat")
    @Test fun dc_v2_round_trip() = roundTrip("/quests/q058/q058-dc-e.bin", "/quests/q058/q058-dc.dat")
    @Test fun pc_v2_round_trip() = roundTrip("/quests/q058/q058-pc-e.bin", "/quests/q058/q058-pc.dat")
    @Test fun gc_nte_round_trip() = roundTrip("/quests/q058/q058-gcn-e.bin", "/quests/q058/q058-gcn.dat")
    @Test fun gc_nte_jp_round_trip() = roundTripJp("/quests/q058/quest58_j_nte.bin", "/quests/q058/quest58_j_nte.dat")

    private fun roundTrip(binPath: String, datPath: String) = testAsync {
        val r1 = parseBinDatToQuestAutoDetect(
            readFile(binPath), readFile(datPath), lenient = false, shiftJis = false,
        )
        assertTrue(r1 is Success, "first parse failed: $r1")
        val quest1 = r1.value.quest

        val (rewrittenBin, rewrittenDat) = writeQuestToBinDat(quest1, quest1.version)

        val r2 = parseBinDatToQuestAutoDetect(
            rewrittenBin.cursor(), rewrittenDat.cursor(),
            lenient = false, shiftJis = false,
            version = quest1.version,
        )
        assertTrue(r2 is Success, "second parse failed: $r2")
        assertIrEquivalent(quest1, r2.value.quest)
    }

    // shiftJis=true variant for the JP quest fixture (existing one from earlier commit).
    private fun roundTripJp(binPath: String, datPath: String) = testAsync {
        val r1 = parseBinDatToQuestAutoDetect(
            readFile(binPath), readFile(datPath), lenient = false, shiftJis = true,
            version = Version.GC_NTE, // explicit since auto-detect can't distinguish from DC_V2.
        )
        assertTrue(r1 is Success, "first parse failed: $r1")
        val quest1 = r1.value.quest
        assertEquals(Version.GC_NTE, quest1.version)

        val (rewrittenBin, rewrittenDat) = writeQuestToBinDat(quest1, quest1.version)

        val r2 = parseBinDatToQuestAutoDetect(
            rewrittenBin.cursor(), rewrittenDat.cursor(),
            lenient = false, shiftJis = true,
            version = Version.GC_NTE,
        )
        assertTrue(r2 is Success, "second parse failed: $r2")
        assertEquals(Version.GC_NTE, r2.value.quest.version)
        assertIrEquivalent(quest1, r2.value.quest)
    }

    private fun assertIrEquivalent(a: Quest, b: Quest) {
        val ir1 = a.bytecodeIr.instructionSegments()
        val ir2 = b.bytecodeIr.instructionSegments()
        assertEquals(ir1.size, ir2.size,
            "segment count differs: ${a.version}->${b.version}")
        for (i in ir1.indices) {
            val s1 = ir1[i]; val s2 = ir2[i]
            assertEquals(s1.labels.toSet(), s2.labels.toSet(),
                "segment $i labels differ")
            assertEquals(s1.instructions.size, s2.instructions.size,
                "segment $i instruction count differs (labels=${s1.labels})")
            for ((j, pair) in s1.instructions.zip(s2.instructions).withIndex()) {
                val (insA, insB) = pair
                assertEquals(insA.opcode.mnemonic, insB.opcode.mnemonic,
                    "seg $i instr $j mnemonic")
                assertEquals(insA.args.size, insB.args.size,
                    "seg $i instr $j arg count (${insA.opcode.mnemonic})")
                for ((k, argPair) in insA.args.zip(insB.args).withIndex()) {
                    assertEquals(argPair.first, argPair.second,
                        "seg $i instr $j arg $k (${insA.opcode.mnemonic})")
                }
            }
        }
    }
}
