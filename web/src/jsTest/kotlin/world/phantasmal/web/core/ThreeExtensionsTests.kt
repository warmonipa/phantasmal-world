package world.phantasmal.web.core

import world.phantasmal.web.externals.three.Group
import world.phantasmal.web.externals.three.Mesh
import world.phantasmal.web.externals.three.SphereGeometry
import world.phantasmal.web.test.WebTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreeExtensionsTests : WebTestSuite {
    @Test
    fun bounding_sphere_copies_the_first_mesh_sphere_into_an_empty_result() = test {
        val group = Group()
        val mesh = Mesh(SphereGeometry(12.0)).apply {
            position.set(5.0, 7.0, 11.0)
        }
        group.add(mesh)
        group.updateMatrixWorld(true)

        val sphere = boundingSphere(group)

        assertEquals(5.0, sphere.center.x, absoluteTolerance = 1e-12)
        assertEquals(7.0, sphere.center.y, absoluteTolerance = 1e-12)
        assertEquals(11.0, sphere.center.z, absoluteTolerance = 1e-12)
        assertEquals(12.0, sphere.radius, absoluteTolerance = 1e-6)
    }
}
