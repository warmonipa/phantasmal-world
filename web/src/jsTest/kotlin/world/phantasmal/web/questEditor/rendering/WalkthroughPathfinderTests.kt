package world.phantasmal.web.questEditor.rendering

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

    @Test
    fun points_just_past_the_walkable_edge_snap_onto_it() = test {
        val collision = Group().apply { add(Mesh(horizontalPlane(100.0))) }
        val pathfinder = assertNotNull(CollisionWalkthroughPathfinder.create(collision))
        val pastEdge = WalkthroughPoint(51.5, 0.0, 0.0)

        assertNull(pathfinder.findPath(pastEdge, pastEdge))
        val snapped = assertNotNull(pathfinder.nearestWalkable(pastEdge))
        assertTrue(snapped.x <= 50.0 && snapped.x >= 47.5, snapped.toString())
        assertNotNull(pathfinder.findPath(WalkthroughPoint(-40.0, 0.0, 0.0), snapped))
        assertNull(pathfinder.nearestWalkable(WalkthroughPoint(80.0, 0.0, 0.0)))
    }

    @Test
    fun seams_join_neighbouring_floor_pieces_that_share_no_vertices() = test {
        // Soul of Dust 2, Cave 3: two floor squares meet along an edge 0.1 units apart in height,
        // and every collision triangle appears twice.
        val near = horizontalPlane(40.0).translate(0.0, 0.9, 0.0)
        val far = horizontalPlane(40.0).translate(0.0, 1.0, 40.0)
        val collision = Group().apply {
            add(Mesh(near), Mesh(near.clone()), Mesh(far), Mesh(far.clone()))
        }
        val pathfinder = assertNotNull(CollisionWalkthroughPathfinder.create(collision))

        assertNull(
            pathfinder.findPath(WalkthroughPoint(0.0, 1.0, -10.0), WalkthroughPoint(0.0, 1.0, 50.0)),
        )
        assertTrue(pathfinder.seams.isNotEmpty())
        val (a, b) = pathfinder.seams.first()
        assertTrue(kotlin.math.abs(a.z - 20.0) < 2.0 && kotlin.math.abs(b.z - 20.0) < 2.0, "$a $b")
        assertNotNull(pathfinder.findPath(WalkthroughPoint(0.0, 1.0, -10.0), if (a.z < 20.0) a else b))
        assertNotNull(pathfinder.findPath(WalkthroughPoint(0.0, 1.0, 50.0), if (a.z < 20.0) b else a))
    }

    @Test
    fun seams_on_a_spatial_hash_cell_boundary_are_found() = test {
        // Lost SHOCK GUNGNIR, VR Temple: floor pieces meet at x = 960 (a multiple of the 8-unit
        // hash cell), one ending at 959.99, the other starting at 960.05.
        val west = horizontalPlane(39.99).translate(940.0 - 0.005, 0.0, 0.0)
        val east = horizontalPlane(39.95).translate(980.0 + 0.025, 0.0, 0.0)
        val collision = Group().apply { add(Mesh(west), Mesh(east)) }
        val pathfinder = assertNotNull(CollisionWalkthroughPathfinder.create(collision))
        val (a, b) = assertNotNull(pathfinder.seams.firstOrNull())

        assertTrue(kotlin.math.abs(a.x - 960.0) < 2.0 && kotlin.math.abs(b.x - 960.0) < 2.0, "$a $b")
    }

    @Test
    fun points_on_a_pad_stacked_on_the_floor_reach_the_floor() = test {
        // Ember in the Dust - Echoes, Seabed: a switch stands on its own base 0.5 above the floor.
        val collision = Group().apply {
            add(
                Mesh(horizontalPlane(100.0)),
                Mesh(horizontalPlane(10.0).translate(20.0, 0.5, 0.0)),
            )
        }
        val pathfinder = assertNotNull(CollisionWalkthroughPathfinder.create(collision))

        assertNotNull(
            pathfinder.findPath(WalkthroughPoint(20.0, 0.5, 0.0), WalkthroughPoint(-40.0, 0.0, 0.0)),
        )
    }

    @Test
    fun points_on_the_walkable_border_snap_onto_a_polygon() = test {
        // Soul of Dust 8, Seabed Lower: a warp pad stands exactly on the border of the floor,
        // where three-pathfinding's polygon test excludes it.
        val collision = Group().apply { add(Mesh(horizontalPlane(100.0))) }
        val pathfinder = assertNotNull(CollisionWalkthroughPathfinder.create(collision))
        val center = WalkthroughPoint(0.0, 0.0, 0.0)

        for (border in listOf(
            WalkthroughPoint(0.0, 0.0, 50.0), WalkthroughPoint(0.0, 0.0, -50.0),
            WalkthroughPoint(50.0, 0.0, 0.0), WalkthroughPoint(-50.0, 0.0, 0.0),
        )) {
            val snapped = assertNotNull(pathfinder.nearestWalkable(border), "$border")
            assertNotNull(pathfinder.findPath(center, snapped), "$border -> $snapped")
        }
    }

    private fun horizontalPlane(size: Double) = PlaneGeometry(size, size, 1.0, 1.0).apply {
        rotateX(-PI / 2)
    }
}
