package world.phantasmal.psolib.fileFormats.quest

import mu.KotlinLogging
import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.Cursor
import world.phantasmal.psolib.cursor.WritableCursor
import world.phantasmal.psolib.cursor.cursor
import kotlin.math.max
import kotlin.math.roundToInt

private val logger = KotlinLogging.logger {}

/**
 * Round to Int safely. Returns 0 for NaN / infinite values instead of throwing.
 * Used by entity getters that read float-encoded integer fields — when a foreign-format
 * dat (e.g., V3 GC) misaligns and a field reads as NaN, this avoids crashing the UI.
 */
internal fun Float.safeRoundToInt(): Int =
    if (isNaN() || isInfinite()) 0 else roundToInt()

private const val EVENT_ACTION_SPAWN_NPCS: Byte = 0x8
private const val EVENT_ACTION_UNLOCK: Byte = 0xA
private const val EVENT_ACTION_LOCK: Byte = 0xB
private const val EVENT_ACTION_TRIGGER_EVENT: Byte = 0xC
private const val CHALLENGE_MODE_EVENT_FLAG: Byte = 0x32
private const val EVENT_SECTION_HEADER_SIZE = 16

const val OBJECT_BYTE_SIZE = 68
const val NPC_BYTE_SIZE = 72

const val DAT_HEADER_SIZE = 16
const val DAT_ENTITY_TYPE_OBJ = 1
const val DAT_ENTITY_TYPE_NPC = 2
const val DAT_ENTITY_TYPE_EVENT = 3
private const val DAT_ENTITY_TYPE_CM_RANDOM_SPAWN = 4
private const val DAT_ENTITY_TYPE_CM_MONSTER_DATA = 5
const val CHALLENGE_MODE_MAX_RANDOM_LOCATIONS_PER_ROOM = 0xFFFF

class DatFile(
    val objs: List<DatEntity>,
    val npcs: List<DatEntity>,
    val events: List<DatEvent>,
    val unknowns: List<DatUnknown>,
    val cmRandomSpawns: List<DatCmRandomSpawn> = emptyList(),
    val cmMonsterMappings: List<DatCmMonsterMapping> = emptyList(),
    val cmConfigPool: List<DatCmConfigPool> = emptyList(),
)

class DatEntity(
    /** Logical client floor from the DAT section header (`dat_table.floor_num` in PSOBB). */
    var floorId: Int,
    val data: Buffer,
)

class DatEvent(
    var id: Int,
    var sectionId: Short,
    var wave: Short,
    var delay: Short,
    val actions: MutableList<DatEventAction>,
    /** Logical client floor from the DAT event section header. */
    var floorId: Int,
    var unknown: Short,
    /**
     * Challenge mode wave settings (4 bytes packed as int):
     * - Byte 0: Min enemies per wave
     * - Byte 1: Max enemies per wave
     * - Bytes 2-3: Max number of waves (u16)
     *
     * Non-null if and only if this event belongs to a challenge mode area.
     */
    var cmWaveSettings: Int? = null,
) {
    /** Whether this event belongs to a challenge mode area. Derived from [cmWaveSettings]. */
    val isChallengeMode: Boolean get() = cmWaveSettings != null
    /** Min enemies per wave (challenge mode only) */
    val cmMinEnemies: Int get() = cmWaveSettings?.let { it and 0xFF } ?: 0

    /** Max enemies per wave (challenge mode only) */
    val cmMaxEnemies: Int get() = cmWaveSettings?.let { (it shr 8) and 0xFF } ?: 0

    /** Max number of waves (challenge mode only) */
    val cmMaxWaves: Int get() = cmWaveSettings?.let { (it ushr 16) and 0xFFFF } ?: 0
}

sealed class DatEventAction {
    class SpawnNpcs(
        var sectionId: Short,
        var appearFlag: Short,
    ) : DatEventAction()

    class Unlock(
        var doorId: Short,
    ) : DatEventAction()

    class Lock(
        var doorId: Short,
    ) : DatEventAction()

    class TriggerEvent(
        var eventId: Int,
    ) : DatEventAction()
}

class DatUnknown(
    var entityType: Int,
    var totalSize: Int,
    /** Uninterpreted DAT section's logical client floor. */
    var floorId: Int,
    var entitiesSize: Int,
    val data: ByteArray,
)

/**
 * Challenge mode random spawn configuration (DAT entity type 4).
 * Defines spawn points for randomly generated monsters.
 * Each instance represents one room within a DAT section for a logical floor.
 */
class DatCmRandomSpawn(
    /** Logical client floor from the DAT section header. */
    var floorId: Int,
    /** Room ID from the internal room table (section within the floor). */
    var roomId: Int,
    val entries: MutableList<DatCmRandomSpawnEntry>,
)

