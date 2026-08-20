package world.phantasmal.psolib.fileFormats.quest

import kotlin.math.floor

/** The result of materializing EP1 Challenge Mode random sections with a JoinGame seed. */
data class ChallengeModeSeedSimulation(
    val seed: UInt,
    val waves: List<ChallengeModeSimulatedWave>,
    val problems: List<ChallengeModeSimulationProblem> = emptyList(),
) {
    val monsters: List<ChallengeModeSimulatedMonster> = waves.flatMap { it.monsters }
}

data class ChallengeModeSimulationProblem(
    val floorId: Int,
    val message: String,
)

data class ChallengeModeSimulatedWave(
    val floorId: Int,
    val sourceEventId: Int,
    val waveNumber: Int,
    val roomId: Int,
    val delay: Int,
    val monsters: List<ChallengeModeSimulatedMonster>,
    /** Event1 ID produced by materializing the source Event2 entry. */
    val materializedEventId: Int = sourceEventId,
    /** Event1 triggered by this wave, or null when this is the final wave. */
    val triggeredEventId: Int? = null,
)

data class ChallengeModeSimulatedMonster(
    val floorId: Int,
    val sourceEventId: Int,
    val waveNumber: Int,
    val roomId: Int,
    val monsterTypeIndex: Int,
    val definitionIndex: Int,
    val numChildren: Int,
    val location: DatCmRandomSpawnEntry,
)

internal const val CHALLENGE_MODE_SIMULATION_MAX_WAVES = 4096
internal const val CHALLENGE_MODE_SIMULATION_MAX_ENEMY_SLOTS = 20000
internal const val CHALLENGE_MODE_SIMULATION_MAX_WEIGHT_SCANS = 1000000

/**
 * Materializes the random waves in an EP1 Challenge Mode quest using newserv's client-matched
 * algorithm. The PRNG state is shared across all floors and events, just as it is when a client
 * joins a game. Type 4 locations remain room-local after parsing, so their room-table base offset
 * is already represented by [DatCmRandomSpawn.entries].
 */
