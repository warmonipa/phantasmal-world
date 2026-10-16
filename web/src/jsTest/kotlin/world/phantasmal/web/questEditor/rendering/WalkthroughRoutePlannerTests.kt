package world.phantasmal.web.questEditor.rendering

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import world.phantasmal.psolib.asm.BytecodeIr
import world.phantasmal.psolib.asm.Instruction
import world.phantasmal.psolib.asm.InstructionSegment
import world.phantasmal.psolib.asm.IntArg
import world.phantasmal.psolib.asm.OP_GO_FLOOR
import world.phantasmal.psolib.asm.OP_LETI
import world.phantasmal.psolib.asm.OP_P_SETPOS
import world.phantasmal.psolib.asm.OP_RET
import world.phantasmal.psolib.asm.OP_SET_MAINWARP
import world.phantasmal.psolib.asm.OP_SET_FLOOR_HANDLER_V3_V4
import world.phantasmal.psolib.asm.OP_UNLOCK_DOOR2
import world.phantasmal.psolib.asm.OP_WARP_OFF
import world.phantasmal.psolib.asm.OP_WARP_ON
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.externals.three.Euler
import world.phantasmal.web.questEditor.models.QuestEventActionModel
import world.phantasmal.web.questEditor.models.QuestEventModel
import world.phantasmal.web.questEditor.models.QuestModel
import world.phantasmal.web.questEditor.models.QuestObjectModel
import world.phantasmal.web.test.WebTestContext
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import world.phantasmal.web.test.createQuestObjectModel

class WalkthroughRoutePlannerTests : WebTestSuite {
    @Test
    fun selected_client_uses_its_own_player_set_entrance() = testAsync {
        val quest = createQuestModel(
            objects = listOf(
                playerSet(floorId = 1, clientId = 0, x = 0.0),
                playerSet(floorId = 1, clientId = 1, x = 100.0),
                eventCollision(floorId = 1, x = 50.0),
            ),
        )

        val redRoute = plan(quest, setOf(1), clientId = 0)
        val greenRoute = plan(quest, setOf(1), clientId = 1)

        assertEquals(0.0, redRoute.segments.first().from.x)
        assertEquals(100.0, greenRoute.segments.first().from.x)
    }

