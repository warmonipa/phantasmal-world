package world.phantasmal.web.viewer.stores

import world.phantasmal.psolib.fileFormats.ninja.XvrTexture
import world.phantasmal.web.core.PwToolType
import world.phantasmal.web.test.TestApplicationUrl
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.viewer.ViewerUrls
import world.phantasmal.web.viewer.models.ViewerModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class ViewerStoreTests : WebTestSuite {
    @Test
    fun weapon_url_omits_character_only_parameters() = test {
        val applicationUrl = TestApplicationUrl(
            "/${PwToolType.Viewer.slug}${ViewerUrls.mesh}" +
                "?model=Weapon_006A00&section_id=Bluefull&body=11"
        )
        components.applicationUrl = applicationUrl

        components.viewerStore

        assertEquals(
            "/${PwToolType.Viewer.slug}${ViewerUrls.mesh}?model=Weapon_006A00",
            applicationUrl.pathAndParams,
        )
    }

    @Test
    fun npc_url_omits_character_only_parameters() = test {
        val applicationUrl = TestApplicationUrl(
            "/${PwToolType.Viewer.slug}${ViewerUrls.mesh}" +
                "?model=Hildebear&section_id=Greenill&body=13"
        )
        components.applicationUrl = applicationUrl

        // Initializing the store normalizes stale character-only parameters out of an NPC URL.
        components.viewerStore

        val params = applicationUrl.pathAndParamsDeconstructed.params
        assertEquals("Hildebear", params[ViewerStore.MODEL_PARAM])
        assertFalse(ViewerStore.SECTION_ID_PARAM in params)
        assertFalse(ViewerStore.BODY_PARAM in params)
        assertEquals(
            "/${PwToolType.Viewer.slug}${ViewerUrls.mesh}?model=Hildebear",
            applicationUrl.pathAndParams,
        )
    }

    @Test
    fun weapon_texture_variants_load_their_itempmt_texture_archive() = testAsync {
        val frozenShooterModel =
            assertIs<ViewerModel.Item>(ViewerModel.findBySlug("ItemModel_68"))
        val snowQueen =
            assertIs<ViewerModel.Item>(ViewerModel.findBySlug("Weapon_004501"))

        assertEquals(68, snowQueen.index)
        assertEquals(275, snowQueen.textureIndex)

        components.viewerStore.setCurrentModel(frozenShooterModel)
        val frozenShooterTextures = textureSignatures(
            components.viewerStore.currentTextures.value,
        )

        components.viewerStore.setCurrentModel(snowQueen)
        val snowQueenTextures = textureSignatures(
            components.viewerStore.currentTextures.value,
        )

        assertNotEquals(emptyList(), frozenShooterTextures)
        assertNotEquals(emptyList(), snowQueenTextures)
        assertNotEquals(frozenShooterTextures, snowQueenTextures)
    }

    private fun textureSignatures(textures: List<XvrTexture?>): List<String> =
        textures.filterNotNull().map { texture ->
            "${texture.id}:${texture.format}:${texture.width}:${texture.height}:" +
                    "${texture.size}:${texture.data.toBase64()}"
        }
}
