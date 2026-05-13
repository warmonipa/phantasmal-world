package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.core.Failure
import world.phantasmal.core.Success
import world.phantasmal.psolib.asm.DataSegment
import world.phantasmal.psolib.asm.InstructionSegment
import world.phantasmal.psolib.asm.StringSegment
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.test.LibTestSuite
import java.io.File
import kotlin.test.Test

class InspectQuest58 : LibTestSuite {
    private val questDir = File(
        System.getenv("PSO_QUEST_DIR")
            ?: "/Users/wangzhen/study/pso-quest-master/quests"
    )

    @Test
    fun decompressNewservGcJ() = testAsync {
        val src = File("/Users/wangzhen/study/newserv/system/quests/retrieval/q058-gc-j.bin")
        if (!src.exists()) { println("Skipping: $src not found"); return@testAsync }
        val buf = Buffer.fromByteArray(src.readBytes())
        val r = world.phantasmal.psolib.compression.prs.prsDecompress(buf.cursor())
        if (r is Success) {
            val out = r.value
            val bytes = out.byteArray(out.size)
            val outFile = File("/tmp/q058-gc-j.dec")
            outFile.writeBytes(bytes)
            println("Decompressed q058-gc-j.bin: ${bytes.size} bytes -> /tmp/q058-gc-j.dec")
        } else {
            println("Decompress failed: $r")
        }
    }

