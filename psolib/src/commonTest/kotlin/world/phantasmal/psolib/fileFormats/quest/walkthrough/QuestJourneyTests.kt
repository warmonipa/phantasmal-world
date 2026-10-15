package world.phantasmal.psolib.fileFormats.quest.walkthrough

import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.dataFlowAnalysis.FloorMapping
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
    )

    private fun playerSet(floorId: Int, slot: Int, returnFlag: Int) =
        QuestObject(ObjectType.PlayerSet, floorId).apply {
            data.setFloat(40, slot.toFloat())
            data.setInt(52, returnFlag)
        }

    private fun teleporter(floorId: Int, destination: Int) =
        QuestObject(ObjectType.Teleporter, floorId).apply { data.setInt(52, destination) }
}
