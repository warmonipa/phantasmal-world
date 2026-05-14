package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.core.Success
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.compression.prs.prsDecompress
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.test.LibTestSuite
import java.io.File
import kotlin.test.Test

/**
 * One-shot diagnostic: for a given quest file, find every `unknown_xx` instruction
 * in our parse and print the byte offset + surrounding bytes + segment labels.
 *
 * Pair with newserv disassembly at /tmp/<name>.dasm to compare.
 */
class _DiffNewserv : LibTestSuite {

    private fun inspect(binPath: String, datPath: String, shiftJis: Boolean = false) = testAsync {
        val binFile = File(binPath)
        val datFile = File(datPath)
        val r = parseBinDatToQuestAutoDetect(
            Buffer.fromByteArray(binFile.readBytes()).cursor(),
            Buffer.fromByteArray(datFile.readBytes()).cursor(),
            lenient = false, shiftJis = shiftJis,
        )
        require(r is Success) { "parse failed for $binPath: $r" }
        val quest = r.value.quest
        println("=== $binPath ===")
        println("detected version: ${quest.version}")

        // Need a way to map IR back to byte offsets. The segment.labels carry label IDs,
        // and bin.labelOffsets maps label ID → byte offset. Re-derive offsets here.
        // Files are PRS-compressed — decompress first.
        val rawBinBytes = binFile.readBytes()
        val decompressed = prsDecompress(Buffer.fromByteArray(rawBinBytes).cursor())
        require(decompressed is Success) { "decompress failed" }
        val bin = parseBin(decompressed.value, shiftJis)
        val rawBytecode = bin.bytecode.cursor().byteArray(bin.bytecode.size)

        fun byteOf(off: Int) = if (off in rawBytecode.indices) (rawBytecode[off].toInt() and 0xff) else -1
        fun dumpRange(start: Int, end: Int): String {
            val sb = StringBuilder()
            for (i in start until minOf(end, rawBytecode.size)) {
                sb.append("%02x ".format(byteOf(i)))
            }
            return sb.toString()
        }

        for (seg in quest.bytecodeIr.instructionSegments()) {
            // Find this segment's start offset via its first label
            val firstLabel = seg.labels.firstOrNull() ?: continue
            val segStartOff = bin.labelOffsets.getOrNull(firstLabel) ?: continue
            if (segStartOff < 0) continue

            // Walk through instructions in this segment; the parser doesn't store per-instruction offsets,
            // but we can re-walk the segment's bytes alongside the instruction list to align them.
            // Simpler: just report which segments contain `unknown_xx` and the byte range of the segment.
            val unknownInThisSeg = seg.instructions.filter { it.opcode.mnemonic.startsWith("unknown_") }
            if (unknownInThisSeg.isEmpty()) continue

            // Find segment end: next label offset after segStartOff, or end of bytecode.
            val nextOff = bin.labelOffsets.filter { it > segStartOff }.minOrNull() ?: rawBytecode.size
            val segLen = nextOff - segStartOff

            println()
            println("  [SEG labels=${seg.labels.take(3)}] start=0x%04x len=$segLen instr=${seg.instructions.size}".format(segStartOff))
            println("    bytes:   ${dumpRange(segStartOff, segStartOff + segLen)}")
            for ((idx, ins) in seg.instructions.withIndex()) {
                val flag = if (!ins.valid) " ***INVALID***" else ""
                val args = try { ins.args.toString().take(80) } catch (e: Exception) { "<err>" }
                println("    [$idx] ${ins.opcode.mnemonic}  args=$args$flag")
            }
        }
    }

    @Test fun d88204_gc_e() = inspect(
        "/Users/wangzhen/study/newserv/system/quests/challenge-ep2/d88204-gc-e.bin",
        "/Users/wangzhen/study/newserv/system/quests/challenge-ep2/d88204-gc.dat",
    )

    @Test fun q026_bb_j() = inspect(
        "/Users/wangzhen/study/newserv/system/quests/solo-story/q026-bb-j.bin",
        "/Users/wangzhen/study/newserv/system/quests/solo-story/q026-bb.dat",
        shiftJis = true,
    )

    @Test fun q072_pc_e() = inspect(
        "/Users/wangzhen/study/newserv/system/quests/download/q072-pc-e.bin",
        "/Users/wangzhen/study/newserv/system/quests/download/q072-pc.dat",
    )

    @Test fun q230_bb_e() = inspect(
        "/Users/wangzhen/study/newserv/system/quests/vr/q230-bb-e.bin",
        "/Users/wangzhen/study/newserv/system/quests/vr/q230-bb.dat",
    )

    @Test fun q137_dc_e() = inspect(
        "/Users/wangzhen/study/newserv/system/quests/retrieval/q137-dc-e.bin",
        "/Users/wangzhen/study/newserv/system/quests/retrieval/q137-dc.dat",
    )

    @Test fun q072_pc_k() = inspect(
        "/Users/wangzhen/study/newserv/system/quests/download/q072-pc-k.bin",
        "/Users/wangzhen/study/newserv/system/quests/download/q072-pc.dat",
    )
}
