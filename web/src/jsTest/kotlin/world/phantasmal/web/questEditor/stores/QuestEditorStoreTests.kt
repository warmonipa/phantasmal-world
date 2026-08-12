package world.phantasmal.web.questEditor.stores

import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.dataFlowAnalysis.FloorMapping
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptNpcCreationOpcode
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptNpcSpawn
import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.DatCmConfigPool
import world.phantasmal.psolib.fileFormats.quest.DatCmConfigPoolEntry
import world.phantasmal.psolib.fileFormats.quest.DatCmMonsterMapping
import world.phantasmal.psolib.fileFormats.quest.DatCmMonsterMappingEntry
import world.phantasmal.psolib.fileFormats.quest.DatCmRandomSpawn
import world.phantasmal.psolib.fileFormats.quest.DatCmRandomSpawnEntry
import world.phantasmal.psolib.fileFormats.quest.QuestNpc
import world.phantasmal.web.core.euler
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.questEditor.controllers.EntityListController
import world.phantasmal.web.questEditor.models.QuestEventModel
import world.phantasmal.web.questEditor.models.QuestNpcModel
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import world.phantasmal.web.test.createQuestNpcModel
import world.phantasmal.web.test.createQuestObjectModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QuestEditorStoreTests : WebTestSuite {
    @Test
    fun script_npc_preview_rejects_every_store_mutation_boundary() = testAsync {
        val store = components.questEditorStore
        val quest = createQuestModel()
        val npc = QuestNpcModel(
            QuestNpc(NpcType.NpcRAmar, Episode.I, floorId = 0, wave = 0),
            waveId = 0,
            scriptSpawn = ScriptNpcSpawn(
                opcode = ScriptNpcCreationOpcode.NpcCrp,
                x = 0,
                y = 0,
                z = 0,
                angle = 0,
                templateIndex = 27,
                executionFloorIds = setOf(0),
            ),
        )
        store.setCurrentQuest(quest)

        assertFailsWith<IllegalArgumentException> { store.removeEntity(quest, npc) }
        assertFailsWith<IllegalArgumentException> {
            store.setEntityPosition(npc, null, Vector3(1.0, 2.0, 3.0))
        }
        assertFailsWith<IllegalArgumentException> {
            store.setEntityWorldPosition(npc, null, Vector3(1.0, 2.0, 3.0))
        }
        assertFailsWith<IllegalArgumentException> {
            store.setEntityRotation(npc, euler(1.0, 2.0, 3.0))
        }
        assertFailsWith<IllegalArgumentException> {
            store.setEntityWorldRotation(npc, euler(1.0, 2.0, 3.0))
        }
        assertFailsWith<IllegalArgumentException> {
            store.setEntityProperty(npc, QuestNpcModel::setWaveId, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            store.setEntityProp(npc, npc.properties.value.first(), 1f)
        }
        assertFailsWith<IllegalArgumentException> { store.setEntitySectionId(npc, 1) }

        assertEquals(Vector3(0.0, 0.0, 0.0), npc.position.value)
        assertEquals(0, npc.wave.value.id)
        assertEquals(0, npc.sectionId.value)
    }

    @Test
    fun challenge_seed_switch_materializes_the_full_unsigned_seed() = testAsync {
        val store = components.questEditorStore
        store.setCurrentQuest(createQuestModel())

        assertFalse(store.challengeSeedSimulationEnabled.value)
        assertEquals(null, store.challengeSeedSimulation.value)

        store.setChallengeSeed(0xFFFFFFFFu.toInt())
        store.setChallengeSeedSimulationEnabled(true)

        assertEquals(0xFFFFFFFFu.toInt(), store.challengeSeed.value)
        assertEquals(0xFFFFFFFFu, assertNotNull(store.challengeSeedSimulation.value).seed)
    }

    @Test
    fun challenge_seed_preview_recomputes_after_event_property_edits() = testAsync {
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
        val quest = createQuestModel(events = listOf(event))
        quest.addCmRandomSpawn(DatCmRandomSpawn(
            0, 1, mutableListOf(DatCmRandomSpawnEntry(0f, 0f, 0f, 0, 0, 0, 0, 0)),
        ))
        quest.addCmConfigPool(DatCmConfigPool(0, mutableListOf(
            DatCmConfigPoolEntry(0f, 0f, 0f, 0f, 0f, 0, 0, 1, 0, 0, 0),
        )))
        quest.addCmMonsterMapping(DatCmMonsterMapping(0, mutableListOf(
            DatCmMonsterMappingEntry(0, 1, 1, 0),
        )))
        val store = components.questEditorStore
        store.setCurrentQuest(quest)
        store.setChallengeSeedSimulationEnabled(true)
        assertTrue(assertNotNull(store.challengeSeedSimulation.value).problems.isEmpty())

        store.setEventProperty(event, QuestEventModel::setSectionId, 2)

        assertTrue(assertNotNull(store.challengeSeedSimulation.value).problems.any {
            it.floorId < 0 && "Simulation stopped" in it.message
        })
    }

    @Test
    fun cross_episode_floor_uses_effective_episode_for_area_and_entity_catalog() = testAsync {
        val store = components.questEditorStore
        val quest = createQuestModel(
            episode = Episode.IV,
            floorMappings = listOf(
                FloorMapping(
                    floorId = 0,
                    mapId = 0x12,
                    mapAreaId = 0,
                    mapVariation = 0,
                    mapEpisode = Episode.II,
                ),
            ),
        )

        store.setCurrentQuest(quest)
        val objectList = disposer.add(
            EntityListController(store, components.questEditorUiStore, npcs = false),
        )

        assertEquals(Episode.II, store.currentMapEpisode.value)
        assertEquals(Episode.II, store.currentAreaVariant.value?.episode)
        assertEquals("Lab", store.currentArea.value?.name)
        assertTrue(ObjectType.LabGlassWindowDoor in objectList.entities.value)
    }

    @Test
    fun setFloorMappings_reconciles_current_area_and_variant() = testAsync {
        val store = components.questEditorStore
        val quest = createQuestModel(episode = Episode.IV)
        store.setCurrentQuest(quest)

        assertEquals("Pioneer II", store.currentArea.value?.name)
        assertEquals(Episode.IV, store.currentAreaVariant.value?.episode)

        store.setFloorMappings(
            listOf(
                FloorMapping(
                    floorId = 0,
                    mapId = 0x12,
                    mapAreaId = 0,
                    mapVariation = 0,
                    mapEpisode = Episode.II,
                ),
            ),
        )

        assertEquals(setOf(0), store.currentFloorIds.value)
        assertEquals("Lab", store.currentArea.value?.name)
        assertEquals(Episode.II, store.currentAreaVariant.value?.episode)
    }

    @Test
    fun challenge_logical_floors_react_when_mappings_change_within_the_same_variant() = testAsync {
        val store = components.questEditorStore
        val quest = createQuestModel(
            episode = Episode.I,
            floorMappings = listOf(
                FloorMapping(0, 0, 0, 0),
                FloorMapping(1, 0, 0, 0),
            ),
        )
        store.setCurrentQuest(quest)
        assertEquals(listOf(0, 1), store.challengeLogicalFloors.value)

        store.setFloorMappings(
            listOf(
                FloorMapping(0, 0, 0, 0),
                FloorMapping(1, 0, 0, 0),
                FloorMapping(2, 0, 0, 0),
            ),
        )

        assertEquals(listOf(0, 1, 2), store.challengeLogicalFloors.value)
    }

    @Test
    fun setCurrentQuest_selects_floor_0_area_for_multi_floor() = testAsync {
        val store = components.questEditorStore

        val quest = createQuestModel(
            episode = Episode.II,
            floorMappings = listOf(
                FloorMapping(floorId = 0, mapId = 0x12, mapAreaId = 0, mapVariation = 0),
                FloorMapping(floorId = 17, mapId = 0x23, mapAreaId = 17, mapVariation = 0),
                FloorMapping(floorId = 16, mapId = 0x23, mapAreaId = 17, mapVariation = 1),
            ),
        )
        store.setCurrentQuest(quest)

        // Should select area 0 (Lab) as the initial area (floor 0 mapping)
        assertNotNull(store.currentArea.value)
        assertEquals(0, store.currentArea.value?.id, "Initial area should be Lab (area 0)")

        // Should select variant 0 for Lab
        assertNotNull(store.currentAreaVariant.value)
        assertEquals(0, store.currentAreaVariant.value?.id, "Initial variant should be 0")
        assertEquals(0, store.currentAreaVariant.value?.area?.id)
    }

    @Test
    fun setSelectedEntity_switches_to_correct_area_and_variant() = testAsync {
        val store = components.questEditorStore

        // NPC on floor 16 (Tower variant 1)
        val npc = createQuestNpcModel(NpcType.Boota, Episode.II, floorId = 16)
        val quest = createQuestModel(
            episode = Episode.II,
            floorMappings = listOf(
                FloorMapping(floorId = 0, mapId = 0x12, mapAreaId = 0, mapVariation = 0),
                FloorMapping(floorId = 17, mapId = 0x23, mapAreaId = 17, mapVariation = 0),
                FloorMapping(floorId = 16, mapId = 0x23, mapAreaId = 17, mapVariation = 1),
            ),
            npcs = listOf(npc),
        )
        store.setCurrentQuest(quest)

        // Initially on Lab (floor 0)
        assertEquals(0, store.currentArea.value?.id)

        // Select the NPC on floor 16 -> should switch to Tower (area 17, variant 1)
        store.setSelectedEntity(npc)

        assertEquals(17, store.currentArea.value?.id, "Should switch to Tower (area 17)")
        assertEquals(1, store.currentAreaVariant.value?.id, "Should switch to variant 1")
    }

    @Test
    fun selecting_an_enemy_from_the_viewport_selects_all_matching_events() = testAsync {
        val store = components.questEditorStore
        val enemy = createQuestNpcModel(NpcType.Booma, Episode.I).apply {
            setSectionId(7)
            setWaveId(4)
        }
        val firstEvent = QuestEventModel(
            id = 100,
            floorId = 0,
            sectionId = 7,
            waveId = 4,
            delay = 0,
            unknown = 0,
            actions = mutableListOf(),
        )
        val secondEvent = QuestEventModel(
            id = 101,
            floorId = 0,
            sectionId = 7,
            waveId = 4,
            delay = 30,
            unknown = 0,
            actions = mutableListOf(),
        )
        val otherEvent = QuestEventModel(
            id = 102,
            floorId = 0,
            sectionId = 7,
            waveId = 5,
            delay = 0,
            unknown = 0,
            actions = mutableListOf(),
        )
        val otherSectionEvent = QuestEventModel(
            id = 103,
            floorId = 0,
            sectionId = 8,
            waveId = 4,
            delay = 0,
            unknown = 0,
            actions = mutableListOf(),
        )
        val otherFloorEvent = QuestEventModel(
            id = 104,
            floorId = 1,
            sectionId = 7,
            waveId = 4,
            delay = 0,
            unknown = 0,
            actions = mutableListOf(),
        )
        store.setCurrentQuest(createQuestModel(
            npcs = listOf(enemy),
            events = listOf(
                firstEvent,
                secondEvent,
                otherEvent,
                otherSectionEvent,
                otherFloorEvent,
            ),
        ))

        store.selectViewportEntity(enemy)

        assertEquals(enemy, store.selectedEntity.value)
        assertEquals(firstEvent, store.selectedEvent.value)
        assertEquals(setOf(firstEvent, secondEvent), store.selectedEvents.value)
    }

    @Test
    fun convertQuestFromModel_preserves_floorMappings() = testAsync {
        val floorMappings = listOf(
            FloorMapping(floorId = 0, mapId = 0x12, mapAreaId = 0, mapVariation = 0),
            FloorMapping(floorId = 17, mapId = 0x23, mapAreaId = 17, mapVariation = 0),
            FloorMapping(floorId = 16, mapId = 0x23, mapAreaId = 17, mapVariation = 1),
        )
        val model = createQuestModel(
            episode = Episode.II,
            floorMappings = floorMappings,
            npcs = listOf(createQuestNpcModel(NpcType.Boota, Episode.II, floorId = 17)),
            objects = listOf(createQuestObjectModel(ObjectType.PlayerSet, floorId = 0)),
        )

        val quest = convertQuestFromModel(model)

        assertEquals(floorMappings.size, quest.floorMappings.size)
        for (i in floorMappings.indices) {
            assertEquals(floorMappings[i], quest.floorMappings[i])
        }
    }
}
