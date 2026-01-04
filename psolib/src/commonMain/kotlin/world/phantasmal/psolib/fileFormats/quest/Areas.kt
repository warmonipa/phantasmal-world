package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.psolib.Episode

class Area(
    val id: Int, // Area ID
    val mapId: Int, // Map ID
    val name: String, // Area name
    val bossArea: Boolean, // Is this a boss area?
    val order: Int, // Order in episode
    val areaVariants: List<AreaVariant>, // Variants of this area
)

class AreaVariant(
    val id: Int, // Area variant ID
    val area: Area, // Parent area
)

fun getAreasForEpisode(episode: Episode): List<Area> =
    AREAS.getValue(episode)

private val AREAS by lazy {
    var order = 0

    @Suppress("UNUSED_CHANGED_VALUE")
    val ep1 = listOf(
        createArea(0, 0x00, "Pioneer II", order++, 1),
        createArea(1, 0x01, "Forest 1", order++, 1),
        createArea(2, 0x02, "Forest 2", order++, 1),
        createArea(3, 0x03, "Cave 1", order++, 6),
        createArea(4, 0x04, "Cave 2", order++, 5),
        createArea(5, 0x05, "Cave 3", order++, 6),
        createArea(6, 0x06, "Mine 1", order++, 6),
        createArea(7, 0x07, "Mine 2", order++, 6),
        createArea(8, 0x08, "Ruins 1", order++, 5),
        createArea(9, 0x09, "Ruins 2", order++, 5),
        createArea(10, 0x0A, "Ruins 3", order++, 5),
        createBossArea(11, 0x0B, "Under the Dome", order++),
        createBossArea(12, 0x0C, "Underground Channel", order++),
        createBossArea(13, 0x0D, "Monitor Room", order++),
        createBossArea(14, 0x0E, "Dark Falz", order++),
        createArea(15, 0x0F, "Lobby", order++, 15),
        createArea(16, 0x10, "BA Spaceship", order++, 3),
        createArea(17, 0x11, "BA Palace", order++, 3),
    )

    order = 0

    @Suppress("UNUSED_CHANGED_VALUE")
    val ep2 = listOf(
        createArea(0, 0x12, "Lab", order++, 1),
        createArea(1, 0x13, "VR Temple Alpha", order++, 3),
        createArea(2, 0x14, "VR Temple Beta", order++, 3),
        createArea(3, 0x15, "VR Spaceship Alpha", order++, 3),
        createArea(4, 0x16, "VR Spaceship Beta", order++, 3),
        createArea(5, 0x17, "Central Control Area", order++, 1),
        createArea(6, 0x18, "Jungle Area East", order++, 1),
        createArea(7, 0x19, "Jungle Area North", order++, 1),
        createArea(8, 0x1A, "Mountain Area", order++, 3),
        createArea(9, 0x1B, "Seaside Area", order++, 1),
        createArea(10, 0x1C, "Seabed Upper Levels", order++, 3),
        createArea(11, 0x1D, "Seabed Lower Levels", order++, 3),
        createBossArea(12, 0x1E, "Cliffs of Gal Da Val", order++),
        createBossArea(13, 0x1F, "Test Subject Disposal Area", order++),
        createBossArea(14, 0x20, "VR Temple Final", order++),
        createBossArea(15, 0x21, "VR Spaceship Final", order++),
        createArea(16, 0x22, "Seaside Area at Night", order++, 2),
        createArea(17, 0x23, "Tower", order++, 5),
    )

    order = 0

    @Suppress("UNUSED_CHANGED_VALUE")
    val ep4 = listOf(
        createArea(0, 0x2D, "Pioneer II", order++, 1),
        createArea(1, 0x24, "Crater Route 1", order++, 1),
        createArea(2, 0x25, "Crater Route 2", order++, 1),
        createArea(3, 0x26, "Crater Route 3", order++, 1),
        createArea(4, 0x27, "Crater Route 4", order++, 1),
        createArea(5, 0x28, "Crater Interior", order++, 1),
        createArea(6, 0x29, "Subterranean Desert 1", order++, 3),
        createArea(7, 0x2A, "Subterranean Desert 2", order++, 3),
        createArea(8, 0x2B, "Subterranean Desert 3", order++, 3),
        createBossArea(9, 0x2C, "Meteor Impact Site", order++),
    )

    mapOf(
        Episode.I to ep1,
        Episode.II to ep2,
        Episode.IV to ep4,
    )
}