/**
 * A single random spawn entry.
 * Structure: 3 floats + 3 ints + 2 shorts = 28 bytes total.
 */
class DatCmRandomSpawnEntry(
    var x: Float,
    var y: Float,
    var z: Float,
    var angleX: Int,
    var angleY: Int,
    var angleZ: Int,
    var unknownA9: Short,
    var unknownA10: Short,
) // Total: 28 bytes per entry

/**
 * Challenge mode monster type mapping (DAT entity type 5).
 * Maps challenge monster types to random-enemy definitions and selection weights.
 */
class DatCmMonsterMapping(
    /** Logical client floor from the DAT section header. */
    var floorId: Int,
    val entries: MutableList<DatCmMonsterMappingEntry>,
)

/**
 * Maps a challenge monster type to a random-enemy definition and weight.
 * Structure: 4 bytes total.
 */
class DatCmMonsterMappingEntry(
    /**
     * Monster type index into the challenge mode monster skin table.
     * Maps to specific monster skins: 0=0x44 (Hildebear), 1=0x43 (Hildetorr), etc.
     * Includes EP1 and EP2 monsters (max index 41 for Ill Gill in BB).
     */
    var monsterTypeIndex: Byte,
    /** Index into the random enemy definition table. */
    var definitionIndex: Byte,
    /** Relative selection weight. */
    var weight: Byte,
    var unknown: Byte,
)

/**
 * Challenge mode config pool (DAT entity type 5, Table 5A).
 * Random-enemy definitions used when materializing Challenge Mode waves.
 */
class DatCmConfigPool(
    /** Logical client floor from the DAT section header. */
    var floorId: Int,
    val entries: MutableList<DatCmConfigPoolEntry>,
)

/**
 * A single config pool entry.
 * Structure: 5 floats + 6 shorts = 32 bytes total.
 */
class DatCmConfigPoolEntry(
    var param1: Float,
    var param2: Float,
    var param3: Float,
    var param4: Float,
    var param5: Float,
    var param7: Short,
    var param6: Short,
    var entryIndex: Short,
    var unknown: Short,
    var minChildren: Short,
    var maxChildren: Short,
) // Total: 32 bytes per entry

fun parseDat(cursor: Cursor): DatFile {
    val objs = mutableListOf<DatEntity>()
    val npcs = mutableListOf<DatEntity>()
    val events = mutableListOf<DatEvent>()
    val unknowns = mutableListOf<DatUnknown>()
    val cmRandomSpawns = mutableListOf<DatCmRandomSpawn>()
    val cmMonsterMappings = mutableListOf<DatCmMonsterMapping>()
    val cmConfigPool = mutableListOf<DatCmConfigPool>()

    while (cursor.hasBytesLeft()) {
        val entityType = cursor.int()
        val totalSize = cursor.int()
        // PSOBB calls this dat_table.floor_num. It selects a logical quest floor, not a map area.
        val floorId = cursor.int()
        val entitiesSize = cursor.int()

        if (entityType == 0) {
            break
        } else {
            require(entitiesSize == totalSize - DAT_HEADER_SIZE) {
                "Malformed DAT file. Expected an entities size of ${totalSize - DAT_HEADER_SIZE}, got ${entitiesSize}."
            }

            val entitiesCursor = cursor.take(entitiesSize)

            when (entityType) {
                DAT_ENTITY_TYPE_OBJ -> parseObjects(entitiesCursor, floorId, objs)
                DAT_ENTITY_TYPE_NPC -> parseNpcs(entitiesCursor, floorId, npcs)
                DAT_ENTITY_TYPE_EVENT -> parseEvents(entitiesCursor, floorId, events)
                DAT_ENTITY_TYPE_CM_RANDOM_SPAWN -> parseChallengeRandomSpawns(entitiesCursor, floorId, cmRandomSpawns)
                DAT_ENTITY_TYPE_CM_MONSTER_DATA -> parseChallengeMonsterData(entitiesCursor, floorId, cmConfigPool, cmMonsterMappings)
                else -> {
                    unknowns.add(
                        DatUnknown(
                            entityType,
                            totalSize,
                            floorId,
                            entitiesSize,
                            data = entitiesCursor.byteArray(entitiesSize),
                        )
                    )
                }
            }

            if (entitiesCursor.hasBytesLeft()) {
                logger.warn {
                    "Read ${entitiesCursor.position} bytes instead of expected ${entitiesCursor.size} for entity type ${entityType}."
                }
            }
        }
    }

    return DatFile(
        objs,
        npcs,
        events,
        unknowns,
        cmRandomSpawns,
        cmMonsterMappings,
        cmConfigPool,
    )
}

private fun parseObjects(
    cursor: Cursor,
    floorId: Int,
    objects: MutableList<DatEntity>,
) {
    val entityCount = cursor.size / OBJECT_BYTE_SIZE

    repeat(entityCount) {
        objects.add(
            DatEntity(
                floorId,
                data = cursor.buffer(OBJECT_BYTE_SIZE),
            )
        )
    }
}