    @Test
    fun floor_the_client_does_not_visit_has_no_route() = testAsync {
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                playerSet(1, 1, 0.0),
                playerSet(2, 1, 0.0),
                eventCollision(2, 50.0),
                teleporter(1, 2, 20.0),
            ),
        )

        val route = plan(quest, setOf(2), clientId = 0)

        assertTrue(route.segments.isEmpty())
        assertTrue("Client 0 does not visit floor 2." in route.diagnostics)
        assertTrue(plan(quest, setOf(2), clientId = 1).segments.isNotEmpty())
    }

    @Test
    fun return_player_set_next_to_an_exit_selects_that_exit() = testAsync {
        val quest = createQuestModel(
            objects = listOf(
                playerSet(3, 0, 0.0),
                playerSet(3, 0, 48.0, returnFlag = 1),
                teleporter(3, 0, 5.0),
                teleporter(3, 0, 50.0),
                teleporter(3, 0, 100.0),
            ),
        )

        val route = plan(quest, setOf(3), clientId = 0)

        assertEquals(listOf(0.0 to 50.0), route.segments.map { it.from.x to it.to.x })
    }

    @Test
    fun pioneer_2_leg_visits_the_main_warp_unlock_before_the_ragol_teleporter() = testAsync {
        val ragolTeleporter = createQuestObjectModel(ObjectType.MainRagolTeleporter, 0).apply {
            setWorldPosition(Vector3(40.0, 0.0, 0.0))
        }
        val quest = createQuestModel(
            objects = listOf(
                playerSet(0, 0, 0.0),
                playerSet(0, 0, 45.0, returnFlag = 1),
                scriptInteraction(0, 80.0, label = 100),
                ragolTeleporter,
                playerSet(1, 0, 0.0),
                teleporter(1, 0, 30.0),
            ),
            bytecodeIr = BytecodeIr(listOf(
                instructionSegment(label = 0),
                instructionSegment(label = 100, instruction(OP_SET_MAINWARP, 1)),
            )),
        )

        val route = plan(quest, setOf(0), clientId = 0)

        // The final visit back on Pioneer 2 ends at the Hunter's Guild counter: no route.
        assertEquals(
            listOf(0.0 to 80.0, 80.0 to 40.0),
            route.segments.map { it.from.x to it.to.x },
        )
    }

    @Test
    fun coming_back_to_a_floor_starts_at_the_return_player_set() = testAsync {
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                playerSet(1, 0, 200.0, returnFlag = 1),
                teleporter(1, 2, 50.0),
                teleporter(1, 3, 300.0),
                playerSet(2, 0, 0.0),
                teleporter(2, 1, 10.0),
                playerSet(3, 0, 0.0),
            ),
        )

        val route = plan(quest, setOf(1), clientId = 0)

        assertEquals(
            listOf(0.0 to 50.0, 200.0 to 300.0),
            route.segments.map { it.from.x to it.to.x },
        )
    }

    @Test
    fun scripted_relocation_is_drawn_as_a_jump() = testAsync {
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                scriptInteraction(1, 20.0, label = 100),
                teleporter(1, 2, 510.0),
            ),
            bytecodeIr = BytecodeIr(listOf(
                instructionSegment(label = 0),
                instructionSegment(
                    label = 100,
                    instruction(OP_LETI, 60, 500),
                    instruction(OP_LETI, 61, 0),
                    instruction(OP_LETI, 62, 0),
                    instruction(OP_LETI, 63, 0),
                    instruction(OP_P_SETPOS, 0, 60),
                ),
            )),
        )
        val pathfinder = WalkthroughPathfinder { from, to ->
            when {
                from.x in 0.0..20.0 && to.x in 0.0..20.0 -> listOf(from, to)
                from.x in 500.0..510.0 && to.x in 500.0..510.0 -> listOf(from, to)
                else -> null
            }
        }

        val route = plan(quest, setOf(1), clientId = 0, pathfinder = pathfinder)

        assertEquals(
            listOf(0.0 to 20.0, 20.0 to 500.0, 500.0 to 510.0),
            route.segments.map { it.from.x to it.to.x },
        )
        assertEquals(listOf(20.0 to 500.0), route.segments.filter { it.jump }.map { it.from.x to it.to.x })
    }

    @Test
    fun entrance_return_flag_uses_only_its_low_16_bits() = testAsync {
        val quest = createQuestModel(
            objects = listOf(playerSet(1, 0, 0.0, returnFlag = 65536), eventCollision(1, 30.0)),
        )

        val route = plan(quest, setOf(1), clientId = 0)

        assertEquals(listOf(0.0 to 30.0), route.segments.map { it.from.x to it.to.x })
    }

    @Test
    fun untraced_script_exit_does_not_draw_a_guessed_endpoint() = testAsync {
        // Floor 1 is left through a go_floor in a label no known script entry reaches.
        val quest = createQuestModel(
            objects = listOf(playerSet(1, 0, 0.0), eventCollision(1, 80.0), playerSet(2, 0, 0.0)),
            bytecodeIr = BytecodeIr(listOf(
                instructionSegment(label = 0),
                instructionSegment(
                    label = 50,
                    instruction(OP_LETI, 60, 2),
                    instruction(OP_GO_FLOOR, 250, 60),
                ),
            )),
        )

        val route = plan(quest, setOf(1), clientId = 0)

        assertTrue(route.segments.isEmpty())
        assertTrue("Floor 1: no reachable exit to floor 2 for client 0." in route.diagnostics)
    }

    @Test
    fun ragol_warp_without_a_teleporter_object_does_not_draw_a_guessed_endpoint() = testAsync {
        val quest = createQuestModel(
            objects = listOf(
                playerSet(0, 0, 0.0),
                eventCollision(0, 80.0),
                playerSet(1, 0, 0.0),
            ),
            bytecodeIr = BytecodeIr(listOf(
                instructionSegment(label = 0, instruction(OP_SET_MAINWARP, 1)),
            )),
        )

        val route = plan(quest, setOf(0), clientId = 0)

        assertTrue(route.segments.isEmpty())
        assertTrue("Floor 0: no reachable exit to floor 1 for client 0." in route.diagnostics)
    }

    @Test
    fun unreachable_exit_does_not_draw_a_guessed_endpoint() = testAsync {
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                eventCollision(1, 30.0),
                teleporter(1, 2, 100.0),
                playerSet(2, 0, 0.0),
            ),
        )
        val pathfinder = WalkthroughPathfinder { from, to ->
            if (from.x <= 40.0 && to.x <= 40.0) listOf(from, to) else null
        }

        val route = plan(quest, setOf(1), clientId = 0, pathfinder = pathfinder)

        assertTrue(route.segments.isEmpty())
        assertTrue("Floor 1: no reachable exit to floor 2 for client 0." in route.diagnostics)
    }

    @Test
    fun primary_route_does_not_chain_reachable_side_objectives() = testAsync {
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                eventCollision(1, 20.0),
                eventCollision(1, 80.0),
            ),
        )

        val route = plan(quest, setOf(1), clientId = 0)

        assertEquals(listOf(0.0 to 80.0), route.segments.map { it.from.x to it.to.x })
    }

    @Test
    fun outgoing_exit_has_priority_over_optional_floor_interactions() = testAsync {
        val exit = createQuestObjectModel(ObjectType.Teleporter, 1).apply {
            entity.data.setInt(52, 2)
            setWorldPosition(Vector3(40.0, 0.0, 0.0))
        }
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                eventCollision(1, 100.0),
                exit,
            ),
        )

        val route = plan(quest, setOf(1), clientId = 0)

        assertEquals(listOf(0.0 to 40.0), route.segments.map { it.from.x to it.to.x })
    }

    @Test
    fun script_gated_warps_visit_event_collisions_before_the_exit() = testAsync {
        val exit = createQuestObjectModel(ObjectType.Teleporter, 1).apply {
            entity.data.setInt(52, 2)
            setWorldPosition(Vector3(40.0, 0.0, 0.0))
        }
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                eventCollision(1, 20.0, eventId = 1),
                eventCollision(1, 80.0, eventId = 2),
                exit,
            ),
            bytecodeIr = warpGatedBytecode(),
        )

        val route = plan(quest, setOf(1), clientId = 0)

        assertEquals(
            listOf(0.0 to 20.0, 20.0 to 80.0, 80.0 to 40.0),
            route.segments.map { it.from.x to it.to.x },
        )
    }

    @Test
    fun script_gated_warps_skip_enemy_boxes_whose_event_opens_nothing() = testAsync {
        // Opening a box is optional; only an Event Collision is always on the player's way.
        val exit = createQuestObjectModel(ObjectType.Teleporter, 1).apply {
            entity.data.setInt(52, 2)
            setWorldPosition(Vector3(40.0, 0.0, 0.0))
        }
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                eventCollision(1, 20.0),
                eventBox(ObjectType.EnemyBoxGrey, 1, 80.0, eventId = 5),
                exit,
            ),
            bytecodeIr = warpGatedBytecode(),
        )

        val route = plan(quest, setOf(1), clientId = 0)

        assertEquals(
            listOf(0.0 to 20.0, 20.0 to 40.0),
            route.segments.map { it.from.x to it.to.x },
        )
        assertTrue(route.diagnostics.isEmpty(), route.diagnostics.toString())
    }

    @Test
    fun multi_switch_doors_use_newservs_switch_count_encoding() = testAsync {
        fun door(type: ObjectType, param5: Int) = createQuestObjectModel(type, 1).apply {
            entity.data.setInt(52, 10)
            entity.data.setInt(56, param5)
        }
        // Caves and Mines: param5 = 4 - number of switch flags.
        assertEquals(10..13, door(ObjectType.Caves4ButtonDoor, 0).controlledDoorIds())
        assertEquals(10..10, door(ObjectType.Caves4ButtonDoor, 3).controlledDoorIds())
        assertEquals(10..13, door(ObjectType.MinesDoor, 0).controlledDoorIds())
        assertEquals(10..12, door(ObjectType.MinesDoor, 1).controlledDoorIds())
        // CCA, Seabed, Temple and Spaceship: param5 = number of switch flags.
        for (type in listOf(
            ObjectType.CcaDoor,
            ObjectType.SeabedDoorWithBlueEdges,
            ObjectType.FourSwitchTempleDoor,
            ObjectType.FourButtonSpaceshipDoor,
        )) {
            assertEquals(10..12, door(type, 3).controlledDoorIds(), type.name)
        }
        // The Mines single-switch door has one switch flag, whatever param5 holds.
        assertEquals(10..10, door(ObjectType.MinesSwitchDoor, 3).controlledDoorIds())
    }

    @Test
    fun script_gated_progression_can_cross_a_one_way_same_floor_warp() = testAsync {
        val warp = intraMapWarp(ObjectType.Warp, 1, sourceX = 5.0, destinationX = 100.0)
        val exit = createQuestObjectModel(ObjectType.Teleporter, 1).apply {
            entity.data.setInt(52, 2)
            setWorldPosition(Vector3(120.0, 0.0, 0.0))
        }
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                eventCollision(1, 10.0, eventId = 1),
                warp,
                eventCollision(1, 110.0, eventId = 2),
                exit,
            ),
            bytecodeIr = warpGatedBytecode(),
        )
        val pathfinder = WalkthroughPathfinder { from, to ->
            when {
                from.x in 0.0..10.0 && to.x in 0.0..10.0 -> listOf(from, to)
                from.x in 100.0..120.0 && to.x in 100.0..120.0 -> listOf(from, to)
                else -> null
            }
        }

        val route = plan(quest, setOf(1), clientId = 0, pathfinder = pathfinder)

        assertEquals(
            listOf(0.0 to 10.0, 10.0 to 5.0, 5.0 to 100.0, 100.0 to 110.0, 110.0 to 120.0),
            route.segments.map { it.from.x to it.to.x },
        )
        assertEquals(listOf(5.0 to 100.0), route.segments.filter { it.jump }.map { it.from.x to it.to.x })
    }

    @Test
    fun warp_off_without_a_later_warp_on_does_not_invent_a_completion_route() = testAsync {
        val exit = createQuestObjectModel(ObjectType.Teleporter, 1).apply {
            entity.data.setInt(52, 2)
            setWorldPosition(Vector3(40.0, 0.0, 0.0))
        }
        val quest = createQuestModel(
            objects = listOf(playerSet(1, 0, 0.0), eventCollision(1, 80.0), exit),
            bytecodeIr = BytecodeIr(listOf(instructionSegment(OP_WARP_OFF))),
        )

        val route = plan(quest, setOf(1), clientId = 0)

        assertEquals(listOf(0.0 to 40.0), route.segments.map { it.from.x to it.to.x })
    }

    @Test
    fun unlocked_door_bridges_disconnected_walkable_regions_without_a_guessed_room_edge() = testAsync {
        val door = createQuestObjectModel(ObjectType.MinesSwitchDoor, 1).apply {
            entity.data.setInt(52, 7)
            setWorldPosition(Vector3(50.0, 0.0, 0.0))
            setWorldRotation(Euler(0.0, PI / 2, 0.0))
        }
        val exit = createQuestObjectModel(ObjectType.Teleporter, 1).apply {
            entity.data.setInt(52, 2)
            setWorldPosition(Vector3(100.0, 0.0, 0.0))
        }
        val unlock = QuestEventModel(
            id = 1,
            floorId = 1,
            sectionId = 1,
            waveId = 1,
            delay = 0,
            unknown = 0,
            actions = mutableListOf(QuestEventActionModel.Door.Unlock(7)),
        )
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                door,
                eventCollision(1, 30.0, eventId = 1),
                eventCollision(1, 90.0, eventId = 2),
                exit,
            ),
            events = listOf(unlock),
            bytecodeIr = warpGatedBytecode(),
        )
        val pathfinder = WalkthroughPathfinder { from, to ->
            when {
                from.x in 0.0..40.0 && to.x in 0.0..40.0 -> listOf(from, to)
                from.x in 60.0..100.0 && to.x in 60.0..100.0 -> listOf(from, to)
                else -> null
            }
        }

        val route = plan(quest, setOf(1), clientId = 0, pathfinder = pathfinder)

        assertEquals(
            listOf(
                0.0 to 30.0,
                30.0 to 38.0,
                38.0 to 62.0,
                62.0 to 90.0,
                90.0 to 100.0,
            ),
            route.segments.map { it.from.x to it.to.x },
        )
    }

    @Test
    fun enemy_boxes_trigger_the_event_that_unlocks_a_door() = testAsync {
        // Opening an enemy box triggers its wave event, like entering an Event Collision.
        val boxes = listOf<(Int, Double, Int) -> QuestObjectModel>(
            { floorId, x, eventId -> eventBox(ObjectType.EnemyBoxGrey, floorId, x, eventId) },
            { floorId, x, eventId -> eventBox(ObjectType.EnemyBoxBrown, floorId, x, eventId) },
            { floorId, x, eventId -> eventBox(ObjectType.EnemyTypeBoxYellow, floorId, x, eventId) },
            { floorId, x, eventId -> eventBox(ObjectType.EnemyTypeBoxBlue, floorId, x, eventId) },
            { floorId, x, eventId -> eventBox(ObjectType.ContainerJungEnemy, floorId, x, eventId) },
            { floorId, x, eventId -> crystalBox(floorId, x, eventId, contentsType = 3f) },
        )
        for (box in boxes) {
            val route = planAcrossEventDoor(box)
            assertEquals(
                listOf(0.0 to 30.0, 30.0 to 38.0, 38.0 to 62.0, 62.0 to 100.0),
                route.segments.map { it.from.x to it.to.x },
            )
        }

        // A crystal box with item contents triggers no event, so it isn't on the way and nothing
        // known opens the door; the route only passes it because the exit is behind it.
        val itemBoxRoute = planAcrossEventDoor { floorId, x, eventId ->
            crystalBox(floorId, x, eventId, contentsType = 1f)
        }
        assertTrue(itemBoxRoute.segments.none { it.to.x == 30.0 })
        assertTrue(itemBoxRoute.diagnostics.any { "nothing known opens" in it })
    }

    private suspend fun WebTestContext.planAcrossEventDoor(
        trigger: (floorId: Int, x: Double, eventId: Int) -> QuestObjectModel,
    ): WalkthroughRoute {
        val door = createQuestObjectModel(ObjectType.MinesSwitchDoor, 1).apply {
            entity.data.setInt(52, 7)
            setWorldPosition(Vector3(50.0, 0.0, 0.0))
            setWorldRotation(Euler(0.0, PI / 2, 0.0))
        }
        val exit = createQuestObjectModel(ObjectType.Teleporter, 1).apply {
            entity.data.setInt(52, 2)
            setWorldPosition(Vector3(100.0, 0.0, 0.0))
        }
        val unlock = QuestEventModel(
            id = 1,
            floorId = 1,
            sectionId = 1,
            waveId = 1,
            delay = 0,
            unknown = 0,
            actions = mutableListOf(QuestEventActionModel.Door.Unlock(7)),
        )
        val quest = createQuestModel(
            objects = listOf(playerSet(1, 0, 0.0), door, trigger(1, 30.0, 1), exit),
            events = listOf(unlock),
            bytecodeIr = warpGatedBytecode(),
        )
        val pathfinder = WalkthroughPathfinder { from, to ->
            when {
                from.x in 0.0..40.0 && to.x in 0.0..40.0 -> listOf(from, to)
                from.x in 60.0..100.0 && to.x in 60.0..100.0 -> listOf(from, to)
                else -> null
            }
        }
        return plan(quest, setOf(1), clientId = 0, pathfinder = pathfinder)
    }

    private fun eventBox(type: ObjectType, floorId: Int, x: Double, eventId: Int) =
        createQuestObjectModel(type, floorId).apply {
            entity.data.setInt(52, eventId)
            setWorldPosition(Vector3(x, 0.0, 0.0))
        }

    private fun crystalBox(floorId: Int, x: Double, eventId: Int, contentsType: Float) =
        eventBox(ObjectType.DesertFixedTypeBoxBreakableCrystals, floorId, x, eventId).apply {
            entity.data.setFloat(40, contentsType)
        }

    @Test
    fun script_switch_extends_a_route_across_the_door_it_unlocks() = testAsync {
        val centralSwitch = scriptInteraction(1, x = 30.0, label = 10)
        val branchSwitch = scriptInteraction(1, x = 90.0, label = 20)
        val door = createQuestObjectModel(ObjectType.MinesSwitchDoor, 1).apply {
            entity.data.setInt(52, 7)
            setWorldPosition(Vector3(50.0, 0.0, 0.0))
            setWorldRotation(Euler(0.0, PI / 2, 0.0))
        }
        val exit = createQuestObjectModel(ObjectType.Teleporter, 1).apply {
            entity.data.setInt(52, 2)
            setWorldPosition(Vector3(120.0, 0.0, 0.0))
        }
        val quest = createQuestModel(
            objects = listOf(playerSet(1, 0, 0.0), centralSwitch, door, branchSwitch, exit),
            bytecodeIr = BytecodeIr(listOf(
                instructionSegment(
                    label = 10,
                    Instruction(
                        OP_UNLOCK_DOOR2,
                        listOf(IntArg(1), IntArg(7)),
                        valid = true,
                        srcLoc = null,
                    ),
                ),
                instructionSegment(label = 20),
            )),
        )
        val pathfinder = WalkthroughPathfinder { from, to ->
            when {
                from.x in 0.0..40.0 && to.x in 0.0..40.0 -> listOf(from, to)
                from.x in 60.0..120.0 && to.x in 60.0..120.0 -> listOf(from, to)
                else -> null
            }
        }

        val route = plan(quest, setOf(1), clientId = 0, pathfinder = pathfinder)

        assertEquals(120.0, route.segments.last().to.x)
        assertTrue(route.segments.any { it.from.x == 38.0 && it.to.x == 62.0 })
        assertTrue(route.diagnostics.isEmpty())
    }

    @Test
    fun locked_door_on_walkable_ground_waits_for_the_floor_button_that_opens_it() = testAsync {
        val button = switchObject(ObjectType.FloorPanel3, 1, x = 20.0, z = 15.0, flag = 7)
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0), gate(ObjectType.MinesSwitchDoor, 1, x = 50.0, flag = 7),
                button, teleporter(1, 2, 120.0),
            ),
        )

        val route = plan(quest, setOf(1), clientId = 0)

        assertEquals(listOf(0.0 to 20.0, 20.0 to 120.0), route.segments.map { it.from.x to it.to.x })
        assertEquals(15.0, route.segments.first().to.z)
        assertTrue(route.diagnostics.isEmpty(), route.diagnostics.toString())
    }

    @Test
    fun door_whose_switch_flag_nothing_sets_blocks_the_route_and_is_reported() = testAsync {
        // Endless Nightmare #3, Mine 1: the door nothing opens is bypassed along another way.
        val detour = eventCollision(1, x = 60.0).apply { setWorldPosition(Vector3(60.0, 0.0, 40.0)) }
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0), gate(ObjectType.MinesSwitchDoor, 1, x = 50.0, flag = 99),
                detour, teleporter(1, 2, 120.0),
            ),
        )

        val route = plan(quest, setOf(1), clientId = 0)

        assertEquals(listOf(0.0 to 60.0, 60.0 to 120.0), route.segments.map { it.from.x to it.to.x })
        assertEquals(
            listOf(
                "Floor 1: Mines Switch Door at (50, 0) needs switch flag 99, which nothing on " +
                    "this floor sets.",
            ),
            route.diagnostics,
        )
    }

    @Test
    fun door_nothing_opens_is_passed_when_it_is_the_only_way_to_the_exit() = testAsync {
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0), gate(ObjectType.MinesSwitchDoor, 1, x = 50.0, flag = 99),
                teleporter(1, 2, 120.0),
            ),
        )

        val route = plan(quest, setOf(1), clientId = 0)

        assertEquals(listOf(0.0 to 120.0), route.segments.map { it.from.x to it.to.x })
        assertTrue(
            route.diagnostics.any { "only reachable through doors or fences that nothing" in it },
            route.diagnostics.toString(),
        )
    }

    @Test
    fun laser_fence_waits_for_its_fence_switch() = testAsync {
        val fenceSwitch = switchObject(
            ObjectType.ForestLaserFenceSwitch, 1, x = 20.0, z = 15.0, flag = 215,
        )
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0), gate(ObjectType.LaserFence, 1, x = 50.0, flag = 215),
                fenceSwitch, teleporter(1, 2, 120.0),
            ),
        )

        val route = plan(quest, setOf(1), clientId = 0)

        assertEquals(15.0, route.segments.first().to.z)
        assertEquals(120.0, route.segments.last().to.x)
        assertTrue(route.diagnostics.isEmpty(), route.diagnostics.toString())
    }

    @Test
    fun multi_switch_door_waits_for_every_switch_flag() = testAsync {
        val door = gate(ObjectType.CcaDoor, 1, x = 50.0, flag = 10).apply {
            entity.data.setInt(56, 2)
        }
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0), door,
                switchObject(ObjectType.FloorPanel3, 1, x = 20.0, z = 15.0, flag = 10),
                switchObject(ObjectType.FloorPanel3, 1, x = 30.0, z = -15.0, flag = 11),
                teleporter(1, 2, 120.0),
            ),
        )

        val route = plan(quest, setOf(1), clientId = 0)

        val visited = route.segments.map { it.to.x to it.to.z }
        assertTrue(20.0 to 15.0 in visited, visited.toString())
        assertTrue(30.0 to -15.0 in visited, visited.toString())
        assertEquals(120.0, route.segments.last().to.x)
    }

    @Test
    fun switch_flag_set_by_the_floor_handler_leaves_its_door_open() = testAsync {
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0), gate(ObjectType.MinesSwitchDoor, 1, x = 50.0, flag = 7),
                teleporter(1, 2, 120.0),
            ),
            bytecodeIr = BytecodeIr(listOf(
                instructionSegment(
                    label = 0,
                    Instruction(
                        OP_SET_FLOOR_HANDLER_V3_V4,
                        listOf(IntArg(1), IntArg(100)),
                        valid = true,
                        srcLoc = null,
                    ),
                ),
                instructionSegment(label = 100, instruction(OP_UNLOCK_DOOR2, 1, 7)),
            )),
        )

        val route = plan(quest, setOf(1), clientId = 0)

        assertEquals(listOf(0.0 to 120.0), route.segments.map { it.from.x to it.to.x })
        assertTrue(route.diagnostics.isEmpty(), route.diagnostics.toString())
    }

    @Test
    fun door_sides_skip_floor_islands_inside_the_door_frame() = testAsync {
        val button = switchObject(ObjectType.FloorPanel3, 1, x = 10.0, z = 0.0, flag = 7)
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0), button,
                gate(ObjectType.MinesSwitchDoor, 1, x = 50.0, flag = 7),
                teleporter(1, 2, 120.0),
            ),
        )
        // The door frame leaves an island of floor at x = 40..48 that leads nowhere.
        fun island(x: Double) = when {
            x <= 30.0 -> 0
            x in 40.0..48.0 -> 1
            x >= 60.0 -> 2
            else -> null
        }
        val pathfinder = WalkthroughPathfinder { from, to ->
            val island = island(from.x)
            if (island != null && island == island(to.x)) listOf(from, to) else null
        }

        val route = plan(quest, setOf(1), clientId = 0, pathfinder = pathfinder)

        assertEquals(120.0, route.segments.last().to.x)
        assertTrue(route.segments.any { it.from.x == 26.0 && it.to.x == 62.0 }, route.toString())
    }

    @Test
    fun route_goes_around_a_closed_fence_through_another_doorway() = testAsync {
        // The fence switch is behind the fence, but an open door next to it leads there too.
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                gate(ObjectType.LaserFence, 1, x = 50.0, flag = 5),
                gate(ObjectType.MinesDoor, 1, x = 50.0, flag = -1).apply {
                    setWorldPosition(Vector3(50.0, 0.0, 60.0))
                },
                switchObject(ObjectType.ForestLaserFenceSwitch, 1, x = 100.0, z = 10.0, flag = 5),
                teleporter(1, 2, 120.0),
            ),
        )

        val route = plan(quest, setOf(1), clientId = 0)

        val points = route.segments.map { it.to.x to it.to.z }
        assertTrue(34.0 to 60.0 in points || 66.0 to 60.0 in points, points.toString())
        assertTrue(100.0 to 10.0 in points, points.toString())
        assertEquals(120.0, route.segments.last().to.x)
        assertTrue(route.diagnostics.isEmpty(), route.diagnostics.toString())
    }

    @Test
    fun one_of_several_event_collisions_for_the_same_event_is_enough() = testAsync {
        // Ember in the Dust - Echoes, Spaceship Alpha: seven Event Collisions trigger one event.
        val unlock = QuestEventModel(
            id = 402, floorId = 1, sectionId = 1, waveId = 1, delay = 0, unknown = 0,
            actions = mutableListOf(QuestEventActionModel.Door.Unlock(10)),
        )
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                eventCollision(1, x = 20.0, eventId = 402),
                eventCollision(1, x = 20.0, eventId = 402).apply {
                    setWorldPosition(Vector3(20.0, 0.0, -300.0))
                },
                gate(ObjectType.MinesSwitchDoor, 1, x = 50.0, flag = 10),
                teleporter(1, 2, 120.0),
            ),
            events = listOf(unlock),
        )

        val route = plan(quest, setOf(1), clientId = 0)

        assertEquals(listOf(0.0 to 20.0, 20.0 to 120.0), route.segments.map { it.from.x to it.to.x })
        assertTrue(route.segments.none { it.to.z == -300.0 })
        assertTrue(route.diagnostics.isEmpty(), route.diagnostics.toString())
    }

    @Test
    fun event_collision_behind_the_door_it_opens_fires_from_the_doorway() = testAsync {
        // Ember in the Dust - Echoes, Seaside: the Event Collision's radius reaches through the
        // door it unlocks, so players trigger it from the near side.
        val unlock = QuestEventModel(
            id = 7, floorId = 1, sectionId = 1, waveId = 1, delay = 0, unknown = 0,
            actions = mutableListOf(QuestEventActionModel.Door.Unlock(7)),
        )
        val trigger = eventCollision(1, x = 65.0, eventId = 7).apply {
            entity.data.setFloat(40, 30f)
        }
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0), gate(ObjectType.MinesSwitchDoor, 1, x = 50.0, flag = 7),
                trigger, teleporter(1, 2, 120.0),
            ),
            events = listOf(unlock),
        )

        val route = plan(quest, setOf(1), clientId = 0)

        assertEquals(42.0, route.segments.first().to.x)
        assertEquals(120.0, route.segments.last().to.x)
        assertTrue(route.diagnostics.isEmpty(), route.diagnostics.toString())
    }

    @Test
    fun objects_just_past_the_walkable_edge_snap_onto_it() = testAsync {
        val warp = intraMapWarp(ObjectType.InstaWarp, 1, sourceX = 101.0, destinationX = 300.0)
        val quest = createQuestModel(
            objects = listOf(playerSet(1, 0, 0.0), warp, teleporter(1, 2, 320.0)),
        )
        // Walkable ground ends at x = 100; the warp pad stands 1 unit past it.
        val pathfinder = object : WalkthroughPathfinder {
            override fun findPath(
                from: WalkthroughPoint,
                to: WalkthroughPoint,
            ): List<WalkthroughPoint>? = when {
                from.x in 0.0..100.0 && to.x in 0.0..100.0 -> listOf(from, to)
                from.x in 300.0..320.0 && to.x in 300.0..320.0 -> listOf(from, to)
                else -> null
            }

            override fun nearestWalkable(point: WalkthroughPoint) =
                if (point.x in 100.0..102.0) point.copy(x = 100.0) else point
        }

        val route = plan(quest, setOf(1), clientId = 0, pathfinder = pathfinder)

        assertEquals(
            listOf(0.0 to 100.0, 100.0 to 300.0, 300.0 to 320.0),
            route.segments.map { it.from.x to it.to.x },
        )
    }

    @Test
    fun warp_gating_is_scoped_to_the_floor_where_the_script_can_execute() = testAsync {
        val otherFloorSwitch = scriptInteraction(2, x = 10.0, label = 20)
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                eventCollision(1, 20.0),
                eventCollision(1, 80.0),
                playerSet(2, 0, 0.0),
                otherFloorSwitch,
            ),
            bytecodeIr = BytecodeIr(listOf(
                instructionSegment(
                    label = 20,
                    Instruction(OP_WARP_OFF, emptyList(), valid = true, srcLoc = null),
                    Instruction(OP_WARP_ON, emptyList(), valid = true, srcLoc = null),
                ),
            )),
        )

        val route = plan(quest, setOf(1), clientId = 0)

        assertEquals(listOf(0.0 to 80.0), route.segments.map { it.from.x to it.to.x })
    }

    @Test
    fun always_open_door_bridges_disconnected_walkable_regions() = testAsync {
        val door = createQuestObjectModel(ObjectType.MinesSwitchDoor, 1).apply {
            entity.data.setInt(52, -1)
            setWorldPosition(Vector3(50.0, 0.0, 0.0))
            setWorldRotation(Euler(0.0, PI / 2, 0.0))
        }
        val exit = createQuestObjectModel(ObjectType.Teleporter, 1).apply {
            entity.data.setInt(52, 2)
            setWorldPosition(Vector3(100.0, 0.0, 0.0))
        }
        val quest = createQuestModel(
            objects = listOf(playerSet(1, 0, 0.0), door, eventCollision(1, 90.0), exit),
            bytecodeIr = warpGatedBytecode(),
        )
        val pathfinder = WalkthroughPathfinder { from, to ->
            when {
                from.x in 0.0..40.0 && to.x in 0.0..40.0 -> listOf(from, to)
                from.x in 60.0..100.0 && to.x in 60.0..100.0 -> listOf(from, to)
                else -> null
            }
        }

        val route = plan(quest, setOf(1), clientId = 0, pathfinder = pathfinder)

        assertEquals(100.0, route.segments.last().to.x)
        assertTrue(route.diagnostics.isEmpty())
    }

    @Test
    fun barba_ray_teleporter_is_a_standard_outgoing_exit() = testAsync {
        val exit = createQuestObjectModel(ObjectType.WarpInBarbaRayRoom, 1).apply {
            entity.destinationFloor = 2
            setWorldPosition(Vector3(40.0, 0.0, 0.0))
        }
        val quest = createQuestModel(
            objects = listOf(playerSet(1, 0, 0.0), eventCollision(1, 100.0), exit),
        )

        val route = plan(quest, setOf(1), clientId = 0)

        assertEquals(listOf(0.0 to 40.0), route.segments.map { it.from.x to it.to.x })
    }

    @Test
    fun required_same_floor_warp_is_drawn_as_a_jump() = testAsync {
        val warp = createQuestObjectModel(ObjectType.Warp, 1).apply {
            setWorldPosition(Vector3(10.0, 0.0, 0.0))
            setDestinationPosition(Vector3(80.0, 0.0, 0.0))
        }
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                warp,
                eventCollision(1, 90.0),
            ),
        )
        val pathfinder = WalkthroughPathfinder { from, to ->
            when {
                from.x <= 10.0 && to.x <= 10.0 -> listOf(from, to)
                from.x >= 80.0 && to.x >= 80.0 -> listOf(from, to)
                else -> null
            }
        }

        val route = plan(quest, setOf(1), clientId = 0, pathfinder = pathfinder)

        assertEquals(
            listOf(0.0 to 10.0, 10.0 to 80.0, 80.0 to 90.0),
            route.segments.map { it.from.x to it.to.x },
        )
        assertEquals(listOf(10.0 to 80.0), route.segments.filter { it.jump }.map { it.from.x to it.to.x })
        assertTrue(route.segments.all { it.endsLeg })
    }

    @Test
    fun mixed_direction_insta_warps_follow_their_explicit_destinations() = testAsync {
        val first = intraMapWarp(ObjectType.InstaWarp, 1, sourceX = 10.0, destinationX = 100.0)
        val descending = intraMapWarp(
            ObjectType.InstaWarp,
            1,
            sourceX = 110.0,
            destinationX = 50.0,
        )
        val ascending = intraMapWarp(
            ObjectType.InstaWarp,
            1,
            sourceX = 60.0,
            destinationX = 200.0,
        )
        val exit = createQuestObjectModel(ObjectType.TeleporterEp2, 1).apply {
            entity.data.setInt(52, 2)
            setWorldPosition(Vector3(210.0, 0.0, 0.0))
        }
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                first,
                descending,
                ascending,
                exit,
            ),
        )
        val islands = listOf(0.0..10.0, 100.0..110.0, 50.0..60.0, 200.0..210.0)
        val pathfinder = WalkthroughPathfinder { from, to ->
            if (islands.any { from.x in it && to.x in it }) listOf(from, to) else null
        }

        val route = plan(quest, setOf(1), clientId = 0, pathfinder = pathfinder)

        assertEquals(
            listOf(0.0 to 10.0, 100.0 to 110.0, 50.0 to 60.0, 200.0 to 210.0),
            route.segments.filterNot { it.jump }.map { it.from.x to it.to.x },
        )
        assertEquals(listOf(10.0 to 100.0, 110.0 to 50.0, 60.0 to 200.0), route.segments.filter { it.jump }.map { it.from.x to it.to.x })
        assertTrue(route.segments.all { it.endsLeg })
    }

    @Test
    fun blue_teleporter_can_be_the_forward_exit_after_a_red_return_exit() = testAsync {
        val returnExit = createQuestObjectModel(ObjectType.TeleporterEp2, 1).apply {
            entity.destinationFloor = 0
            entity.data.setInt(60, 1)
            setWorldPosition(Vector3(5.0, 0.0, 0.0))
        }
        val warp = intraMapWarp(ObjectType.InstaWarp, 1, sourceX = 10.0, destinationX = 100.0)
        val forwardExit = createQuestObjectModel(ObjectType.TeleporterEp2, 1).apply {
            entity.destinationFloor = 2
            entity.data.setInt(60, 0)
            setWorldPosition(Vector3(110.0, 0.0, 0.0))
        }
        val quest = createQuestModel(
            objects = listOf(playerSet(1, 0, 0.0), returnExit, warp, forwardExit),
        )
        val pathfinder = WalkthroughPathfinder { from, to ->
            when {
                from.x in 0.0..10.0 && to.x in 0.0..10.0 -> listOf(from, to)
                from.x in 100.0..110.0 && to.x in 100.0..110.0 -> listOf(from, to)
                else -> null
            }
        }

        val route = plan(quest, setOf(1), clientId = 0, pathfinder = pathfinder)

        assertEquals(
            listOf(0.0 to 10.0, 10.0 to 100.0, 100.0 to 110.0),
            route.segments.map { it.from.x to it.to.x },
        )
        assertEquals(listOf(10.0 to 100.0), route.segments.filter { it.jump }.map { it.from.x to it.to.x })
    }

    @Test
    fun unused_same_floor_warp_is_not_added_to_the_route() = testAsync {
        val warp = createQuestObjectModel(ObjectType.Warp, 1).apply {
            setWorldPosition(Vector3(10.0, 0.0, 0.0))
            setDestinationPosition(Vector3(100.0, 0.0, 0.0))
        }
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                warp,
                eventCollision(1, 20.0),
            ),
        )

        val route = plan(quest, setOf(1), clientId = 0)

        assertEquals(listOf(0.0 to 20.0), route.segments.map { it.from.x to it.to.x })
    }

    @Test
    fun same_floor_quest_warp_uses_its_special_player_set_as_destination() = testAsync {
        val destination = playerSet(1, 0, 80.0).apply { entity.data.setInt(52, 2) }
        val warp = createQuestObjectModel(ObjectType.QuestWarp, 1).apply {
            entity.data.setFloat(40, 2f)
            entity.data.setInt(52, 1)
            setWorldPosition(Vector3(10.0, 0.0, 0.0))
        }
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                destination,
                warp,
                eventCollision(1, 90.0),
            ),
        )
        val pathfinder = WalkthroughPathfinder { from, to ->
            when {
                from.x <= 10.0 && to.x <= 10.0 -> listOf(from, to)
                from.x >= 80.0 && to.x >= 80.0 -> listOf(from, to)
                else -> null
            }
        }

        val route = plan(quest, setOf(1), clientId = 0, pathfinder = pathfinder)

        assertEquals(
            listOf(0.0 to 10.0, 10.0 to 80.0, 80.0 to 90.0),
            route.segments.map { it.from.x to it.to.x },
        )
        assertEquals(listOf(10.0 to 80.0), route.segments.filter { it.jump }.map { it.from.x to it.to.x })
    }

    @Test
    fun tower_progression_is_one_path_without_door_or_side_branch_fan_out() = testAsync {
        val sectionOrder = listOf(7, 1, 2, 3, 4, 5, 6, 8)
        val corridor = sectionOrder.mapIndexed { index, sectionId ->
            eventCollision(7, (index + 1) * 10.0).apply { setSectionId(sectionId) }
        }
        val sideTrigger = eventCollision(7, 25.0).apply { setSectionId(20) }
        val doors = listOf(500.0, 600.0, 700.0).map { x ->
            createQuestObjectModel(ObjectType.CcaDoor, 7).apply {
                entity.data.setInt(52, 73)
                setWorldPosition(Vector3(x, 0.0, 0.0))
            }
        }
        val quest = createQuestModel(
            objects = listOf(playerSet(7, 0, 0.0)) + corridor + sideTrigger + doors,
        )
        val pathfinder = WalkthroughPathfinder { from, to ->
            if (from.x !in 0.0..80.0 || to.x !in 0.0..80.0) {
                null
            } else {
                val direction = if (to.x >= from.x) 1 else -1
                buildList {
                    add(from)
                    var x = from.x + direction * 10.0
                    while ((direction > 0 && x < to.x) || (direction < 0 && x > to.x)) {
                        add(WalkthroughPoint(x, 0.0, 0.0))
                        x += direction * 10.0
                    }
                    add(to)
                }
            }
        }

        val route = plan(quest, setOf(7), clientId = 0, pathfinder = pathfinder)

        assertEquals(
            listOf(0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0),
            listOf(route.segments.first().from.x) + route.segments.map { it.to.x },
        )
        assertTrue(route.segments.none { it.from.x == 25.0 || it.to.x == 25.0 })
        assertTrue(route.segments.all { it.from.x < 100.0 && it.to.x < 100.0 })
    }

    @Test
    fun route_uses_navigation_corners_instead_of_drawing_through_the_map() = testAsync {
        val quest = createQuestModel(
            objects = listOf(playerSet(1, 0, 0.0), eventCollision(1, 20.0)),
        )
        val corner = WalkthroughPoint(10.0, 0.0, 10.0)

        val route = plan(
            quest,
            setOf(1),
            clientId = 0,
            pathfinder = WalkthroughPathfinder { from, to -> listOf(from, corner, to) },
        )

        assertEquals(listOf(corner, WalkthroughPoint(20.0, 0.0, 0.0)), route.segments.map { it.to })
        assertTrue(route.segments.first().endsLeg.not())
        assertTrue(route.segments.last().endsLeg)
    }

    @Test
    fun missing_player_entrance_does_not_invent_a_route() = testAsync {
        val quest = createQuestModel(
            objects = listOf(playerSet(1, 0, 0.0), eventCollision(1, 10.0)),
        )

        val route = plan(quest, setOf(1), clientId = 1)

        assertTrue(route.segments.isEmpty())
        assertTrue("No Player Set for client 1." in route.diagnostics)
    }

    @Test
    fun unreachable_objective_does_not_create_a_guessed_line() = testAsync {
        val quest = createQuestModel(
            objects = listOf(playerSet(1, 0, 0.0), eventCollision(1, 100.0)),
        )

        val route = plan(
            quest,
            setOf(1),
            clientId = 0,
            pathfinder = WalkthroughPathfinder { _, _ -> null },
        )

        assertTrue(route.segments.isEmpty())
        assertTrue(route.diagnostics.any { "no route objective is reachable" in it })
    }

    @Test
    fun visible_floors_produce_independent_routes() = testAsync {
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                eventCollision(1, 10.0),
                playerSet(2, 0, 100.0),
                eventCollision(2, 110.0),
            ),
        )

        val route = plan(quest, setOf(1, 2), clientId = 0)

        assertEquals(setOf(1, 2), route.segments.mapTo(mutableSetOf()) { it.floorId })
        assertTrue(route.segments.filter { it.floorId == 1 }.all { it.from.x < 50 && it.to.x < 50 })
        assertTrue(route.segments.filter { it.floorId == 2 }.all { it.from.x > 50 && it.to.x > 50 })
    }

    @Test
    fun forest_door_id_masks_signed_metadata_before_validation() = testAsync {
        val door = createQuestObjectModel(ObjectType.ForestDoor, 1).apply {
            entity.data.setInt(52, 0x80000009u.toInt())
        }
        val unsetDoor = createQuestObjectModel(ObjectType.ForestDoor, 1).apply {
            entity.data.setInt(52, -1)
        }

        assertEquals(9..9, door.controlledDoorIds())
        assertNull(unsetDoor.controlledDoorIds())
    }

    @Test
    fun all_pairs_planning_yields_and_can_be_cancelled_between_origins() = testAsync {
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                eventCollision(1, 20.0),
                eventCollision(1, 40.0),
                eventCollision(1, 60.0),
            ),
        )
        var pathCount = 0

        coroutineScope {
            val planning = launch(start = CoroutineStart.UNDISPATCHED) {
                plan(
                    quest,
                    setOf(1),
                    clientId = 0,
                    pathfinder = WalkthroughPathfinder { from, to ->
                        pathCount++
                        listOf(from, to)
                    },
                )
            }

            assertTrue(pathCount > 0)
            assertFalse(planning.isCompleted)

            planning.cancelAndJoin()
        }
    }

    /**
     * A door or fence at [x] that players cross by walking along the X axis: doors span their
     * local X axis, laser fences their local Z axis.
     */
    private fun gate(type: ObjectType, floorId: Int, x: Double, flag: Int) =
        createQuestObjectModel(type, floorId).apply {
            entity.data.setInt(52, flag)
            setWorldPosition(Vector3(x, 0.0, 0.0))
            val laserFence = type == ObjectType.LaserFence || type == ObjectType.LaserFenceEx
            setWorldRotation(Euler(0.0, if (laserFence) 0.0 else PI / 2, 0.0))
        }

    private fun switchObject(type: ObjectType, floorId: Int, x: Double, z: Double, flag: Int) =
        createQuestObjectModel(type, floorId).apply {
            entity.data.setInt(52, flag)
            setWorldPosition(Vector3(x, 0.0, z))
        }

    private fun playerSet(floorId: Int, clientId: Int, x: Double, returnFlag: Int = 0) =
        createQuestObjectModel(ObjectType.PlayerSet, floorId).apply {
            entity.data.setFloat(40, clientId.toFloat())
            entity.data.setInt(52, returnFlag)
            setWorldPosition(Vector3(x, 0.0, 0.0))
        }

    private fun teleporter(floorId: Int, destination: Int, x: Double) =
        createQuestObjectModel(ObjectType.Teleporter, floorId).apply {
            entity.data.setInt(52, destination)
            setWorldPosition(Vector3(x, 0.0, 0.0))
        }

    private fun instruction(opcode: world.phantasmal.psolib.asm.Opcode, vararg args: Int) =
        Instruction(opcode, args.map { IntArg(it) }, valid = true, srcLoc = null)

    private fun eventCollision(floorId: Int, x: Double, eventId: Int = 0) =
        createQuestObjectModel(ObjectType.EventCollision, floorId).apply {
            entity.data.setInt(52, eventId)
            setWorldPosition(Vector3(x, 0.0, 0.0))
        }

    private fun scriptInteraction(floorId: Int, x: Double, label: Int) =
        createQuestObjectModel(ObjectType.TalkLinkToSupport, floorId).apply {
            entity.data.setInt(52, label)
            setWorldPosition(Vector3(x, 0.0, 0.0))
        }

    private fun intraMapWarp(
        type: ObjectType,
        floorId: Int,
        sourceX: Double,
        destinationX: Double,
    ) = createQuestObjectModel(type, floorId).apply {
        setWorldPosition(Vector3(sourceX, 0.0, 0.0))
        setDestinationPosition(Vector3(destinationX, 0.0, 0.0))
    }

    /**
     * Warp gating is scoped to floors where warp_off and warp_on are reachable, so run them from
     * the floor 1 handler that label 0 registers.
     */
    private fun warpGatedBytecode() = BytecodeIr(listOf(
        instructionSegment(
            label = 0,
            Instruction(
                OP_SET_FLOOR_HANDLER_V3_V4,
                listOf(IntArg(1), IntArg(100)),
                valid = true,
                srcLoc = null,
            ),
        ),
        instructionSegment(
            label = 100,
            Instruction(OP_WARP_OFF, emptyList(), valid = true, srcLoc = null),
            Instruction(OP_WARP_ON, emptyList(), valid = true, srcLoc = null),
        ),
    ))

    private fun instructionSegment(opcode: world.phantasmal.psolib.asm.Opcode) =
        InstructionSegment(
            mutableListOf(),
            mutableListOf(Instruction(opcode, emptyList(), valid = true, srcLoc = null)),
        )

    private fun instructionSegment(label: Int, vararg instructions: Instruction) =
        InstructionSegment(
            mutableListOf(label),
            (instructions + Instruction(OP_RET, emptyList(), valid = true, srcLoc = null))
                .toMutableList(),
        )

    private suspend fun plan(
        quest: QuestModel,
        visibleFloorIds: Set<Int>,
        clientId: Int,
        pathfinder: WalkthroughPathfinder = WalkthroughPathfinder { from, to -> listOf(from, to) },
    ): WalkthroughRoute = planWalkthroughRoute(
        quest = quest,
        visibleFloorIds = visibleFloorIds,
        clientId = clientId,
        pathfinder = pathfinder,
    ).also(::assertContinuous)

    /**
     * A route is continuous except where a leg ends: a non-rendered warp or relocation, or the end
     * of a floor visit.
     */
    private fun assertContinuous(route: WalkthroughRoute) {
        for ((previous, next) in route.segments.zipWithNext()) {
            if (!previous.endsLeg) {
                assertEquals(previous.to, next.from, "Route breaks without a warp: $previous -> $next")
                assertEquals(previous.floorId, next.floorId)
            }
        }
    }
}
