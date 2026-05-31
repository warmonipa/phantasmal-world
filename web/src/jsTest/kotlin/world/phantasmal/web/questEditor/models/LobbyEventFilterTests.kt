package world.phantasmal.web.questEditor.models

import world.phantasmal.psolib.fileFormats.quest.LobbyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LobbyEventFilterTests {
    @Test
    fun none_hides_all_season_objects_but_keeps_non_season() {
        assertTrue(lobbyEventSeasonOk(null, LobbyEventFilter.None))
        assertFalse(lobbyEventSeasonOk(LobbyEvent.Christmas, LobbyEventFilter.None))
        assertFalse(lobbyEventSeasonOk(LobbyEvent.Halloween, LobbyEventFilter.None))
    }

    @Test
    fun all_shows_everything() {
        assertTrue(lobbyEventSeasonOk(null, LobbyEventFilter.All))
        assertTrue(lobbyEventSeasonOk(LobbyEvent.Christmas, LobbyEventFilter.All))
        assertTrue(lobbyEventSeasonOk(LobbyEvent.Sonic, LobbyEventFilter.All))
    }

    @Test
    fun event_shows_only_that_event_plus_non_season() {
        val xmas = LobbyEventFilter.Event(LobbyEvent.Christmas)
        assertTrue(lobbyEventSeasonOk(null, xmas))
        assertTrue(lobbyEventSeasonOk(LobbyEvent.Christmas, xmas))
        assertFalse(lobbyEventSeasonOk(LobbyEvent.Halloween, xmas))
    }

    @Test
    fun labels_are_human_readable() {
        assertEquals("None", lobbyEventFilterLabel(LobbyEventFilter.None))
        assertEquals("All", lobbyEventFilterLabel(LobbyEventFilter.All))
        assertEquals("Christmas", lobbyEventFilterLabel(LobbyEventFilter.Event(LobbyEvent.Christmas)))
        assertEquals("New Year", lobbyEventFilterLabel(LobbyEventFilter.Event(LobbyEvent.NewYear)))
    }
}
