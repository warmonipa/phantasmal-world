package world.phantasmal.web.core.rendering.conversion

import org.khronos.webgl.Uint8Array
import world.phantasmal.core.unsafe.UnsafeMap
import world.phantasmal.web.externals.three.DataTexture
import world.phantasmal.web.externals.three.MeshBasicMaterial
import world.phantasmal.web.externals.three.Texture
import world.phantasmal.web.externals.three.Vector2
import world.phantasmal.web.externals.three.Vector3
import kotlin.js.unsafeCast
import kotlin.test.Test
import kotlin.test.assertSame

class MeshBuilderTests {
    @Test
    fun material_groups_reuse_cached_texture() {
        val texture = DataTexture(Uint8Array(4), 1, 1)
        val textureCache = UnsafeMap<Int, Texture?>().apply { set(3, texture) }
        val builder = MeshBuilder(textureCache = textureCache)
        val opaqueGroup = builder.getGroupIndex(3, alpha = false, additiveBlending = false)
        val alphaGroup = builder.getGroupIndex(3, alpha = true, additiveBlending = false)

        builder.vertex(Vector3(), Vector3(0.0, 1.0, 0.0), Vector2())
        builder.vertex(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), Vector2(1.0, 0.0))
        builder.vertex(Vector3(0.0, 0.0, 1.0), Vector3(0.0, 1.0, 0.0), Vector2(0.0, 1.0))
        for (index in 0..2) {
            builder.index(opaqueGroup, index)
            builder.index(alphaGroup, index)
        }

        val materials = builder.buildMesh().material.unsafeCast<Array<MeshBasicMaterial>>()

        assertSame(texture, materials[0].map)
        assertSame(texture, materials[1].map)
    }
}