private fun parseNpcs(
    cursor: Cursor,
    floorId: Int,
    npcs: MutableList<DatEntity>,
) {
    val entityCount = cursor.size / NPC_BYTE_SIZE

    repeat(entityCount) {
        npcs.add(
            DatEntity(
                floorId,
                data = cursor.buffer(NPC_BYTE_SIZE),
            )
        )
    }
}

private fun parseChallengeRandomSpawns(
    cursor: Cursor,
    floorId: Int,
    spawns: MutableList<DatCmRandomSpawn>
) {
    logger.debug { "Parsing CM random spawns for floor $floorId, cursor size=${cursor.size}, bytesLeft=${cursor.bytesLeft}" }

    if (cursor.size < 12) {
        logger.warn { "CM random spawns floor $floorId: section is smaller than its 12-byte header, skipping." }
        return
    }

    // Header format:
    //   offset 0: tableHeaderSize (4 bytes) — size of this header (always 12)
    //   offset 4: startOffset (4 bytes) — byte offset where 28-byte entries begin
    //   offset 8: numRooms (4 bytes) — number of room table entries
    val roomTableOffset = cursor.uInt().toLong() // offset 0
    val startOffset = cursor.uInt().toLong()     // offset 4
    val numRooms = cursor.uInt().toLong()        // offset 8

    logger.debug { "CM spawn header: roomTableOffset=$roomTableOffset, startOffset=$startOffset, numRooms=$numRooms" }

    if (numRooms > 1000L) {
        logger.warn { "CM random spawns: suspicious numRooms=$numRooms, skipping." }
        return
    }

    val roomTableEnd = roomTableOffset + numRooms * 8L
    if (roomTableOffset < 12L || roomTableEnd > cursor.size.toLong()) {
        logger.warn {
            "CM random spawns floor $floorId: room table range $roomTableOffset..$roomTableEnd " +
                    "is outside section size ${cursor.size}, skipping."
        }
        return
    }
    if (startOffset < roomTableEnd || startOffset > cursor.size.toLong()) {
        logger.warn {
            "CM random spawns floor $floorId: entries offset $startOffset is invalid, skipping."
        }
        return
    }

    // Room table: numRooms entries, each 8 bytes:
    //   roomId (u16), entryCount (u16), byteOffset (u32)
    data class RoomTableEntry(val roomId: Int, val entryCount: Int, val byteOffset: Long)

    val roomTable = mutableListOf<RoomTableEntry>()
    cursor.seekStart(roomTableOffset.toInt())
    for (i in 0 until numRooms.toInt()) {
        val roomId = cursor.uShort().toInt()
        val entryCount = cursor.uShort().toInt()
        val byteOffset = cursor.uInt().toLong()
        roomTable.add(RoomTableEntry(roomId, entryCount, byteOffset))
        logger.debug { "  Room table[$i]: roomId=$roomId, entryCount=$entryCount, byteOffset=$byteOffset" }
    }

    // Parse entries for each room.
    for (room in roomTable) {
        val seekPos = startOffset + room.byteOffset
        // An empty room may legitimately point at the end of the entries table. Non-empty rooms
        // still need their complete location data to fit in the section.
        val entryBytes = room.entryCount.toLong() * 28L
        if (seekPos > cursor.size.toLong() || seekPos + entryBytes > cursor.size.toLong()) {
            logger.warn {
                "CM random spawns floor $floorId: room ${room.roomId} data offset $seekPos " +
                    "out of bounds (cursor size=${cursor.size}), skipping room."
            }
            continue
        }
        cursor.seekStart(seekPos.toInt())
        val entries = mutableListOf<DatCmRandomSpawnEntry>()

        for (entryIdx in 0 until room.entryCount) {
            entries.add(
                DatCmRandomSpawnEntry(
                    x = cursor.float(),
                    y = cursor.float(),
                    z = cursor.float(),
                    angleX = cursor.int(),
                    angleY = cursor.int(),
                    angleZ = cursor.int(),
                    unknownA9 = cursor.short(),
                    unknownA10 = cursor.short(),
                )
            )
        }

        logger.debug { "Parsed ${entries.size} random spawn entries for floor $floorId, room ${room.roomId}" }
        spawns.add(DatCmRandomSpawn(floorId, room.roomId, entries))
    }
}

/**
 * Parses entityType=5 data which contains both Table 5A (Config Pool) and Table 5B (Monsters Setting).
 *
 * Header format (16 bytes):
 *   offset 0: headerSize (u32) — always 16
 *   offset 4: table5bOffset (u32) — byte offset where Table 5B starts
 *   offset 8: numConfigs (u32) — number of Table 5A entries
 *   offset 12: numMonsters (u32) — number of Table 5B entries
 *
 * Table 5A starts at headerSize, each entry 32 bytes.
 * Table 5B starts at table5bOffset, each entry 4 bytes.
 */
