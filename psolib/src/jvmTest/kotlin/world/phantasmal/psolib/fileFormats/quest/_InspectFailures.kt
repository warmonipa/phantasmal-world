package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.core.Failure
import world.phantasmal.core.Success
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.compression.prs.prsDecompress
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.test.LibTestSuite
import java.io.File
import kotlin.test.Test

class _InspectFailures : LibTestSuite {

    @Test
    fun q072_pc_e() = testAsync {
        val bin = File("/Users/wangzhen/study/newserv/system/quests/download/q072-pc-e.bin")
        val dat = File("/Users/wangzhen/study/newserv/system/quests/download/q072-pc.dat")
        println("bin exists=${bin.exists()} size=${bin.length()}")
        println("dat exists=${dat.exists()} size=${dat.length()}")
        val r = parseBinDatToQuestAutoDetect(
            Buffer.fromByteArray(bin.readBytes()).cursor(),
            Buffer.fromByteArray(dat.readBytes()).cursor(),
            lenient = false, shiftJis = false,
        )
        println("result class: ${r::class.simpleName}")
        println("problems count: ${r.problems.size}")
        for (p in r.problems) {
            println(" [${p.severity}] ${p.uiMessage}")
            println("   message: ${p.message}")
            p.cause?.let { println("   cause: ${it.javaClass.name}: ${it.message}") }
        }
        if (r is Success) {
            println("quest version: ${r.value.quest.version}")
            println("segments: ${r.value.quest.bytecodeIr.segments.size}")
        }
    }

    @Test
    fun q026_bb_e() = testAsync {
        val bin = File("/Users/wangzhen/study/newserv/system/quests/solo-story/q026-bb-e.bin")
        val dat = File("/Users/wangzhen/study/newserv/system/quests/solo-story/q026-bb.dat")
        val r = parseBinDatToQuestAutoDetect(
            Buffer.fromByteArray(bin.readBytes()).cursor(),
            Buffer.fromByteArray(dat.readBytes()).cursor(),
            lenient = false, shiftJis = false,
        )
        if (r is Success) {
            val q = r.value.quest
            println("version: ${q.version}")
            for (seg in q.bytecodeIr.instructionSegments()) {
                val unknownInstructions = seg.instructions.filter { it.opcode.mnemonic.startsWith("unknown_") }
                if (unknownInstructions.isNotEmpty()) {
                    println("Segment labels=${seg.labels}: ${unknownInstructions.size} unknown opcodes:")
                    for (inst in unknownInstructions) {
                        println("  ${inst.opcode.mnemonic} (${inst.opcode.code})")
                    }
                }
            }
        } else {
            println("Parse failed: ${r.problems}")
        }
    }

    @Test
    fun q312_bb_e() = testAsync {
        val bin = File("/Users/wangzhen/study/newserv/system/quests/events/q312-bb-e.bin")
        val dat = File("/Users/wangzhen/study/newserv/system/quests/events/q312-bb.dat")
        val r = parseBinDatToQuestAutoDetect(
            Buffer.fromByteArray(bin.readBytes()).cursor(),
            Buffer.fromByteArray(dat.readBytes()).cursor(),
            lenient = false, shiftJis = false,
        )
        if (r is Success) {
            val q = r.value.quest
            println("version: ${q.version}")
            for (seg in q.bytecodeIr.instructionSegments()) {
                val unknownInstructions = seg.instructions.filter { it.opcode.mnemonic.startsWith("unknown_") }
                if (unknownInstructions.isNotEmpty()) {
                    println("Segment labels=${seg.labels}: ${unknownInstructions.size} unknown opcodes:")
                    for (inst in unknownInstructions) {
                        println("  ${inst.opcode.mnemonic} (${inst.opcode.code})")
                    }
                }
            }
            for (p in r.problems) {
                println("[${p.severity}] ${p.uiMessage}")
                println("   ${p.message}")
            }
        } else {
            println("Parse failed: ${r.problems}")
        }
    }

    @Test
    fun q230_bb_e() = testAsync {
        val bin = File("/Users/wangzhen/study/newserv/system/quests/vr/q230-bb-e.bin")
        val dat = File("/Users/wangzhen/study/newserv/system/quests/vr/q230-bb.dat")
        val r = parseBinDatToQuestAutoDetect(
            Buffer.fromByteArray(bin.readBytes()).cursor(),
            Buffer.fromByteArray(dat.readBytes()).cursor(),
            lenient = false, shiftJis = false,
        )
        if (r is Success) {
            val q = r.value.quest
            println("version: ${q.version}")
            for (seg in q.bytecodeIr.instructionSegments()) {
                val unknownInstructions = seg.instructions.filter { it.opcode.mnemonic.startsWith("unknown_") }
                if (unknownInstructions.isNotEmpty()) {
                    println("Segment labels=${seg.labels}: ${unknownInstructions.size} unknown opcodes:")
                    for (inst in unknownInstructions) {
                        println("  ${inst.opcode.mnemonic} (${inst.opcode.code})")
                    }
                }
            }
        } else {
            println("Parse failed: ${r.problems}")
        }
    }

