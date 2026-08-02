package world.phantasmal.web.questEditor.controllers

import world.phantasmal.cell.Cell
import world.phantasmal.cell.cell
import world.phantasmal.cell.flatMap
import world.phantasmal.cell.isNull
import world.phantasmal.cell.map
import world.phantasmal.cell.mutableCell
import world.phantasmal.cell.mutate
import world.phantasmal.cell.mutateDeferred
import world.phantasmal.psolib.fileFormats.quest.CHALLENGE_MODE_MONSTER_TYPE_IDS
import world.phantasmal.psolib.fileFormats.quest.CHALLENGE_MODE_MAX_RANDOM_LOCATIONS_PER_ROOM
import world.phantasmal.psolib.fileFormats.quest.ChallengeModeSimulatedMonster
import world.phantasmal.psolib.fileFormats.quest.DatCmConfigPool
import world.phantasmal.psolib.fileFormats.quest.DatCmConfigPoolEntry
import world.phantasmal.psolib.fileFormats.quest.DatCmMonsterMapping
import world.phantasmal.psolib.fileFormats.quest.DatCmMonsterMappingEntry
import world.phantasmal.psolib.fileFormats.quest.DatCmRandomSpawn
import world.phantasmal.psolib.fileFormats.quest.DatCmRandomSpawnEntry
import world.phantasmal.psolib.fileFormats.quest.getNpcTypeForChallengeMonsterIndex
import world.phantasmal.web.questEditor.commands.EditChallengeDataCommand
import world.phantasmal.web.questEditor.commands.EditChallengeValueCommand
import world.phantasmal.web.questEditor.models.QuestModel
import world.phantasmal.web.questEditor.stores.QuestEditorStore
import world.phantasmal.webui.controllers.Tab
import world.phantasmal.webui.controllers.TabContainerController

enum class MonsterRandomnessTab(override val title: String) : Tab {
    MonsterPosition("Monster position"),
    EnemyConfiguration("Enemy configuration"),
}

data class RoomInfo(val globalIndex: Int, val roomId: Int, val entryCount: Int)
data class IndexedSpawnEntry(val index: Int, val entry: DatCmRandomSpawnEntry)
data class IndexedConfigPoolEntry(
    val tableIndex: Int,
    val entryIndex: Int,
    val displayIndex: Int,
    val entry: DatCmConfigPoolEntry,
)
data class IndexedMappingEntry(
    val tableIndex: Int,
    val entryIndex: Int,
    val displayIndex: Int,
    val entry: DatCmMonsterMappingEntry,
)
data class MonsterTypeOption(val index: Int, val name: String)

internal fun parseChallengeSeed(value: String): Int? {
    val normalized = value.trim().removePrefix("0x").removePrefix("0X")
    return normalized.toUIntOrNull(16)?.toInt()
}