private fun parseChallengeMonsterData(
    cursor: Cursor,
    floorId: Int,
    configPool: MutableList<DatCmConfigPool>,
    mappings: MutableList<DatCmMonsterMapping>
) {
    logger.debug { "Parsing CM monster data for floor $floorId, cursor size=${cursor.size}" }

    if (cursor.size < 16) {
        logger.warn { "CM monster data floor $floorId: section is smaller than its 16-byte header, skipping." }
        return
    }

    // Read 16-byte header.
    val headerSize = cursor.uInt().toLong()       // offset 0
    val table5bOffset = cursor.uInt().toLong()    // offset 4
    val numConfigs = cursor.uInt().toLong()       // offset 8
    val numMonsters = cursor.uInt().toLong()      // offset 12

    logger.debug { "CM monster header: headerSize=$headerSize, table5bOffset=$table5bOffset, numConfigs=$numConfigs, numMonsters=$numMonsters" }

    if (numConfigs > 10000L || numMonsters > 10000L) {
        logger.warn { "CM monster data: suspicious counts (configs=$numConfigs, monsters=$numMonsters), skipping." }
        return
    }

    val definitionsEnd = headerSize + numConfigs * 32L
    val weightsEnd = table5bOffset + numMonsters * 4L
    if (headerSize < 16L || definitionsEnd > cursor.size.toLong() ||
        table5bOffset < definitionsEnd || weightsEnd > cursor.size.toLong()
    ) {
        logger.warn {
            "CM monster data floor $floorId: table offsets or counts exceed section size ${cursor.size}, skipping."
        }
        return
    }

    // Parse Table 5A (Config Pool) — 32 bytes per entry, starts at headerSize.
    cursor.seekStart(headerSize.toInt())
    val configEntries = mutableListOf<DatCmConfigPoolEntry>()

    for (i in 0 until numConfigs.toInt()) {
        configEntries.add(
            DatCmConfigPoolEntry(
                param1 = cursor.float(),
                param2 = cursor.float(),
                param3 = cursor.float(),
                param4 = cursor.float(),
                param5 = cursor.float(),
                param7 = cursor.short(),
                param6 = cursor.short(),
                entryIndex = cursor.short(),
                unknown = cursor.short(),
                minChildren = cursor.short(),
                maxChildren = cursor.short(),
            )
        )
    }

    logger.debug { "Parsed ${configEntries.size} config pool entries for floor $floorId" }
    configPool.add(DatCmConfigPool(floorId, configEntries))

    // Parse Table 5B (Monsters Setting) — 4 bytes per entry, starts at table5bOffset.
    cursor.seekStart(table5bOffset.toInt())
    val monsterEntries = mutableListOf<DatCmMonsterMappingEntry>()

    for (i in 0 until numMonsters.toInt()) {
        monsterEntries.add(
            DatCmMonsterMappingEntry(
                monsterTypeIndex = cursor.byte(),
                definitionIndex = cursor.byte(),
                weight = cursor.byte(),
                unknown = cursor.byte(),
            )
        )
    }

    logger.debug { "Parsed ${monsterEntries.size} monster mapping entries for floor $floorId" }
    mappings.add(DatCmMonsterMapping(floorId, monsterEntries))
}

private fun parseEvents(cursor: Cursor, floorId: Int, events: MutableList<DatEvent>) {
    val actionsOffset = cursor.int()
    cursor.seek(4) // Always 0x10
    val eventCount = cursor.int()
    val eventTypeBytes = cursor.byteArray(4)
    val eventType = eventTypeBytes[3]

    val isChallengeMode = (eventType == CHALLENGE_MODE_EVENT_FLAG)

    cursor.seekStart(actionsOffset)
    val actionsCursor = cursor.take(cursor.bytesLeft)
    cursor.seekStart(EVENT_SECTION_HEADER_SIZE)

    repeat(eventCount) {
        events.add(parseSingleEvent(cursor, isChallengeMode, actionsCursor, floorId))
    }

    if (cursor.position != actionsOffset) {
        logger.warn {
            "Floor $floorId: Event data size mismatch. " +
                    "Read ${cursor.position - EVENT_SECTION_HEADER_SIZE} bytes but expected ${actionsOffset - EVENT_SECTION_HEADER_SIZE}."
        }
    }

    var lastByte: Byte = -1

    while (actionsCursor.hasBytesLeft()) {
        lastByte = actionsCursor.byte()

        if (lastByte.toInt() != -1) {
            break
        }
    }

    if (lastByte.toInt() != -1) {
        actionsCursor.seek(-1)
    }

    // Make sure the cursor position represents the amount of bytes we've consumed.
    cursor.seekStart(actionsOffset + actionsCursor.position)
}

