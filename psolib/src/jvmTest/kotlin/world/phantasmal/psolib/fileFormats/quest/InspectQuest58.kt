package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.core.Failure
import world.phantasmal.core.Severity
import world.phantasmal.core.Success
import world.phantasmal.psolib.asm.dataFlowAnalysis.FloorMapping
import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleSpawn
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.test.LibTestSuite
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import world.phantasmal.psolib.fileFormats.quest.Dialect

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
    fun parseV0V2_gc_nte_quest58() = testAsync {
        // Auto-detect can't distinguish GC_NTE from DC_V2 from bytes alone for this quest
        // (newserv symlinks q058-gcn → q058-dc). The byte-level guarantee is that the V0_V2
        // dialect parses cleanly. Sub-version disambiguation requires outer context (e.g.,
        // explicit `version` param or .qst wrapper) — see parseV0V2_gc_nte_explicit below.
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
        assertEquals(Dialect.V0_V2, r.value.quest.version.dialect,
            "expected V0_V2 dialect; got ${r.value.quest.version}")
        val nonInfo = r.problems.filter { it.severity != Severity.Info }
        assertTrue(nonInfo.isEmpty(),
            "expected zero non-Info problems; got: ${nonInfo.joinToString { it.message ?: "<null>" }}")
        val invalid = r.value.quest.bytecodeIr.instructionSegments()
            .sumOf { seg -> seg.instructions.count { !it.valid } }
        assertEquals(0, invalid, "expected zero invalid instructions")
    }

    @Test
    fun parseV0V2_gc_nte_explicit() = testAsync {
        // When the caller knows the version, the GC_NTE code path strict-parses cleanly.
        val binBytes = this::class.java.classLoader
            .getResource("quest58_j_nte.bin")!!.readBytes()
        val datBytes = this::class.java.classLoader
            .getResource("quest58_j_nte.dat")!!.readBytes()

        val r = parseBinDatToQuestAutoDetect(
            Buffer.fromByteArray(binBytes).cursor(),
            Buffer.fromByteArray(datBytes).cursor(),
            lenient = false,
            shiftJis = true,
            version = Version.GC_NTE,
        )
        assertTrue(r is Success, "explicit GC_NTE parse failed: ${(r as? Failure)?.problems}")
        assertEquals(Version.GC_NTE, r.value.quest.version)
        val invalid = r.value.quest.bytecodeIr.instructionSegments()
            .sumOf { seg -> seg.instructions.count { !it.valid } }
        assertEquals(0, invalid, "expected zero invalid instructions on explicit GC_NTE path")
    }

    @Test
    fun nte_and_v3_quest58_produce_equivalent_analysis() = testAsync {
        val nte = parseBinDatToQuestAutoDetect(
            Buffer.fromByteArray(
                this::class.java.classLoader.getResource("quest58_j_nte.bin")!!.readBytes()
            ).cursor(),
            Buffer.fromByteArray(
                this::class.java.classLoader.getResource("quest58_j_nte.dat")!!.readBytes()
            ).cursor(),
            lenient = false, shiftJis = true,
            version = Version.GC_NTE,
        )
        val v3 = parseBinDatToQuestAutoDetect(
            Buffer.fromByteArray(
                this::class.java.classLoader.getResource("quest58_j.bin")!!.readBytes()
            ).cursor(),
            Buffer.fromByteArray(
                this::class.java.classLoader.getResource("quest58_j.dat")!!.readBytes()
            ).cursor(),
            lenient = false, shiftJis = true,
        )
        assertTrue(nte is Success, "NTE parse: $nte")
        assertTrue(v3 is Success, "V3 parse: $v3")
        assertEquals(Version.GC_NTE, nte.value.quest.version)
        assertEquals(Version.GC_V3, v3.value.quest.version)

        val nteQ = nte.value.quest
        val v3Q = v3.value.quest

        // FloorMapping is a pure-semantic data class (floorId, mapId, areaId, variantId,
        // mapEpisode) with no offset-dependent fields — use full set equality.
        assertEquals(
            nteQ.floorMappings.toSet(),
            v3Q.floorMappings.toSet(),
            "floorMappings differ NTE vs V3",
        )

        // ParticleSpawn is likewise pure-semantic (x, y, z, particleId, frames, floorIds)
        // with no offset-dependent fields — use full set equality.
        assertEquals(
            nteQ.particleSpawns.toSet(),
            v3Q.particleSpawns.toSet(),
            "particleSpawns differ NTE vs V3",
        )
    }
}