private fun createArea(id: Int, mapId: Int, name: String, order: Int, variants: Int): Area {
    return createArea(id, mapId, name, false, order, variants)
}

private fun createBossArea(id: Int, mapId: Int, name: String, order: Int): Area {
    return createArea(id, mapId, name, true, order, 1)
}

private fun createArea(id: Int, mapId: Int, name: String, bossArea: Boolean, order: Int, variants: Int): Area {
    val avs = mutableListOf<AreaVariant>()
    val area = Area(id, mapId, name, bossArea, order, avs)

    for (avId in 0 until variants) {
        avs.add(AreaVariant(avId, area))
    }

    return area
}

/**
 * Cache of all areas indexed by mapId for quick lookup.
 */
private val areasByMapId: Map<Int, Area> by lazy {
    (getAreasForEpisode(Episode.I) +
     getAreasForEpisode(Episode.II) +
     getAreasForEpisode(Episode.IV))
        .associateBy { it.mapId }
}

/**
 * Cache of all areas indexed by (episode, areaId) for quick lookup.
 * Episode is represented as Int: 0=I, 1=II, 2=IV
 */
private val areasByEpisodeAndId: Map<Pair<Int, Int>, Area> by lazy {
    val result = mutableMapOf<Pair<Int, Int>, Area>()

    getAreasForEpisode(Episode.I).forEach { area ->
        result[Pair(0, area.id)] = area
    }
    getAreasForEpisode(Episode.II).forEach { area ->
        result[Pair(1, area.id)] = area
    }
    getAreasForEpisode(Episode.IV).forEach { area ->
        result[Pair(2, area.id)] = area
    }

    result
}

/**
 * Finds an Area by its mapId.
 * @param mapId The game-internal map ID
 * @return The corresponding Area or null if not found
 */
fun findAreaByMapId(mapId: Int): Area? = areasByMapId[mapId]

/**
 * Finds an Area by episode and areaId.
 * @param episode The episode number (0=I, 1=II, 2=IV)
 * @param areaId The area ID within the episode
 * @return The corresponding Area or null if not found
 */
fun findAreaByEpisodeAndAreaId(episode: Int, areaId: Int): Area? =
    areasByEpisodeAndId[Pair(episode, areaId)]

/**
 * Maps game-internal map ID to model area ID.
 * @param mapId The game-internal map ID
 * @return The area ID or null if not found
 */
fun getAreaIdByMapId(mapId: Int): Int? = findAreaByMapId(mapId)?.id

/**
 * Maps episode and area ID to map ID.
 * @param episode The episode number (0=I, 1=II, 2=IV)
 * @param areaId The area ID within the episode
 * @return The map ID or 0 if not found
 */
fun getMapId(episode: Int, areaId: Int): Int =
    findAreaByEpisodeAndAreaId(episode, areaId)?.mapId ?: 0

/**
 * Check if current area is a boss area
 */
fun isBossArea(episode: Episode, areaId: Int): Boolean {
    AREAS[episode]?.any { area -> area.id == areaId && area.bossArea }.let { return it == true }
}

fun isPioneer2OrLab(episode: Episode, areaId: Int): Boolean {
    return when (episode) {
        Episode.I -> areaId == 0  // EP1 Pioneer II
        Episode.II -> areaId == 0 // EP2 Lab
        Episode.IV -> areaId == 0 // EP4 Pioneer II
    }
}