/**
 * Parses a single event entry from the cursor, reading its fields and associated actions.
 */
private fun parseSingleEvent(
    cursor: Cursor,
    isChallengeMode: Boolean,
    actionsCursor: Cursor,
    floorId: Int,
): DatEvent {
    val id = cursor.int()

    // Skip 4 bytes (always 0x00010000)
    cursor.seek(4)

    val sectionId = cursor.short()
    val wave = cursor.short()
    val delay = cursor.short()
    val unknown = cursor.short()

    var cmWaveSettings: Int? = null

    // For challenge mode, wave settings come next (4 bytes)
    if (isChallengeMode) {
        cmWaveSettings = cursor.int()
    }

    // Actions offset is a 4-byte int, relative to the actions section start.
    // In practice, values fit in 16 bits but we read 4 bytes for correctness.
    val eventActionsOffset = cursor.int()

    val actions: MutableList<DatEventAction> =
        if (eventActionsOffset < actionsCursor.size) {
            actionsCursor.seekStart(eventActionsOffset)
            parseEventActions(actionsCursor)
        } else {
            logger.warn { "Invalid event actions offset $eventActionsOffset for event $id on floor $floorId." }
            mutableListOf()
        }

    return DatEvent(
        id,
        sectionId,
        wave,
        delay,
        actions,
        floorId,
        unknown,
        cmWaveSettings,
    )
}

private fun parseEventActions(cursor: Cursor): MutableList<DatEventAction> {
    val actions = mutableListOf<DatEventAction>()

    outer@ while (cursor.hasBytesLeft()) {
        when (val type = cursor.byte()) {
            (1).toByte() -> break@outer

            EVENT_ACTION_SPAWN_NPCS ->
                actions.add(
                    DatEventAction.SpawnNpcs(
                        sectionId = cursor.short(),
                        appearFlag = cursor.short(),
                    )
                )

            EVENT_ACTION_UNLOCK ->
                actions.add(
                    DatEventAction.Unlock(
                        doorId = cursor.short(),
                    )
                )

            EVENT_ACTION_LOCK ->
                actions.add(
                    DatEventAction.Lock(
                        doorId = cursor.short(),
                    )
                )

            EVENT_ACTION_TRIGGER_EVENT ->
                actions.add(
                    DatEventAction.TriggerEvent(
                        eventId = cursor.int(),
                    )
                )

            else -> {
                logger.warn { "Unexpected event action type ${type}." }
                break@outer
            }
        }
    }

    return actions
}

fun writeDat(dat: DatFile): Buffer {
    val buffer = Buffer.withCapacity(
        dat.objs.size * (DAT_HEADER_SIZE + OBJECT_BYTE_SIZE) +
                dat.npcs.size * (DAT_HEADER_SIZE + NPC_BYTE_SIZE) +
                dat.events.size * 24 + // Approximate event data size.
                // Per floor: DAT header(16) + internal header(12) + room table(numRooms*8) + entries(n*28)
                dat.cmRandomSpawns.groupBy { it.floorId }.values.sumOf { floorSpawns ->
                    DAT_HEADER_SIZE + 12 + floorSpawns.size * 8 + floorSpawns.sumOf { it.entries.size } * 28
                } +
                dat.cmConfigPool.sumOf { 16 + 16 + it.entries.size * 32 } +
                dat.cmMonsterMappings.sumOf { it.entries.size * 4 } +
                dat.unknowns.sumOf { it.totalSize } +
                DAT_HEADER_SIZE, // Final trailer.
        endianness = Endianness.Little,
    )
    val cursor = buffer.cursor()

    writeObjects(cursor, dat.objs)
    writeNpcs(cursor, dat.npcs)
    writeEvents(cursor, dat.events)
    writeChallengeRandomSpawns(cursor, dat.cmRandomSpawns)
    writeChallengeMonsterData(cursor, dat.cmConfigPool, dat.cmMonsterMappings)

    for (unknown in dat.unknowns) {
        cursor.writeInt(unknown.entityType)
        cursor.writeInt(unknown.totalSize)
        cursor.writeInt(unknown.floorId)
        cursor.writeInt(unknown.entitiesSize)
        cursor.writeByteArray(unknown.data)
    }

    // Final header.
    cursor.writeInt(0)
    cursor.writeInt(0)
    cursor.writeInt(0)
    cursor.writeInt(0)

    return buffer
}

/**
 * Serializes event entity data for [floorId] to a [Buffer] suitable for writing as a .evt file.
 * Returns null if there are no events on that floor.
 *
 * The buffer contains only the entity data (event section header + entries + actions), without
 * the 16-byte DAT chunk header that wraps it inside a .dat file.
 */
