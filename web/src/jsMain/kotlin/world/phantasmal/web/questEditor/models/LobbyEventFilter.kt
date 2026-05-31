package world.phantasmal.web.questEditor.models

import world.phantasmal.psolib.fileFormats.quest.LobbyEvent

/**
 * UI selection for which Pioneer 2 / city seasonal decorations to render.
 *
 * - [None]: hide all seasonal decorations (default — a clean city).
 * - [All]: show every seasonal decoration at once (the raw data; opt-in).
 * - [Event]: show only the given event's decorations.
 *
 * Objects with no [LobbyEvent] (`ObjectType.lobbyEvent == null`) are always shown.
 */
sealed interface LobbyEventFilter {
    object None : LobbyEventFilter
    object All : LobbyEventFilter
    data class Event(val event: LobbyEvent) : LobbyEventFilter
}

/**
 * Returns `true` if an object should be shown under [filter].
 *
 * Objects whose `ObjectType.lobbyEvent` is `null` (non-seasonal) always pass. Seasonal objects
 * (non-null [objectEvent]) pass only when [filter] is [LobbyEventFilter.All] or matches their
 * specific event.
 */
fun lobbyEventSeasonOk(objectEvent: LobbyEvent?, filter: LobbyEventFilter): Boolean =
    when (filter) {
        LobbyEventFilter.None -> objectEvent == null
        LobbyEventFilter.All -> true
        is LobbyEventFilter.Event -> objectEvent == null || objectEvent == filter.event
    }

/** Human-readable label for the toolbar dropdown. */
fun lobbyEventFilterLabel(filter: LobbyEventFilter): String =
    when (filter) {
        LobbyEventFilter.None -> "None"
        LobbyEventFilter.All -> "All"
        is LobbyEventFilter.Event -> when (filter.event) {
            LobbyEvent.Christmas -> "Christmas"
            LobbyEvent.Valentine -> "Valentine"
            LobbyEvent.Easter -> "Easter"
            LobbyEvent.Halloween -> "Halloween"
            LobbyEvent.Sonic -> "Sonic"
            LobbyEvent.NewYear -> "New Year"
        }
    }
