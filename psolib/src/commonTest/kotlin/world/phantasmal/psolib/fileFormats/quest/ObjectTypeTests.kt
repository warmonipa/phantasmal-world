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
    fun welcome_board_and_non_season_types_have_no_lobby_event() {
        assertNull(ObjectType.WelcomeBoard.lobbyEvent)
        assertNull(ObjectType.PlayerSet.lobbyEvent)
        assertNull(ObjectType.Teleporter.lobbyEvent)
        assertNull(ObjectType.Unknown.lobbyEvent)
    }
}
