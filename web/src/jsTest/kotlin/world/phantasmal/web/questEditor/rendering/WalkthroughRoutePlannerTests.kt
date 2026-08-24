package world.phantasmal.web.questEditor.rendering

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import world.phantasmal.core.Success
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.BytecodeIr
import world.phantasmal.psolib.asm.assemble
import world.phantasmal.psolib.fileFormats.quest.Version
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.questEditor.models.QuestEventActionModel
import world.phantasmal.web.questEditor.models.QuestEventModel
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import world.phantasmal.web.test.createQuestObjectModel
import world.phantasmal.web.test.createQuestNpcModel

class WalkthroughRoutePlannerTests : WebTestSuite {
    @Test
    fun selected_client_uses_its_own_player_set_entrance() = test {
        val red = playerSet(floorId = 1, clientId = 0, x = 0.0)
        val green = playerSet(floorId = 1, clientId = 1, x = 100.0)
        val collision = eventCollision(floorId = 1, eventId = 7, x = 25.0)
        val quest = createQuestModel(
            objects = listOf(red, green, collision),
            events = listOf(event(floorId = 1, id = 7, sectionId = 0)),
        )

        val redRoute = planWalkthroughRoute(quest, setOf(1), clientId = 0)
        val greenRoute = planWalkthroughRoute(quest, setOf(1), clientId = 1)

        assertEquals(0.0, redRoute.segments.first().from.x)
        assertEquals(100.0, greenRoute.segments.first().from.x)
    }

    @Test
    fun selected_client_excludes_spatial_interactions_created_by_other_client_branches() = test {
        val bytecode = assembleBytecode("""
            0:
                get_slotnumber r20
                jmpi_= r20, 1, 100
                leti r0, 10
                leti r1, 0
                leti r2, 0
                leti r3, 20
                leti r4, 200
                at_coords_call r0
                ret
            100:
                leti r10, 100
                leti r11, 0
                leti r12, 0
                leti r13, 20
                leti r14, 201
                at_coords_call r10
                ret
            200:
                ret
            201:
                ret
        """)
        val quest = createQuestModel(
            objects = listOf(playerSet(0, 0, 0.0), playerSet(0, 1, 50.0)),
            bytecodeIr = bytecode,
        )

        val redXs = planWalkthroughRoute(quest, setOf(0), clientId = 0).allXCoordinates()
        val greenXs = planWalkthroughRoute(quest, setOf(0), clientId = 1).allXCoordinates()

        assertTrue(10.0 in redXs)
        assertTrue(100.0 !in redXs)
        assertTrue(100.0 in greenXs)
        assertTrue(10.0 !in greenXs)
    }

    @Test
    fun selected_client_excludes_script_npc_interactions_from_other_client_branches() = test {
        val bytecode = assembleBytecode("""
            0:
                get_slotnumber r30
                jmpi_= r30, 1, 100
                leti r0, 10
                leti r1, 0
                leti r2, 0
                leti r3, 0
                leti r4, 0
                leti r5, 27
                npc_crptalk_v3 r0
                leti r10, 10
                leti r11, 0
                leti r12, 0
                leti r13, 25
                leti r14, 200
                at_coords_talk r10
                ret
            100:
                leti r40, 100
                leti r41, 0
                leti r42, 0
                leti r43, 0
                leti r44, 0
                leti r45, 27
                npc_crptalk_v3 r40
                leti r50, 100
                leti r51, 0
                leti r52, 0
                leti r53, 25
                leti r54, 201
                at_coords_talk r50
                ret
            200:
                ret
            201:
                ret
        """)
        val quest = createQuestModel(
            objects = listOf(playerSet(0, 0, 0.0), playerSet(0, 1, 50.0)),
            bytecodeIr = bytecode,
        )
        val scriptNpcs = quest.scriptNpcSpawns.value

        val redXs = planWalkthroughRoute(
            quest, setOf(0), clientId = 0,
            scriptNpcSpawns = scriptNpcs,
            scriptSpatialInteractions = emptyList(),
        ).allXCoordinates()
        val greenXs = planWalkthroughRoute(
            quest, setOf(0), clientId = 1,
            scriptNpcSpawns = scriptNpcs,
            scriptSpatialInteractions = emptyList(),
        ).allXCoordinates()

        assertTrue(10.0 in redXs)
        assertTrue(100.0 !in redXs)
        assertTrue(100.0 in greenXs)
        assertTrue(10.0 !in greenXs)
    }

