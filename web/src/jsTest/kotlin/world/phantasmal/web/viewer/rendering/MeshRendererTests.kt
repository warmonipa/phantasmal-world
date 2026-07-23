package world.phantasmal.web.viewer.rendering

import kotlinx.coroutines.delay
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.viewer.models.ViewerModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class MeshRendererTests : WebTestSuite {
    @Test
    fun item_models_use_their_presentation_rotation() = test {
        assertEquals(550, ViewerModel.ITEMS.size)

        for (model in ViewerModel.ITEMS) {
            assertIs<ViewerModel.Item>(model)
            assertNotNull(MeshRenderer.presentationProfile(model), model.slug)
            assertNotNull(MeshRenderer.presentationRotation(model), model.slug)
        }

        assertProfile(MeshRenderer.ItemPresentationProfile.Catalog, 0, 21, 233)
        assertProfile(MeshRenderer.ItemPresentationProfile.SwordPartisan, 1, 215, 270)
        assertProfile(MeshRenderer.ItemPresentationProfile.Dagger, 2, 161, 266)
        assertProfile(MeshRenderer.ItemPresentationProfile.Gun, 5, 126, 267)
        assertProfile(MeshRenderer.ItemPresentationProfile.Claw, 12, 184, 258)
        assertProfile(MeshRenderer.ItemPresentationProfile.Wok, 54)
        assertProfile(MeshRenderer.ItemPresentationProfile.Default, 14, 271, 407)
    }

    @Test
    fun verified_items_apply_their_presentation_rotation_to_the_mesh() = testAsync {
        val renderer = disposer.add(
            MeshRenderer(components.viewerStore, components.createThreeRenderer)
        )

        val verifiedIndices =
            (0..13) + (16..21) + listOf(27, 28, 29, 30) + (31..110) + (126..270)
        for (index in verifiedIndices) {
            val slug = "ItemModel_$index"
            val item = assertIs<ViewerModel.Item>(ViewerModel.findBySlug(slug))
            val expected = assertNotNull(MeshRenderer.presentationRotation(item))
            components.viewerStore.setCurrentModel(item)
            delay(50)

            val obj3d = assertNotNull(renderer.renderedObject)
            val presentationObj =
                if (index in (
                        setOf(
                            52, 55, 56, 89, 93, 95, 98, 99, 100, 101, 102, 103,
                            104, 105, 106, 108, 110, 128, 132, 133, 134, 138, 139,
                            140, 141, 161, 162, 163, 170, 171, 172, 174, 176, 181,
                            187, 188, 191, 192, 193, 198, 210, 215, 236, 245, 253,
                        ) + (78..88)
                    )
                ) {
                    obj3d.children.single()
                } else {
                    obj3d
                }

            if (index in setOf(151, 175, 177, 178)) {
                assertEquals(if (index == 151) 5 else 4, presentationObj.children.size)
                for (holder in presentationObj.children) {
                    val item = holder.children.single()
                    assertEquals(expected.x, item.rotation.x, absoluteTolerance = 1e-12)
                    assertEquals(expected.y, item.rotation.y, absoluteTolerance = 1e-12)
                    assertEquals(expected.z, item.rotation.z, absoluteTolerance = 1e-12)
                }
                continue
            }

            if (index in setOf(
                    2, 53, 55, 56, 70, 71, 72, 74, 75, 96, 97, 107, 132, 133, 134,
                    161, 162, 163, 170, 171, 172, 173, 187, 198, 213, 256, 261, 266,
                )
            ) {
                assertEquals(2, presentationObj.children.size)
                for (holder in presentationObj.children) {
                    val item = holder.children.single()
                    assertEquals(expected.x, item.rotation.x, absoluteTolerance = 1e-12)
                    assertEquals(expected.y, item.rotation.y, absoluteTolerance = 1e-12)
                    assertEquals(expected.z, item.rotation.z, absoluteTolerance = 1e-12)
                }
                continue
            }

            assertEquals(expected.x, presentationObj.rotation.x, absoluteTolerance = 1e-12)
            assertEquals(expected.y, presentationObj.rotation.y, absoluteTolerance = 1e-12)
            assertEquals(expected.z, presentationObj.rotation.z, absoluteTolerance = 1e-12)
        }
    }

    private fun assertProfile(
        expected: MeshRenderer.ItemPresentationProfile,
        vararg modelIndices: Int,
    ) {
        for (index in modelIndices) {
            val model = assertIs<ViewerModel.Item>(
                ViewerModel.findBySlug("ItemModel_$index")
            )
            assertEquals(expected, MeshRenderer.presentationProfile(model), model.slug)
        }
    }
}
