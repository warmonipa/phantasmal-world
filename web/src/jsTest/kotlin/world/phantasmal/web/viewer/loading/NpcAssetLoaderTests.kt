package world.phantasmal.web.viewer.loading

import org.khronos.webgl.Uint8Array
import world.phantasmal.psolib.fileFormats.ninja.XvrTexture
import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.web.test.WebTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals

class NpcAssetLoaderTests : WebTestSuite {
    @Test
    fun sinow_blue_and_red_use_the_retracted_geometry() = testAsync {
        assertAssetBytesEqual("SinowBeat.nj", "SinowBeat.ult.nj")
        assertAssetBytesEqual("SinowGold.nj", "SinowGold.ult.nj")
    }

    @Test
    fun sinow_gold_and_red_keep_segas_original_rare_textures() = testAsync {
        val beat = components.npcAssetLoader.loadXvrTextures(NpcType.SinowBeat)
        val gold = components.npcAssetLoader.loadXvrTextures(NpcType.SinowGold)
        val blue = components.npcAssetLoader.loadXvrTextures(NpcType.SinowBeat, ultimate = true)
        val red = components.npcAssetLoader.loadXvrTextures(NpcType.SinowGold, ultimate = true)

        assertEquals(beat.take(3).map(::textureSignature), gold.drop(3).map(::textureSignature))
        assertEquals(blue.take(3).map(::textureSignature), red.drop(3).map(::textureSignature))
    }

    private suspend fun world.phantasmal.web.test.WebTestContext.assertAssetBytesEqual(
        expectedName: String,
        actualName: String,
    ) {
        val expected = Uint8Array(components.assetLoader.loadArrayBuffer("/npcs/$expectedName"))
        val actual = Uint8Array(components.assetLoader.loadArrayBuffer("/npcs/$actualName"))
        assertEquals(expected.length, actual.length)
        for (index in 0 until expected.length) {
            val expectedByte = expected.asDynamic()[index] as Int
            val actualByte = actual.asDynamic()[index] as Int
            assertEquals(expectedByte, actualByte, "$actualName differs at byte $index")
        }
    }

    private fun textureSignature(texture: XvrTexture): String =
        "${texture.format}:${texture.width}:${texture.height}:${texture.size}:${texture.data.toBase64()}"
}