fun writeEventDataForFloor(events: List<DatEvent>, floorId: Int): Buffer? {
    val floorEvents = events.filter { it.floorId == floorId }
    if (floorEvents.isEmpty()) return null

    val isChallengeMode = floorEvents.any { it.isChallengeMode }
    val eventSize = if (isChallengeMode) 24 else 20
    val actionsOffset = EVENT_SECTION_HEADER_SIZE + eventSize * floorEvents.size

    val estimatedActionsSize: Int = floorEvents.sumOf { event ->
        val actionBytes: Int = event.actions.sumOf { action ->
            when (action) {
                is DatEventAction.SpawnNpcs -> 5
                is DatEventAction.Unlock -> 3
                is DatEventAction.Lock -> 3
                is DatEventAction.TriggerEvent -> 5
            } as Int
        }
        actionBytes + 1 // end byte (0x01)
    }
    val buffer = Buffer.withCapacity(
        max(actionsOffset + estimatedActionsSize + 4, actionsOffset + 4),
        Endianness.Little,
    )
    val cursor = buffer.cursor()

    // Event section header (16 bytes).
    cursor.writeInt(actionsOffset)
    cursor.writeInt(0x10)
    cursor.writeInt(floorEvents.size)
    if (isChallengeMode) {
        cursor.writeByte('e'.code.toByte())
        cursor.writeByte('v'.code.toByte())
        cursor.writeByte('t'.code.toByte())
        cursor.writeByte(CHALLENGE_MODE_EVENT_FLAG)
    } else {
        cursor.writeInt(0)
    }

    // Reserve space so seekStart to actionsOffset doesn't fail.
    cursor.size = max(actionsOffset, cursor.size)

    // Event entries and actions.
    var eventActionsOffset = 0
    for (event in floorEvents) {
        eventActionsOffset = writeSingleEvent(cursor, event, isChallengeMode, actionsOffset, eventActionsOffset)
    }

    cursor.seekStart(actionsOffset + eventActionsOffset)

    val paddingByte: Byte = if (isChallengeMode) 0 else -1
    while ((cursor.position - actionsOffset) % 4 != 0) {
        cursor.writeByte(paddingByte)
    }

    return buffer.slice(0, cursor.position)
}

private fun writeObjects(cursor: WritableCursor, objects: List<DatEntity>) {
    writeEntityGroup(cursor, objects, entityType = 1, entitySize = OBJECT_BYTE_SIZE)
}

private fun writeNpcs(cursor: WritableCursor, npcs: List<DatEntity>) {
    writeEntityGroup(cursor, npcs, entityType = 2, entitySize = NPC_BYTE_SIZE)
}

private fun writeEntityGroup(
    cursor: WritableCursor,
    entities: List<DatEntity>,
    entityType: Int,
    entitySize: Int,
) {
    val groupedEntities = entities.groupBy { it.floorId }

    for ((floorId, floorEntities) in groupedEntities.entries) {
        val entitiesSize = floorEntities.size * entitySize
        cursor.writeInt(entityType)
        cursor.writeInt(DAT_HEADER_SIZE + entitiesSize)
        cursor.writeInt(floorId)
        cursor.writeInt(entitiesSize)
        val startPos = cursor.position

        for (entity in floorEntities) {
            require(entity.data.size == entitySize) {
                "Malformed entity on floor $floorId, data buffer was of size ${
                    entity.data.size
                } instead of expected $entitySize."
            }

            cursor.writeCursor(entity.data.cursor())
        }

        check(cursor.position == startPos + entitiesSize) {
            "Wrote ${
                cursor.position - startPos
            } bytes of entity data instead of expected $entitiesSize bytes for floor $floorId."
        }
    }
}