fun simulateChallengeModeSeed(quest: Quest, seed: UInt): ChallengeModeSeedSimulation {
    val random = ChallengeRandomState(seed)
    val waves = mutableListOf<ChallengeModeSimulatedWave>()
    val problems = mutableListOf<ChallengeModeSimulationProblem>()
    val problemKeys = mutableSetOf<Pair<Int, String>>()
    var simulatedWaveCount = 0
    var simulatedEnemySlotCount = 0
    var weightScanCount = 0L
    fun report(floorId: Int, message: String) {
        if (problemKeys.add(floorId to message)) {
            problems += ChallengeModeSimulationProblem(floorId, message)
        }
    }

    floorLoop@ for (floorId in 0 until 0x12) {
        val events = quest.events.filter { it.floorId == floorId && it.isChallengeMode }
        if (events.isEmpty()) continue

        val rawLocationTables = quest.challengeData.cmRandomSpawns.filter { it.floorId == floorId }
        val locationTables = rawLocationTables.sortedBy { it.roomId }
        val configTables = quest.challengeData.cmConfigPool.filter { it.floorId == floorId }
        val mappingTables = quest.challengeData.cmMonsterMappings.filter { it.floorId == floorId }
        val duplicateRoomIds = rawLocationTables.groupBy { it.roomId }.filterValues { it.size > 1 }.keys
        val roomIdsAreSorted = rawLocationTables.zipWithNext().all { (a, b) -> a.roomId < b.roomId }
        val duplicateDefinitionIndexes = configTables.singleOrNull()?.entries
            ?.groupBy { it.entryIndex.toInt() and 0xFFFF }
            ?.filterValues { it.size > 1 }
            ?.keys
            .orEmpty()
        val definitionIndexesAreSorted = configTables.singleOrNull()?.entries
            ?.zipWithNext()
            ?.all { (a, b) ->
                (a.entryIndex.toInt() and 0xFFFF) < (b.entryIndex.toInt() and 0xFFFF)
            }
            ?: true
        val locationsByRoom = locationTables
            .associateBy { it.roomId }
        val definitions = configTables.singleOrNull()
            ?.entries
            ?.sortedBy { it.entryIndex.toInt() and 0xFFFF }
            .orEmpty()
        val definitionsByIndex = definitions.associateBy {
            it.entryIndex.toInt() and 0xFFFF
        }
        val mappings = mappingTables.singleOrNull()
            ?.entries
            .orEmpty()
        val mappingsRequiringDefinitions = mappings.filter {
            (it.weight.toInt() and 0xFF) != 0 &&
                    (it.monsterTypeIndex.toInt() and 0xFF) != 0xFF &&
                    (it.definitionIndex.toInt() and 0xFF) != 0xFF
        }
        val weightTotal = mappings.sumOf { it.weight.toInt() and 0xFF }

        if (duplicateRoomIds.isNotEmpty()) report(floorId, "Random location table has duplicate room IDs.")
        if (duplicateRoomIds.isEmpty() && !roomIdsAreSorted) {
            report(floorId, "Random location room table is not sorted; preview uses the canonical save order.")
        }
        if (configTables.size > 1) report(floorId, "Multiple random enemy definition tables exist for this floor.")
        if (mappingTables.size > 1) report(floorId, "Multiple random enemy weight tables exist for this floor.")
        if (duplicateDefinitionIndexes.isNotEmpty()) {
            report(floorId, "Random enemy definition table has duplicate indexes.")
        }
        if (duplicateDefinitionIndexes.isEmpty() && !definitionIndexesAreSorted) {
            report(floorId, "Random enemy definition table is not sorted; preview uses the canonical save order.")
        }
        if (locationsByRoom.isEmpty()) report(floorId, "Random location table is missing or empty.")
        if (definitions.isEmpty() && mappingsRequiringDefinitions.isNotEmpty()) {
            report(floorId, "Random enemy definition table is missing or empty.")
        }
        if (mappings.isEmpty()) report(floorId, "Random enemy weight table is missing or empty.")
        if (mappings.isNotEmpty() && weightTotal <= 0) report(floorId, "Random enemy weight total is zero.")
        val invalidMonsterTypeIndexes = mappings
            .filter { (it.weight.toInt() and 0xFF) != 0 }
            .map { it.monsterTypeIndex.toInt() and 0xFF }
            .filter { index ->
                index != 0xFF &&
                        (index !in CHALLENGE_MODE_MONSTER_TYPE_IDS.indices ||
                                CHALLENGE_MODE_MONSTER_TYPE_IDS[index] == 0)
            }
            .distinct()
        if (invalidMonsterTypeIndexes.isNotEmpty()) {
            report(
                floorId,
                "Random enemy weight table has invalid monster type indexes: " +
                        invalidMonsterTypeIndexes.joinToString(", "),
            )
        }

        val missingRoomIds = events
            .filter { it.cmMaxEnemies > 0 }
            .map { it.sectionId.toInt() and 0xFFFF }
            .filter { locationsByRoom[it]?.entries.isNullOrEmpty() }
            .distinct()
        missingRoomIds.forEach {
            report(floorId, "Challenge events reference room $it without random locations.")
        }
        val missingDefinitionIndexes = mappingsRequiringDefinitions
            .map { it.definitionIndex.toInt() and 0xFF }
            .filter { it !in definitionsByIndex }
            .distinct()
        missingDefinitionIndexes.forEach {
            report(floorId, "Random enemy definition $it was not found.")
        }

        if (locationsByRoom.isEmpty() ||
            (definitions.isEmpty() && mappingsRequiringDefinitions.isNotEmpty()) ||
            weightTotal <= 0 ||
            duplicateRoomIds.isNotEmpty() || configTables.size > 1 || mappingTables.size > 1 ||
            duplicateDefinitionIndexes.isNotEmpty() || invalidMonsterTypeIndexes.isNotEmpty() ||
            missingRoomIds.isNotEmpty() || missingDefinitionIndexes.isNotEmpty()
        ) {
            report(-1, "Simulation stopped at floor $floorId because later floors depend on its RNG state.")
            break
        }

        for (event in events) {
            var remainingWaves = random.biasedInt(1, event.cmMaxWaves)
            var waveNumber = event.wave.toInt() and 0xFFFF
            val roomId = event.sectionId.toInt() and 0xFFFF
            var materializedEventId = event.id

            while (remainingWaves > 0) {
                if (simulatedWaveCount >= CHALLENGE_MODE_SIMULATION_MAX_WAVES) {
                    report(floorId, "Simulation stopped after $CHALLENGE_MODE_SIMULATION_MAX_WAVES waves.")
                    report(-1, "Simulation work limit reached; later floors were not materialized.")
                    break@floorLoop
                }
                simulatedWaveCount++
                remainingWaves--
                val currentEventId = materializedEventId
                val triggeredEventId = if (remainingWaves > 0) {
                    (event.id + waveNumber + 10000).also { materializedEventId = it }
                } else {
                    null
                }
                var remainingEnemies = random.biasedInt(event.cmMinEnemies, event.cmMaxEnemies)
                val roomLocations = locationsByRoom[roomId]?.entries.orEmpty()
                if (remainingEnemies > 0 && roomLocations.isEmpty()) {
                    report(floorId, "Event ${event.id} references room $roomId without random locations.")
                }
                val shuffledLocations = random.shuffledLocations(roomLocations)
                var locationIndex = 0
                val monsters = mutableListOf<ChallengeModeSimulatedMonster>()

                while (remainingEnemies > 0) {
                    if (simulatedEnemySlotCount >= CHALLENGE_MODE_SIMULATION_MAX_ENEMY_SLOTS) {
                        report(
                            floorId,
                            "Simulation stopped after $CHALLENGE_MODE_SIMULATION_MAX_ENEMY_SLOTS enemy slots.",
                        )
                        report(-1, "Simulation work limit reached; later floors were not materialized.")
                        break@floorLoop
                    }
                    if (weightScanCount + mappings.size > CHALLENGE_MODE_SIMULATION_MAX_WEIGHT_SCANS) {
                        report(
                            floorId,
                            "Simulation stopped after $CHALLENGE_MODE_SIMULATION_MAX_WEIGHT_SCANS weight scans.",
                        )
                        report(-1, "Simulation work limit reached; later floors were not materialized.")
                        break@floorLoop
                    }
                    simulatedEnemySlotCount++
                    weightScanCount += mappings.size
                    remainingEnemies--
                    var determinant = random.biasedInt(0, weightTotal - 1)
                    var selected: DatCmMonsterMappingEntry? = null
                    for (mapping in mappings) {
                        val weight = mapping.weight.toInt() and 0xFF
                        if (determinant < weight) {
                            selected = mapping
                            break
                        }
                        determinant -= weight
                    }

                    val monsterTypeIndex = selected?.monsterTypeIndex?.toInt()?.and(0xFF) ?: continue
                    val definitionIndex = selected.definitionIndex.toInt() and 0xFF
                    if (monsterTypeIndex == 0xFF || definitionIndex == 0xFF) continue

                    val definition = definitionsByIndex[definitionIndex]
                    if (definition == null) {
                        report(floorId, "Random enemy definition $definitionIndex was not found.")
                        continue
                    }
                    val numChildren = random.biasedInt(
                        definition.minChildren.toInt() and 0xFFFF,
                        definition.maxChildren.toInt() and 0xFFFF,
                    )
                    val location = when {
                        shuffledLocations.isEmpty() -> null
                        locationIndex < shuffledLocations.size -> shuffledLocations[locationIndex++]
                        else -> shuffledLocations[0]
                    } ?: continue

                    monsters += ChallengeModeSimulatedMonster(
                        floorId = floorId,
                        sourceEventId = event.id,
                        waveNumber = waveNumber,
                        roomId = roomId,
                        monsterTypeIndex = monsterTypeIndex,
                        definitionIndex = definitionIndex,
                        numChildren = numChildren,
                        location = location,
                    )
                }

                // Intermediate waves create transition Event1 entries; the final wave creates
                // the Event1 entry that runs the source Event2 action stream. Both paths consume
                // delay RNG in the client/newserv materialization loop.
                val delay = random.biasedInt(
                    event.delay.toInt() and 0xFFFF,
                    event.unknown.toInt() and 0xFFFF,
                )
                waves += ChallengeModeSimulatedWave(
                    floorId = floorId,
                    sourceEventId = event.id,
                    waveNumber = waveNumber,
                    roomId = roomId,
                    delay = delay,
                    monsters = monsters,
                    materializedEventId = currentEventId,
                    triggeredEventId = triggeredEventId,
                )
                waveNumber++
            }
        }
    }

    return ChallengeModeSeedSimulation(seed, waves, problems)
}

