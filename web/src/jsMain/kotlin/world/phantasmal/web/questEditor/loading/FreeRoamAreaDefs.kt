package world.phantasmal.web.questEditor.loading

import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.fileFormats.quest.Quest

// ---- Free roam area info ----

internal const val LOBBY_FLOOR_ID = 15

/**
 * Unified info for a free roam area, regardless of whether the user opened a bin or dat file.
 */
class FreeRoamAreaInfo(
    val episode: Episode,
    val floorRange: IntRange,
    /** Bin prefix for looking up the bin file, e.g. "map_forest", "map_city". */
    val binPrefix: String? = null,
    /** Dat token base for looking up per-floor dat/evt files, e.g. "forest", "cave". */
    val tokenBase: String? = null,
    /** Whether this is a city/lab area (has online/offline mode). */
    val isCity: Boolean = false,
    val offline: Boolean = false,
    val ultimate: Boolean = false,
)

/**
 * Result of loading a free roam quest.
 */
class FreeRoamResult(
    val quest: Quest,
    /** The bin filename that was loaded, or null if no bin was available. */
    val binName: String?,
    /** Map of floorId → (objDatName, npcDatName, evtName) for saving back. */
    val datFilesByFloor: Map<Int, Triple<String, String, String>>,
)

// ---- Filename parsing ----

/** Language suffixes to try when looking for bin files. */
internal val BIN_LANG_SUFFIXES = listOf("j", "e")

private val CITY_BIN_REGEX =
    Regex("""^(map_city02|map_city|map_labo)_(on|off)_[a-z](_u)?\.bin$""")

private val FIELD_BIN_REGEX =
    Regex("""^(map_forest|map_cave|map_machine|map_ancient|map_ruin|map_space|map_jungle|map_seabed|map_wilds|map_crater|map_desert)_[a-z](_u)?\.bin$""")

/**
 * Captures the full dat token including digits, e.g. "forest01", "city00", "city02", "wilds01".
 *
 * Accepts both:
 *   - BB style: `map_<token>(_NN)*[oe](_s)?.dat`     (separate obj/npc/event files)
 *   - V3 style: `map_<token>(_NN)*a?d.dat`            (combined d.dat + alt ad.dat)
 *
 * Group 1 = token, group 2 = "_s" offline marker or empty (V3 has no `_s`).
 */
private val FREE_ROAM_DAT_REGEX =
    Regex("""^map_([a-z]+\d*)(?:_\d+)*(?:[oe]|a?d)(_s)?\.dat$""")

data class LobbyVariantDef(
    val number: Int,
    val assetBaseName: String,
    val datFileName: String = "${assetBaseName}o.dat",
)

/** Ephinea's 30-lobby layout, introduced in July 2024. */
val LOBBY_VARIANTS: List<LobbyVariantDef> =
    (1..10).map { number ->
        LobbyVariantDef(number, "map_lobby_${number.toString().padStart(2, '0')}")
    } + listOf(
        LobbyVariantDef(11, "map_lobby_black_be00"),
        LobbyVariantDef(12, "map_lobby_blue_be00"),
        LobbyVariantDef(13, "map_lobby_bluegreen_be00"),
        LobbyVariantDef(14, "map_lobby_green_be00"),
        LobbyVariantDef(15, "map_lobby_orange_be00"),
        LobbyVariantDef(16, "map_lobby_purple_be00"),
        LobbyVariantDef(17, "map_lobby_red_be00"),
        LobbyVariantDef(18, "map_lobby_white_be00"),
        LobbyVariantDef(19, "map_lobby_y_green_be00"),
        LobbyVariantDef(20, "map_lobby_yellow_be00"),
    ) + (1..5).map { index ->
        LobbyVariantDef(20 + index, "map_cardlobby${index.toString().padStart(2, '0')}")
    } + (11..15).map { index ->
        LobbyVariantDef(15 + index, "map_soccer$index")
    }

private val LOBBY_VARIANTS_BY_FILE_NAME =
    LOBBY_VARIANTS.associateBy { it.datFileName }.toMutableMap().apply {
        // Original Episode III filenames used by older clients.
        this["map_lobby_soccer01o.dat"] = LOBBY_VARIANTS[25]
        this["map_lobby_soccer02o.dat"] = LOBBY_VARIANTS[26]
    }

fun getLobbyVariant(number: Int): LobbyVariantDef? =
    LOBBY_VARIANTS.getOrNull(number - 1)?.takeIf { it.number == number }

/**
 * Resolves the resource basename for an editor lobby variant.
 *
 * Variant 0 predates Ephinea's numbered layout. Its geometry uses `map_lobby_00`, while its
 * texture has always fallen back to lobby 1 because there is no `map_lobby_00.xvm`.
 */
