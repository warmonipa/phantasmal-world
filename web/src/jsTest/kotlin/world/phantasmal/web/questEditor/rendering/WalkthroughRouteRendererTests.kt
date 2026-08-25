package world.phantasmal.web.questEditor.rendering

import kotlin.test.Test
import kotlin.test.assertEquals
import world.phantasmal.web.test.WebTestSuite

class WalkthroughRouteRendererTests : WebTestSuite {
    @Test
    fun route_segments_follow_sampled_ground_height_with_a_small_clearance() = test {
        val segment = WalkthroughSegment(
            floorId = 7,
            from = WalkthroughPoint(0.0, -10.0, 0.0),
            to = WalkthroughPoint(24.0, -10.0, 0.0),
        )

        val points = sampleWalkthroughSegment(segment, maxSampleLength = 12.0) { x, _, _ ->
            x / 2.0
        }

        assertEquals(listOf(2.0, 8.0, 14.0), points.map { it.y })
    }

    @Test
    fun route_segments_fall_back_to_interpolated_entity_height_outside_the_map() = test {
        val segment = WalkthroughSegment(
            floorId = 7,
            from = WalkthroughPoint(0.0, 3.0, 0.0),
            to = WalkthroughPoint(12.0, 9.0, 0.0),
        )

        val points = sampleWalkthroughSegment(segment, maxSampleLength = 6.0) { _, _, _ -> null }

        assertEquals(listOf(5.0, 8.0, 11.0), points.map { it.y })
    }
}