internal class ChallengeRandomState(seed: UInt) {
    private val random = PsoV2Random(seed)
    private val locationRandom = PsoV2Random(0u)

    fun biasedInt(min: Int, max: Int): Int {
        val determinant = random.next().toFloat()
        val result = floor(((max + 1).toFloat() * determinant) / UINT32_RANGE).toInt()
        return result.coerceAtLeast(min)
    }

    fun shuffledLocations(entries: List<DatCmRandomSpawnEntry>): List<DatCmRandomSpawnEntry> {
        if (entries.isEmpty()) return emptyList()
        val result = entries.toMutableList()
        repeat(4) {
            for (index in result.indices) {
                val determinant = locationRandom.next().toFloat()
                val choice = floor((result.size.toFloat() * determinant) / UINT32_RANGE).toInt()
                    .coerceIn(result.indices)
                val tmp = result[index]
                result[index] = result[choice]
                result[choice] = tmp
            }
        }
        return result
    }

    companion object {
        private const val UINT32_RANGE = 4294967296.0f
    }
}

/** PSOV2Encryption used by the client for Challenge Mode random materialization. */
internal class PsoV2Random(seed: UInt) {
    private val stream = IntArray(0x39)
    private var offset = 0

    init {
        var a = 1
        var b = seed.toInt()
        stream[0x37] = b
        var virtualIndex = 0x15
        while (virtualIndex <= 0x36 * 0x15) {
            stream[virtualIndex % 0x37] = a
            val c = b - a
            b = a
            a = c
            virtualIndex += 0x15
        }
        repeat(5) { updateStream() }
    }

    fun next(): UInt {
        if (offset == 0x38) updateStream()
        return stream[offset++].toUInt()
    }

    private fun updateStream() {
        for (index in 1 until 0x19) {
            stream[index] -= stream[index + 0x1F]
        }
        for (index in 0x19 until 0x38) {
            stream[index] -= stream[index - 0x18]
        }
        offset = 1
    }
}
