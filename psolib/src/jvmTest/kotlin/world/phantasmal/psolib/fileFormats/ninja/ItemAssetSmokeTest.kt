package world.phantasmal.psolib.fileFormats.ninja

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.fail
import kotlin.test.assertTrue
import world.phantasmal.core.Success
import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.compression.prs.prsDecompress
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.fileFormats.parseAfs
import world.phantasmal.psolib.test.LibTestSuite

class ItemAssetSmokeTest : LibTestSuite {
    @Test
    fun can_parse_item_models() = test {
        val afs = parseAfs(
            readRepoFile("web/src/jsMain/resources/assets/items/ItemModelEp4.afs").cursor()
        ).unwrap()

        for (index in afs.indices) {
            val parsed = try {
                val xjItem = prsDecompress(afs[index].cursor()).unwrap()
                val xjResult = runCatching { parseXj(xjItem) }.getOrNull()

                if (xjResult is Success && xjResult.value.isNotEmpty()) {
                    xjResult.value.any { it.model?.meshes?.isNotEmpty() == true } ||
                        index !in REPRESENTATIVE_VISIBLE_MODELS
                } else {
                    val njItem = prsDecompress(afs[index].cursor()).unwrap()
                    val njResult = runCatching { parseNj(njItem) }.getOrNull()
                    njResult is Success &&
                        njResult.value.isNotEmpty() &&
                        (
                            njResult.value.any { it.model?.meshes?.isNotEmpty() == true } ||
                                index !in REPRESENTATIVE_VISIBLE_MODELS
                            )
                }
            } catch (e: Exception) {
                fail("Expected item model $index to parse.", e)
            }

            assertTrue(parsed, "Expected item model $index to parse.")
        }
    }

    private fun readRepoFile(path: String): Buffer {
        val userDir = Path.of(System.getProperty("user.dir"))
        val candidates = listOf(userDir.resolve(path), userDir.parent.resolve(path))
        val file = candidates.first(Files::exists)
        return Buffer.fromByteArray(Files.readAllBytes(file), Endianness.Little)
    }

    private companion object {
        val REPRESENTATIVE_VISIBLE_MODELS = setOf(0, 1, 2, 10)
    }
}
