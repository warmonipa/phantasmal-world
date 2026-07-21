package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.test.LibTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BossTeleporterTests : LibTestSuite {
    @Test
    fun episode_1_normal_mode_destinations_match_bb_client_table() {
        val expected = listOf(
            0, 11, 11, 12, 12, 12, 13, 13, 14, 14, 14, 0, 0, 0, 0, 0, 0, 0,
        )

        assertEquals(
            expected,
            expected.indices.map { getNormalBossTeleporterDestinationFloor(Episode.I, it) },
        )
    }

    @Test
    fun episode_2_and_4_normal_mode_destinations_match_bb_client_table() {
        assertEquals(14, getNormalBossTeleporterDestinationFloor(Episode.II, 1))
        assertEquals(15, getNormalBossTeleporterDestinationFloor(Episode.II, 3))
        assertEquals(12, getNormalBossTeleporterDestinationFloor(Episode.II, 5))
        assertEquals(13, getNormalBossTeleporterDestinationFloor(Episode.II, 10))
        assertEquals(9, getNormalBossTeleporterDestinationFloor(Episode.IV, 1))
        assertEquals(9, getNormalBossTeleporterDestinationFloor(Episode.IV, 10))
    }

    @Test
    fun invalid_logical_floor_has_no_destination() {
        assertNull(getNormalBossTeleporterDestinationFloor(Episode.I, -1))
        assertNull(getNormalBossTeleporterDestinationFloor(Episode.I, 18))
    }
}
