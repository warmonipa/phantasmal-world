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

    private fun materials(mesh: InstancedMesh): Array<MeshBasicMaterial> =
        mesh.material.unsafeCast<Array<MeshBasicMaterial>>()
}
