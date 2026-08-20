package world.phantasmal.web.questEditor.controllers

import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.dataFlowAnalysis.FloorMapping
import world.phantasmal.psolib.fileFormats.quest.DatCmConfigPool
import world.phantasmal.psolib.fileFormats.quest.DatCmConfigPoolEntry
import world.phantasmal.psolib.fileFormats.quest.DatCmMonsterMapping
import world.phantasmal.psolib.fileFormats.quest.DatCmMonsterMappingEntry
import world.phantasmal.psolib.fileFormats.quest.DatCmRandomSpawn
import world.phantasmal.psolib.fileFormats.quest.DatCmRandomSpawnEntry
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import world.phantasmal.web.questEditor.models.QuestEventModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MonsterRandomnessControllerTests : WebTestSuite {
    @Test
    fun parses_join_game_seed_as_full_unsigned_hex() {
        assertEquals(0, parseChallengeSeed("00000000"))
        assertEquals(0x12345678, parseChallengeSeed("0x12345678"))
        assertEquals(0xFFFFFFFFu.toInt(), parseChallengeSeed("FFFFFFFF"))
        assertEquals(null, parseChallengeSeed("100000000"))
        assertEquals(null, parseChallengeSeed("not-a-seed"))
    }

    @Test
    fun supports_patched_client_room_sizes_and_enforces_unique_room_ids() = testAsync {
        val quest = createQuestModel()
        quest.addCmRandomSpawn(DatCmRandomSpawn(0, 1, mutableListOf()))
        quest.addCmRandomSpawn(DatCmRandomSpawn(0, 2, mutableListOf()))
        components.questEditorStore.setCurrentQuest(quest)
        val controller = disposer.add(MonsterRandomnessController(components.questEditorStore))
        controller.selectRoom(0)

        repeat(37) {
            controller.addSpawnEntry()
        }
        assertEquals(37, quest.cmRandomSpawns.value[0].entries.size)
        assertTrue(controller.canAddSpawnEntry.value)
        assertTrue(controller.canDeleteRoom.value)

        controller.setRoomId(1, 1)
        assertEquals(2, quest.cmRandomSpawns.value[1].roomId, "Duplicate room IDs must be rejected.")
    }

    @Test
    fun selected_room_filters_materialized_results_without_reseeding() = testAsync {
        val quest = createQuestModel()
        quest.addCmRandomSpawn(DatCmRandomSpawn(0, 1, mutableListOf()))
        quest.addCmRandomSpawn(DatCmRandomSpawn(0, 2, mutableListOf()))
        components.questEditorStore.setCurrentQuest(quest)
        val controller = disposer.add(MonsterRandomnessController(components.questEditorStore))

        controller.selectRoom(1)

        assertEquals(2, controller.selectedRoomId.value)
        assertEquals(2, components.questEditorStore.selectedChallengeRoomId.value)
        controller.selectRoom(-1)
        assertEquals(null, controller.selectedRoomId.value)
    }

    @Test
    fun changing_logical_floor_clears_room_selection_atomically() = testAsync {
        val quest = createQuestModel(
            episode = Episode.I,
            floorMappings = listOf(
                FloorMapping(0, 0, 0, 0),
                FloorMapping(1, 0, 0, 0),
            ),
        )
        quest.addCmRandomSpawn(DatCmRandomSpawn(0, 7, mutableListOf()))
        quest.addCmRandomSpawn(DatCmRandomSpawn(1, 8, mutableListOf()))
        components.questEditorStore.setCurrentQuest(quest)
        val controller = disposer.add(MonsterRandomnessController(components.questEditorStore))

        controller.selectRoom(0)
        assertEquals(0, controller.selectedRoomIndex.value)
        assertEquals(7, controller.selectedRoomId.value)

        controller.setLogicalFloor(1)

        assertEquals(1, controller.selectedLogicalFloor.value)
        assertEquals(-1, controller.selectedRoomIndex.value)
        assertEquals(null, controller.selectedRoomId.value)
    }

    @Test
    fun challenge_edits_participate_in_dirty_tracking_and_undo_redo() = testAsync {
        val quest = createQuestModel()
        components.questEditorStore.setCurrentQuest(quest)
        val controller = disposer.add(MonsterRandomnessController(components.questEditorStore))

        assertFalse(components.questEditorStore.canSaveChanges.value)
        controller.addRoom()
        assertTrue(components.questEditorStore.canSaveChanges.value)
        assertEquals(1, quest.cmRandomSpawns.value.size)

        components.questEditorStore.undo()
        assertEquals(0, quest.cmRandomSpawns.value.size)
        assertFalse(components.questEditorStore.canSaveChanges.value)

        components.questEditorStore.redo()
        assertEquals(1, quest.cmRandomSpawns.value.size)
        assertTrue(components.questEditorStore.canSaveChanges.value)
    }

    @Test
    fun field_undo_redo_preserves_unrelated_challenge_object_identity() = testAsync {
        val editedEntry = DatCmRandomSpawnEntry(1f, 2f, 3f, 4, 5, 6, 7, 8)
        val unrelatedEntry = DatCmRandomSpawnEntry(11f, 12f, 13f, 14, 15, 16, 17, 18)
        val editedRoom = DatCmRandomSpawn(0, 1, mutableListOf(editedEntry))
        val unrelatedRoom = DatCmRandomSpawn(0, 2, mutableListOf(unrelatedEntry))
        val quest = createQuestModel()
        quest.addCmRandomSpawn(editedRoom)
        quest.addCmRandomSpawn(unrelatedRoom)
        components.questEditorStore.setCurrentQuest(quest)
        val controller = disposer.add(MonsterRandomnessController(components.questEditorStore))
        controller.selectRoom(0)

        controller.setSpawnField(0) { it.x = 99f }
        assertEquals(99f, editedEntry.x)
        assertSame(unrelatedRoom, quest.cmRandomSpawns.value[1])
        assertSame(unrelatedEntry, quest.cmRandomSpawns.value[1].entries.single())

        components.questEditorStore.undo()
        assertEquals(1f, editedEntry.x)
        assertSame(editedRoom, quest.cmRandomSpawns.value[0])
        assertSame(unrelatedRoom, quest.cmRandomSpawns.value[1])

        components.questEditorStore.redo()
        assertEquals(99f, editedEntry.x)
        assertSame(unrelatedEntry, quest.cmRandomSpawns.value[1].entries.single())
    }

    @Test
    fun rejects_duplicate_definition_indexes() = testAsync {
        val first = DatCmConfigPoolEntry(0f, 0f, 0f, 0f, 0f, 0, 0, 1, 0, 0, 0)
        val second = DatCmConfigPoolEntry(0f, 0f, 0f, 0f, 0f, 0, 0, 2, 0, 0, 0)
        val quest = createQuestModel()
        quest.addCmConfigPool(DatCmConfigPool(0, mutableListOf(first, second)))
        components.questEditorStore.setCurrentQuest(quest)
        val controller = disposer.add(MonsterRandomnessController(components.questEditorStore))

        controller.setConfigPoolEntryIndex(
            IndexedConfigPoolEntry(0, 1, 1, second),
            1,
        )

        assertEquals(2, second.entryIndex.toInt() and 0xFFFF)
        assertFalse(components.questEditorStore.canSaveChanges.value)
    }

    @Test
    fun exposes_invalid_random_tables_as_seed_preview_problems() = testAsync {
        val event = QuestEventModel(
            id = 1,
            floorId = 0,
            sectionId = 1,
            waveId = 1,
            delay = 0,
            unknown = 0,
            actions = mutableListOf(),
            cmWaveSettings = 0x010101,
        )
        components.questEditorStore.setCurrentQuest(createQuestModel(events = listOf(event)))
        components.questEditorStore.setChallengeSeedSimulationEnabled(true)
        val controller = disposer.add(MonsterRandomnessController(components.questEditorStore))

        assertEquals(
            listOf(
                "Floor 0: Random location table is missing or empty.",
                "Floor 0: Random enemy definition table is missing or empty.",
                "Floor 0: Random enemy weight table is missing or empty.",
            ),
            controller.simulationProblems.value,
        )
    }

    @Test
    fun reuses_free_ids_instead_of_overflowing_unsigned_shorts() = testAsync {
        val quest = createQuestModel()
        quest.addCmRandomSpawn(DatCmRandomSpawn(0, 0xFFFF, mutableListOf()))
        quest.addCmConfigPool(DatCmConfigPool(0, mutableListOf(
            DatCmConfigPoolEntry(
                0f, 0f, 0f, 0f, 0f, 0, 0, 0xFFFF.toShort(), 0, 0, 0,
            ),
        )))
        components.questEditorStore.setCurrentQuest(quest)
        val controller = disposer.add(MonsterRandomnessController(components.questEditorStore))

        controller.addRoom()
        controller.addConfigPoolEntry()

        assertEquals(listOf(0xFFFF, 0), quest.cmRandomSpawns.value.map { it.roomId })
        assertEquals(
            listOf(0xFFFF, 0),
            quest.cmConfigPool.value.single().entries.map { it.entryIndex.toInt() and 0xFFFF },
        )
    }

    @Test
    fun edits_and_deletes_entries_in_their_own_logical_floor_tables() = testAsync {
        fun definition(value: Float) = DatCmConfigPoolEntry(
            value, 0f, 0f, 0f, 0f, 0, 0, 1, 0, 0, 0,
        )
        val firstDefinition = definition(1f)
        val secondDefinition = definition(2f)
        val firstMapping = DatCmMonsterMappingEntry(0, 1, 1, 0)
        val secondMapping = DatCmMonsterMappingEntry(1, 1, 1, 0)
        val quest = createQuestModel()
        quest.addCmConfigPool(DatCmConfigPool(0, mutableListOf(firstDefinition)))
        quest.addCmConfigPool(DatCmConfigPool(1, mutableListOf(secondDefinition)))
        quest.addCmMonsterMapping(DatCmMonsterMapping(0, mutableListOf(firstMapping)))
        quest.addCmMonsterMapping(DatCmMonsterMapping(1, mutableListOf(secondMapping)))
        components.questEditorStore.setCurrentQuest(quest)
        val controller = disposer.add(MonsterRandomnessController(components.questEditorStore))

        val secondDefinitionLocator = IndexedConfigPoolEntry(1, 0, 1, secondDefinition)
        controller.setConfigPoolField(secondDefinitionLocator) { it.param1 = 22f }
        assertEquals(1f, firstDefinition.param1)
        assertEquals(22f, secondDefinition.param1)

        val secondMappingLocator = IndexedMappingEntry(1, 0, 1, secondMapping)
        controller.deleteMappingEntry(secondMappingLocator)
        assertEquals(listOf(firstMapping), quest.cmMonsterMappings.value[0].entries)
        assertEquals(emptyList(), quest.cmMonsterMappings.value[1].entries)

        components.questEditorStore.undo()
        assertEquals(listOf(secondMapping), quest.cmMonsterMappings.value[1].entries)
        components.questEditorStore.redo()
        assertEquals(emptyList(), quest.cmMonsterMappings.value[1].entries)
    }

    @Test
    fun adds_challenge_data_to_the_selected_logical_floor() = testAsync {
        val quest = createQuestModel(
            episode = Episode.I,
            floorMappings = listOf(
                FloorMapping(0, 0, 0, 0),
                FloorMapping(1, 0, 0, 0),
            ),
        )
        components.questEditorStore.setCurrentQuest(quest)
        val controller = disposer.add(MonsterRandomnessController(components.questEditorStore))
        assertEquals(listOf(0, 1), controller.logicalFloors.value)
        assertEquals(0, controller.selectedLogicalFloor.value)

        controller.setLogicalFloor(1)
        controller.addRoom()
        controller.addConfigPoolEntry()
        controller.addMappingEntry()

        assertEquals(1, controller.selectedLogicalFloor.value)
        assertEquals(listOf(1), quest.cmRandomSpawns.value.map { it.floorId })
        assertEquals(listOf(1), quest.cmConfigPool.value.map { it.floorId })
        assertEquals(listOf(1), quest.cmMonsterMappings.value.map { it.floorId })
    }
}