private fun writeEvents(cursor: WritableCursor, events: List<DatEvent>) {
    val groupedEvents = events.groupBy { it.floorId }

    for ((floorId, floorEvents) in groupedEvents.entries) {
        val isChallengeMode = floorEvents.any { it.isChallengeMode }
        if (isChallengeMode && floorEvents.any { !it.isChallengeMode }) {
            logger.warn {
                "Floor $floorId has both CM and non-CM events; non-CM events padded to 24 bytes."
            }
        }

        // Standard header.
        cursor.writeInt(DAT_ENTITY_TYPE_EVENT)
        val totalSizeOffset = cursor.position
        cursor.writeInt(0) // Placeholder for the total size.
        cursor.writeInt(floorId)
        val entitiesSizeOffset = cursor.position
        cursor.writeInt(0) // Placeholder for the entities size.

        // Event header.
        val startPos = cursor.position
        val eventSize = if (isChallengeMode) 24 else 20  // CM events: 20 + 4 wave settings = 24
        // Absolute offset.
        val actionsOffset = startPos + EVENT_SECTION_HEADER_SIZE + eventSize * floorEvents.size
        cursor.size = max(actionsOffset, cursor.size)

        cursor.writeInt(actionsOffset - startPos)
        cursor.writeInt(0x10)
        cursor.writeInt(floorEvents.size)
        if (isChallengeMode) {
            // "evt" marker followed by challenge mode flag
            cursor.writeByte('e'.code.toByte())
            cursor.writeByte('v'.code.toByte())
            cursor.writeByte('t'.code.toByte())
            cursor.writeByte(CHALLENGE_MODE_EVENT_FLAG)
        } else {
            cursor.writeByte(0)
            cursor.writeByte(0)
            cursor.writeByte(0)
            cursor.writeByte(0)
        }

        // Relative offset.
        var eventActionsOffset = 0

        for (event in floorEvents) {
            eventActionsOffset = writeSingleEvent(
                cursor, event, isChallengeMode, actionsOffset, eventActionsOffset,
            )
        }

        cursor.seekStart(actionsOffset + eventActionsOffset)

        val paddingByte: Byte = if (isChallengeMode) 0 else -1
        while ((cursor.position - actionsOffset) % 4 != 0) {
            cursor.writeByte(paddingByte)
        }

        val endPos = cursor.position

        cursor.seekStart(totalSizeOffset)
        cursor.writeInt(DAT_HEADER_SIZE + endPos - startPos)

        cursor.seekStart(entitiesSizeOffset)
        cursor.writeInt(endPos - startPos)

        cursor.seekStart(endPos)
    }
}

/**
 * Writes a single event's fields and actions to the cursor.
 * Returns the updated eventActionsOffset (relative to the actions section start).
 */
private fun writeSingleEvent(
    cursor: WritableCursor,
    event: DatEvent,
    isChallengeMode: Boolean,
    actionsOffset: Int,
    eventActionsOffset: Int,
): Int {
    cursor.writeInt(event.id)

    // Constant 0x00010000
    cursor.writeInt(0x10000)

    cursor.writeShort(event.sectionId)
    cursor.writeShort(event.wave)
    cursor.writeShort(event.delay)
    cursor.writeShort(event.unknown)

    // For challenge mode, write wave settings (4 bytes)
    if (isChallengeMode) {
        cursor.writeInt(event.cmWaveSettings ?: 0)
    }

    // Actions offset is a 4-byte int, relative to the actions section start.
    cursor.writeInt(eventActionsOffset)

    val nextEventPos = cursor.position

    cursor.seekStart(actionsOffset + eventActionsOffset)

    for (action in event.actions) {
        when (action) {
            is DatEventAction.SpawnNpcs -> {
                cursor.writeByte(EVENT_ACTION_SPAWN_NPCS)
                cursor.writeShort(action.sectionId)
                cursor.writeShort(action.appearFlag)
            }
            is DatEventAction.Unlock -> {
                cursor.writeByte(EVENT_ACTION_UNLOCK)
                cursor.writeShort(action.doorId)
            }
            is DatEventAction.Lock -> {
                cursor.writeByte(EVENT_ACTION_LOCK)
                cursor.writeShort(action.doorId)
            }
            is DatEventAction.TriggerEvent -> {
                cursor.writeByte(EVENT_ACTION_TRIGGER_EVENT)
                cursor.writeInt(action.eventId)
            }
        }
    }

    // End of event actions.
    cursor.writeByte(1)

    val newActionsOffset = cursor.position - actionsOffset

    cursor.seekStart(nextEventPos)

    return newActionsOffset
}