    @Test
    fun parseBinStrict() = testAsync {
        val binFile = File(questDir, "quest58_j.bin")
        val datFile = File(questDir, "quest58_j.dat")
        if (!binFile.exists()) { println("Skipping: $binFile not found"); return@testAsync }

        val binBuf = Buffer.fromByteArray(binFile.readBytes())
        val datBuf = Buffer.fromByteArray(datFile.readBytes())

        println("=== parseBinDatToQuestAutoDetect lenient=false ===")
        val r = parseBinDatToQuestAutoDetect(
            binBuf.cursor(), datBuf.cursor(),
            lenient = false, shiftJis = true,
        )
        when (r) {
            is Success -> {
                println("OK (compressed=${r.value.compressed}) – ${r.problems.size} problems:")
                for (p in r.problems) {
                    println("  [${p.severity}] ${p.uiMessage}  ||  ${p.message}")
                    p.cause?.let { it.printStackTrace() }
                }
            }
            is Failure -> {
                println("FAILED with ${r.problems.size} problems:")
                for (p in r.problems) {
                    println("  [${p.severity}] ${p.uiMessage}  ||  ${p.message}")
                    p.cause?.let { it.printStackTrace() }
                }
            }
        }

        println()
        println("=== bytecode dump near 0x241 (577) ===")
        val bin = parseBin(Buffer.fromByteArray(binFile.readBytes()).cursor(), shiftJis = true)
        val bcSize = bin.bytecode.size
        val raw = bin.bytecode.cursor().byteArray(bcSize)
        fun byteOf(off: Int) = raw[off].toInt() and 0xff
        fun dumpRange(start: Int, end: Int) {
            var i = start
            while (i < end) {
                val rowEnd = minOf(i + 16, end)
                val hex = (i until rowEnd).joinToString(" ") { "%02x".format(byteOf(it)) }
                println("  ${"0x%04x".format(i)}: $hex")
                i = rowEnd
            }
        }
        println("All used labels with offsets (sorted by offset):")
        val labelsByOff = bin.labelOffsets.withIndex().filter { it.value != -1 }.sortedBy { it.value }
        for ((idx, off) in labelsByOff) {
            println("  L$idx -> 0x%04x".format(off))
        }
        println()
        println("dump L150 area 0x9a..0xc0:"); dumpRange(0x9a, 0xc0)
        println("dump L151 area 0x241..0x270:"); dumpRange(0x241, 0x270)
        println("dump L152 area 0x248..0x260:"); dumpRange(0x248, 0x260)
        println("dump L161 area 0x24f..0x270:"); dumpRange(0x24f, 0x270)
        println("dump 0x95..0x9a:"); dumpRange(0x95, 0x9a)

        println()
        println("=== bytecode tail (last 64 bytes) ===")
        dumpRange(maxOf(0, bcSize - 64), bcSize)

        // Search for opcode 0x2a (switch_call) and 0x29 (switch_jmp) with large arg count.
        println("=== scan for 'opcode 2a/29' across whole bytecode ===")
        for (i in 0 until bcSize - 1) {
            if (byteOf(i) == 0x2a || byteOf(i) == 0x29) {
                val cnt = byteOf(i + 1)
                val need = 2 + cnt * 2
                val have = bcSize - i
                if (cnt > 16 || need > have) {
                    println("  hit at 0x%04x: op=%02x count=%d need=%d have=%d".format(i, byteOf(i), cnt, need, have))
                }
            }
        }

        // Decode L150 onward step by step, conservatively (only opcodes I know length of).
        println()
        println("=== quick walk from L150 0x9a, single-byte opcodes ===")
        // Minimal opcode-length map for plain (non-F8/F9) single-byte opcodes.
        // Just print bytes; let the human eye spot.
        var p = 0x9a
        while (p < 0x250 && p < bcSize) {
            val b = byteOf(p)
            val name = when (b) {
                0x00 -> "nop"
                0x01 -> "ret"
                0x02 -> "sync"
                0x40 -> "va_start"
                0xa1 -> "next_frame"
                else -> "?op_%02x".format(b)
            }
            print("0x%04x:%02x ".format(p, b))
            if (b == 0x01 || b == 0x40) println("  <-- $name")
            p++
        }
        println()

        println()
        println("=== parseBinDatToQuestAutoDetect lenient=true (control) ===")
        val r2 = parseBinDatToQuestAutoDetect(
            Buffer.fromByteArray(binFile.readBytes()).cursor(),
            Buffer.fromByteArray(datFile.readBytes()).cursor(),
            lenient = true, shiftJis = true,
        )
        when (r2) {
            is Success -> {
                val q = r2.value.quest
                println("OK (compressed=${r2.value.compressed}) – ${r2.problems.size} problems, ${q.objects.size} objects, ${q.npcs.size} NPCs")
                for (p in r2.problems) {
                    println("  [${p.severity}] ${p.uiMessage}  ||  ${p.message}")
                }

                println()
                println("=== entry points from objects/npcs ===")
                val suspect = setOf(310, 311, 320, 321)
                for ((i, o) in q.objects.withIndex()) {
                    val s1 = o.scriptLabel; val s2 = o.scriptLabel2
                    if (s1 in suspect || s2 in suspect) {
                        println("  obj#$i type=${o.type} floor=${o.areaId} scriptLabel=$s1 scriptLabel2=$s2")
                    }
                }
                for ((i, n) in q.npcs.withIndex()) {
                    if (n.scriptLabel in suspect) {
                        println("  npc#$i type=${n.type} floor=${n.areaId} scriptLabel=${n.scriptLabel}")
                    }
                }
                println("All scriptLabels seen on objects:")
                val objLabels = q.objects.flatMap { listOfNotNull(it.scriptLabel, it.scriptLabel2) }.toSortedSet()
                println("  $objLabels")
                val npcLabels = q.npcs.map { it.scriptLabel }.toSortedSet()
                println("All scriptLabels seen on npcs:")
                println("  $npcLabels")

                println()
                println("=== IR segments dump ===")
                // Walk every segment in offset order; identify invalid instructions.
                for (seg in q.bytecodeIr.segments) {
                    val kind = when (seg) {
                        is InstructionSegment -> "INSTR"
                        is DataSegment -> "DATA "
                        is StringSegment -> "STR  "
                    }
                    val labels = seg.labels.joinToString(",")
                    println("[$kind] labels={$labels}")
                    val showAll = seg is InstructionSegment && (310 in seg.labels || 320 in seg.labels)
                    if (seg is InstructionSegment) {
                        for ((j, instr) in seg.instructions.withIndex()) {
                            val flag = if (instr.valid) "" else "  ***INVALID***"
                            val argsStr = try {
                                if (instr.opcode.mnemonic == "switch_call" || instr.opcode.mnemonic == "switch_jmp") {
                                    "<${instr.args.size} labels>"
                                } else instr.args.toString()
                            } catch (e: Exception) { "<err: ${e.message}>" }
                            if (showAll || !instr.valid || instr.opcode.mnemonic.startsWith("switch_")) {
                                println("    $j: ${instr.opcode.mnemonic}  args=$argsStr$flag")
                            }
                            if (!instr.valid) break
                        }
                    }
                }
            }
            is Failure -> {
                println("FAILED with ${r2.problems.size} problems:")
                for (p in r2.problems) {
                    println("  [${p.severity}] ${p.uiMessage}  ||  ${p.message}")
                }
            }
        }
    }
}
