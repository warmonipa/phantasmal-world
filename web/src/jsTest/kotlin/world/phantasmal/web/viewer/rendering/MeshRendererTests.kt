package world.phantasmal.web.viewer.rendering

import kotlinx.coroutines.delay
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.viewer.models.ViewerModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.math.PI

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
        assertProfile(MeshRenderer.ItemPresentationProfile.Dagger, 2, 161, 253, 266)
        assertProfile(MeshRenderer.ItemPresentationProfile.Gun, 5, 126, 267)
        assertProfile(MeshRenderer.ItemPresentationProfile.Claw, 12, 184, 258)
        assertProfile(MeshRenderer.ItemPresentationProfile.Wok, 54)
        assertProfile(MeshRenderer.ItemPresentationProfile.Default, 14, 271, 407)
    }

    @Test
    fun named_weapons_use_canonical_weapon_kinds_and_shapes() = test {
        val yasminkovs = listOf(0x006A00, 0x006500, 0x006B00, 0x006C00)
        for (itemTypeId in yasminkovs) {
            val model = weapon(itemTypeId)
            assertEquals(MeshRenderer.ItemPresentationProfile.Gun,
                MeshRenderer.presentationProfile(model), model.uiName)
            assertEquals(100.0 * PI / 180.0,
                assertNotNull(MeshRenderer.presentationRotation(model)).z,
                absoluteTolerance = 1e-12,
                message = model.uiName)
            assertNull(MeshRenderer.presentationScreenRotation(model), model.uiName)
        }

        val sectionIdCard = weapon(0x009300)
        assertEquals(MeshRenderer.ItemPresentationProfile.Card,
            MeshRenderer.presentationProfile(sectionIdCard))
        assertTrue(MeshRenderer.isCardFanModel(sectionIdCard.index))

        val yunchang = weapon(0x00BA00)
        assertEquals(MeshRenderer.ItemPresentationProfile.SwordPartisan,
            MeshRenderer.presentationProfile(yunchang))
        assertNull(MeshRenderer.presentationScreenRotation(yunchang))

        val zeroDivide = weapon(0x000308)
        assertEquals(MeshRenderer.ItemPresentationProfile.Dagger,
            MeshRenderer.presentationProfile(zeroDivide))
        assertTrue(MeshRenderer.isPairedModel(zeroDivide.index))
        assertNull(MeshRenderer.presentationScreenRotation(zeroDivide))

        val yamato = weapon(0x008901)
        assertEquals(MeshRenderer.ItemPresentationProfile.SwordPartisan,
            MeshRenderer.presentationProfile(yamato))
        assertTrue(MeshRenderer.isPairedModel(yamato.index))
        assertNull(MeshRenderer.presentationScreenRotation(yamato))
        assertNull(MeshRenderer.pairScreenRotation(yamato.index, first = true))
        assertNull(MeshRenderer.pairScreenRotation(yamato.index, first = false))
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
                            52, 55, 56, 89, 93, 95, 98, 99, 101, 102, 103, 104,
                            108, 110, 128, 132, 133, 134, 138, 139, 140, 141, 161,
                            162, 163, 174, 176, 181, 187, 188, 191, 192, 193, 198,
                            210, 236, 245,
                        ) + (78..88)
                    )
                ) {
                    obj3d.children.single()
                } else {
                    obj3d
                }

            if (index == 54) {
                assertEquals(2, presentationObj.children.size)
                val wokHolder = presentationObj.children[0]
                val ladleHolder = presentationObj.children[1]
                val wok = wokHolder.children.single()
                val ladle = ladleHolder.children.single()
                assertEquals(PI, wok.rotation.y, absoluteTolerance = 1e-12)
                assertEquals(PI, ladle.rotation.z, absoluteTolerance = 1e-12)
                assertTrue(wok.scale.x > .0 && wok.scale.y > .0 && wok.scale.z > .0)
                assertEquals(.56, ladleHolder.scale.x, absoluteTolerance = 1e-12)
                assertEquals(.56, ladleHolder.scale.y, absoluteTolerance = 1e-12)
                assertEquals(.56, ladleHolder.scale.z, absoluteTolerance = 1e-12)
                assertTrue(
                    ladleHolder.rotation.x != .0 ||
                        ladleHolder.rotation.y != .0 ||
                        ladleHolder.rotation.z != .0
                )
                assertTrue(
                    ladleHolder.position.x != .0 ||
                        ladleHolder.position.y != .0 ||
                        ladleHolder.position.z != .0
                )
                continue
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
                    161, 162, 163, 170, 171, 172, 173, 187, 198, 213, 253, 256,
                    261, 266,
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

    private fun weapon(itemTypeId: Int): ViewerModel.Item =
        assertIs(ViewerModel.findBySlug(
            "Weapon_${itemTypeId.toString(16).padStart(6, '0').uppercase()}"
        ))
}
