package world.phantasmal.web.core.rendering.conversion

import org.khronos.webgl.Uint8Array
import world.phantasmal.core.unsafe.UnsafeMap
import world.phantasmal.web.externals.three.Bone
import world.phantasmal.web.externals.three.CustomBlending
import world.phantasmal.web.externals.three.DataTexture
import world.phantasmal.web.externals.three.DoubleSide
import world.phantasmal.web.externals.three.DstColorFactor
import world.phantasmal.web.externals.three.FrontSide
import world.phantasmal.web.externals.three.Material
import world.phantasmal.web.externals.three.MeshBasicMaterial
import world.phantasmal.web.externals.three.MeshLambertMaterial
import world.phantasmal.web.externals.three.NormalBlending
import world.phantasmal.web.externals.three.OneFactor
import world.phantasmal.web.externals.three.SrcAlphaFactor
import world.phantasmal.web.externals.three.SrcColorFactor
import world.phantasmal.web.externals.three.Texture
import world.phantasmal.web.externals.three.Vector2
import world.phantasmal.web.externals.three.Vector3
import kotlin.js.unsafeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MeshBuilderTests {
    @Test
    fun material_groups_reuse_cached_texture() {
        val (builder, texture) = builderWithTexture()
        val opaqueGroup = builder.getGroupIndex(3, alpha = false)
        val alphaGroup = builder.getGroupIndex(3, alpha = true)

        addTriangle(builder, opaqueGroup, alphaGroup)

        val materials = builder.buildMesh().material.unsafeCast<Array<MeshBasicMaterial>>()

        assertSame(texture, materials[0].map)
        assertSame(texture, materials[1].map)
    }

    @Test
    fun vertex_colors_become_an_rgba_attribute() {
        val (builder, _) = builderWithTexture()
        val group = builder.getGroupIndex(3, alpha = false)

        builder.vertex(Vector3(), Vector3(0.0, 1.0, 0.0), Vector2(), color = 0x80FF0000.toInt())
        builder.vertex(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), Vector2())
        builder.vertex(Vector3(0.0, 0.0, 1.0), Vector3(0.0, 1.0, 0.0), Vector2())
        for (index in 0..2) builder.index(group, index)

        val mesh = builder.buildMesh()
        val color = mesh.geometry.asDynamic().attributes.color

        assertEquals(4, color.itemSize)
        val array = color.array
        assertEquals(1.0, array[0].unsafeCast<Double>())
        assertEquals(0.0, array[1].unsafeCast<Double>())
        assertEquals(128.0 / 255, array[3].unsafeCast<Double>(), 1e-6)
        // Vertices without a color are white.
        for (i in 4 until 8) assertEquals(1.0, array[i].unsafeCast<Double>())

        val material = mesh.material.unsafeCast<Array<MeshBasicMaterial>>()[0].asDynamic()
        assertTrue(material.vertexColors.unsafeCast<Boolean>())
    }

    @Test
    fun models_without_baked_lighting_use_lit_materials() {
        // Items and characters carry no (or only white) vertex colors: the game lights them.
        for (color in listOf(null, -1)) {
            val (builder, texture) = builderWithTexture()
            val group = builder.getGroupIndex(3, alpha = false)
            for (x in 0..2) {
                builder.vertex(Vector3(x.toDouble(), 0.0, 0.0), Vector3(0.0, 1.0, 0.0), Vector2(), color = color)
                builder.index(group, x)
            }

            val material = builder.buildMesh().material.unsafeCast<Array<Material>>()[0]

            assertTrue(material is MeshLambertMaterial, "vertex color $color")
            assertSame(texture, material.asDynamic().map)
        }
    }

    @Test
    fun models_with_baked_lighting_stay_unlit() {
        val (builder, _) = builderWithTexture()
        val group = builder.getGroupIndex(3, alpha = false)
        for (x in 0..2) {
            builder.vertex(Vector3(x.toDouble(), 0.0, 0.0), Vector3(0.0, 1.0, 0.0), Vector2(), color = 0xFF808080.toInt())
            builder.index(group, x)
        }

        val material = builder.buildMesh().material.unsafeCast<Array<Material>>()[0]

        assertTrue(material is MeshBasicMaterial)
    }

    @Test
    fun lit_models_get_face_normals_where_vertex_normals_face_away() {
        // Two triangles share vertices 0 and 1: a floor (normal +y) and a wall facing +z. The
        // shared vertices carry the floor's normal, which lies in the wall's plane.
        val builder = MeshBuilder()
        val group = builder.getGroupIndex(null, alpha = false)
        val up = Vector3(0.0, 1.0, 0.0)
        builder.vertex(Vector3(0.0, 0.0, 0.0), up)
        builder.vertex(Vector3(1.0, 0.0, 0.0), up)
        builder.vertex(Vector3(0.0, 0.0, -1.0), up)
        builder.vertex(Vector3(0.0, -1.0, 0.0), Vector3(0.0, 0.0, 1.0))
        for (index in listOf(0, 1, 2, 0, 3, 1)) builder.index(group, index)

        val geometry = builder.buildMesh().geometry.asDynamic()
        val normals = geometry.attributes.normal.array
        val index = geometry.index.array

        // Floor corners keep their authored normal.
        for (c in 0..2) {
            assertEquals(1.0, (normals[3 * (index[c] as Int) + 1] as Number).toDouble(), 1e-6)
        }
        // Wall corners all face +z, on copies of the shared vertices.
        for (c in 3..5) {
            assertEquals(1.0, (normals[3 * (index[c] as Int) + 2] as Number).toDouble(), 1e-6)
        }
        assertEquals(6, geometry.attributes.position.count as Int)
    }

    @Test
    fun baked_lighting_models_keep_their_normals() {
        val builder = MeshBuilder()
        val group = builder.getGroupIndex(null, alpha = false)
        builder.vertex(Vector3(0.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), color = 0xFF808080.toInt())
        builder.vertex(Vector3(0.0, 0.0, 1.0), Vector3(0.0, 1.0, 0.0), color = 0xFF808080.toInt())
        builder.vertex(Vector3(0.0, -1.0, 0.0), Vector3(0.0, 1.0, 0.0), color = 0xFF808080.toInt())
        for (index in 0..2) builder.index(group, index)

        val geometry = builder.buildMesh().geometry.asDynamic()

        assertEquals(3, geometry.attributes.position.count as Int)
        assertEquals(1.0, (geometry.attributes.normal.array[1] as Number).toDouble(), 1e-6)
    }

    @Test
    fun blended_groups_render_in_one_pass() {
        val (builder, _) = builderWithTexture()
        val alphaGroup = builder.getGroupIndex(3, alpha = true)
        val additiveGroup = builder.getGroupIndex(3, alpha = false, srcAlpha = 4, dstAlpha = 1)
        addTriangle(builder, alphaGroup, additiveGroup)

        val materials = builder.buildMesh().material.unsafeCast<Array<Material>>()

        for (material in materials) {
            assertTrue(material.asDynamic().forceSinglePass == true)
        }
    }

    @Test
    fun skinned_meshes_are_not_frustum_culled() {
        val (builder, _) = builderWithTexture()
        builder.bone(Bone())
        val group = builder.getGroupIndex(3, alpha = false)
        for (position in listOf(Vector3(), Vector3(1.0, 0.0, 0.0), Vector3(0.0, 0.0, 1.0))) {
            builder.vertex(
                position,
                Vector3(0.0, 1.0, 0.0),
                Vector2(),
                boneIndices = intArrayOf(0, 0, 0, 0),
                boneWeights = floatArrayOf(1f, 0f, 0f, 0f),
            )
        }
        for (index in 0..2) builder.index(group, index)

        // three.js r151+ would cull by the pose of the first render instead of the current one.
        assertFalse(builder.buildSkinnedMesh().frustumCulled)
    }

    @Test
    fun no_color_attribute_without_vertex_colors() {
        val (builder, _) = builderWithTexture()
        val group = builder.getGroupIndex(3, alpha = false)
        addTriangle(builder, group)

        val mesh = builder.buildMesh()

        assertNull(mesh.geometry.asDynamic().attributes.color)
        val material = mesh.material.unsafeCast<Array<MeshBasicMaterial>>()[0].asDynamic()
        assertFalse(material.vertexColors.unsafeCast<Boolean>())
    }

    @Test
    fun ninja_blend_modes_map_to_three_blending() {
        val (builder, _) = builderWithTexture()
        val normal = builder.getGroupIndex(3, alpha = true, srcAlpha = 4, dstAlpha = 5)
        val additive = builder.getGroupIndex(3, alpha = true, srcAlpha = 4, dstAlpha = 1)
        val cutout = builder.getGroupIndex(3, alpha = false, alphaTest = .5)
        addTriangle(builder, normal, additive, cutout)

        val materials = builder.buildMesh().material.unsafeCast<Array<MeshBasicMaterial>>()
            .map { it.asDynamic() }

        assertSame(NormalBlending, materials[0].blending)
        assertTrue(materials[0].transparent.unsafeCast<Boolean>())

        assertSame(CustomBlending, materials[1].blending)
        assertSame(SrcAlphaFactor, materials[1].blendSrc)
        assertSame(OneFactor, materials[1].blendDst)
        assertFalse(materials[1].depthWrite.unsafeCast<Boolean>())

        assertFalse(materials[2].transparent.unsafeCast<Boolean>())
        assertEquals(.5, materials[2].alphaTest.unsafeCast<Double>())
    }

    @Test
    fun double_side_and_diffuse_are_applied_to_materials() {
        val (builder, _) = builderWithTexture()
        val single = builder.getGroupIndex(
            3, alpha = true, doubleSide = false, diffuse = 0x80FF8000.toInt(),
        )
        val double = builder.getGroupIndex(3, alpha = false, diffuse = 0x80FF8000.toInt())
        addTriangle(builder, single, double)

        val materials = builder.buildMesh().material.unsafeCast<Array<MeshBasicMaterial>>()
            .map { it.asDynamic() }

        assertSame(FrontSide, materials[0].side)
        assertEquals(1.0, materials[0].color.r.unsafeCast<Double>())
        assertEquals(128.0 / 255, materials[0].color.g.unsafeCast<Double>(), 1e-6)
        assertEquals(0.0, materials[0].color.b.unsafeCast<Double>())
        assertEquals(128.0 / 255, materials[0].opacity.unsafeCast<Double>(), 1e-6)

        assertSame(DoubleSide, materials[1].side)
        // Diffuse alpha only affects alpha blended groups.
        assertEquals(1.0, materials[1].opacity.unsafeCast<Double>())
    }

    @Test
    fun untextured_groups_use_their_diffuse_color() {
        val defaultMaterial = MeshLambertMaterial()
        val builder = MeshBuilder()
        builder.defaultMaterial(defaultMaterial)
        val colored = builder.getGroupIndex(null, alpha = false, diffuse = 0xFF0080FF.toInt())
        val plain = builder.getGroupIndex(null, alpha = false)
        addTriangle(builder, colored, plain)

        val materials = builder.buildMesh().material.unsafeCast<Array<Material>>()

        assertTrue(materials[0] is MeshLambertMaterial)
        assertNotSame(defaultMaterial, materials[0])
        val color = materials[0].asDynamic().color
        assertEquals(0.0, color.r.unsafeCast<Double>())
        assertEquals(128.0 / 255, color.g.unsafeCast<Double>(), 1e-6)
        assertEquals(1.0, color.b.unsafeCast<Double>())
        assertSame(defaultMaterial, materials[1])
    }

    @Test
    fun ninja_blend_factors() {
        assertSame(SrcAlphaFactor, ninjaBlendFactor(4, source = true))
        assertSame(OneFactor, ninjaBlendFactor(1, source = false))
        assertSame(DstColorFactor, ninjaBlendFactor(2, source = true))
        assertSame(SrcColorFactor, ninjaBlendFactor(2, source = false))
    }

    private fun builderWithTexture(): Pair<MeshBuilder, Texture> {
        val texture = DataTexture(Uint8Array(4), 1, 1)
        val textureCache = UnsafeMap<Int, Texture?>().apply { set(3, texture) }
        return Pair(MeshBuilder(textureCache = textureCache), texture)
    }

    private fun addTriangle(builder: MeshBuilder, vararg groups: Int) {
        builder.vertex(Vector3(), Vector3(0.0, 1.0, 0.0), Vector2())
        builder.vertex(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), Vector2(1.0, 0.0))
        builder.vertex(Vector3(0.0, 0.0, 1.0), Vector3(0.0, 1.0, 0.0), Vector2(0.0, 1.0))
        for (index in 0..2) {
            for (group in groups) builder.index(group, index)
        }
    }
}
