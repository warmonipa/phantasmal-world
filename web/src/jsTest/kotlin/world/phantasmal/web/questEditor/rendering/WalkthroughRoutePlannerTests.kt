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
import world.phantasmal.psolib.asm.OP_RET
import world.phantasmal.psolib.asm.OP_UNLOCK_DOOR2
import world.phantasmal.psolib.asm.OP_WARP_OFF
import world.phantasmal.psolib.asm.OP_WARP_ON
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.externals.three.Euler
import world.phantasmal.web.questEditor.models.QuestEventActionModel
import world.phantasmal.web.questEditor.models.QuestEventModel
import world.phantasmal.web.questEditor.models.QuestModel
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
                eventCollision(1, 20.0),
                eventCollision(1, 80.0),
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
    fun script_gated_progression_can_cross_a_one_way_same_floor_warp() = testAsync {
        val warp = intraMapWarp(ObjectType.Warp, 1, sourceX = 5.0, destinationX = 100.0)
        val exit = createQuestObjectModel(ObjectType.Teleporter, 1).apply {
            entity.data.setInt(52, 2)
            setWorldPosition(Vector3(120.0, 0.0, 0.0))
        }
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                eventCollision(1, 10.0),
                warp,
                eventCollision(1, 110.0),
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
            listOf(0.0 to 10.0, 10.0 to 5.0, 100.0 to 110.0, 110.0 to 120.0),
            route.segments.map { it.from.x to it.to.x },
        )
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
    fun required_same_floor_warp_is_a_non_rendered_transition() = testAsync {
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

        assertEquals(listOf(0.0 to 10.0, 80.0 to 90.0), route.segments.map { it.from.x to it.to.x })
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
            route.segments.map { it.from.x to it.to.x },
        )
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
            listOf(0.0 to 10.0, 100.0 to 110.0),
            route.segments.map { it.from.x to it.to.x },
        )
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

        assertEquals(listOf(0.0 to 10.0, 80.0 to 90.0), route.segments.map { it.from.x to it.to.x })
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
        assertTrue(route.diagnostics.any { "no Player Set entrance" in it })
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

    private fun playerSet(floorId: Int, clientId: Int, x: Double) =
        createQuestObjectModel(ObjectType.PlayerSet, floorId).apply {
            entity.data.setFloat(40, clientId.toFloat())
            entity.data.setInt(52, 0)
            setWorldPosition(Vector3(x, 0.0, 0.0))
        }

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

    private fun warpGatedBytecode() = BytecodeIr(listOf(
        instructionSegment(OP_WARP_OFF),
        instructionSegment(OP_WARP_ON),
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
    )
}