    @Test
    fun q230_vr_warnings() = testAsync {
        for (variant in listOf("q230-gc", "q230-xb")) {
            val bin = File("/Users/wangzhen/study/newserv/system/quests/vr/$variant-e.bin")
            val dat = File("/Users/wangzhen/study/newserv/system/quests/vr/$variant.dat")
            if (!bin.exists() || !dat.exists()) {
                println("$variant: skip (missing fixture)")
                continue
            }
            val r = parseBinDatToQuestAutoDetect(
                Buffer.fromByteArray(bin.readBytes()).cursor(),
                Buffer.fromByteArray(dat.readBytes()).cursor(),
                lenient = false, shiftJis = false,
            )
            println("=== $variant ===")
            println("class: ${r::class.simpleName}")
            for (p in r.problems) {
                println(" [${p.severity}] ${p.uiMessage}")
                println("   msg: ${p.message}")
            }
        }
    }

    @Test
    fun q072_pc_dat_analysis() = testAsync {
        val datFile = File("/Users/wangzhen/study/newserv/system/quests/download/q072-pc.dat")
        val r = prsDecompress(
            Buffer.fromByteArray(datFile.readBytes()).cursor()
        )
        if (r is Success) {
            val decompressed = r.value
            val bytes = decompressed.byteArray(decompressed.size)
            println("decompressed size: ${bytes.size}")
            File("/tmp/q072-pc.dat.dec").writeBytes(bytes)
        }
    }

    @Test
    fun q026_bb_dat_analysis() = testAsync {
        for (datName in listOf("q026-bb.dat")) {
            val datFile = File("/Users/wangzhen/study/newserv/system/quests/solo-story/$datName")
            val r = prsDecompress(Buffer.fromByteArray(datFile.readBytes()).cursor())
            if (r is Success) {
                val bytes = r.value.byteArray(r.value.size)
                println("$datName decompressed: ${bytes.size} bytes")
                File("/tmp/$datName.dec").writeBytes(bytes)
            }
        }
    }

    @Test
    fun q312_bb_dat_analysis() = testAsync {
        for (datName in listOf("q312-bb.dat")) {
            val datFile = File("/Users/wangzhen/study/newserv/system/quests/events/$datName")
            val r = prsDecompress(Buffer.fromByteArray(datFile.readBytes()).cursor())
            if (r is Success) {
                val bytes = r.value.byteArray(r.value.size)
                println("$datName decompressed: ${bytes.size} bytes")
                File("/tmp/$datName.dec").writeBytes(bytes)
            }
        }
    }

    @Test
    fun q230_bb_dat_analysis() = testAsync {
        for (datName in listOf("q230-bb.dat")) {
            val datFile = File("/Users/wangzhen/study/newserv/system/quests/vr/$datName")
            val r = prsDecompress(Buffer.fromByteArray(datFile.readBytes()).cursor())
            if (r is Success) {
                val bytes = r.value.byteArray(r.value.size)
                println("$datName decompressed: ${bytes.size} bytes")
                File("/tmp/$datName.dec").writeBytes(bytes)
            }
        }
    }


    @Test
    fun warn_unknown_opcode_distribution() = testAsync {
        // Walk newserv quests, count which unknown_xx opcodes appear.
        val root = File("/Users/wangzhen/study/newserv/system/quests")
        val bins = root.walkTopDown().filter { it.isFile && it.name.endsWith(".bin") }.toList()
        val unknownByCode = mutableMapOf<String, Int>()
        val sampleFile = mutableMapOf<String, String>()

        for (binFile in bins) {
            val name = binFile.name.removeSuffix(".bin")
            val lastDash = name.lastIndexOf('-')
            val stem = if (lastDash > 0 && name.length - lastDash <= 3) {
                val maybeLang = name.substring(lastDash + 1)
                if (maybeLang.length in 1..2 && maybeLang.all { it.isLetterOrDigit() }) name.substring(0, lastDash) else name
            } else name
            val datFile = File(binFile.parentFile, "$stem.dat")
            if (!datFile.exists()) continue

            try {
                val r = parseBinDatToQuestAutoDetect(
                    Buffer.fromByteArray(binFile.readBytes()).cursor(),
                    Buffer.fromByteArray(datFile.readBytes()).cursor(),
                    lenient = false, shiftJis = name.endsWith("-j"),
                )
                if (r is Success) {
                    for (seg in r.value.quest.bytecodeIr.instructionSegments()) {
                        for (ins in seg.instructions) {
                            val mn = ins.opcode.mnemonic
                            if (mn.startsWith("unknown_")) {
                                unknownByCode[mn] = (unknownByCode[mn] ?: 0) + 1
                                sampleFile.putIfAbsent(mn, "${binFile.parentFile.name}/${binFile.name} (${r.value.quest.version})")
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // ignore parse exceptions for this scan
            }
        }

        println("=== unknown opcode distribution across newserv (occurrence count) ===")
        for ((mn, count) in unknownByCode.toList().sortedByDescending { it.second }) {
            val sample = sampleFile[mn] ?: "?"
            println("  $mn  count=$count  sample=$sample")
        }
        println("total distinct unknown mnemonics: ${unknownByCode.size}")
    }
}
