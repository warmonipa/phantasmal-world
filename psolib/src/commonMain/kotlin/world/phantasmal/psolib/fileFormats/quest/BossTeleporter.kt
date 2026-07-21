package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.psolib.Episode

/**
 * Returns the destination logical floor selected by the BB client's normal-mode Boss Teleporter
 * table, or null when [sourceFloorId] is outside the client's 18 logical floor slots.
 *
 * BB `get_boss_floor_for_current_floor` at 0x0080C340 indexes the table at 0x0097F380 with
 * `g_CurrentFloor + g_CurrentEpisode * 0x12`. Challenge mode uses a separate map-indexed table and
 * is intentionally not represented by this function.
 */
fun getNormalBossTeleporterDestinationFloor(
    episode: Episode,
    sourceFloorId: Int,
): Int? =
    NORMAL_BOSS_TELEPORTER_DESTINATIONS
        .getValue(episode)
        .getOrNull(sourceFloorId)

private val NORMAL_BOSS_TELEPORTER_DESTINATIONS: Map<Episode, IntArray> = mapOf(
    Episode.I to intArrayOf(
        0, 11, 11, 12, 12, 12, 13, 13, 14, 14, 14, 0, 0, 0, 0, 0, 0, 0,
    ),
    Episode.II to intArrayOf(
        0, 14, 14, 15, 15, 12, 12, 12, 12, 12, 13, 13, 0, 0, 0, 0, 0, 0,
    ),
    Episode.IV to intArrayOf(
        0, 9, 9, 9, 9, 9, 9, 9, 9, 0, 9, 0, 0, 0, 0, 0, 0, 0,
    ),
)