    @Test
    fun runtime_dependent_spatial_branches_remain_conservative() = test {
        val bytecode = assembleBytecode("""
            0:
                jmpi_= r20, 1, 100
                leti r0, 10
                leti r1, 0
                leti r2, 0
                leti r3, 20
                leti r4, 200
                at_coords_call r0
                ret
            100:
                leti r10, 100
                leti r11, 0
                leti r12, 0
                leti r13, 20
                leti r14, 201
                at_coords_call r10
                ret
            200:
                ret
            201:
                ret
        """)
        val quest = createQuestModel(
            objects = listOf(playerSet(0, 0, 0.0)),
            bytecodeIr = bytecode,
        )

        val routeXs = planWalkthroughRoute(quest, setOf(0), clientId = 0).allXCoordinates()

        assertTrue(10.0 in routeXs)
        assertTrue(100.0 in routeXs)
    }

    @Test
    fun spatial_reachability_preserves_the_client_floor_pair() = test {
        val bytecode = assembleBytecode("""
            0:
                set_floor_handler 1, 100
                set_floor_handler 2, 100
                ret
            100:
                get_floor_number 0, r30
                switch_jmp r30, 900, 200, 300
            200:
                get_slotnumber r31
                jmpi_= r31, 1, 900
                leti r0, 10
                leti r1, 0
                leti r2, 0
                leti r3, 20
                leti r4, 400
                at_coords_call r0
                ret
            300:
                get_slotnumber r32
                jmpi_= r32, 0, 900
                leti r10, 100
                leti r11, 0
                leti r12, 0
                leti r13, 20
                leti r14, 401
                at_coords_call r10
                ret
            400:
                ret
            401:
                ret
            900:
                ret
        """)
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                playerSet(2, 0, 20.0),
                playerSet(1, 1, 50.0),
                playerSet(2, 1, 70.0),
            ),
            bytecodeIr = bytecode,
        )

        val redXs = planWalkthroughRoute(quest, setOf(1, 2), clientId = 0).allXCoordinates()
        val greenXs = planWalkthroughRoute(quest, setOf(1, 2), clientId = 1).allXCoordinates()

        assertTrue(10.0 in redXs)
        assertTrue(100.0 !in redXs)
        assertTrue(100.0 in greenXs)
        assertTrue(10.0 !in greenXs)
    }

    @Test
    fun duplicate_event_ids_are_both_kept_and_trigger_targets_are_directed() = test {
        val entrance = playerSet(1, 0, 0.0)
        val trigger = eventCollision(1, 10, 10.0)
        val first = event(
            floorId = 1,
            id = 10,
            sectionId = 0,
            actions = mutableListOf(QuestEventActionModel.TriggerEvent(20)),
        )
        val duplicate = event(floorId = 1, id = 10, sectionId = 0)
        val target = event(floorId = 1, id = 20, sectionId = 0)
        first.setSectionId(1)
        duplicate.setSectionId(2)
        target.setSectionId(3)
        val firstAnchor = createQuestNpcModel(NpcType.Booma, Episode.I, 1).apply {
            setSectionId(1)
            setWorldPosition(Vector3(20.0, 0.0, 0.0))
        }
        val duplicateAnchor = createQuestNpcModel(NpcType.Booma, Episode.I, 1).apply {
            setSectionId(2)
            setWorldPosition(Vector3(30.0, 0.0, 0.0))
        }
        val targetAnchor = createQuestNpcModel(NpcType.Booma, Episode.I, 1).apply {
            setSectionId(3)
            setWorldPosition(Vector3(40.0, 0.0, 0.0))
        }
        val quest = createQuestModel(
            objects = listOf(entrance, trigger),
            npcs = listOf(firstAnchor, duplicateAnchor, targetAnchor),
            events = listOf(first, duplicate, target),
        )

        val route = planWalkthroughRoute(quest, setOf(1), clientId = 0)

        // Entrance + interaction + three unique event records form one continuous traversal.
        assertTrue(route.segments.size >= 4)
        assertTrue(route.segments.count { it.relation == WalkthroughRelation.Explicit } >= 3)
        assertTrue(route.segments.any {
            it.relation == WalkthroughRelation.Explicit && it.from.x == 20.0 && it.to.x == 40.0
        })
        assertTrue(route.segments.none {
            it.relation == WalkthroughRelation.Explicit && it.from.x == 30.0 && it.to.x == 40.0
        })
    }

    @Test
    fun visible_floors_produce_independent_routes_without_cross_floor_segments() = test {
        val quest = createQuestModel(
            objects = listOf(
                playerSet(1, 0, 0.0),
                eventCollision(1, 1, 10.0),
                playerSet(2, 0, 100.0),
                eventCollision(2, 2, 110.0),
            ),
            events = listOf(event(1, 1, 0), event(2, 2, 0)),
        )

        val route = planWalkthroughRoute(quest, setOf(1, 2), clientId = 0)

        assertEquals(setOf(1, 2), route.segments.mapTo(mutableSetOf()) { it.floorId })
        assertTrue(route.segments.filter { it.floorId == 1 }.all { it.from.x < 50 && it.to.x < 50 })
        assertTrue(route.segments.filter { it.floorId == 2 }.all { it.from.x > 50 && it.to.x > 50 })
    }

    @Test
    fun intra_floor_warp_source_and_destination_are_connected() = test {
        val warp = createQuestObjectModel(ObjectType.Warp, 1).apply {
            setWorldPosition(Vector3(10.0, 0.0, 0.0))
            setDestinationPosition(Vector3(80.0, 0.0, 0.0))
        }
        val quest = createQuestModel(objects = listOf(playerSet(1, 0, 0.0), warp))

        val route = planWalkthroughRoute(quest, setOf(1), clientId = 0)

        assertTrue(route.segments.any {
            it.from.x == 10.0 && it.to.x == 80.0 && it.relation == WalkthroughRelation.Explicit
        })
    }

    @Test
    fun same_floor_quest_warp_connects_to_its_special_player_set() = test {
        val destination = playerSet(1, 0, 80.0).apply { entity.data.setInt(52, 2) }
        val warp = createQuestObjectModel(ObjectType.QuestWarp, 1).apply {
            entity.data.setFloat(40, 2f)
            entity.data.setInt(52, 1)
            setWorldPosition(Vector3(10.0, 0.0, 0.0))
        }
        val quest = createQuestModel(objects = listOf(playerSet(1, 0, 0.0), destination, warp))

        val route = planWalkthroughRoute(quest, setOf(1), clientId = 0)

        assertTrue(route.segments.any {
            it.from.x == 10.0 && it.to.x == 80.0 && it.relation == WalkthroughRelation.Explicit
        })
    }

    @Test
    fun missing_player_entrance_does_not_invent_a_route() = test {
        val quest = createQuestModel(
            objects = listOf(playerSet(1, 0, 0.0), eventCollision(1, 7, 10.0)),
            events = listOf(event(1, 7, 0)),
        )

        val route = planWalkthroughRoute(quest, setOf(1), clientId = 1)

        assertTrue(route.segments.isEmpty())
        assertTrue(route.diagnostics.any { "no Player Set entrance" in it })
    }

    @Test
    fun spawn_and_door_actions_create_directed_anchors() = test {
        val trigger = eventCollision(1, 7, 10.0)
        val spawnedNpc = createQuestNpcModel(NpcType.Booma, Episode.I, 1).apply {
            setSectionId(4)
            setWaveId(5)
            setWorldPosition(Vector3(80.0, 0.0, 0.0))
        }
        val door = createQuestObjectModel(ObjectType.ForestDoor, 1).apply {
            entity.data.setInt(52, 9)
            setWorldPosition(Vector3(100.0, 0.0, 0.0))
        }
        val sourceEvent = event(
            floorId = 1,
            id = 7,
            sectionId = 0,
            actions = mutableListOf(
                QuestEventActionModel.SpawnNpcs(4, 5),
                QuestEventActionModel.Door.Unlock(9),
            ),
        )
        val quest = createQuestModel(
            objects = listOf(playerSet(1, 0, 0.0), trigger, door),
            npcs = listOf(spawnedNpc),
            events = listOf(sourceEvent),
        )

        val route = planWalkthroughRoute(quest, setOf(1), clientId = 0)

        assertTrue(route.segments.any {
            it.relation == WalkthroughRelation.Explicit && it.from.x == 10.0 && it.to.x == 80.0
        })
        assertTrue(route.segments.any {
            it.relation == WalkthroughRelation.Explicit && it.from.x == 10.0 && it.to.x == 100.0
        })
    }

    @Test
    fun door_actions_match_multi_id_door_ranges() = test {
        val trigger = eventCollision(1, 7, 10.0)
        val door = createQuestObjectModel(ObjectType.Ruins4ButtonDoor, 1).apply {
            entity.data.setInt(52, 20)
            setWorldPosition(Vector3(100.0, 0.0, 0.0))
        }
        val sourceEvent = event(
            floorId = 1,
            id = 7,
            sectionId = 0,
            actions = mutableListOf(QuestEventActionModel.Door.Unlock(23)),
        )
        val quest = createQuestModel(
            objects = listOf(playerSet(1, 0, 0.0), trigger, door),
            events = listOf(sourceEvent),
        )

        val route = planWalkthroughRoute(quest, setOf(1), clientId = 0)

        assertTrue(route.segments.any {
            it.relation == WalkthroughRelation.Explicit && it.from.x == 10.0 && it.to.x == 100.0
        })
    }

    @Test
    fun forest_door_id_masks_signed_metadata_before_validation() = test {
        val door = createQuestObjectModel(ObjectType.ForestDoor, 1).apply {
            entity.data.setInt(52, 0x80000009u.toInt())
        }
        val unsetDoor = createQuestObjectModel(ObjectType.ForestDoor, 1).apply {
            entity.data.setInt(52, -1)
        }

        assertEquals(9..9, door.controlledDoorIds())
        assertNull(unsetDoor.controlledDoorIds())
    }

    private fun playerSet(floorId: Int, clientId: Int, x: Double) =
        createQuestObjectModel(ObjectType.PlayerSet, floorId).apply {
            entity.data.setFloat(40, clientId.toFloat())
            entity.data.setInt(52, 0)
            setWorldPosition(Vector3(x, 0.0, 0.0))
        }

    private fun eventCollision(floorId: Int, eventId: Int, x: Double) =
        createQuestObjectModel(ObjectType.EventCollision, floorId).apply {
            entity.data.setInt(52, eventId)
            setWorldPosition(Vector3(x, 0.0, 0.0))
        }

    private fun event(
        floorId: Int,
        id: Int,
        sectionId: Int,
        actions: MutableList<QuestEventActionModel> = mutableListOf(),
    ) = QuestEventModel(
        id = id,
        floorId = floorId,
        sectionId = sectionId,
        waveId = 0,
        delay = 0,
        unknown = 0,
        actions = actions,
    )

    private fun WalkthroughRoute.allXCoordinates(): Set<Double> = segments
        .flatMapTo(mutableSetOf()) { listOf(it.from.x, it.to.x) }

    private fun assembleBytecode(assembly: String): BytecodeIr {
        val result = assemble(assembly.trimIndent().lines(), Version.BB_V4)
        assertTrue(result is Success)
        return result.value
    }
}
