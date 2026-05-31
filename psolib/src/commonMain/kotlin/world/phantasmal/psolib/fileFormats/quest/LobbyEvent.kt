package world.phantasmal.psolib.fileFormats.quest

/**
 * PSO lobby (Pioneer 2 / city) seasonal events. The game shows only the `TObjCity_Season_*`
 * decoration objects whose event matches the current lobby event (sent by the server via the
 * `DA` command). Values and numbering follow newserv `StaticGameData.cc`:
 * `1 xmas, 3 val, 4 easter, 5 hallo, 6 sonic, 7 newyear`. Only events that have at least one
 * decoration object type are represented here.
 */
enum class LobbyEvent {
    Christmas,
    Valentine,
    Easter,
    Halloween,
    Sonic,
    NewYear,
}