internal fun getLobbyAssetBaseName(number: Int, texture: Boolean): String? =
    if (number == 0) {
        if (texture) "map_lobby_01" else "map_lobby_00"
    } else {
        getLobbyVariant(number)?.assetBaseName
    }

/** Returns the Ephinea lobby number associated with a loose lobby object DAT. */
fun parseLobbyDatFilename(fileName: String): Int? =
    LOBBY_VARIANTS_BY_FILE_NAME[fileName.lowercase()]?.number

/**
 * Single source of truth for all free roam area definitions.
 * Each entry: group key → (episode, floor range, bin prefix or null, isCity).
 */
private data class AreaDef(
    val episode: Episode,
    val floorRange: IntRange,
    val binPrefix: String?,
    val isCity: Boolean = false,
)

private val AREA_DEFS: Map<String, AreaDef> = mapOf(
    // EP1
    "city00"  to AreaDef(Episode.I,  0..0,   "map_city",    isCity = true),
    "forest"  to AreaDef(Episode.I,  1..2,   "map_forest"),
    "cave"    to AreaDef(Episode.I,  3..5,   "map_cave"),
    "machine" to AreaDef(Episode.I,  6..7,   "map_machine"),
    "ancient" to AreaDef(Episode.I,  8..10,  "map_ancient"),
    // EP2
    "labo"    to AreaDef(Episode.II, 0..0,   "map_labo",    isCity = true),
    "ruins"   to AreaDef(Episode.II, 1..2,   "map_ruin"),  // dat "ruins" → bin "map_ruin"
    "space"   to AreaDef(Episode.II, 3..4,   "map_space"),
    "jungle"  to AreaDef(Episode.II, 5..9,   "map_jungle"),
    "seabed"  to AreaDef(Episode.II, 10..11, "map_seabed"),
    // EP4
    "city02"  to AreaDef(Episode.IV, 0..0,   "map_city02",  isCity = true),
    "wilds"   to AreaDef(Episode.IV, 1..4,   null),
    "crater"  to AreaDef(Episode.IV, 5..5,   null),
    "desert"  to AreaDef(Episode.IV, 6..8,   null),
)

/** Reverse lookup: bin prefix → (group key, AreaDef). Built from AREA_DEFS. */
private val BIN_PREFIX_TO_AREA: Map<String, Pair<String, AreaDef>> =
    AREA_DEFS.entries
        .filter { it.value.binPrefix != null }
        .associate { (key, def) -> def.binPrefix!! to Pair(key, def) }

/**
 * Resolves a dat token (captured from filename) to an area group key.
 * Exact match first (handles city00 vs city02), then strips trailing digits.
 */
private fun datTokenToGroupKey(fullToken: String): String {
    if (fullToken in AREA_DEFS) return fullToken
    return fullToken.trimEnd { it.isDigit() }
}

/**
 * Parse a free roam filename (bin or dat) into a unified [FreeRoamAreaInfo].
 * Returns null if the filename doesn't match any known free roam pattern.
 */
fun parseFreeRoamFilename(fileName: String): FreeRoamAreaInfo? {
    // Try city bin pattern.
    CITY_BIN_REGEX.matchEntire(fileName)?.let { match ->
        val prefix = match.groupValues[1]
        val (groupKey, def) = BIN_PREFIX_TO_AREA[prefix] ?: return null
        val offline = match.groupValues[2] == "off"
        val ultimate = match.groupValues[3] == "_u"
        return FreeRoamAreaInfo(
            def.episode, def.floorRange, binPrefix = prefix, tokenBase = groupKey,
            isCity = true, offline = offline, ultimate = ultimate,
        )
    }

    // Try field bin pattern.
    FIELD_BIN_REGEX.matchEntire(fileName)?.let { match ->
        val prefix = match.groupValues[1]
        val (groupKey, def) = BIN_PREFIX_TO_AREA[prefix] ?: return null
        val ultimate = match.groupValues[2] == "_u"
        return FreeRoamAreaInfo(
            def.episode, def.floorRange, binPrefix = prefix, tokenBase = groupKey,
            ultimate = ultimate,
        )
    }

    // Try dat pattern.
    FREE_ROAM_DAT_REGEX.matchEntire(fileName)?.let { match ->
        val fullToken = match.groupValues[1]
        val offlineSuffix = match.groupValues[2] // "_s" or empty
        val groupKey = datTokenToGroupKey(fullToken)
        val def = AREA_DEFS[groupKey] ?: return null
        return FreeRoamAreaInfo(
            def.episode, def.floorRange, binPrefix = def.binPrefix, tokenBase = groupKey,
            isCity = def.isCity,
            offline = offlineSuffix.isNotEmpty(),
        )
    }

    return null
}
