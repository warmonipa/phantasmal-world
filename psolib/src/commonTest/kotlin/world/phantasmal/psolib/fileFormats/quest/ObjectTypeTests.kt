package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.psolib.test.LibTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObjectTypeTests : LibTestSuite {
    @Test
    fun season_object_types_map_to_their_lobby_event() {
        assertEquals(LobbyEvent.Christmas, ObjectType.ChristmasTree.lobbyEvent)
        assertEquals(LobbyEvent.Christmas, ObjectType.ChristmasWreath.lobbyEvent)
        assertEquals(LobbyEvent.Valentine, ObjectType.ValentinesHeart.lobbyEvent)
        assertEquals(LobbyEvent.Easter, ObjectType.EasterEgg.lobbyEvent)
        assertEquals(LobbyEvent.Halloween, ObjectType.HalloweenPumpkin.lobbyEvent)
        assertEquals(LobbyEvent.Sonic, ObjectType.Sonic.lobbyEvent)
        assertEquals(LobbyEvent.NewYear, ObjectType.TwentyFirstCentury.lobbyEvent)
        assertEquals(LobbyEvent.NewYear, ObjectType.Firework.lobbyEvent)
    }

    @Test
    fun welcome_board_has_no_lobby_event_because_its_event_is_unknown() {
        // WelcomeBoard is a city-lobby object but its event ID is not known, so it is
        // treated as always-visible (lobbyEvent = null).
        assertNull(ObjectType.WelcomeBoard.lobbyEvent)
    }

    @Test
    fun non_season_object_types_have_no_lobby_event() {
        assertNull(ObjectType.PlayerSet.lobbyEvent)
        assertNull(ObjectType.Teleporter.lobbyEvent)
        assertNull(ObjectType.Unknown.lobbyEvent)
    }

    @Test
    fun exactly_eight_object_types_are_seasonal() {
        // Guard: if a TObjCity_Season_* type is added/removed, update the mapping test above.
        val seasonal = ObjectType.entries.filter { it.lobbyEvent != null }
        assertEquals(8, seasonal.size, "Update the seasonal mapping test when this changes.")
    }
}
