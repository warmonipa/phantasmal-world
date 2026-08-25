package world.phantasmal.web.questEditor.rendering

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import world.phantasmal.web.externals.three.Group
import world.phantasmal.web.externals.three.Mesh
import world.phantasmal.web.externals.three.PlaneGeometry
import world.phantasmal.web.test.WebTestSuite

class WalkthroughPathfinderTests : WebTestSuite {
    @Test
    fun finds_a_path_on_loaded_walkable_collision_geometry() = test {
        val collision = Group().apply {
            add(Mesh(horizontalPlane(100.0)))
        }
        val pathfinder = assertNotNull(CollisionWalkthroughPathfinder.create(collision))

        val path = assertNotNull(
            pathfinder.findPath(
                WalkthroughPoint(-40.0, 0.0, -40.0),
                WalkthroughPoint(40.0, 0.0, 40.0),
            ),
        )

        assertEquals(-40.0, path.first().x)
        assertEquals(-40.0, path.first().z)
        assertEquals(0.0, path.first().y, absoluteTolerance = 1e-9)
        assertEquals(40.0, path.last().x)
        assertEquals(40.0, path.last().z)
        assertEquals(0.0, path.last().y, absoluteTolerance = 1e-9)
    }

    @Test
    fun does_not_connect_disconnected_collision_islands() = test {
        val collision = Group().apply {
            add(
                Mesh(horizontalPlane(40.0).translate(-60.0, 0.0, 0.0)),
                Mesh(horizontalPlane(40.0).translate(60.0, 0.0, 0.0)),
            )
        }
        val pathfinder = assertNotNull(CollisionWalkthroughPathfinder.create(collision))

        assertNull(
            pathfinder.findPath(
                WalkthroughPoint(-60.0, 0.0, 0.0),
                WalkthroughPoint(60.0, 0.0, 0.0),
            ),
        )
    }

    @Test
    fun shared_geometry_instances_are_kept_at_each_world_transform() = test {
        val plane = horizontalPlane(40.0)
        val collision = Group().apply {
            add(
                Mesh(plane).apply { position.x = -60.0 },
                Mesh(plane).apply { position.x = 60.0 },
            )
        }
        val pathfinder = assertNotNull(CollisionWalkthroughPathfinder.create(collision))

        assertNotNull(
            pathfinder.findPath(
                WalkthroughPoint(60.0, 0.0, -10.0),
                WalkthroughPoint(60.0, 0.0, 10.0),
            ),
        )
    }

    @Test
    fun world_transform_rotated_floor_is_walkable() = test {
        val collision = Group().apply {
            add(Mesh(PlaneGeometry(40.0, 40.0)).apply { rotation.x = -PI / 2 })
        }
        val pathfinder = assertNotNull(CollisionWalkthroughPathfinder.create(collision))

        assertNotNull(
            pathfinder.findPath(
                WalkthroughPoint(-10.0, 0.0, -10.0),
                WalkthroughPoint(10.0, 0.0, 10.0),
            ),
        )
    }

    @Test
    fun projects_route_endpoints_to_the_nearest_vertical_floor() = test {
        val collision = Group().apply {
            add(
                Mesh(horizontalPlane(100.0)),
                Mesh(horizontalPlane(100.0).translate(0.0, 20.0, 0.0)),
            )
        }
        val pathfinder = assertNotNull(CollisionWalkthroughPathfinder.create(collision))

        val lowerPath = assertNotNull(
            pathfinder.findPath(
                WalkthroughPoint(-20.0, 1.0, -20.0),
                WalkthroughPoint(20.0, 1.0, 20.0),
            ),
        )
        val upperPath = assertNotNull(
            pathfinder.findPath(
                WalkthroughPoint(-20.0, 19.0, -20.0),
                WalkthroughPoint(20.0, 19.0, 20.0),
            ),
        )

        assertEquals(0.0, lowerPath.first().y, absoluteTolerance = 1e-9)
        assertEquals(20.0, upperPath.first().y, absoluteTolerance = 1e-9)
    }

    @Test
    fun vertical_collision_faces_do_not_connect_stacked_walkable_surfaces() = test {
        val collision = Group().apply {
            add(
                Mesh(horizontalPlane(40.0).translate(-20.0, 0.0, 0.0)),
                Mesh(horizontalPlane(40.0).translate(20.0, 20.0, 0.0)),
                Mesh(PlaneGeometry(40.0, 20.0).rotateY(PI / 2).translate(0.0, 10.0, 0.0)),
            )
        }
        val pathfinder = assertNotNull(CollisionWalkthroughPathfinder.create(collision))

        assertNull(
            pathfinder.findPath(
                WalkthroughPoint(-20.0, 0.0, 0.0),
                WalkthroughPoint(20.0, 20.0, 0.0),
            ),
        )
    }

    private fun horizontalPlane(size: Double) = PlaneGeometry(size, size, 1.0, 1.0).apply {
        rotateX(-PI / 2)
    }
}
