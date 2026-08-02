package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.core.Success
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.test.LibTestSuite
import world.phantasmal.psolib.test.QUEST_RESOURCE_PREFIX
import world.phantasmal.psolib.test.assertDeepEquals
import world.phantasmal.psolib.test.readFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatTests : LibTestSuite {
    @Test
    fun parse_quest_towards_the_future() = testAsync {
        val dat = parseDat(readFile("/quests/ep1/vr/towards the future (decompressed).dat"))

        assertEquals(277, dat.objs.size)
        assertEquals(216, dat.npcs.size)
    }

    /**
     * Parse a file, convert the resulting structure to DAT again and check whether the end result
     * is byte-for-byte equal to the original.
     */
    @Test
    fun parse_dat_and_write_dat() = testAsync {
        val origDat = readFile("/quests/ep1/vr/towards the future (decompressed).dat")
        val newDat = writeDat(parseDat(origDat)).cursor()
        origDat.seekStart(0)

        assertDeepEquals(origDat, newDat)
    }

    @Test
    fun dat_section_floor_is_independent_from_raw_record_floor_bytes() = testAsync {
        val dat = parseDat(readFile("/quests/ep1/vr/towards the future (decompressed).dat"))

        // The client selects quest floors from dat_table.floor_num. Official DAT records may keep
        // different values in ObjectSetEntry.floor (0x06) or EnemySetEntry.floor (0x08), so the
        // editor must not treat either embedded field as the entity's logical floor.
        assertTrue(
            dat.objs.any { it.floorId != it.data.getUShort(0x06).toInt() } ||
                dat.npcs.any { it.floorId != it.data.getUShort(0x08).toInt() },
            "Fixture should demonstrate that DAT section floor and raw record floor can differ.",
        )
    }

    /**
     * Parse a file, modify the resulting structure, convert it to DAT again and check whether the
     * end result is byte-for-byte equal to the original except for the bytes that should be
     * changed.
     */
    @Test
    fun parse_modify_write_dat() = testAsync {
        val origDat = readFile("/quests/ep1/vr/towards the future (decompressed).dat")
        val parsedDat = parseDat(origDat)
        origDat.seekStart(0)

        parsedDat.objs[9].data.setFloat(16, 13f)
        parsedDat.objs[9].data.setFloat(20, 17f)
        parsedDat.objs[9].data.setFloat(24, 19f)

        val newDat = writeDat(parsedDat).cursor()

        assertEquals(origDat.size, newDat.size)

        while (origDat.hasBytesLeft()) {
            if (origDat.position == 16 + 9 * OBJECT_BYTE_SIZE + 16) {
                origDat.seek(12)

                assertEquals(13f, newDat.float())
                assertEquals(17f, newDat.float())
                assertEquals(19f, newDat.float())
            } else {
                assertEquals(origDat.byte(), newDat.byte())
            }
        }
    }

    @Test
    fun parse_challenge_mode_quest_chl01() = testAsync {
        val qstResult = parseQstToQuest(readFile("$QUEST_RESOURCE_PREFIX/chl/ep1/1c1_e.qst"), lenient = true)
        assertTrue(qstResult is world.phantasmal.core.Success)
        val quest = qstResult.value.quest

        val dat = DatFile(
            objs = quest.objects.map { DatEntity(it.floorId, it.data) },
            npcs = quest.npcs.map { DatEntity(it.floorId, it.data) },
            events = quest.events,
            unknowns = quest.datUnknowns,
            cmRandomSpawns = quest.challengeData.cmRandomSpawns,
            cmConfigPool = quest.challengeData.cmConfigPool,
            cmMonsterMappings = quest.challengeData.cmMonsterMappings,
        )

        // Floor 1 should have 8 rooms with specific room IDs and entry counts.
        val floor1Spawns = dat.cmRandomSpawns.filter { it.floorId == 1 }
        assertEquals(8, floor1Spawns.size)
        assertEquals(listOf(2, 4, 5, 7, 8, 10, 11, 16), floor1Spawns.map { it.roomId })
        assertEquals(listOf(32, 32, 32, 32, 31, 23, 31, 27), floor1Spawns.map { it.entries.size })

        // Verify config pool is parsed (Table 5A).
        assertEquals(2, dat.cmConfigPool.size)
        assertEquals(12, dat.cmConfigPool[0].entries.size)
        // Config IDs should be sequential 1..12.
        assertEquals(
            (1..12).toList(),
            dat.cmConfigPool[0].entries.map { it.entryIndex.toInt() and 0xFFFF },
        )

        // Verify monster mappings are parsed (Table 5B).
        assertEquals(2, dat.cmMonsterMappings.size)
        assertEquals(11, dat.cmMonsterMappings[0].entries.size)
        assertEquals(13, dat.cmMonsterMappings[1].entries.size)
    }

    @Test
    fun challenge_mode_binary_layout_matches_client_structures() {
        val spawnEntry = DatCmRandomSpawnEntry(
            x = 1.25f,
            y = 2.5f,
            z = 3.75f,
            angleX = 0x10203040,
            angleY = 0x11213141,
            angleZ = 0x12223242,
            unknownA9 = 0x5152,
            unknownA10 = 0x6162,
        )
        val definition = DatCmConfigPoolEntry(
            param1 = 11.25f,
            param2 = 12.5f,
            param3 = 13.75f,
            param4 = 14.25f,
            param5 = 15.5f,
            param7 = 0x1112,
            param6 = 0x2122,
            entryIndex = 0x3132,
            unknown = 0x4142,
            minChildren = 0x5152,
            maxChildren = 0x6162,
        )
        val mapping = DatCmMonsterMappingEntry(
            monsterTypeIndex = 0x11,
            definitionIndex = 0x22,
            weight = 0x33,
            unknown = 0x44,
        )
        val dat = DatFile(
            objs = emptyList(),
            npcs = emptyList(),
            events = emptyList(),
            unknowns = emptyList(),
            cmRandomSpawns = listOf(DatCmRandomSpawn(7, 0x1234, mutableListOf(spawnEntry))),
            cmConfigPool = listOf(DatCmConfigPool(7, mutableListOf(definition))),
            cmMonsterMappings = listOf(DatCmMonsterMapping(7, mutableListOf(mapping))),
        )

        val bytes = writeDat(dat)

        // Type 4 header, room table, and RandomEnemyLocation (0x1C bytes).
        assertEquals(4, bytes.getInt(0))
        assertEquals(64, bytes.getInt(4))
        assertEquals(7, bytes.getInt(8))
        assertEquals(48, bytes.getInt(12))
        assertEquals(12, bytes.getInt(16))
        assertEquals(20, bytes.getInt(20))
        assertEquals(1, bytes.getInt(24))
        assertEquals(0x1234u, bytes.getUShort(28).toUInt())
        assertEquals(1u, bytes.getUShort(30).toUInt())
        assertEquals(0, bytes.getInt(32))
        assertEquals(1.25f, bytes.getFloat(36))
        assertEquals(2.5f, bytes.getFloat(40))
        assertEquals(3.75f, bytes.getFloat(44))
        assertEquals(0x10203040, bytes.getInt(48))
        assertEquals(0x11213141, bytes.getInt(52))
        assertEquals(0x12223242, bytes.getInt(56))
        assertEquals(0x5152u, bytes.getUShort(60).toUInt())
        assertEquals(0x6162u, bytes.getUShort(62).toUInt())

        // Type 5 header, RandomEnemyDefinition (0x20 bytes), and RandomEnemyWeight (4 bytes).
        assertEquals(5, bytes.getInt(64))
        assertEquals(68, bytes.getInt(68))
        assertEquals(7, bytes.getInt(72))
        assertEquals(52, bytes.getInt(76))
        assertEquals(16, bytes.getInt(80))
        assertEquals(48, bytes.getInt(84))
        assertEquals(1, bytes.getInt(88))
        assertEquals(1, bytes.getInt(92))
        assertEquals(11.25f, bytes.getFloat(96))
        assertEquals(12.5f, bytes.getFloat(100))
        assertEquals(13.75f, bytes.getFloat(104))
        assertEquals(14.25f, bytes.getFloat(108))
        assertEquals(15.5f, bytes.getFloat(112))
        assertEquals(0x1112u, bytes.getUShort(116).toUInt())
        assertEquals(0x2122u, bytes.getUShort(118).toUInt())
        assertEquals(0x3132u, bytes.getUShort(120).toUInt())
        assertEquals(0x4142u, bytes.getUShort(122).toUInt())
        assertEquals(0x5152u, bytes.getUShort(124).toUInt())
        assertEquals(0x6162u, bytes.getUShort(126).toUInt())
        assertEquals(0x11u, bytes.getUByte(128).toUInt())
        assertEquals(0x22u, bytes.getUByte(129).toUInt())
        assertEquals(0x33u, bytes.getUByte(130).toUInt())
        assertEquals(0x44u, bytes.getUByte(131).toUInt())

        // Parsing is checked independently from the raw writer layout above.
        val reparsed = parseDat(bytes.cursor())
        val parsedSpawn = reparsed.cmRandomSpawns.single().entries.single()
        assertEquals(spawnEntry.x, parsedSpawn.x)
        assertEquals(spawnEntry.y, parsedSpawn.y)
        assertEquals(spawnEntry.z, parsedSpawn.z)
        assertEquals(spawnEntry.angleX, parsedSpawn.angleX)
        assertEquals(spawnEntry.angleY, parsedSpawn.angleY)
        assertEquals(spawnEntry.angleZ, parsedSpawn.angleZ)
        assertEquals(spawnEntry.unknownA9, parsedSpawn.unknownA9)
        assertEquals(spawnEntry.unknownA10, parsedSpawn.unknownA10)

        val parsedDefinition = reparsed.cmConfigPool.single().entries.single()
        assertEquals(definition.param1, parsedDefinition.param1)
        assertEquals(definition.param2, parsedDefinition.param2)
        assertEquals(definition.param3, parsedDefinition.param3)
        assertEquals(definition.param4, parsedDefinition.param4)
        assertEquals(definition.param5, parsedDefinition.param5)
        assertEquals(definition.param7, parsedDefinition.param7)
        assertEquals(definition.param6, parsedDefinition.param6)
        assertEquals(definition.entryIndex, parsedDefinition.entryIndex)
        assertEquals(definition.unknown, parsedDefinition.unknown)
        assertEquals(definition.minChildren, parsedDefinition.minChildren)
        assertEquals(definition.maxChildren, parsedDefinition.maxChildren)

        val parsedMapping = reparsed.cmMonsterMappings.single().entries.single()
        assertEquals(mapping.monsterTypeIndex, parsedMapping.monsterTypeIndex)
        assertEquals(mapping.definitionIndex, parsedMapping.definitionIndex)
        assertEquals(mapping.weight, parsedMapping.weight)
        assertEquals(mapping.unknown, parsedMapping.unknown)
    }

    @Test
    fun challenge_writer_sorts_lookup_tables_and_rejects_location_overflow() {
        fun location(x: Float) = DatCmRandomSpawnEntry(x, 0f, 0f, 0, 0, 0, 0, 0)
        fun definition(index: Int) = DatCmConfigPoolEntry(
            0f, 0f, 0f, 0f, 0f, 0, 0, index.toShort(), 0, 0, 0,
        )

        val sorted = parseDat(writeDat(DatFile(
            objs = emptyList(),
            npcs = emptyList(),
            events = emptyList(),
            unknowns = emptyList(),
            cmRandomSpawns = listOf(
                DatCmRandomSpawn(1, 9, mutableListOf(location(9f))),
                DatCmRandomSpawn(1, 2, mutableListOf(location(2f))),
            ),
            cmConfigPool = listOf(DatCmConfigPool(1, mutableListOf(
                definition(9),
                definition(2),
            ))),
            cmMonsterMappings = listOf(DatCmMonsterMapping(1, mutableListOf())),
        )).cursor())

        assertEquals(listOf(2, 9), sorted.cmRandomSpawns.map { it.roomId })
        assertEquals(listOf(2f, 9f), sorted.cmRandomSpawns.map { it.entries.single().x })
        assertEquals(
            listOf(2, 9),
            sorted.cmConfigPool.single().entries.map { it.entryIndex.toInt() and 0xFFFF },
        )

        val tooManyLocations = DatFile(
            objs = emptyList(),
            npcs = emptyList(),
            events = emptyList(),
            unknowns = emptyList(),
            cmRandomSpawns = listOf(DatCmRandomSpawn(
                1,
                2,
                MutableList(CHALLENGE_MODE_MAX_RANDOM_LOCATIONS_PER_ROOM + 1) { location(it.toFloat()) },
            )),
            cmConfigPool = emptyList(),
            cmMonsterMappings = emptyList(),
        )
        assertFailsWith<IllegalArgumentException> { writeDat(tooManyLocations) }

        fun dat(
            spawns: List<DatCmRandomSpawn> = emptyList(),
            pools: List<DatCmConfigPool> = emptyList(),
            mappings: List<DatCmMonsterMapping> = emptyList(),
        ) = DatFile(
            objs = emptyList(), npcs = emptyList(), events = emptyList(), unknowns = emptyList(),
            cmRandomSpawns = spawns, cmConfigPool = pools, cmMonsterMappings = mappings,
        )

        assertFailsWith<IllegalArgumentException> {
            writeDat(dat(spawns = listOf(DatCmRandomSpawn(1, 0x10000, mutableListOf()))))
        }
        assertFailsWith<IllegalArgumentException> {
            writeDat(dat(pools = listOf(
                DatCmConfigPool(1, mutableListOf()),
                DatCmConfigPool(1, mutableListOf()),
            )))
        }
        assertFailsWith<IllegalArgumentException> {
            writeDat(dat(mappings = listOf(
                DatCmMonsterMapping(1, mutableListOf()),
                DatCmMonsterMapping(1, mutableListOf()),
            )))
        }
        assertFailsWith<IllegalArgumentException> {
            writeDat(dat(mappings = listOf(DatCmMonsterMapping(
                1,
                mutableListOf(DatCmMonsterMappingEntry(41, 1, 1, 0)),
            ))))
        }

        val unreachableInvalidType = parseDat(writeDat(dat(
            mappings = listOf(DatCmMonsterMapping(
                1,
                mutableListOf(DatCmMonsterMappingEntry(41, 1, 0, 0)),
            )),
        )).cursor())
        assertEquals(
            41,
            unreachableInvalidType.cmMonsterMappings.single()
                .entries.single().monsterTypeIndex.toInt() and 0xFF,
        )
    }

    @Test
    fun challenge_empty_rooms_survive_write_and_parse() {
        val dat = DatFile(
            objs = emptyList(),
            npcs = emptyList(),
            events = emptyList(),
            unknowns = emptyList(),
            cmRandomSpawns = listOf(
                DatCmRandomSpawn(1, 2, mutableListOf()),
                DatCmRandomSpawn(1, 9, mutableListOf()),
            ),
            cmConfigPool = emptyList(),
            cmMonsterMappings = emptyList(),
        )

        val reparsed = parseDat(writeDat(dat).cursor())

        assertEquals(listOf(2, 9), reparsed.cmRandomSpawns.map { it.roomId })
        assertTrue(reparsed.cmRandomSpawns.all { it.entries.isEmpty() })
    }

    @Test
    fun truncated_challenge_tables_are_skipped_without_throwing() {
        fun datWithSection(type: Int, bodySize: Int, initialize: Buffer.() -> Unit): Buffer {
            val buffer = Buffer.withSize(DAT_HEADER_SIZE + bodySize + DAT_HEADER_SIZE)
            buffer.setInt(0, type)
            buffer.setInt(4, DAT_HEADER_SIZE + bodySize)
            buffer.setInt(8, 1)
            buffer.setInt(12, bodySize)
            buffer.initialize()
            return buffer
        }

        val truncatedLocations = datWithSection(4, 12) {
            setInt(16, 12) // room table offset
            setInt(20, 20) // entries offset
            setInt(24, 1) // one room, but no room-table bytes
        }
        val parsedLocations = parseDat(truncatedLocations.cursor())
        assertTrue(parsedLocations.cmRandomSpawns.isEmpty())

        val truncatedDefinitions = datWithSection(5, 16) {
            setInt(16, 16) // definitions offset
            setInt(20, 48) // weights offset
            setInt(24, 1) // one definition, but no definition bytes
            setInt(28, 0)
        }
        val parsedDefinitions = parseDat(truncatedDefinitions.cursor())
        assertTrue(parsedDefinitions.cmConfigPool.isEmpty())
        assertTrue(parsedDefinitions.cmMonsterMappings.isEmpty())
    }

    /**
     * Parse a challenge mode QST, write the DAT portion back, and verify structural equality.
     */
    @Test
    fun parse_cm_dat_and_write_dat() = testAsync {
        val qstResult = parseQstToQuest(readFile("$QUEST_RESOURCE_PREFIX/chl/ep1/1c1_e.qst"), lenient = true)
        assertTrue(qstResult is world.phantasmal.core.Success)
        val quest = qstResult.value.quest

        val origDat = DatFile(
            objs = quest.objects.map { DatEntity(it.floorId, it.data) },
            npcs = quest.npcs.map { DatEntity(it.floorId, it.data) },
            events = quest.events,
            unknowns = quest.datUnknowns,
            cmRandomSpawns = quest.challengeData.cmRandomSpawns,
            cmConfigPool = quest.challengeData.cmConfigPool,
            cmMonsterMappings = quest.challengeData.cmMonsterMappings,
        )
        val reparsed = parseDat(writeDat(origDat).cursor())

        assertEquals(origDat.objs.size, reparsed.objs.size)
        assertEquals(origDat.npcs.size, reparsed.npcs.size)
        assertEquals(origDat.events.size, reparsed.events.size)
        assertEquals(origDat.cmRandomSpawns.size, reparsed.cmRandomSpawns.size)
        assertEquals(origDat.cmConfigPool.size, reparsed.cmConfigPool.size)
        assertEquals(origDat.cmMonsterMappings.size, reparsed.cmMonsterMappings.size)
    }

}
