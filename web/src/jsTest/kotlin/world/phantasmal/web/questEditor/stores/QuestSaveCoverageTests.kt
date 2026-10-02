package world.phantasmal.web.questEditor.stores

import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.assemble
import world.phantasmal.psolib.fileFormats.quest.DatCmRandomSpawn
import world.phantasmal.psolib.fileFormats.quest.DatCmRandomSpawnEntry
import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.Version
import world.phantasmal.web.questEditor.loading.LOBBY_FLOOR_ID
import world.phantasmal.web.questEditor.models.QuestEventActionModel
import world.phantasmal.web.questEditor.models.QuestEventModel
import world.phantasmal.web.questEditor.models.QuestModel
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import world.phantasmal.web.test.createQuestNpcModel
import world.phantasmal.web.test.createQuestObjectModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuestSaveCoverageTests : WebTestSuite {
    @Test
    fun lobby_allows_object_edits_without_mutating_or_requiring_empty_omitted_data() = testAsync {
        val obj = createQuestObjectModel(ObjectType.ForestDoor, LOBBY_FLOOR_ID)
        val npc = createQuestNpcModel(NpcType.Booma, Episode.I, LOBBY_FLOOR_ID)
        val event = event(LOBBY_FLOOR_ID)
        val quest = createQuestModel(objects = listOf(obj), npcs = listOf(npc), events = listOf(event))
        val coverage = QuestSaveCoverage.forLobby(quest)

        obj.entity.data.setFloat(40, 12f)
        assertTrue(coverage.canSave(quest))
        quest.removeEntity(obj)
        assertTrue(coverage.canSave(quest))
        quest.addObject(createQuestObjectModel(ObjectType.ForestDoor, LOBBY_FLOOR_ID))
        assertTrue(coverage.canSave(quest))
        assertEquals(1, quest.objects.value.size)
        assertEquals(listOf(npc), quest.npcs.value)
        assertEquals(listOf(event), quest.events.value)
    }

    @Test
    fun lobby_rejects_every_bin_metadata_or_script_edit() = testAsync {
        val changes: List<QuestModel.() -> Unit> = listOf(
            { setId(2) },
            { setLanguage(2) },
            { setName("Changed") },
            { setShortDescription("Changed") },
            { setLongDescription("Changed") },
            { setBytecodeIr(assemble(listOf("0:", "nop", "ret"), version).unwrap()) },
        )
        for (change in changes) {
            val quest = createQuestModel()
            val coverage = QuestSaveCoverage.forLobby(quest)
            quest.change()
            assertFalse(coverage.canSave(quest))
        }
    }

    @Test
    fun lobby_rejects_npc_event_and_other_floor_object_changes() = testAsync {
        val npc = createQuestNpcModel(NpcType.Booma, Episode.I, LOBBY_FLOOR_ID)
        val event = event(LOBBY_FLOOR_ID)
        val obj = createQuestObjectModel(ObjectType.ForestDoor, 1)
        val quest = createQuestModel(npcs = listOf(npc), events = listOf(event), objects = listOf(obj))
        val coverage = QuestSaveCoverage.forLobby(quest)

        val oldNpcByte = npc.entity.data.getByte(40)
        npc.entity.data.setByte(40, (oldNpcByte + 1).toByte())
        assertFalse(coverage.canSave(quest))
        npc.entity.data.setByte(40, oldNpcByte)
        assertTrue(coverage.canSave(quest))

        event.setDelay(10)
        assertFalse(coverage.canSave(quest))
        event.setDelay(0)
        assertTrue(coverage.canSave(quest))

        obj.entity.data.setFloat(40, 12f)
        assertFalse(coverage.canSave(quest))
    }

    @Test
    fun free_roam_allows_only_the_categories_and_floors_that_have_targets() = testAsync {
        val quest = createQuestModel()
        val coverage = QuestSaveCoverage.forFreeRoam(quest, "map.bin", targets)
        val obj = createQuestObjectModel(ObjectType.ForestDoor, 1)
        val npc = createQuestNpcModel(NpcType.Booma, Episode.I, 1)
        val event = event(1)
        quest.addObject(obj)
        quest.addNpc(npc)
        quest.addEvent(0, event)
        quest.setName("Updated")
        quest.setBytecodeIr(assemble(listOf("0:", "ret"), Version.BB_V4).unwrap())
        assertTrue(coverage.canSave(quest))

        quest.removeEntity(obj)
        quest.removeEntity(npc)
        quest.removeEvent(event)
        assertTrue(coverage.canSave(quest))

        val outside = createQuestObjectModel(ObjectType.ForestDoor, 2)
        quest.addObject(outside)
        assertFalse(coverage.canSave(quest))
        quest.removeEntity(outside)
        val outsideNpc = createQuestNpcModel(NpcType.Booma, Episode.I, 2)
        quest.addNpc(outsideNpc)
        assertFalse(coverage.canSave(quest))
        quest.removeEntity(outsideNpc)
        quest.addEvent(0, event(2))
        assertFalse(coverage.canSave(quest))
    }

    @Test
    fun free_roam_without_bin_rejects_script_and_metadata_changes() = testAsync {
        for (binName in listOf(null, "")) {
            val quest = createQuestModel()
            val coverage = QuestSaveCoverage.forFreeRoam(quest, binName, targets)
            quest.addObject(createQuestObjectModel(ObjectType.ForestDoor, 1))
            assertTrue(coverage.canSave(quest))
            quest.setName("Changed")
            assertFalse(coverage.canSave(quest))
            quest.setName("Test")
            assertTrue(coverage.canSave(quest))
            quest.setBytecodeIr(assemble(listOf("0:", "ret"), Version.BB_V4).unwrap())
            assertFalse(coverage.canSave(quest))
        }
    }

    @Test
    fun empty_v3_file_names_do_not_claim_npc_or_event_coverage() = testAsync {
        val quest = createQuestModel(version = Version.GC_V3)
        val coverage = QuestSaveCoverage.forFreeRoam(
            quest, "map.bin", mapOf(1 to Triple("mapo.dat", "", "")),
        )
        quest.addObject(createQuestObjectModel(ObjectType.ForestDoor, 1))
        assertTrue(coverage.canSave(quest))
        val npc = createQuestNpcModel(NpcType.Booma, Episode.I, 1)
        quest.addNpc(npc)
        assertFalse(coverage.canSave(quest))
        quest.removeEntity(npc)
        quest.addEvent(0, event(1))
        assertFalse(coverage.canSave(quest))
    }

    @Test
    fun omitted_event_snapshot_detects_nested_action_edits_and_undo() = testAsync {
        val event = event(2)
        val action = QuestEventActionModel.TriggerEvent(10)
        event.addAction(action)
        val quest = createQuestModel(events = listOf(event))
        val coverage = QuestSaveCoverage.forFreeRoam(quest, "map.bin", targets)
        action.setEventId(20)
        assertFalse(coverage.canSave(quest))
        action.setEventId(10)
        assertTrue(coverage.canSave(quest))
    }

    @Test
    fun raw_free_roam_files_do_not_cover_challenge_tables() = testAsync {
        val quest = createQuestModel()
        val coverage = QuestSaveCoverage.forFreeRoam(quest, "map.bin", targets)
        quest.addCmRandomSpawn(DatCmRandomSpawn(1, 0, mutableListOf(
            DatCmRandomSpawnEntry(1f, 2f, 3f, 0, 0, 0, 0, 0),
        )))
        assertFalse(coverage.canSave(quest))
    }

    private fun event(floorId: Int) = QuestEventModel(1, floorId, 0, 0, 0, 0, mutableListOf())

    private val targets = mapOf(1 to Triple("mapo.dat", "mape.dat", "map.evt"))
}
