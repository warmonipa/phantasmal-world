package world.phantasmal.web.questEditor.loading

import org.khronos.webgl.Uint8Array
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.QuestObject
import world.phantasmal.web.core.loading.AssetLoader
import world.phantasmal.web.core.rendering.disposeObject3DResources
import world.phantasmal.web.externals.three.DataTexture
import world.phantasmal.web.externals.three.InstancedMesh
import world.phantasmal.web.externals.three.MeshBasicMaterial
import world.phantasmal.web.externals.three.PlaneGeometry
import world.phantasmal.webui.obj
import world.phantasmal.web.test.WebTestSuite
import kotlin.js.unsafeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EntityAssetLoaderTests : WebTestSuite {
    @Test
    fun tower_terminal_variant_loads_its_textured_model() = testAsync {
        // PW4 places type 0x223, param4=3, in Tower section 30.
        val terminal = QuestObject(ObjectType.LittleCryotube, floorId = 17)
        terminal.data.setInt(52, 3)
        val loader = EntityAssetLoader(AssetLoader(basePath = "/assets"))
        try {
            val mesh = loader.loadInstancedMesh(terminal.type, model = terminal.model)
            try {
                assertEquals(terminal.type.uniqueName, mesh.name)
                assertTrue((mesh.geometry.asDynamic().attributes.position.count as Int) > 100)
                assertTrue(textureImages(mesh).isNotEmpty())
            } finally {
                disposeObject3DResources(mesh)
            }
        } finally {
            loader.dispose()
        }
    }

    @Test
    fun forest_door_digits_align_with_the_pso_world_font_texture_order() {
        assertEquals((0..9).toList(), (0..9).map(::forestDoorDigitTextureIndex))
        assertEquals(1, forestDoorDigitTextureIndex(11))
    }

    @Test
    fun stock_forest_door_params_select_the_expected_single_digit_texture() {
        val cases = listOf(
            // Packed param4, actual Door ID, displayed digit/texture slot.
            Triple(0x0602, 2, 6),
            Triple(0x0909, 9, 9),
            Triple(0x0408, 8, 4),
            Triple(0x0501, 1, 5),
            Triple(0x0803, 3, 8),
            Triple(0x0206, 6, 2),
            Triple(0x0307, 7, 3),
            Triple(0x073C, 60, 7),
            Triple(0x0B05, 5, 1),
        )

        for ((packedParam4, expectedDoorId, expectedDigitTexture) in cases) {
            val door = QuestObject(ObjectType.ForestDoor, floorId = 1)
            door.data.setInt(52, packedParam4)

            assertEquals(expectedDoorId, packedParam4 and 0xFF)
            assertEquals(expectedDigitTexture, door.forestDoorDigit)
            assertEquals(
                expectedDigitTexture,
                forestDoorDigitTextureIndex(door.forestDoorDigit),
            )
        }
    }

    @Test
    fun warp_color_param_selects_the_blue_or_red_line_texture() {
        // newserv: param6 0 = blue, 1 = red. The line models reference the red texture.
        for ((type, red, blue) in listOf(
            Triple(ObjectType.Teleporter, 2, 3),
            Triple(ObjectType.QuestWarp, 2, 3),
            Triple(ObjectType.Epilogue, 2, 3),
            Triple(ObjectType.TeleporterEp2, 8, 9),
        )) {
            val variant = entityTextureVariant(type)!!
            val warp = QuestObject(type, floorId = 1)

            warp.data.setInt(60, 0)
            assertEquals(mapOf(red to blue), variant.textureIndexOverrides(variant.variantOf(warp)))
            warp.data.setInt(60, 1)
            assertEquals(emptyMap(), variant.textureIndexOverrides(variant.variantOf(warp)))
        }
    }

    @Test
    fun laser_fence_color_params_select_the_matching_laser_texture() {
        // newserv: <=0 = red, 1 = cyan, 2 = green, >=3 = magenta; float params are truncated.
        val fence = QuestObject(ObjectType.LaserFence, floorId = 1)
        val fenceVariant = entityTextureVariant(ObjectType.LaserFence)!!
        for ((param1, texture) in listOf(-1f to 0, 0f to 0, 1.0000002f to 1, 2f to 2, 4f to 3)) {
            fence.data.setFloat(40, param1)
            assertEquals(
                mapOf(0 to texture),
                fenceVariant.textureIndexOverrides(fenceVariant.variantOf(fence)),
            )
        }

        val fenceSwitch = QuestObject(ObjectType.ForestLaserFenceSwitch, floorId = 1)
        val switchVariant = entityTextureVariant(ObjectType.ForestLaserFenceSwitch)!!
        fenceSwitch.data.setInt(60, 2)
        assertEquals(
            mapOf(0 to 2),
            switchVariant.textureIndexOverrides(switchVariant.variantOf(fenceSwitch)),
        )

        val ruinsFence = QuestObject(ObjectType.RuinsLaserFence4x2, floorId = 9)
        val ruinsFenceVariant = entityTextureVariant(ObjectType.RuinsLaserFence4x2)!!
        val ruinsSwitch = QuestObject(ObjectType.RuinsFenceSwitch, floorId = 9)
        val ruinsSwitchVariant = entityTextureVariant(ObjectType.RuinsFenceSwitch)!!
        for ((param5, fenceTexture, switchTexture) in listOf(
            Triple(0, 0, 1), Triple(1, 4, 7), Triple(2, 5, 8), Triple(3, 6, 9), Triple(7, 6, 9),
        )) {
            ruinsFence.data.setInt(56, param5)
            ruinsSwitch.data.setInt(56, param5)
            assertEquals(
                mapOf(0 to fenceTexture),
                ruinsFenceVariant.textureIndexOverrides(ruinsFenceVariant.variantOf(ruinsFence)),
            )
            assertEquals(
                mapOf(1 to switchTexture),
                ruinsSwitchVariant.textureIndexOverrides(ruinsSwitchVariant.variantOf(ruinsSwitch)),
            )
        }
    }

    @Test
    fun blue_and_red_warps_load_distinct_line_textures() = testAsync {
        val loader = EntityAssetLoader(AssetLoader(basePath = "/assets"))
        val red = loader.loadInstancedMesh(ObjectType.Teleporter, model = null, renderVariant = 1)
        val blue = loader.loadInstancedMesh(ObjectType.Teleporter, model = null, renderVariant = 0)

        assertTrue(textureImages(red).isNotEmpty())
        assertTrue(textureImages(red) != textureImages(blue))

        disposeObject3DResources(red)
        disposeObject3DResources(blue)
        loader.dispose()
    }

    @Test
    fun cloned_mesh_owns_its_disposable_resources() {
        val texture = DataTexture(Uint8Array(4), 1, 1).apply { needsUpdate = true }
        val firstSourceMaterial = MeshBasicMaterial(obj { map = texture })
        val secondSourceMaterial = MeshBasicMaterial(obj { map = texture })
        val source = InstancedMesh(
            PlaneGeometry(),
            arrayOf(firstSourceMaterial, secondSourceMaterial),
            1,
        )

        val first = cloneInstancedMeshWithOwnedResources(source)
        val second = cloneInstancedMeshWithOwnedResources(source)

        assertNotSame(source.geometry, first.geometry)
        assertNotSame(firstSourceMaterial, materials(first)[0])
        assertNotSame(texture, materials(first)[0].map)
        assertSame(materials(first)[0].map, materials(first)[1].map)
        assertTrue(materials(first)[0].map!!.asDynamic().version > 0)
        assertNotSame(first.geometry, second.geometry)
        assertNotSame(materials(first)[0], materials(second)[0])
        assertNotSame(materials(first)[0].map, materials(second)[0].map)
    }

    @Test
    fun transient_asset_failure_is_evicted_and_retried() = testAsync {
        val delegate = AssetLoader(basePath = "/assets")
        var geometryRequests = 0
        val loader = EntityAssetLoader { path ->
            if (path == "/objects/70.xj") {
                geometryRequests++
                if (geometryRequests == 1) {
                    error("Transient failure.")
                }
            }
            delegate.loadArrayBuffer(path)
        }

        assertFailsWith<IllegalStateException> {
            loader.loadInstancedMesh(ObjectType.ShopDoor, model = null)
        }

        val mesh = loader.loadInstancedMesh(ObjectType.ShopDoor, model = null)
        assertEquals(2, geometryRequests)

        disposeObject3DResources(mesh)
        loader.dispose()
    }

    /** Fingerprints of the texture data each material samples. */
    private fun textureImages(mesh: InstancedMesh): List<String> =
        materials(mesh).mapNotNull { material ->
            val map = material.map?.asDynamic() ?: return@mapNotNull null
            // DataTextures keep texels on the image; compressed textures keep them per mipmap.
            val mipmaps = map.mipmaps?.unsafeCast<Array<dynamic>>()
            val bytes = (map.image?.data ?: mipmaps?.get(0)?.data)?.unsafeCast<Uint8Array>()
                ?: return@mapNotNull null
            (0 until minOf(bytes.length, 64)).joinToString(",") { bytes.asDynamic()[it].toString() }
        }

    private fun materials(mesh: InstancedMesh): Array<MeshBasicMaterial> =
        mesh.material.unsafeCast<Array<MeshBasicMaterial>>()
}
