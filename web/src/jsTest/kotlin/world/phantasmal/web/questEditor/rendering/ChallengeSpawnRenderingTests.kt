package world.phantasmal.web.questEditor.rendering

import kotlinx.browser.document
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.w3c.dom.HTMLCanvasElement
import world.phantasmal.cell.Cell
import world.phantasmal.cell.cell
import world.phantasmal.cell.mutableCell
import world.phantasmal.cell.list.ListCell
import world.phantasmal.cell.list.emptyListCell
import world.phantasmal.cell.observe
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.fileFormats.quest.ChallengeModeSeedSimulation
import world.phantasmal.psolib.fileFormats.quest.ChallengeModeSimulatedMonster
import world.phantasmal.psolib.fileFormats.quest.ChallengeModeSimulatedWave
import world.phantasmal.psolib.fileFormats.quest.DatCmRandomSpawn
import world.phantasmal.psolib.fileFormats.quest.DatCmRandomSpawnEntry
import world.phantasmal.psolib.fileFormats.quest.EntityType
import world.phantasmal.web.core.euler
import world.phantasmal.web.externals.three.InstancedMesh
import world.phantasmal.web.externals.three.MeshBasicMaterial
import world.phantasmal.web.externals.three.PerspectiveCamera
import world.phantasmal.web.externals.three.PlaneGeometry
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.questEditor.loading.EntityMeshLoader
import world.phantasmal.web.questEditor.models.AreaModel
import world.phantasmal.web.questEditor.models.AreaVariantModel
import world.phantasmal.web.questEditor.models.QuestEntityModel
import world.phantasmal.web.questEditor.models.QuestEventModel
import world.phantasmal.web.questEditor.models.QuestModel
import world.phantasmal.web.questEditor.models.QuestObjectModel
import world.phantasmal.web.questEditor.models.SectionModel
import world.phantasmal.web.questEditor.stores.QuestEditorRenderState
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import kotlin.js.unsafeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChallengeSpawnRenderingTests : WebTestSuite {
    @Test
    fun seeded_preview_model_initialization_is_deferred_out_of_cell_observers() = test {
        val selectionRevision = mutableCell(0)
        val initializedModels = mutableCell(0)
        disposer.add(selectionRevision.observe {
            deferChallengeSpawnRender {
                // Temporary QuestNpcModel initialization changes cells in the real renderer.
                initializedModels.value++
            }
        })

        selectionRevision.value++

        assertEquals(1, initializedModels.value)
    }

    @Test
    fun renderer_shows_candidates_without_seed_and_only_materialized_locations_with_seed() = test {
        val first = location(1f)
        val selected = location(2f)
        val quest = createQuestModel()
        quest.addCmRandomSpawn(DatCmRandomSpawn(3, 17, mutableListOf(first, selected)))

        val candidates = challengeSpawnsToRender(quest, null)
        assertEquals(2, candidates.size)
        assertSame(first, candidates[0].entry)
        assertSame(selected, candidates[1].entry)

        val monster = ChallengeModeSimulatedMonster(3, 9, 4, 17, 2, 1, 0, selected)
        val simulation = ChallengeModeSeedSimulation(
            0x12345678u,
            listOf(ChallengeModeSimulatedWave(3, 9, 4, 17, 30, listOf(monster))),
        )
        val materialized = challengeSpawnsToRender(quest, simulation)

        assertEquals(1, materialized.size)
        assertEquals(3, materialized.single().floorId)
        assertEquals(17, materialized.single().roomId)
        assertSame(selected, materialized.single().entry)
    }

    @Test
    fun renderer_only_shows_the_selected_logical_floor() = test {
        val floor3 = location(3f)
        val floor4 = location(4f)
        val quest = createQuestModel()
        quest.addCmRandomSpawn(DatCmRandomSpawn(3, 17, mutableListOf(floor3)))
        quest.addCmRandomSpawn(DatCmRandomSpawn(4, 18, mutableListOf(floor4)))

        val candidates = challengeSpawnsToRender(quest, null, logicalFloor = 4)
        assertEquals(1, candidates.size)
        assertEquals(4, candidates.single().floorId)
        assertSame(floor4, candidates.single().entry)

        val monsters = listOf(
            ChallengeModeSimulatedMonster(3, 9, 4, 17, 2, 1, 0, floor3),
            ChallengeModeSimulatedMonster(4, 10, 5, 18, 3, 1, 0, floor4),
        )
        val simulation = ChallengeModeSeedSimulation(
            0x12345678u,
            listOf(
                ChallengeModeSimulatedWave(3, 9, 4, 17, 30, listOf(monsters[0])),
                ChallengeModeSimulatedWave(4, 10, 5, 18, 30, listOf(monsters[1])),
            ),
        )

        val materialized = challengeSpawnsToRender(quest, simulation, logicalFloor = 4)
        assertEquals(1, materialized.size)
        assertEquals(4, materialized.single().floorId)
        assertSame(floor4, materialized.single().entry)
    }

    @Test
    fun seed_renderer_can_focus_one_room_without_changing_the_simulation() = test {
        val room17 = location(17f)
        val room18 = location(18f)
        val monsters = listOf(
            ChallengeModeSimulatedMonster(3, 9, 4, 17, 2, 1, 0, room17),
            ChallengeModeSimulatedMonster(3, 10, 5, 18, 3, 1, 0, room18),
        )
        val simulation = ChallengeModeSeedSimulation(
            0x12345678u,
            listOf(
                ChallengeModeSimulatedWave(3, 9, 4, 17, 30, listOf(monsters[0])),
                ChallengeModeSimulatedWave(3, 10, 5, 18, 30, listOf(monsters[1])),
            ),
        )

        val focused = challengeMonstersToRender(simulation, logicalFloor = 3, roomId = 18)

        assertEquals(listOf(monsters[1]), focused)
        assertEquals(2, simulation.monsters.size, "Room focus must not alter global materialization.")
    }

    @Test
    fun selected_challenge_event_shows_all_of_its_materialized_waves_only() = test {
        val event9Wave4 = ChallengeModeSimulatedMonster(3, 9, 4, 17, 2, 1, 0, location(1f))
        val event9Wave5 = ChallengeModeSimulatedMonster(3, 9, 5, 17, 3, 1, 0, location(2f))
        val event10 = ChallengeModeSimulatedMonster(3, 10, 6, 17, 4, 1, 0, location(3f))
        val otherFloorEvent9 = ChallengeModeSimulatedMonster(4, 9, 4, 17, 5, 1, 0, location(4f))
        val simulation = ChallengeModeSeedSimulation(
            0x12345678u,
            listOf(
                ChallengeModeSimulatedWave(3, 9, 4, 17, 30, listOf(event9Wave4)),
                ChallengeModeSimulatedWave(3, 9, 5, 17, 30, listOf(event9Wave5)),
                ChallengeModeSimulatedWave(3, 10, 6, 17, 30, listOf(event10)),
                ChallengeModeSimulatedWave(4, 9, 4, 17, 30, listOf(otherFloorEvent9)),
            ),
        )

        val focused = challengeMonstersToRender(
            simulation,
            logicalFloor = 3,
            roomId = null,
            selectedEvents = setOf(ChallengeEventSelection(3, 9, 17)),
        )

        assertEquals(listOf(event9Wave4, event9Wave5), focused)
    }

    @Test
    fun selected_materialized_event1_shows_only_its_own_wave() = test {
        val wave4 = ChallengeModeSimulatedMonster(3, 9, 4, 17, 2, 1, 0, location(1f))
        val wave5 = ChallengeModeSimulatedMonster(3, 9, 5, 17, 3, 1, 0, location(2f))
        val simulation = ChallengeModeSeedSimulation(
            0x12345678u,
            listOf(
                ChallengeModeSimulatedWave(3, 9, 4, 17, 30, listOf(wave4)),
                ChallengeModeSimulatedWave(3, 9, 5, 17, 30, listOf(wave5)),
            ),
        )

        val focused = challengeMonstersToRender(
            simulation,
            logicalFloor = 3,
            roomId = null,
            selectedEvents = setOf(ChallengeEventSelection(3, 9, 17, waveNumber = 5)),
        )

        assertEquals(listOf(wave5), focused)
    }

    @Test
    fun selected_challenge_event_filters_unseeded_candidates_to_its_room() = test {
        val room17 = location(17f)
        val room18 = location(18f)
        val quest = createQuestModel()
        quest.addCmRandomSpawn(DatCmRandomSpawn(3, 17, mutableListOf(room17)))
        quest.addCmRandomSpawn(DatCmRandomSpawn(3, 18, mutableListOf(room18)))

        val focused = challengeSpawnsToRender(
            quest,
            simulation = null,
            logicalFloor = 3,
            selectedEvents = setOf(ChallengeEventSelection(3, 10, 18)),
        )

        assertEquals(1, focused.size)
        assertEquals(18, focused.single().roomId)
        assertSame(room18, focused.single().entry)
    }

    @Test
    fun seeded_monsters_follow_the_shared_direction_toggle() = testAsync {
        val floorId = 3
        val roomId = 17
        val location = location(2f)
        val quest = createQuestModel()
        quest.addCmRandomSpawn(DatCmRandomSpawn(floorId, roomId, mutableListOf(location)))
        val simulation = ChallengeModeSeedSimulation(
            0x12345678u,
            listOf(
                ChallengeModeSimulatedWave(
                    floorId = floorId,
                    sourceEventId = 9,
                    waveNumber = 4,
                    roomId = roomId,
                    delay = 30,
                    monsters = listOf(
                        ChallengeModeSimulatedMonster(
                            floorId = floorId,
                            sourceEventId = 9,
                            waveNumber = 4,
                            roomId = roomId,
                            monsterTypeIndex = 0,
                            definitionIndex = 1,
                            numChildren = 0,
                            location = location,
                        )
                    ),
                )
            ),
        )
        val area = AreaModel(floorId, "Test Area", bossArea = false, order = 0, emptyList())
        val variant = AreaVariantModel(0, area, Episode.I).apply {
            setSections(listOf(SectionModel(roomId, Vector3(), euler(0.0, 0.0, 0.0), this)))
        }
        val state = challengeRenderState(quest, area, variant, simulation)
        val context = disposer.add(
            QuestRenderContext(
                document.createElement("canvas").unsafeCast<HTMLCanvasElement>(),
                PerspectiveCamera(),
            )
        )
        disposer.add(
            ChallengePreviewMeshManager(
                state,
                components.questEditorUiStore,
                context,
                TestEntityMeshLoader(),
            )
        )

        val directionMesh = withTimeout(5_000) {
            while (true) {
                context.helpers.children
                    .filterIsInstance<InstancedMesh>()
                    .firstOrNull { it.count == 1 }
                    ?.let { return@withTimeout it }
                yield()
            }
            error("Unreachable")
        }
        assertFalse(directionMesh.visible)

        components.questEditorUiStore.setShowEntityDirections(true)

        assertTrue(directionMesh.visible)
    }

    private fun challengeRenderState(
        quest: QuestModel,
        area: AreaModel,
        variant: AreaVariantModel,
        simulation: ChallengeModeSeedSimulation,
    ): QuestEditorRenderState = object : QuestEditorRenderState {
        override val currentQuest: Cell<QuestModel?> = cell(quest)
        override val currentArea: Cell<AreaModel?> = cell(area)
        override val currentAreaVariant: Cell<AreaVariantModel?> = cell(variant)
        override val currentFloorIds: Cell<Set<Int>?> = cell(setOf(area.id))
        override val currentAreaObjects: ListCell<QuestObjectModel> = emptyListCell()
        override val selectedSection: Cell<SectionModel?> = cell(null)
        override val selectedEvents: Cell<Set<QuestEventModel>> = cell(emptySet())
        override val selectedEventsSectionWaves: Cell<Set<Pair<Int, Int>>> = cell(emptySet())
        override val highlightedEntity: Cell<QuestEntityModel<*, *>?> = cell(null)
        override val selectedEntity: Cell<QuestEntityModel<*, *>?> = cell(null)
        override val challengeSeedSimulation: Cell<ChallengeModeSeedSimulation?> = cell(simulation)
        override val selectedChallengeLogicalFloor: Cell<Int?> = cell(null)
        override val selectedChallengeRoomId: Cell<Int?> = cell(null)
    }

    private class TestEntityMeshLoader : EntityMeshLoader {
        override suspend fun loadInstancedMesh(
            type: EntityType,
            model: Int?,
            ultimate: Boolean,
            renderVariant: Int?,
        ): InstancedMesh = InstancedMesh(PlaneGeometry(), MeshBasicMaterial(), 10).apply {
            count = 0
        }
    }

    private fun location(x: Float) = DatCmRandomSpawnEntry(x, 0f, 0f, 0, 0, 0, 0, 0)
}