class MonsterRandomnessController(
    private val store: QuestEditorStore,
) : TabContainerController<MonsterRandomnessTab>() {

    override val tabs: List<MonsterRandomnessTab> = MonsterRandomnessTab.values().toList()

    private val _activeTab = mutableCell<MonsterRandomnessTab?>(MonsterRandomnessTab.MonsterPosition)
    override val activeTab: Cell<MonsterRandomnessTab?> = _activeTab

    override fun setActiveTab(tab: MonsterRandomnessTab?) {
        _activeTab.value = tab
    }

    val unavailable: Cell<Boolean> = store.currentQuest.isNull()
    val enabled: Cell<Boolean> = store.questEditingEnabled
    val simulateSeed: Cell<Boolean> = store.challengeSeedSimulationEnabled
    val seed: Cell<Int> = store.challengeSeed
    val seedHex: Cell<String> = seed.map {
        it.toUInt().toString(16).uppercase().padStart(8, '0')
    }
    val logicalFloors: Cell<List<Int>> = store.challengeLogicalFloors
    val selectedLogicalFloor: Cell<Int?> = store.selectedChallengeLogicalFloor
    val selectedRoomId: Cell<Int?> = store.selectedChallengeRoomId

    fun setSimulateSeed(enabled: Boolean) {
        store.setChallengeSeedSimulationEnabled(enabled)
    }

    fun setSeed(seed: Int) {
        store.setChallengeSeed(seed)
    }

    fun setSeedHex(seed: String) {
        parseChallengeSeed(seed)?.let(store::setChallengeSeed)
    }

    fun nextSeed() {
        store.setChallengeSeed(store.challengeSeed.value + 1)
    }

    fun setLogicalFloor(floorId: Int) {
        store.setSelectedChallengeLogicalFloor(floorId)
    }

    val monsterTypeOptions: List<MonsterTypeOption> =
        CHALLENGE_MODE_MONSTER_TYPE_IDS.indices
            .filter { CHALLENGE_MODE_MONSTER_TYPE_IDS[it] != 0 }
            .map { idx ->
                val npcType = getNpcTypeForChallengeMonsterIndex(idx)
                val name = npcType?.simpleName ?: "Unknown(0x${CHALLENGE_MODE_MONSTER_TYPE_IDS[idx].toString(16).uppercase()})"
                MonsterTypeOption(idx, name)
            }

    val cmDataRevision: Cell<Int> =
        store.currentQuest.flatMap { it?.cmDataRevision ?: cell(0) }

    // === Monster Position tab ===

    /** Rooms filtered by the current area/variant. */
    val rooms: Cell<List<RoomInfo>> =
        map(store.currentQuest, selectedLogicalFloor, cmDataRevision) { quest, floorId, _ ->
            if (quest == null || floorId == null) return@map emptyList()
            quest.cmRandomSpawns.value.mapIndexedNotNull { globalIdx, spawn ->
                if (spawn.floorId == floorId) RoomInfo(globalIdx, spawn.roomId, spawn.entries.size) else null
            }
        }

    private val _selectedRoomIndex = mutableCell(-1)
    val selectedRoomIndex: Cell<Int> = _selectedRoomIndex

    val selectedRoomEntries: Cell<List<IndexedSpawnEntry>> =
        map(store.currentQuest, rooms, selectedRoomIndex, cmDataRevision) { quest, roomList, selIdx, _ ->
            if (quest == null || selIdx < 0 || selIdx >= roomList.size) return@map emptyList()
            val globalIdx = roomList[selIdx].globalIndex
            quest.cmRandomSpawns.value[globalIdx].entries.mapIndexed { idx, entry ->
                IndexedSpawnEntry(idx, entry)
            }
        }

    val canAddSpawnEntry: Cell<Boolean> =
        map(enabled, selectedRoomIndex, simulateSeed, selectedRoomEntries) { e, index, sim, entries ->
            e && index >= 0 && !sim && entries.size < CHALLENGE_MODE_MAX_RANDOM_LOCATIONS_PER_ROOM
        }
    val canDeleteRoom: Cell<Boolean> =
        map(enabled, selectedRoomIndex) { editingEnabled, index ->
            editingEnabled && index >= 0
        }

    val simulatedMonsters: Cell<List<ChallengeModeSimulatedMonster>> =
        map(store.challengeSeedSimulation, selectedLogicalFloor, selectedRoomId, cmDataRevision) {
                simulation, floorId, roomId, _ ->
            if (simulation == null || floorId == null) return@map emptyList()
            simulation.monsters.filter {
                it.floorId == floorId && (roomId == null || it.roomId == roomId)
            }
        }

    val simulationProblems: Cell<List<String>> =
        map(store.challengeSeedSimulation, selectedLogicalFloor, cmDataRevision) {
                simulation, floorId, _ ->
            if (simulation == null || floorId == null) return@map emptyList()
            simulation.problems
                .filter { it.floorId < 0 || it.floorId == floorId }
                .map { if (it.floorId < 0) it.message else "Floor ${it.floorId}: ${it.message}" }
        }

    fun simulatedMonsterName(monster: ChallengeModeSimulatedMonster): String =
        getNpcTypeForChallengeMonsterIndex(monster.monsterTypeIndex)?.simpleName
            ?: "Unknown(${monster.monsterTypeIndex})"

    // === Enemy Configuration tab ===

    /** Config pool entries (Table 5A) filtered by current area/variant. */
    val configPoolEntries: Cell<List<IndexedConfigPoolEntry>> =
        map(store.currentQuest, selectedLogicalFloor, cmDataRevision) { quest, floorId, _ ->
            if (quest == null || floorId == null) return@map emptyList()
            buildList {
                quest.cmConfigPool.value.forEachIndexed { tableIndex, table ->
                    if (table.floorId == floorId) {
                        table.entries.forEachIndexed { entryIndex, entry ->
                            add(IndexedConfigPoolEntry(tableIndex, entryIndex, size, entry))
                        }
                    }
                }
            }
        }

    /** Monster setting entries (Table 5B) filtered by current area/variant. */
    val monsterSettingEntries: Cell<List<IndexedMappingEntry>> =
        map(store.currentQuest, selectedLogicalFloor, cmDataRevision) { quest, floorId, _ ->
            if (quest == null || floorId == null) return@map emptyList()
            buildList {
                quest.cmMonsterMappings.value.forEachIndexed { tableIndex, table ->
                    if (table.floorId == floorId) {
                        table.entries.forEachIndexed { entryIndex, entry ->
                            add(IndexedMappingEntry(tableIndex, entryIndex, size, entry))
                        }
                    }
                }
            }
        }

    init {
        // Reset room selection when quest or floor changes.
        observe(store.currentQuest) {
            clearRoomSelectionDeferred()
        }
        observe(store.currentArea) {
            clearRoomSelectionDeferred()
        }
        observe(selectedLogicalFloor) {
            clearRoomSelectionDeferred()
        }
    }

    // === Monster Position actions ===

    fun selectRoom(index: Int) {
        val room = rooms.value.getOrNull(index)
        mutate {
            _selectedRoomIndex.value = if (room == null) -1 else index
            store.setSelectedChallengeRoomId(room?.roomId)
        }
    }

    private fun clearRoomSelectionDeferred() {
        mutateDeferred {
            mutate {
                _selectedRoomIndex.value = -1
                store.setSelectedChallengeRoomId(null)
            }
        }
    }

    /** Resolves the global spawn index from the current room selection. Returns -1 if invalid. */
    private fun selectedGlobalSpawnIndex(): Int {
        val roomList = rooms.value
        val selIdx = _selectedRoomIndex.value
        if (selIdx < 0 || selIdx >= roomList.size) return -1
        return roomList[selIdx].globalIndex
    }

    fun addRoom() {
        val quest = store.currentQuest.value ?: return
        val floorId = selectedLogicalFloor.value ?: return
        val floorSpawns = quest.cmRandomSpawns.value.filter { it.floorId == floorId }
        val usedRoomIds = floorSpawns.mapTo(mutableSetOf()) { it.roomId }
        val nextRoomId = (floorSpawns.maxOfOrNull { it.roomId } ?: -1) + 1
        val newRoomId = nextRoomId.takeIf { it <= 0xFFFF }
            ?: (0..0xFFFF).firstOrNull { it !in usedRoomIds }
            ?: return
        val room = DatCmRandomSpawn(floorId, newRoomId, mutableListOf())
        editChallengeData(
            "Add Challenge Mode room",
            execute = { quest.addCmRandomSpawn(room) },
            undo = { quest.removeCmRandomSpawn(room) },
        )
    }

    fun deleteRoom() {
        val quest = store.currentQuest.value ?: return
        val globalIdx = selectedGlobalSpawnIndex()
        if (globalIdx < 0) return
        val room = quest.cmRandomSpawns.value[globalIdx]
        editChallengeData(
            "Delete Challenge Mode room",
            execute = { quest.removeCmRandomSpawn(room) },
            undo = { quest.addCmRandomSpawn(globalIdx, room) },
        )
        selectRoom((_selectedRoomIndex.value - 1).coerceAtLeast(-1))
    }

    fun addSpawnEntry() {
        val quest = store.currentQuest.value ?: return
        val globalIdx = selectedGlobalSpawnIndex()
        if (globalIdx < 0) return
        val entries = quest.cmRandomSpawns.value[globalIdx].entries
        if (entries.size >= CHALLENGE_MODE_MAX_RANDOM_LOCATIONS_PER_ROOM) return
        val entry = DatCmRandomSpawnEntry(
            x = 0f, y = 0f, z = 0f,
            angleX = 0, angleY = 0, angleZ = 0,
            unknownA9 = 0, unknownA10 = 0,
        )
        editChallengeData(
            "Add Challenge Mode spawn location",
            execute = { entries.add(entry); quest.bumpCmRevision() },
            undo = { entries.remove(entry); quest.bumpCmRevision() },
        )
    }

    fun deleteSpawnEntry(entryIndex: Int) {
        val quest = store.currentQuest.value ?: return
        val globalIdx = selectedGlobalSpawnIndex()
        if (globalIdx < 0) return
        val entries = quest.cmRandomSpawns.value[globalIdx].entries
        if (entryIndex < 0 || entryIndex >= entries.size) return
        val entry = entries[entryIndex]
        editChallengeData(
            "Delete Challenge Mode spawn location",
            execute = { entries.removeAt(entryIndex); quest.bumpCmRevision() },
            undo = { entries.add(entryIndex, entry); quest.bumpCmRevision() },
        )
    }

    fun setSpawnField(entryIndex: Int, setter: (DatCmRandomSpawnEntry) -> Unit) {
        val quest = store.currentQuest.value ?: return
        val globalIdx = selectedGlobalSpawnIndex()
        if (globalIdx < 0) return
        val entries = quest.cmRandomSpawns.value[globalIdx].entries
        if (entryIndex < 0 || entryIndex >= entries.size) return
        val entry = entries[entryIndex]
        editChallengeValue(
            quest,
            "Edit Challenge Mode spawn location",
            capture = { entry.snapshot() },
            restore = entry::restore,
            edit = { setter(entry) },
        )
    }

    fun setRoomId(globalIndex: Int, roomId: Int) {
        val quest = store.currentQuest.value ?: return
        val spawns = quest.cmRandomSpawns.value
        if (globalIndex < 0 || globalIndex >= spawns.size) return
        if (roomId !in 0..0xFFFF) return
        val floorId = spawns[globalIndex].floorId
        if (spawns.withIndex().any { (index, spawn) ->
                index != globalIndex && spawn.floorId == floorId && spawn.roomId == roomId
            }) return
        val spawn = spawns[globalIndex]
        if (spawn.roomId == roomId) return
        editChallengeValue(
            quest,
            "Edit Challenge Mode room ID",
            capture = { spawn.roomId },
            restore = { spawn.roomId = it },
            edit = { spawn.roomId = roomId },
        )
    }

    // === Enemy Configuration actions ===

    fun setConfigPoolField(indexed: IndexedConfigPoolEntry, setter: (DatCmConfigPoolEntry) -> Unit) {
        val quest = store.currentQuest.value ?: return
        val pool = quest.cmConfigPool.value.getOrNull(indexed.tableIndex) ?: return
        val entry = pool.entries.getOrNull(indexed.entryIndex) ?: return
        if (entry !== indexed.entry) return
        editChallengeValue(
            quest,
            "Edit Challenge Mode enemy definition",
            capture = { entry.snapshot() },
            restore = entry::restore,
            edit = { setter(entry) },
        )
    }

    fun setConfigPoolEntryIndex(indexed: IndexedConfigPoolEntry, entryIndex: Int) {
        if (entryIndex !in 0..0xFFFF) return
        val quest = store.currentQuest.value ?: return
        val pool = quest.cmConfigPool.value.getOrNull(indexed.tableIndex) ?: return
        val entry = pool.entries.getOrNull(indexed.entryIndex) ?: return
        if (entry !== indexed.entry) return
        if ((entry.entryIndex.toInt() and 0xFFFF) == entryIndex) return
        if (pool.entries.any {
                it !== entry && (it.entryIndex.toInt() and 0xFFFF) == entryIndex
            }) return
        setConfigPoolField(indexed) { it.entryIndex = entryIndex.toShort() }
    }

    fun addConfigPoolEntry() {
        val quest = store.currentQuest.value ?: return
        val floorId = selectedLogicalFloor.value ?: return
        val existingPool = quest.cmConfigPool.value.find { it.floorId == floorId }
        val pool = existingPool ?: DatCmConfigPool(floorId, mutableListOf())
        val usedEntryIndexes = pool.entries
            .mapTo(mutableSetOf()) { it.entryIndex.toInt() and 0xFFFF }
        val nextSequentialIndex = (usedEntryIndexes.maxOrNull() ?: 0) + 1
        val nextEntryIndex = nextSequentialIndex.takeIf { it <= 0xFFFF }
            ?: (0..0xFFFF).firstOrNull { it !in usedEntryIndexes }
            ?: return
        val newEntry = DatCmConfigPoolEntry(
            param1 = 0f, param2 = 0f, param3 = 0f, param4 = 0f, param5 = 0f,
            param7 = 0, param6 = 0, entryIndex = nextEntryIndex.toShort(), unknown = 0,
            minChildren = 0, maxChildren = 0,
        )

        if (existingPool == null) {
            pool.entries.add(newEntry)
            editChallengeData(
                "Add Challenge Mode enemy definition",
                execute = { quest.addCmConfigPool(pool) },
                undo = { quest.removeCmConfigPool(pool) },
            )
        } else {
            editChallengeData(
                "Add Challenge Mode enemy definition",
                execute = { pool.entries.add(newEntry); quest.bumpCmRevision() },
                undo = { pool.entries.remove(newEntry); quest.bumpCmRevision() },
            )
        }
    }

    fun deleteConfigPoolEntry(indexed: IndexedConfigPoolEntry) {
        val quest = store.currentQuest.value ?: return
        val pool = quest.cmConfigPool.value.getOrNull(indexed.tableIndex) ?: return
        val entry = pool.entries.getOrNull(indexed.entryIndex) ?: return
        if (entry !== indexed.entry) return
        editChallengeData(
            "Delete Challenge Mode enemy definition",
            execute = { pool.entries.removeAt(indexed.entryIndex); quest.bumpCmRevision() },
            undo = { pool.entries.add(indexed.entryIndex, entry); quest.bumpCmRevision() },
        )
    }

    fun setMappingField(indexed: IndexedMappingEntry, setter: (DatCmMonsterMappingEntry) -> Unit) {
        val quest = store.currentQuest.value ?: return
        val mapping = quest.cmMonsterMappings.value.getOrNull(indexed.tableIndex) ?: return
        val entry = mapping.entries.getOrNull(indexed.entryIndex) ?: return
        if (entry !== indexed.entry) return
        editChallengeValue(
            quest,
            "Edit Challenge Mode monster weight",
            capture = { entry.snapshot() },
            restore = entry::restore,
            edit = { setter(entry) },
        )
    }

    fun addMappingEntry() {
        val quest = store.currentQuest.value ?: return
        val floorId = selectedLogicalFloor.value ?: return
        val existingMapping = quest.cmMonsterMappings.value.find { it.floorId == floorId }
        val mapping = existingMapping ?: DatCmMonsterMapping(floorId, mutableListOf())
        val newEntry = DatCmMonsterMappingEntry(
            monsterTypeIndex = 0,
            definitionIndex = 0,
            weight = 0,
            unknown = 0,
        )
        if (existingMapping == null) {
            mapping.entries.add(newEntry)
            editChallengeData(
                "Add Challenge Mode monster weight",
                execute = { quest.addCmMonsterMapping(mapping) },
                undo = { quest.removeCmMonsterMapping(mapping) },
            )
        } else {
            editChallengeData(
                "Add Challenge Mode monster weight",
                execute = { mapping.entries.add(newEntry); quest.bumpCmRevision() },
                undo = { mapping.entries.remove(newEntry); quest.bumpCmRevision() },
            )
        }
    }

    fun deleteMappingEntry(indexed: IndexedMappingEntry) {
        val quest = store.currentQuest.value ?: return
        val mapping = quest.cmMonsterMappings.value.getOrNull(indexed.tableIndex) ?: return
        val entry = mapping.entries.getOrNull(indexed.entryIndex) ?: return
        if (entry !== indexed.entry) return
        editChallengeData(
            "Delete Challenge Mode monster weight",
            execute = { mapping.entries.removeAt(indexed.entryIndex); quest.bumpCmRevision() },
            undo = { mapping.entries.add(indexed.entryIndex, entry); quest.bumpCmRevision() },
        )
    }

    private fun editChallengeData(description: String, execute: () -> Unit, undo: () -> Unit) {
        store.executeAction(EditChallengeDataCommand(description, execute, undo))
    }

    private fun <T> editChallengeValue(
        quest: QuestModel,
        description: String,
        capture: () -> T,
        restore: (T) -> Unit,
        edit: () -> Unit,
    ) {
        store.executeAction(EditChallengeValueCommand(
            description,
            capture,
            restore = { state -> restore(state); quest.bumpCmRevision() },
            edit = { edit(); quest.bumpCmRevision() },
        ))
    }

    fun focused() {
        store.makeMainUndoCurrent()
    }
}

