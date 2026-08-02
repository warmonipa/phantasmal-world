package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.core.Success
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.test.LibTestSuite
import world.phantasmal.psolib.test.QUEST_RESOURCE_PREFIX
import world.phantasmal.psolib.test.readFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ChallengeModeSeedSimulationTests : LibTestSuite {
    @Test
    fun pso_v2_random_matches_reference_vectors() {
        val random = PsoV2Random(0x12345678u)

        assertEquals(
            listOf(
                0xDAE88B96u,
                0xCB3060B9u,
                0xAE4C4E68u,
                0xF0EE2029u,
                0xB20BA18Au,
                0x33F1123Fu,
                0x3EAA77D2u,
                0x03F5454Bu,
            ),
            List(8) { random.next() },
        )
    }

    @Test
    fun invalid_biased_range_still_advances_the_main_prng() {
        val seed = 0x12345678u
        val reference = PsoV2Random(seed)
        reference.next() // Consumed by the invalid-range call below.
        val expectedDeterminant = reference.next().toFloat()
        val expectedNext = kotlin.math.floor(
            (256.0f * expectedDeterminant) / 4294967296.0f,
        ).toInt()
        val state = ChallengeRandomState(seed)

        assertEquals(5, state.biasedInt(5, 4))
        assertEquals(expectedNext, state.biasedInt(0, 255))
    }

    @Test
    fun zero_max_waves_materializes_one_wave_and_advances_prng() = testAsync {
        val quest = load1c1()
        quest.events.filter { it.isChallengeMode }.forEach { event ->
            event.cmWaveSettings = event.cmWaveSettings!! and 0xFFFF
        }

        val simulation = simulateChallengeModeSeed(quest, 0x12345678u)

        assertTrue(simulation.waves.isNotEmpty())
        assertTrue(
            simulation.waves
                .groupBy { it.floorId to it.sourceEventId }
                .values
                .all { it.size == 1 },
        )
        assertTrue(simulation.waves.all { wave ->
            val event = quest.events.single { it.floorId == wave.floorId && it.id == wave.sourceEventId }
            wave.delay in (event.delay.toInt() and 0xFFFF)..(event.unknown.toInt() and 0xFFFF)
        })
    }

    @Test
    fun all_ep1_challenge_stages_materialize_deterministically() = testAsync {
        val summaries = mutableListOf<String>()

        for (stage in 1..9) {
            val quest = loadStage(stage)
            val first = simulateChallengeModeSeed(quest, 0x12345678u)
            val again = simulateChallengeModeSeed(quest, 0x12345678u)
            val other = simulateChallengeModeSeed(quest, 0x12345679u)
            val roundTrippedDat = parseDat(writeDat(DatFile(
                objs = emptyList(),
                npcs = emptyList(),
                events = emptyList(),
                unknowns = emptyList(),
                cmRandomSpawns = quest.challengeData.cmRandomSpawns,
                cmMonsterMappings = quest.challengeData.cmMonsterMappings,
                cmConfigPool = quest.challengeData.cmConfigPool,
            )).cursor())
            val afterDatRoundTrip = simulateChallengeModeSeed(
                copyWithChallengeData(
                    quest,
                    QuestChallengeData(
                        roundTrippedDat.cmRandomSpawns,
                        roundTrippedDat.cmMonsterMappings,
                        roundTrippedDat.cmConfigPool,
                    ),
                ),
                0x12345678u,
            )

            assertTrue(first.waves.isNotEmpty(), "1c$stage must materialize waves.")
            assertTrue(first.monsters.isNotEmpty(), "1c$stage must materialize monsters.")
            assertEquals(fingerprint(first), fingerprint(again), "1c$stage must be deterministic.")
            assertEquals(
                fingerprint(first),
                fingerprint(afterDatRoundTrip),
                "1c$stage Type 4/5 DAT round-trip must preserve seed materialization.",
            )
            assertNotEquals(fingerprint(first), fingerprint(other), "1c$stage must react to seed changes.")

            first.waves.forEach { wave ->
                val event = quest.events.single {
                    it.floorId == wave.floorId && it.id == wave.sourceEventId
                }
                assertTrue(
                    wave.delay in
                            (event.delay.toInt() and 0xFFFF)..(event.unknown.toInt() and 0xFFFF),
                    "1c$stage generated a delay outside its source Event2 range.",
                )
            }
            first.waves.groupBy { it.floorId to it.sourceEventId }.forEach { (_, waves) ->
                assertEquals(
                    waves.first().sourceEventId,
                    waves.first().materializedEventId,
                    "1c$stage Event2 must keep its ID on the first materialized Event1.",
                )
                waves.zipWithNext().forEach { (current, next) ->
                    assertEquals(
                        current.sourceEventId + current.waveNumber + 10000,
                        current.triggeredEventId,
                        "1c$stage intermediate Event1 trigger ID must match newserv.",
                    )
                    assertEquals(current.triggeredEventId, next.materializedEventId)
                }
                assertEquals(
                    null,
                    waves.last().triggeredEventId,
                    "1c$stage final Event1 must use the source action stream.",
                )
            }
            for (monster in first.monsters) {
                val room = quest.challengeData.cmRandomSpawns.single {
                    it.floorId == monster.floorId && it.roomId == monster.roomId
                }
                assertTrue(
                    room.entries.any { it === monster.location },
                    "1c$stage used a location outside floor ${monster.floorId}, room ${monster.roomId}.",
                )
                val definition = quest.challengeData.cmConfigPool
                    .single { it.floorId == monster.floorId }
                    .entries
                    .single { (it.entryIndex.toInt() and 0xFFFF) == monster.definitionIndex }
                assertTrue(monster.numChildren in
                    (definition.minChildren.toInt() and 0xFFFF)..
                        (definition.maxChildren.toInt() and 0xFFFF))
            }

            summaries += "1c$stage:${first.waves.size}:${first.monsters.size}:${fingerprint(first)}"
        }

        assertEquals(
            listOf(
                "1c1:23:97:0daf55d0",
                "1c2:80:332:c3fdac29",
                "1c3:84:326:770fd262",
                "1c4:90:385:0ba2f48c",
                "1c5:71:320:8150689c",
                "1c6:94:409:72808fbf",
                "1c7:92:416:6bd2765d",
                "1c8:95:436:1b1d15b9",
                "1c9:94:448:a813816f",
            ),
            summaries,
        )
    }

    @Test
    fun seed_materialization_is_deterministic_and_uses_room_locations() = testAsync {
        val result = parseQstToQuest(
            readFile("$QUEST_RESOURCE_PREFIX/chl/ep1/1c1_e.qst"),
            lenient = true,
        )
        assertTrue(result is Success)
        val quest = result.value.quest

        val first = simulateChallengeModeSeed(quest, 0x12345678u)
        val again = simulateChallengeModeSeed(quest, 0x12345678u)
        val other = simulateChallengeModeSeed(quest, 0x12345679u)

        fun signature(simulation: ChallengeModeSeedSimulation) = simulation.waves.map { wave ->
            listOf(
                wave.floorId,
                wave.sourceEventId,
                wave.waveNumber,
                wave.roomId,
                wave.delay,
                wave.monsters.size,
                *wave.monsters.flatMap { monster ->
                    listOf(
                        monster.monsterTypeIndex,
                        monster.definitionIndex,
                        monster.numChildren,
                        monster.location.x.toBits(),
                        monster.location.y.toBits(),
                        monster.location.z.toBits(),
                    )
                }.toTypedArray(),
            )
        }

        assertTrue(first.waves.isNotEmpty())
        assertTrue(first.monsters.isNotEmpty())
        assertEquals(signature(first), signature(again))
        assertNotEquals(signature(first), signature(other))

        for (monster in first.monsters) {
            val room = quest.challengeData.cmRandomSpawns.single {
                it.floorId == monster.floorId && it.roomId == monster.roomId
            }
            assertTrue(
                room.entries.any { it === monster.location },
                "Materialized location must come from floor ${monster.floorId}, room ${monster.roomId}.",
            )
            val definition = quest.challengeData.cmConfigPool
                .single { it.floorId == monster.floorId }
                .entries
                .single { (it.entryIndex.toInt() and 0xFFFF) == monster.definitionIndex }
            assertTrue(monster.numChildren in
                (definition.minChildren.toInt() and 0xFFFF)..
                    (definition.maxChildren.toInt() and 0xFFFF))
        }
    }

    @Test
    fun real_1c1_seed_has_a_stable_materialization_snapshot() = testAsync {
        val quest = load1c1()
        val simulation = simulateChallengeModeSeed(quest, 0x12345678u)
        val snapshot = buildString {
            append("waves=${simulation.waves.size};monsters=${simulation.monsters.size}")
            simulation.waves.take(3).forEach { wave ->
                append("|")
                append(listOf(
                    wave.floorId,
                    wave.sourceEventId,
                    wave.waveNumber,
                    wave.roomId,
                    wave.delay,
                    wave.monsters.size,
                ).joinToString(","))
                wave.monsters.take(3).forEach { monster ->
                    append(":")
                    append(listOf(
                        monster.monsterTypeIndex,
                        monster.definitionIndex,
                        monster.numChildren,
                        monster.location.x.toBits(),
                        monster.location.y.toBits(),
                        monster.location.z.toBits(),
                    ).joinToString(","))
                }
            }
        }

        assertEquals(
            "waves=23;monsters=97" +
                "|1,21,1,2,43,4:1,7,0,-1028998108,1099637749,1119504212" +
                ":1,10,0,-1024107884,1100943120,1111421368" +
                ":0,2,0,-1025828408,1099426091,1121682144" +
                "|1,21,2,2,37,3:0,3,0,-1035428320,1099728210,-1056663328" +
                ":3,9,30,-1016314534,1098871128,1108617920" +
                ":0,1,0,-1032780884,1098065689,-1030069424" +
                "|1,51,1,5,41,3:2,7,0,-1031143904,1100933363,1117675584" +
                ":0,1,0,1103736016,1099261029,-1036879364" +
                ":0,2,0,1109133408,1097624671,1105912160",
            snapshot,
        )
    }

    @Test
    fun malformed_random_tables_do_not_crash_seed_preview() = testAsync {
        val quest = load1c1()

        val noRooms = copyWithChallengeData(
            quest,
            quest.challengeData.copy(cmRandomSpawns = emptyList()),
        )
        val noRoomsSimulation = simulateChallengeModeSeed(noRooms, 1u)
        assertTrue(noRoomsSimulation.waves.isEmpty())
        assertTrue(noRoomsSimulation.problems.any { "location table" in it.message })
        assertTrue(noRoomsSimulation.problems.any {
            it.floorId < 0 && "later floors depend on its RNG state" in it.message
        })

        val zeroWeights = quest.challengeData.cmMonsterMappings.map { table ->
            DatCmMonsterMapping(
                table.floorId,
                table.entries.map { entry ->
                    DatCmMonsterMappingEntry(
                        entry.monsterTypeIndex,
                        entry.definitionIndex,
                        0,
                        entry.unknown,
                    )
                }.toMutableList(),
            )
        }
        val zeroWeightSimulation =
            simulateChallengeModeSeed(
                copyWithChallengeData(
                    quest,
                    quest.challengeData.copy(cmMonsterMappings = zeroWeights),
                ),
                1u,
            )
        assertTrue(zeroWeightSimulation.waves.isEmpty())
        assertTrue(zeroWeightSimulation.problems.any { "weight total is zero" in it.message })

        val noDefinitions = quest.challengeData.cmConfigPool.map { table ->
            DatCmConfigPool(table.floorId, mutableListOf())
        }
        val noDefinitionsSimulation =
            simulateChallengeModeSeed(
                copyWithChallengeData(
                    quest,
                    quest.challengeData.copy(cmConfigPool = noDefinitions),
                ),
                1u,
            )
        assertTrue(noDefinitionsSimulation.waves.isEmpty())
        assertTrue(noDefinitionsSimulation.problems.any { "definition table" in it.message })

        val oversizedRooms = quest.challengeData.cmRandomSpawns.map { room ->
            if (room.entries.isEmpty()) {
                room
            } else {
                DatCmRandomSpawn(
                    room.floorId,
                    room.roomId,
                    MutableList(CHALLENGE_MODE_MAX_RANDOM_LOCATIONS_PER_ROOM + 1) {
                        room.entries.first()
                    },
                )
            }
        }
        val oversizedSimulation = simulateChallengeModeSeed(
            copyWithChallengeData(
                quest,
                quest.challengeData.copy(cmRandomSpawns = oversizedRooms),
            ),
            1u,
        )
        assertTrue(oversizedSimulation.monsters.isEmpty())
        assertTrue(oversizedSimulation.problems.any { "more than 32" in it.message })

        val duplicateConfigFloor = simulateChallengeModeSeed(
            copyWithChallengeData(
                quest,
                quest.challengeData.copy(
                    cmConfigPool = quest.challengeData.cmConfigPool +
                            quest.challengeData.cmConfigPool.first(),
                ),
            ),
            1u,
        )
        assertTrue(duplicateConfigFloor.problems.any { "Multiple random enemy definition" in it.message })

        val duplicateRoom = simulateChallengeModeSeed(
            copyWithChallengeData(
                quest,
                quest.challengeData.copy(
                    cmRandomSpawns = quest.challengeData.cmRandomSpawns +
                            quest.challengeData.cmRandomSpawns.first(),
                ),
            ),
            1u,
        )
        assertTrue(duplicateRoom.problems.any { "duplicate room IDs" in it.message })

        val invalidTypeTable = quest.challengeData.cmMonsterMappings.first()
        val invalidMonsterType = simulateChallengeModeSeed(
            copyWithChallengeData(
                quest,
                quest.challengeData.copy(
                    cmMonsterMappings = quest.challengeData.cmMonsterMappings.map { table ->
                        if (table === invalidTypeTable) {
                            DatCmMonsterMapping(
                                table.floorId,
                                (table.entries + DatCmMonsterMappingEntry(41, 1, 1, 0)).toMutableList(),
                            )
                        } else {
                            table
                        }
                    },
                ),
            ),
            1u,
        )
        assertTrue(invalidMonsterType.problems.any { "invalid monster type indexes: 41" in it.message })
        assertTrue(invalidMonsterType.monsters.none { it.floorId == invalidTypeTable.floorId })

        val unreachableInvalidMappings = simulateChallengeModeSeed(
            copyWithChallengeData(
                quest,
                quest.challengeData.copy(
                    cmMonsterMappings = quest.challengeData.cmMonsterMappings.map { table ->
                        if (table === invalidTypeTable) {
                            DatCmMonsterMapping(
                                table.floorId,
                                (table.entries + DatCmMonsterMappingEntry(
                                    monsterTypeIndex = 41,
                                    definitionIndex = 0xFE.toByte(),
                                    weight = 0,
                                    unknown = 0,
                                )).toMutableList(),
                            )
                        } else {
                            table
                        }
                    },
                ),
            ),
            1u,
        )
        assertTrue(unreachableInvalidMappings.problems.none {
            "invalid monster type indexes" in it.message || "definition 254 was not found" in it.message
        })
        assertTrue(unreachableInvalidMappings.monsters.any { it.floorId == invalidTypeTable.floorId })
    }

    @Test
    fun sentinel_only_mappings_do_not_require_enemy_definitions() = testAsync {
        val quest = load1c1()
        val sentinelMappings = quest.challengeData.cmMonsterMappings.map { table ->
            DatCmMonsterMapping(
                table.floorId,
                table.entries.map { entry ->
                    DatCmMonsterMappingEntry(
                        0xFF.toByte(),
                        0xFF.toByte(),
                        entry.weight,
                        entry.unknown,
                    )
                }.toMutableList(),
            )
        }
        val simulation = simulateChallengeModeSeed(
            copyWithChallengeData(
                quest,
                quest.challengeData.copy(
                    cmConfigPool = emptyList(),
                    cmMonsterMappings = sentinelMappings,
                ),
            ),
            0x12345678u,
        )

        assertTrue(simulation.waves.isNotEmpty())
        assertTrue(simulation.monsters.isEmpty())
        assertTrue(simulation.problems.none { "definition table" in it.message })
    }

    @Test
    fun unsorted_client_lookup_tables_use_canonical_preview_order() = testAsync {
        val quest = load1c1()
        val unsortedRooms = quest.challengeData.cmRandomSpawns
            .groupBy { it.floorId }
            .values
            .flatMap { it.reversed() }
        val roomSimulation = simulateChallengeModeSeed(
            copyWithChallengeData(
                quest,
                quest.challengeData.copy(cmRandomSpawns = unsortedRooms),
            ),
            1u,
        )
        assertTrue(roomSimulation.problems.any { "room table is not sorted" in it.message })
        assertTrue(roomSimulation.waves.isNotEmpty())

        val unsortedDefinitions = quest.challengeData.cmConfigPool.map { table ->
            DatCmConfigPool(table.floorId, table.entries.reversed().toMutableList())
        }
        val definitionSimulation = simulateChallengeModeSeed(
            copyWithChallengeData(
                quest,
                quest.challengeData.copy(cmConfigPool = unsortedDefinitions),
            ),
            1u,
        )
        assertTrue(definitionSimulation.problems.any { "definition table is not sorted" in it.message })
        assertTrue(definitionSimulation.waves.isNotEmpty())
    }

    @Test
    fun simulation_work_budget_stops_legal_extreme_values() = testAsync {
        val quest = load1c1()
        val event = quest.events.first { it.isChallengeMode }
        event.cmWaveSettings = 0xFFFF0000u.toInt() // 0 enemies, up to 65535 waves
        var seed = 0u
        while (ChallengeRandomState(seed).biasedInt(1, 0xFFFF) <=
            CHALLENGE_MODE_SIMULATION_MAX_WAVES
        ) {
            seed++
        }
        val singleEventQuest = Quest(
            quest.id, quest.language, quest.name, quest.shortDescription, quest.longDescription,
            quest.episode, quest.objects, quest.npcs, listOf(event), quest.datUnknowns,
            quest.challengeData, quest.bytecodeIr, quest.shopItems, quest.floorMappings,
            quest.bytecodeOffset, quest.shiftJis, quest.binFormat, quest.version,
            quest.particleSpawns,
        )

        val simulation = simulateChallengeModeSeed(singleEventQuest, seed)

        assertEquals(CHALLENGE_MODE_SIMULATION_MAX_WAVES, simulation.waves.size)
        assertTrue(simulation.problems.any { "work limit reached" in it.message })
    }

    private suspend fun load1c1(): Quest {
        return loadStage(1)
    }

    private suspend fun loadStage(stage: Int): Quest {
        val result = parseQstToQuest(
            readFile("$QUEST_RESOURCE_PREFIX/chl/ep1/1c${stage}_e.qst"),
            lenient = true,
        )
        assertTrue(result is Success)
        return result.value.quest
    }

    private fun fingerprint(simulation: ChallengeModeSeedSimulation): String {
        var hash = 0x811C9DC5u
        fun mix(value: Int) {
            hash = (hash xor value.toUInt()) * 0x01000193u
        }

        for (wave in simulation.waves) {
            mix(wave.floorId)
            mix(wave.sourceEventId)
            mix(wave.waveNumber)
            mix(wave.roomId)
            mix(wave.delay)
            mix(wave.monsters.size)
            for (monster in wave.monsters) {
                mix(monster.monsterTypeIndex)
                mix(monster.definitionIndex)
                mix(monster.numChildren)
                mix(monster.location.x.toBits())
                mix(monster.location.y.toBits())
                mix(monster.location.z.toBits())
                mix(monster.location.angleX)
                mix(monster.location.angleY)
                mix(monster.location.angleZ)
            }
        }
        return hash.toString(16).padStart(8, '0')
    }

    private fun copyWithChallengeData(quest: Quest, challengeData: QuestChallengeData) = Quest(
        quest.id,
        quest.language,
        quest.name,
        quest.shortDescription,
        quest.longDescription,
        quest.episode,
        quest.objects.toMutableList(),
        quest.npcs.toMutableList(),
        quest.events,
        quest.datUnknowns,
        challengeData,
        quest.bytecodeIr,
        quest.shopItems,
        quest.floorMappings,
        quest.bytecodeOffset,
        quest.shiftJis,
        quest.binFormat,
        quest.version,
        quest.particleSpawns,
    )
}
