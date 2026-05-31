package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.cursor.Cursor

/**
 * Parsed PSO V3 GC `SetDataTable*.rel`. Maps `(area, layout, entities)` indices to the
 * basenames used to construct map data filenames.
 *
 * On a V3 GC disc the actual on-disk file is `<areaSetupBasename>d.dat` (objects) and
 * `<areaSetupBasename>ad.dat` (enemies + events). The longer obj/enemy basenames are
 * exposed too but the disc does not use them as filenames.
 */
class SetDataTableRel(
    /** Indexed `[area][layout][entities]`. */
    val areas: List<List<List<SetDataEntry>>>,
) {
    /** Lookup an entry; returns null if any index is out of range. */
    fun get(area: Int, layout: Int, entities: Int): SetDataEntry? =
        areas.getOrNull(area)?.getOrNull(layout)?.getOrNull(entities)
}

class SetDataEntry(
    /** BB-style long object basename (e.g., `map_cave01_00_00`). May not match an on-disc file. */
    val objectBasename: String,
    /** BB-style long enemy basename. May have `_off` suffix on offline tables. */
    val enemyBasename: String,
    /** Short per-layout basename. On V3 GC discs this is the actual file basename — i.e. file is `<this>d.dat` / `<this>ad.dat`. */
    val areaSetupBasename: String,
)

/**
 * Parse a PSO V3 GC `SetDataTable*.rel` file. The format is big-endian (PowerPC).
 *
 * Layout (per newserv `Map.cc SetDataTable::load_table_t`):
 * - Footer @ end-of-file − 0x20:
 *   - +0x00 relocations_offset (u32)
 *   - +0x04 num_relocations (u32)
 *   - +0x08 unused1[2]
 *   - +0x10 root_offset_ptr (u32) — points to a u32 holding the root table offset
 *   - +0x14 unused2[3]
 * - At root_offset_ptr: u32 root_table_offset
 * - Root table size = `root_offset_ptr - root_table_offset` bytes, one (u32 offset, u32 count) pair per area (8 bytes each)
 * - Layout table per area: (u32 offset, u32 count) per layout, 8 bytes each
 * - Entities table per (area, layout): 3 × u32 string pointers per entry, 12 bytes each — (obj, enemy, areaSetup)
 * - String pointers point to null-terminated ASCII C strings within the file
 */
fun parseSetDataTableRel(cursor: Cursor): SetDataTableRel {
    require(cursor.endianness == Endianness.Big) {
        "SetDataTable.rel parser requires a big-endian cursor (got ${cursor.endianness})."
    }
    val size = cursor.size
    require(size >= 0x20) { "SetDataTable.rel too small (got $size bytes)." }

    // Footer
    cursor.seekStart(size - 0x20)
    val relocationsOffset = cursor.uInt().toInt()
    cursor.uInt() // num_relocations (unused here)
    cursor.uInt(); cursor.uInt() // unused1
    val rootOffsetPtr = cursor.uInt().toInt()
    require(rootOffsetPtr in 0 until size) {
        "Invalid root_offset_ptr 0x${rootOffsetPtr.toString(16)} (file size 0x${size.toString(16)})."
    }
    require(relocationsOffset in 0..size) {
        "Invalid relocations_offset 0x${relocationsOffset.toString(16)}."
    }

    cursor.seekStart(rootOffsetPtr)
    val rootTableOffset = cursor.uInt().toInt()
    val rootTableSize = rootOffsetPtr - rootTableOffset
    require(rootTableOffset in 0..rootOffsetPtr && rootTableSize > 0 && rootTableSize % 8 == 0) {
        "Invalid root table: offset=0x${rootTableOffset.toString(16)} size=$rootTableSize."
    }
    val numAreas = rootTableSize / 8

    fun readCstr(at: Int): String {
        cursor.seekStart(at)
        val sb = StringBuilder()
        while (cursor.position < size) {
            val b = cursor.uByte().toInt()
            if (b == 0) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    val areas = ArrayList<List<List<SetDataEntry>>>(numAreas)
    for (a in 0 until numAreas) {
        cursor.seekStart(rootTableOffset + a * 8)
        val layoutTableOffset = cursor.uInt().toInt()
        val layoutCount = cursor.uInt().toInt()
        val layouts = ArrayList<List<SetDataEntry>>(layoutCount)
        for (l in 0 until layoutCount) {
            cursor.seekStart(layoutTableOffset + l * 8)
            val entitiesTableOffset = cursor.uInt().toInt()
            val entitiesCount = cursor.uInt().toInt()
            val entities = ArrayList<SetDataEntry>(entitiesCount)
            for (e in 0 until entitiesCount) {
                cursor.seekStart(entitiesTableOffset + e * 12)
                val objPtr = cursor.uInt().toInt()
                val enemyPtr = cursor.uInt().toInt()
                val setupPtr = cursor.uInt().toInt()
                entities.add(
                    SetDataEntry(
                        objectBasename = readCstr(objPtr),
                        enemyBasename = readCstr(enemyPtr),
                        areaSetupBasename = readCstr(setupPtr),
                    )
                )
            }
            layouts.add(entities)
        }
        areas.add(layouts)
    }

    return SetDataTableRel(areas)
}