private fun writeChallengeRandomSpawns(
    cursor: WritableCursor,
    spawns: List<DatCmRandomSpawn>
) {
    // Group rooms by the logical floor stored in the DAT section header.
    val grouped = spawns.groupBy { it.floorId }

    for ((floorId, unsortedRoomSpawns) in grouped) {
        val roomSpawns = unsortedRoomSpawns.sortedBy { it.roomId }
        require(roomSpawns.all { it.roomId in 0..0xFFFF }) {
            "Challenge Mode room IDs must be between 0 and 65535."
        }
        require(roomSpawns.map { it.roomId }.distinct().size == roomSpawns.size) {
            "Challenge Mode floor $floorId has duplicate room IDs."
        }
        require(roomSpawns.all { it.entries.size <= CHALLENGE_MODE_MAX_RANDOM_LOCATIONS_PER_ROOM }) {
            "Challenge Mode random-location counts must fit in the DAT room table."
        }
        val numRooms = roomSpawns.size
        val tableHeaderSize = 12                      // 3 ints: headerSize, startOffset, numRooms
        val roomTableSize = numRooms * 8              // 8 bytes per room table entry
        val startOffset = tableHeaderSize + roomTableSize
        val totalEntries = roomSpawns.sumOf { it.entries.size }
        val entryDataSize = totalEntries * 28
        val entitiesSize = startOffset + entryDataSize

        // DAT chunk header.
        cursor.writeInt(DAT_ENTITY_TYPE_CM_RANDOM_SPAWN)
        cursor.writeInt(DAT_HEADER_SIZE + entitiesSize)
        cursor.writeInt(floorId)
        cursor.writeInt(entitiesSize)

        // Internal header: tableHeaderSize, startOffset, numRooms.
        cursor.writeInt(tableHeaderSize)
        cursor.writeInt(startOffset)
        cursor.writeInt(numRooms)

        // Room table.
        var byteOffset = 0
        for (roomSpawn in roomSpawns) {
            cursor.writeShort(roomSpawn.roomId.toShort())
            cursor.writeShort(roomSpawn.entries.size.toShort())
            cursor.writeInt(byteOffset)
            byteOffset += roomSpawn.entries.size * 28
        }

        // Entry data.
        for (roomSpawn in roomSpawns) {
            for (entry in roomSpawn.entries) {
                cursor.writeFloat(entry.x)
                cursor.writeFloat(entry.y)
                cursor.writeFloat(entry.z)
                cursor.writeInt(entry.angleX)
                cursor.writeInt(entry.angleY)
                cursor.writeInt(entry.angleZ)
                cursor.writeShort(entry.unknownA9)
                cursor.writeShort(entry.unknownA10)
            }
        }
    }
}

/**
 * Writes entityType=5 data combining Table 5A (Config Pool) and Table 5B (Monsters Setting).
 * Pairs config-pool and monster-mapping data by logical floor.
 */
private fun writeChallengeMonsterData(
    cursor: WritableCursor,
    configPool: List<DatCmConfigPool>,
    mappings: List<DatCmMonsterMapping>
) {
    require(configPool.map { it.floorId }.distinct().size == configPool.size) {
        "Challenge Mode has multiple random-enemy definition tables for the same floor."
    }
    require(mappings.map { it.floorId }.distinct().size == mappings.size) {
        "Challenge Mode has multiple random-enemy weight tables for the same floor."
    }

    // Collect all area IDs from both config pool and mappings.
    val floorIds = (configPool.map { it.floorId } + mappings.map { it.floorId }).distinct().sorted()

    for (floorId in floorIds) {
        val configs = configPool.find { it.floorId == floorId }?.entries
            ?.sortedBy { it.entryIndex.toInt() and 0xFFFF }
            ?: emptyList()
        val monsters = mappings.find { it.floorId == floorId }?.entries ?: emptyList()
        require(configs.map { it.entryIndex.toInt() and 0xFFFF }.distinct().size == configs.size) {
            "Challenge Mode floor $floorId has duplicate random-enemy definition indexes."
        }
        require(monsters.filter { (it.weight.toInt() and 0xFF) != 0 }.all { entry ->
            val index = entry.monsterTypeIndex.toInt() and 0xFF
            index == 0xFF ||
                    (index in CHALLENGE_MODE_MONSTER_TYPE_IDS.indices &&
                            CHALLENGE_MODE_MONSTER_TYPE_IDS[index] != 0)
        }) {
            "Challenge Mode floor $floorId has invalid random-enemy monster type indexes."
        }

        val headerSize = 16
        val table5aSize = configs.size * 32
        val table5bOffset = headerSize + table5aSize
        val table5bSize = monsters.size * 4
        val entitiesSize = table5bOffset + table5bSize

        // DAT chunk header.
        cursor.writeInt(DAT_ENTITY_TYPE_CM_MONSTER_DATA)
        cursor.writeInt(DAT_HEADER_SIZE + entitiesSize)
        cursor.writeInt(floorId)
        cursor.writeInt(entitiesSize)

        // Internal header (16 bytes).
        cursor.writeInt(headerSize)
        cursor.writeInt(table5bOffset)
        cursor.writeInt(configs.size)
        cursor.writeInt(monsters.size)

        // Table 5A (Config Pool) — 32 bytes per entry.
        for (entry in configs) {
            cursor.writeFloat(entry.param1)
            cursor.writeFloat(entry.param2)
            cursor.writeFloat(entry.param3)
            cursor.writeFloat(entry.param4)
            cursor.writeFloat(entry.param5)
            cursor.writeShort(entry.param7)
            cursor.writeShort(entry.param6)
            cursor.writeShort(entry.entryIndex)
            cursor.writeShort(entry.unknown)
            cursor.writeShort(entry.minChildren)
            cursor.writeShort(entry.maxChildren)
        }

        // Table 5B (Monsters Setting) — 4 bytes per entry.
        for (entry in monsters) {
            cursor.writeByte(entry.monsterTypeIndex)
            cursor.writeByte(entry.definitionIndex)
            cursor.writeByte(entry.weight)
            cursor.writeByte(entry.unknown)
        }
    }
}