private data class SpawnEntryState(
    val x: Float,
    val y: Float,
    val z: Float,
    val angleX: Int,
    val angleY: Int,
    val angleZ: Int,
    val unknownA9: Short,
    val unknownA10: Short,
)

private fun DatCmRandomSpawnEntry.snapshot() = SpawnEntryState(
    x, y, z, angleX, angleY, angleZ, unknownA9, unknownA10,
)

private fun DatCmRandomSpawnEntry.restore(state: SpawnEntryState) {
    x = state.x
    y = state.y
    z = state.z
    angleX = state.angleX
    angleY = state.angleY
    angleZ = state.angleZ
    unknownA9 = state.unknownA9
    unknownA10 = state.unknownA10
}

private data class ConfigEntryState(
    val param1: Float, val param2: Float, val param3: Float, val param4: Float, val param5: Float,
    val param7: Short, val param6: Short, val entryIndex: Short, val unknown: Short,
    val minChildren: Short, val maxChildren: Short,
)

private fun DatCmConfigPoolEntry.snapshot() = ConfigEntryState(
    param1, param2, param3, param4, param5, param7, param6, entryIndex, unknown,
    minChildren, maxChildren,
)

private fun DatCmConfigPoolEntry.restore(state: ConfigEntryState) {
    param1 = state.param1
    param2 = state.param2
    param3 = state.param3
    param4 = state.param4
    param5 = state.param5
    param7 = state.param7
    param6 = state.param6
    entryIndex = state.entryIndex
    unknown = state.unknown
    minChildren = state.minChildren
    maxChildren = state.maxChildren
}

private data class MappingEntryState(
    val monsterTypeIndex: Byte,
    val definitionIndex: Byte,
    val weight: Byte,
    val unknown: Byte,
)

private fun DatCmMonsterMappingEntry.snapshot() = MappingEntryState(
    monsterTypeIndex, definitionIndex, weight, unknown,
)

private fun DatCmMonsterMappingEntry.restore(state: MappingEntryState) {
    monsterTypeIndex = state.monsterTypeIndex
    definitionIndex = state.definitionIndex
    weight = state.weight
    unknown = state.unknown
}
