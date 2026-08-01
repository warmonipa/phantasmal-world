package world.phantasmal.web.core.rendering.conversion

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import world.phantasmal.psolib.fileFormats.Vec2
import world.phantasmal.psolib.fileFormats.Vec3
import world.phantasmal.psolib.fileFormats.ninja.XjMaterial
import world.phantasmal.psolib.fileFormats.ninja.XjMesh
import world.phantasmal.psolib.fileFormats.ninja.XjModel
import world.phantasmal.psolib.fileFormats.ninja.XjVertex

class NinjaGeometryConversionTests {
    @Test
    fun detectsEnvironmentMappedXjMeshes() {
        val vertices = listOf(
            vertex(uv = null),
            vertex(uv = null),
            vertex(uv = Vec2(0f, 0f)),
        )
        val model = XjModel(vertices, emptyList(), Vec3(0f, 0f, 0f), 1f)

        assertTrue(usesEnvironmentMapping(model, mesh(0, 1), textureIndex = 0))
        assertFalse(usesEnvironmentMapping(model, mesh(0, 2), textureIndex = 0))
        assertFalse(usesEnvironmentMapping(model, mesh(0, 1), textureIndex = null))
        assertFalse(usesEnvironmentMapping(model, mesh(), textureIndex = 0))
    }

    private fun vertex(uv: Vec2?) =
        XjVertex(Vec3(0f, 0f, 0f), Vec3(0f, 1f, 0f), uv)

    private fun mesh(vararg indices: Int) =
        XjMesh(
            XjMaterial(
                srcAlpha = null,
                dstAlpha = null,
                textureId = null,
                diffuseR = null,
                diffuseG = null,
                diffuseB = null,
                diffuseA = null,
            ),
            indices.toList(),
        )
}
