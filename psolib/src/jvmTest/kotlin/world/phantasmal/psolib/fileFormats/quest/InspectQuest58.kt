package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.core.Failure
import world.phantasmal.core.Severity
import world.phantasmal.core.Success
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.test.LibTestSuite
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InspectQuest58 : LibTestSuite {
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
    fun parseBinStrict_gc_nte_quest58() = testAsync {
        val binBytes = this::class.java.classLoader
            .getResource("quest58_j_nte.bin")!!.readBytes()
        val datBytes = this::class.java.classLoader
            .getResource("quest58_j_nte.dat")!!.readBytes()

        val r = parseBinDatToQuestAutoDetect(
            Buffer.fromByteArray(binBytes).cursor(),
            Buffer.fromByteArray(datBytes).cursor(),
            lenient = false,
            shiftJis = true,
        )
        assertTrue(r is Success, "auto-detect failed: ${(r as? Failure)?.problems}")
        assertEquals(Version.GC_NTE, r.value.quest.version)
        val nonInfo = r.problems.filter { it.severity != Severity.Info }
        assertTrue(nonInfo.isEmpty(),
            "expected zero non-Info problems; got: ${nonInfo.joinToString { it.message ?: "<null>" }}")
        val invalid = r.value.quest.bytecodeIr.instructionSegments()
            .sumOf { seg -> seg.instructions.count { !it.valid } }
        assertEquals(0, invalid, "expected zero invalid instructions")
    }
}
