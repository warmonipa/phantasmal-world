package world.phantasmal.web.assetsGeneration

import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.compression.prs.prsCompress
import java.nio.file.Files
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BmlExtractionTests {
    /** Exercise the production entry point with a small, intentionally incomplete GSL. */
    private fun rejectsExport(model: ByteArray, texture: ByteArray?, archive: String, index: Int, output: String) {
        val directory = Files.createTempDirectory("bml-export-test").toFile()
        try {
            fun compressed(bytes: ByteArray): Buffer = prsCompress(Buffer.fromByteArray(bytes).cursor()).buffer()
            val data = compressed(model)
            val tex = texture?.let(::compressed)
            val textureStart = 0x800 + (data.size + 31) / 32 * 32
            val bml = Buffer.withSize(textureStart + (tex?.size ?: 0))
            bml.setInt(4, index + 1).setByte(8, 0x50).setByte(9, 1)
            val header = 0x40 + index * 0x40
            bml.setInt(header + 0x20, data.size).setInt(header + 0x28, model.size)
            bml.setInt(header + 0x2C, tex?.size ?: 0).setInt(header + 0x30, texture?.size ?: 0)
            for (i in 0 until data.size) bml.setByte(0x800 + i, data.getByte(i))
            if (tex != null) for (i in 0 until tex.size) bml.setByte(textureStart + i, tex.getByte(i))
            val gsl = Buffer.withSize(0x800 + bml.size)
            for ((i, char) in archive.withIndex()) gsl.setByte(i, char.code.toByte())
            gsl.setInt(0x20, 1).setInt(0x24, bml.size)
            for (i in 0 until bml.size) gsl.setByte(0x800 + i, bml.getByte(i))
            File(directory, "data.gsl").writeBytes(gsl.byteArray.copyOf(gsl.size))
            val out = File(directory, "out")
            // Multiple generated programs expose main(Array<String>) in this package.
            // Select the same JVM entry point as the extractNpcModels Gradle task.
            val error = assertFailsWith<InvocationTargetException> {
                Class.forName("world.phantasmal.web.assetsGeneration.ExtractNpcModelsKt")
                    .getMethod("main", Array<String>::class.java)
                    .invoke(null, arrayOf(directory.absolutePath, out.absolutePath))
            }
            assertIs<IllegalStateException>(error.targetException)
            assertTrue(error.targetException.message!!.startsWith("NPC extraction failed"))
            assertFalse(File(out, output).exists(), "Invalid asset was exported: $output")
        } finally { directory.deleteRecursively() }
    }

    @Test fun invalid_model_is_not_written_even_when_prs_and_size_are_valid() {
        rejectsExport(byteArrayOf(0x41), null, "bm_ene_me3_shinowa.bml", 0, "SinowBeat.nj")
    }

    @Test fun incomplete_texture_archive_does_not_write_either_half_of_pair() {
        val model = File("../src/jsMain/resources/assets/npcs/SinowBeat.nj").readBytes()
        // XVMH declares one texture, but there is no XVRT chunk.
        val texture = Buffer.withSize(10).setInt(0, 0x484D5658).setInt(4, 2).setShort(8, 1)
        for (file in listOf("SinowBeat.nj", "SinowBeat.xvm")) {
            rejectsExport(model, texture.byteArray.copyOf(texture.size), "bm_ene_me3_shinowa.bml", 0, file)
        }
    }

    @Test fun invalid_motion_is_not_written_even_when_prs_and_size_are_valid() {
        rejectsExport(byteArrayOf(0x41), null, "bm4_ps_ma_body.bml", 7, "PanArms_apper.njm")
    }

    // PRS: literal 0x41 followed by the two-bit long-copy end marker.
    private fun literal() = Buffer.fromByteArray(byteArrayOf(5, 0x41, 0, 0))

    @Test fun preserves_valid_stream() {
        assertContentEquals(byteArrayOf(0x41), decompressBmlStream(literal(), 1))
    }

    @Test fun rejects_declared_size_mismatch() {
        assertFails { decompressBmlStream(literal(), 2) }
    }

    @Test fun rejects_bad_prs_instead_of_exporting_raw_bytes() {
        assertFails { decompressBmlStream(Buffer.fromByteArray(byteArrayOf(1)), 1) }
    }
}
