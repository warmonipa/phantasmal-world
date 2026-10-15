package world.phantasmal.psolib.fileFormats.quest.walkthrough

import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.dataFlowAnalysis.FloorMapping
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptFloorTransitions
import world.phantasmal.psolib.asm.dataFlowAnalysis.WalkthroughGoFloor
import world.phantasmal.psolib.asm.dataFlowAnalysis.WalkthroughScriptAnalysis
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.QuestObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QuestJourneyTests {
    @Test
    fun forward_chain_from_pioneer_2_returns_to_pioneer_2() {
        val p2Start = playerSet(0, slot = 0, returnFlag = 0)
        val p2Return = playerSet(0, slot = 0, returnFlag = 1)
        val f1 = playerSet(1, 0, 0)
        val f2Forward = playerSet(2, 0, 0)
        val toF2 = teleporter(1, 2)
        val toP2 = teleporter(2, 0)
        val objects = listOf(p2Start, p2Return, f1, f2Forward, toF2, toP2, playerSet(2, 0, 1))

        val journey = plan(objects, mainWarpAreas = setOf(1))

        assertEquals(listOf(0, 1, 2, 0), journey.visits.map { it.floorId })
        assertSame(p2Start, journey.visits[0].arrival)
        assertEquals(FloorTransition.MainWarp(1), journey.visits[0].departure)
        assertSame(f1, journey.visits[1].arrival)
        assertEquals(FloorTransition.Exit(listOf(toF2), 2), journey.visits[1].departure)
        assertSame(f2Forward, journey.visits[2].arrival)
        assertEquals(FloorTransition.Exit(listOf(toP2), 0), journey.visits[2].departure)
        assertSame(p2Return, journey.visits[3].arrival)
        assertEquals(null, journey.visits[3].departure)
        assertEquals(mapOf(1 to 1), journey.mainWarpLandings)
    }

    @Test
    fun journey_does_not_return_to_pioneer_2_without_the_clients_pioneer_2_data() {
        val objects = listOf(playerSet(1, 0, 0), teleporter(1, 0))

        assertEquals(listOf(1), plan(objects).visits.map { it.floorId })
    }

    @Test
    fun coming_back_from_a_later_floor_uses_the_return_player_set() {
        val f1Return = playerSet(1, 0, 1)
        val objects = listOf(
            playerSet(1, 0, 0), f1Return, playerSet(2, 0, 0),
            teleporter(1, 2), teleporter(2, 1),
            playerSet(3, 0, 0), teleporter(1, 3),
        )

        val journey = plan(objects)

        assertEquals(listOf(1, 2, 1, 3), journey.visits.map { it.floorId })
        assertSame(f1Return, journey.visits[2].arrival)
    }

    @Test
    fun quest_warp_lands_on_its_player_set_id() {
        val target = playerSet(2, 0, 2)
        val warp = QuestObject(ObjectType.QuestWarp, 1).apply {
            data.setFloat(40, 2f)
            data.setInt(52, 2)
        }
        val objects = listOf(playerSet(1, 0, 0), playerSet(2, 0, 0), target, warp)

        val journey = plan(objects)

        assertSame(target, journey.visits[1].arrival)
    }

    @Test
    fun floor_without_the_clients_player_set_is_not_visited() {
        val objects = listOf(
            playerSet(1, 0, 0), playerSet(1, 1, 0),
            playerSet(2, 1, 0), teleporter(1, 2),
        )

        assertEquals(listOf(1), plan(objects, clientId = 0).visits.map { it.floorId })
        assertEquals(listOf(1, 2), plan(objects, clientId = 1).visits.map { it.floorId })
    }

    @Test
    fun go_floor_connects_floors() {
        val objects = listOf(playerSet(3, 0, 0), playerSet(4, 0, 0))

        val journey = plan(objects, goFloors = setOf(WalkthroughGoFloor(3, 4)))

        assertEquals(listOf(3, 4), journey.visits.map { it.floorId })
        assertEquals(FloorTransition.Script(4), journey.visits[0].departure)
    }

    @Test
    fun return_flag_uses_only_the_low_16_bits() {
        val start = playerSet(0, 0, 65536)

        val journey = plan(
            listOf(start, playerSet(1, 0, 0)),
            mainWarpAreas = setOf(1),
        )

        assertSame(start, journey.visits.first().arrival)
        assertEquals(listOf(0, 1), journey.visits.map { it.floorId })
    }

    @Test
    fun one_based_slot_ids_on_a_floor_are_shifted_to_client_ids() {
        val oneBased = (1..4).map { playerSet(2, slot = it, returnFlag = 0) }
        val objects = listOf(playerSet(1, 0, 0), playerSet(1, 3, 0), teleporter(1, 2)) + oneBased

        assertSame(oneBased[0], plan(objects, clientId = 0).visits[1].arrival)
        assertSame(oneBased[3], plan(objects, clientId = 3).visits[1].arrival)
        assertEquals(
            listOf(oneBased[3]),
            playerSetsForClient(objects, 3).filter { it.floorId == 2 },
        )
    }

    @Test
    fun challenge_quests_visit_floors_in_ascending_order_without_pioneer_2() {
        val objects = listOf(
            playerSet(0, 0, 0), playerSet(5, 0, 0), playerSet(3, 0, 0), playerSet(4, 0, 0),
            teleporter(3, 0), teleporter(4, 0),
        )

        val journey = plan(objects, isChallengeMode = true)

        assertEquals(listOf(3, 4, 5), journey.visits.map { it.floorId })
        assertEquals(4, journey.visits[0].departure?.toFloorId)
        assertEquals(null, journey.visits[2].departure)
    }

    @Test
    fun quest_without_main_warp_starts_on_its_lowest_ragol_floor() {
        val objects = listOf(
            playerSet(0, 0, 0), playerSet(2, 0, 0), playerSet(5, 0, 0),
            teleporter(2, 5), teleporter(5, 0),
        )

        assertEquals(listOf(2, 5, 0), plan(objects).visits.map { it.floorId })
    }

    @Test
    fun main_warp_operand_is_an_episode_absolute_area() {
        val objects = listOf(
            playerSet(0, 0, 0), playerSet(6, 0, 0), playerSet(7, 0, 0), teleporter(6, 7),
        )

        val journey = plan(objects, episode = Episode.IV, mainWarpAreas = setOf(41))

        // Area 5 is not in the quest, so the warp goes to the next quest area.
        assertEquals(listOf(0, 6, 7), journey.visits.map { it.floorId })
        assertEquals(mapOf(41 to 6), journey.mainWarpLandings)
    }

    @Test
    fun main_warp_operand_matches_the_designated_client_map_id() {
        // EP4 quests designate floor 1 as client map 36 (Crater East) and Pioneer 2 as map 45.
        val objects = listOf(playerSet(0, 0, 0), playerSet(1, 0, 0), teleporter(1, 0))

        val journey = plan(
            objects,
            episode = Episode.IV,
            mainWarpAreas = setOf(36),
            floorMappings = listOf(
                FloorMapping(0, mapId = 45, mapAreaId = 0, mapVariation = 0),
                FloorMapping(1, mapId = 36, mapAreaId = 1, mapVariation = 0),
            ),
        )

        assertEquals(listOf(0, 1, 0), journey.visits.map { it.floorId })
    }

    @Test
    fun ep4_main_warp_operands_count_ragol_areas_from_36() {
        val objects = listOf(playerSet(0, 0, 0), playerSet(6, 0, 0), playerSet(7, 0, 0))

        val journey = plan(objects, episode = Episode.IV, mainWarpAreas = setOf(42))

        // 42 is Desert 2 (area 7), not the next area after 6.
        assertEquals(listOf(0, 7), journey.visits.take(2).map { it.floorId })
    }

    @Test
    fun main_warp_area_is_mapped_through_floor_mappings() {
        val objects = listOf(playerSet(0, 0, 0), playerSet(4, 0, 0))

        val journey = plan(
            objects,
            mainWarpAreas = setOf(2),
            floorMappings = listOf(FloorMapping(4, mapId = 0, mapAreaId = 2, mapVariation = 0)),
        )

        assertEquals(listOf(0, 4), journey.visits.map { it.floorId })
    }

    @Test
    fun main_warp_to_the_end_of_an_unreachable_chain_lands_on_its_head() {
        val objects = listOf(
            playerSet(0, 0, 0), playerSet(1, 0, 0), playerSet(2, 0, 0),
            teleporter(1, 2), teleporter(2, 0),
        )

        val journey = plan(objects, mainWarpAreas = setOf(2))

        assertEquals(listOf(0, 1, 2, 0), journey.visits.map { it.floorId })
        assertEquals(mapOf(2 to 1), journey.mainWarpLandings)
    }

    @Test
    fun main_warp_does_not_move_to_a_predecessor_reachable_from_its_landing() {
        val objects = listOf(
            playerSet(0, 0, 0), playerSet(1, 0, 0), playerSet(2, 0, 0),
            teleporter(1, 2), teleporter(2, 1), teleporter(2, 0),
        )

        val journey = plan(objects, mainWarpAreas = setOf(1))

        assertEquals(listOf(0, 1, 2, 0), journey.visits.map { it.floorId })
        assertEquals(mapOf(1 to 1), journey.mainWarpLandings)
    }

    @Test
    fun script_transitions_outside_known_entries_place_otherwise_unreached_floors() {
        // A go_floor of unknown origin can run on whatever floor the client is on.
        val objects = listOf(
            playerSet(0, 0, 0), playerSet(0, 0, 1), playerSet(1, 0, 0), playerSet(3, 0, 0),
            playerSet(4, 0, 0), teleporter(1, 0), teleporter(3, 0), teleporter(4, 0),
        )

        val journey = plan(
            objects,
            mainWarpAreas = setOf(1),
            fallback = ScriptFloorTransitions(goFloorTargets = setOf(4), mainWarpAreas = setOf(3)),
        )

        assertEquals(listOf(0, 1, 4, 0, 3, 0), journey.visits.map { it.floorId })
        assertTrue(journey.visits.all { it.orderKnown })
        assertTrue("Floor 3: entered through a script the analysis cannot trace." in journey.diagnostics)
        assertTrue("Floor 4: entered through a script the analysis cannot trace." in journey.diagnostics)
    }

    @Test
    fun fallback_transitions_are_not_used_when_floors_are_reachable() {
        val objects = listOf(playerSet(1, 0, 0), playerSet(2, 0, 0), teleporter(1, 2))

        val journey = plan(
            objects,
            fallback = ScriptFloorTransitions(goFloorTargets = setOf(2), mainWarpAreas = emptySet()),
        )

        assertEquals(FloorTransition.Exit(listOf(objects[2]), 2), journey.visits[0].departure)
        assertTrue(journey.diagnostics.isEmpty())
    }

    @Test
    fun main_warp_to_a_hub_with_several_predecessors_lands_on_the_hub() {
        // Floors 6 and 7 both lead back to hub 5 and to each other (opened later by the script).
        val objects = listOf(
            playerSet(0, 0, 0), playerSet(5, 0, 0), playerSet(6, 0, 0), playerSet(7, 0, 0),
            teleporter(5, 0), teleporter(6, 5), teleporter(6, 7), teleporter(7, 5), teleporter(7, 6),
        )

        val journey = plan(objects, mainWarpAreas = setOf(5))

        assertEquals(mapOf(5 to 5), journey.mainWarpLandings)
        assertEquals(listOf(0, 5), journey.visits.take(2).map { it.floorId })
    }

    @Test
    fun floors_without_any_player_set_are_still_visited() {
        // Floor 1 has no Player Sets at all; the client appears next to its teleporter back.
        val backToP2 = teleporter(1, 0)
        val objects = listOf(
            playerSet(0, 0, 1), backToP2, bossTeleporter(1), playerSet(11, 0, 0), teleporter(11, 0),
        )

        val journey = plan(objects, mainWarpAreas = setOf(1), initialFloors = setOf(0))

        assertEquals(listOf(0, 1, 11, 0), journey.visits.map { it.floorId })
        assertSame(backToP2, journey.visits[1].arrival)
        assertTrue("Floor 1: no Player Set for client 0; arriving at its exit to floor 0." in journey.diagnostics)
    }

    @Test
    fun initial_floor_from_the_script_is_the_start() {
        val objects = listOf(playerSet(0, 0, 1), playerSet(2, 0, 0), teleporter(2, 0))

        val journey = plan(objects, mainWarpAreas = setOf(2), initialFloors = setOf(0))

        assertEquals(listOf(0, 2, 0), journey.visits.map { it.floorId })
    }

    @Test
    fun region_entries_are_reachable_through_the_default_ragol_warp() {
        // The Ruins (floor 8) open through game progress, not through the quest script.
        val objects = listOf(
            playerSet(0, 0, 0), playerSet(7, 0, 0), teleporter(7, 0),
            playerSet(8, 0, 0), teleporter(8, 0), playerSet(9, 0, 0), teleporter(8, 9), teleporter(9, 0),
        )

        val journey = plan(
            objects,
            mainWarpAreas = setOf(7),
            fallback = ScriptFloorTransitions(emptySet(), emptySet(), clearedMainWarpAreas = setOf(9)),
        )

        assertEquals(listOf(0, 7, 0, 8, 9, 0), journey.visits.map { it.floorId })
        assertTrue(
            "Floor 8: entered through the Ragol warp opened by game progress." in journey.diagnostics,
        )
    }

    @Test
    fun cleared_region_entries_are_not_default_ragol_warps() {
        val objects = listOf(playerSet(0, 0, 0), playerSet(3, 0, 0))

        val journey = plan(
            objects,
            fallback = ScriptFloorTransitions(
                emptySet(), emptySet(), initialFloors = setOf(0), clearedMainWarpAreas = setOf(3),
            ),
        )

        assertFalse(journey.visits.single { it.floorId == 3 }.orderKnown)
    }

    @Test
    fun script_go_floor_back_to_pioneer_2_connects_alternative_courses() {
        // A menu sends players to floor 2 or floor 6; the end of course 2 returns to Pioneer 2.
        val objects = listOf(
            playerSet(0, 0, 0), playerSet(2, 0, 0), playerSet(6, 0, 0),
        )

        val journey = plan(
            objects,
            goFloors = setOf(WalkthroughGoFloor(0, 2), WalkthroughGoFloor(0, 6)),
            fallback = ScriptFloorTransitions(goFloorTargets = setOf(0), mainWarpAreas = emptySet()),
        )

        assertEquals(listOf(0, 2, 0, 6, 0), journey.visits.map { it.floorId })
    }

    @Test
    fun floor_only_reachable_before_another_branch_is_an_alternative() {
        // Pioneer 2 offers course 2 or course 6; course 2 never returns to Pioneer 2.
        val objects = listOf(playerSet(0, 0, 0), playerSet(2, 0, 0), playerSet(6, 0, 0))

        val journey = plan(objects, goFloors = setOf(WalkthroughGoFloor(0, 2), WalkthroughGoFloor(0, 6)))

        assertEquals(listOf(0, 2, 6), journey.visits.map { it.floorId })
        assertFalse(journey.visits[2].orderKnown)
        assertTrue("Floor 6: alternative branch from floor 0." in journey.diagnostics)
    }

    @Test
    fun dead_end_floors_are_left_for_last_when_equally_near() {
        // From floor 1, floor 2 is a dead end back to Pioneer 2 while floor 3 returns to 1.
        val objects = listOf(
            playerSet(0, 0, 0), playerSet(1, 0, 0), playerSet(2, 0, 0), playerSet(3, 0, 0),
            teleporter(1, 2), teleporter(1, 3), teleporter(2, 0), teleporter(3, 1),
        )

        val journey = plan(objects, mainWarpAreas = setOf(1))

        assertEquals(listOf(0, 1, 3, 1, 2, 0), journey.visits.map { it.floorId })
    }

    @Test
    fun fallback_diagnostics_name_only_floors_entered_through_fallbacks() {
        val objects = listOf(
            playerSet(0, 0, 0), playerSet(8, 0, 0), playerSet(9, 0, 0), teleporter(8, 9),
            teleporter(9, 0),
        )

        // Like "4-1 the lost ruins", floor 9 is designated with the Ruins 1 map area too.
        val journey = plan(
            objects,
            floorMappings = listOf(
                FloorMapping(8, mapId = 8, mapAreaId = 8, mapVariation = 0),
                FloorMapping(9, mapId = 8, mapAreaId = 8, mapVariation = 1),
            ),
            fallback = ScriptFloorTransitions(emptySet(), emptySet(), initialFloors = setOf(0)),
        )

        assertEquals(listOf(0, 8, 9, 0), journey.visits.map { it.floorId })
        assertTrue("Floor 8: entered through the Ragol warp opened by game progress." in journey.diagnostics)
        assertTrue(journey.diagnostics.none { it.startsWith("Floor 9:") })
    }

    @Test
    fun unreachable_floor_is_appended_with_unknown_order() {
        val objects = listOf(playerSet(1, 0, 0), playerSet(3, 0, 0))

        val journey = plan(objects)

        assertEquals(listOf(1, 3), journey.visits.map { it.floorId })
        assertTrue(journey.visits[0].orderKnown)
        assertFalse(journey.visits[1].orderKnown)
        assertTrue("Floor 3: order unknown for client 0." in journey.diagnostics)
    }

    @Test
    fun client_without_player_sets_has_no_journey() {
        val journey = plan(listOf(playerSet(1, 0, 0)), clientId = 2)

        assertEquals(emptyList(), journey.visits)
        assertEquals(listOf("No Player Set for client 2."), journey.diagnostics)
    }

    @Test
    fun challenge_mode_is_detected_from_challenge_opcodes() {
        val ir = world.phantasmal.psolib.asm.BytecodeIr(
            world.phantasmal.psolib.test.toInstructions(
                """
                0:
                    chl_recovery
                    ret
                """.trimIndent(),
            ),
        )

        assertTrue(usesChallengeModeScript(ir))
    }

    private fun plan(
        objects: List<QuestObject>,
        clientId: Int = 0,
        episode: Episode = Episode.I,
        mainWarpAreas: Set<Int> = emptySet(),
        goFloors: Set<WalkthroughGoFloor> = emptySet(),
        floorMappings: List<FloorMapping> = emptyList(),
        isChallengeMode: Boolean = false,
        fallback: ScriptFloorTransitions? = null,
        initialFloors: Set<Int> = emptySet(),
    ): QuestJourney = planQuestJourney(
        episode,
        objects,
        floorMappings,
        listOf(
            WalkthroughScriptAnalysis(
                emptySet(), emptySet(), emptySet(), emptySet(), emptyMap(),
                mainWarpAreas = mainWarpAreas,
                goFloors = goFloors,
            ),
        ),
        isChallengeMode,
        clientId,
        fallback ?: ScriptFloorTransitions(emptySet(), emptySet(), initialFloors),
    )

    private fun playerSet(floorId: Int, slot: Int, returnFlag: Int) =
        QuestObject(ObjectType.PlayerSet, floorId).apply {
            data.setFloat(40, slot.toFloat())
            data.setInt(52, returnFlag)
        }

    private fun bossTeleporter(floorId: Int) = QuestObject(ObjectType.BossTeleporter, floorId)

    private fun teleporter(floorId: Int, destination: Int) =
        QuestObject(ObjectType.Teleporter, floorId).apply { data.setInt(52, destination) }
}
