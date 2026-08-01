package world.phantasmal.psolib.fileFormats.fog

import world.phantasmal.psolib.cursor.Cursor

/** One 0x40-byte entry from the PSOBB client `fogentry.dat` table. */
data class FogEntry(
    /** 1 for a static entry and 2 for an animated entry in the stock table. */
    val type: Int,
    /** D3DCOLOR-compatible 0x00RRGGBB value. */
    val color: Int,
    val end: Float,
    val start: Float,
    val density: Float,
    val animationSpeed: Float,
    val endPulseDistance: Float,
    val startPulseDistance: Float,
    val transitionDistance: Float,
    val endPulsePhase: Int,
    val startPulsePhase: Int,
    val transitionPulseDistance: Int,
)

const val FOG_ENTRY_SIZE = 0x40
const val FOG_ENTRY_COUNT = 0x100

fun parseFogEntry(cursor: Cursor): FogEntry {
    require(cursor.bytesLeft >= FOG_ENTRY_SIZE) { "Truncated fog entry." }
    val startOffset = cursor.position

    val type = cursor.int()
    val color = cursor.int()
    val end = cursor.float()
    val start = cursor.float()
    val density = cursor.float()
    cursor.seek(4) // Unknown integer at 0x14.
    val animationSpeed = cursor.float()
    cursor.seek(4) // Unknown float at 0x1C.
    val endPulseDistance = cursor.float()
    cursor.seek(4) // Unknown integer at 0x24.
    val startPulseDistance = cursor.float()
    cursor.seek(4) // Unknown integer at 0x2C.
    val transitionDistance = cursor.float()
    cursor.seek(4) // Unknown integer at 0x34.
    cursor.seek(2) // Unknown bytes at 0x38 and 0x39.
    val endPulsePhase = cursor.uByte().toInt()
    cursor.seek(1)
    val startPulsePhase = cursor.uByte().toInt()
    cursor.seek(1)
    val transitionPulseDistance = cursor.uByte().toInt()
    cursor.seek(1)

    check(cursor.position - startOffset == FOG_ENTRY_SIZE)
    return FogEntry(
        type,
        color,
        end,
        start,
        density,
        animationSpeed,
        endPulseDistance,
        startPulseDistance,
        transitionDistance,
        endPulsePhase,
        startPulsePhase,
        transitionPulseDistance,
    )
}

fun parseFogEntryList(cursor: Cursor): List<FogEntry> {
    require(cursor.bytesLeft == FOG_ENTRY_COUNT * FOG_ENTRY_SIZE) {
        "Expected ${FOG_ENTRY_COUNT * FOG_ENTRY_SIZE} fog bytes, got ${cursor.bytesLeft}."
    }
    return List(FOG_ENTRY_COUNT) { parseFogEntry(cursor) }
}
