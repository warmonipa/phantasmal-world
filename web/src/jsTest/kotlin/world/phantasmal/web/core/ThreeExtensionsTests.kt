package world.phantasmal.web.core

import world.phantasmal.web.externals.three.Group
import world.phantasmal.web.externals.three.InstancedMesh
import world.phantasmal.web.externals.three.Matrix4
import world.phantasmal.web.externals.three.Mesh
import world.phantasmal.web.externals.three.MeshBasicMaterial
import world.phantasmal.web.externals.three.Quaternion
import world.phantasmal.web.externals.three.Raycaster
import world.phantasmal.web.externals.three.SphereGeometry
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.test.WebTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun moved_instances_stay_pickable() = test {
        val mesh = InstancedMesh(SphereGeometry(1.0), MeshBasicMaterial(), 1)
        mesh.setMatrixAt(0, Matrix4())
        mesh.instancesChanged()
        val raycaster = Raycaster()

        // The first raycast computes and caches the bounds with the instance at the origin.
        raycaster.set(Vector3(0.3, 10.0, 0.0), Vector3(0.0, -1.0, 0.0))
        assertTrue(raycaster.intersectObject(mesh).isNotEmpty())

        mesh.setMatrixAt(
            0,
            Matrix4().compose(Vector3(100.0, 0.0, 0.0), Quaternion(), Vector3(1.0, 1.0, 1.0)),
        )
        mesh.instancesChanged()

        raycaster.set(Vector3(100.3, 10.0, 0.0), Vector3(0.0, -1.0, 0.0))
        assertTrue(raycaster.intersectObject(mesh).isNotEmpty())
    }
}
