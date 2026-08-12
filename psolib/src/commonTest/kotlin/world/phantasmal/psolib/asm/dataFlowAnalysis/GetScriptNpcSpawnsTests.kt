package world.phantasmal.psolib.asm.dataFlowAnalysis

import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.IntArg
import world.phantasmal.psolib.asm.OP_NPC_CRP_V0_V2
import world.phantasmal.psolib.asm.OP_NPC_CRPTALK_V0_V2
import world.phantasmal.psolib.asm.RegType
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.fileFormats.quest.Version
import world.phantasmal.psolib.fileFormats.quest.parseBinDatToQuest
import world.phantasmal.psolib.fileFormats.quest.parseQstToQuest
import world.phantasmal.psolib.fileFormats.quest.writeQuestToBinDat
import world.phantasmal.psolib.test.LibTestSuite
import world.phantasmal.psolib.test.readFile
import world.phantasmal.psolib.test.toInstructions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GetScriptNpcSpawnsTests : LibTestSuite {
    @Test
    fun resolves_all_six_creation_opcodes_with_version_specific_semantics() {
        val segments = toInstructions("""
            0:
                leti r0, 10
                leti r1, 11
                leti r2, 12
                leti r3, 13
                leti r4, 14
                leti r5, 15
                npc_crp_v3 r0

                leti r10, 20
                leti r11, 21
                leti r12, 22
                leti r13, 23
                leti r14, 24
                leti r15, 25
                leti r16, 26
                npc_crppk_v3 r10

                leti r20, 30
                leti r21, 31
                leti r22, 32
                leti r23, 33
                leti r24, 34
                leti r25, 35
                npc_crptalk_v3 r20

                leti r30, 40
                leti r31, 41
                leti r32, 42
                leti r33, 43
                leti r34, 44
                leti r35, 45
                leti r36, 46
                npc_crp_id_v3 r30

                leti r40, 50
                leti r41, 51
                leti r42, 52
                leti r43, 53
                leti r44, 54
                leti r45, 55
                leti r46, 56
                npc_crptalk_id_v3 r40

                leti r50, 60
                leti r51, 61
                leti r52, 62
                leti r53, 63
                leti r54, 64
                leti r55, 57
                leti r56, 66
                leti r57, 67
                npc_talk_pl_v3 r50
                ret
        """.trimIndent())

        for (version in listOf(Version.DC_V2, Version.PC_V2, Version.GC_V3, Version.BB_V4)) {
            val spawns = getScriptNpcSpawns(version, Episode.I, segments) { ControlFlowGraph.create(segments) }
            assertEquals(ScriptNpcCreationOpcode.entries, spawns.map { it.opcode }, version.toString())

            assertEquals(10, spawns[0].x)
            assertEquals(15, spawns[0].templateIndex)
            assertEquals(14.takeIf { version == Version.BB_V4 }, spawns[0].ownerSlot)
            assertEquals(14.takeIf { version != Version.BB_V4 }, spawns[0].state)
            assertEquals(1.takeIf { version != Version.BB_V4 }, spawns[0].npcSlot)

            assertEquals(25, spawns[1].templateIndex)
            assertEquals(26, spawns[1].npcSlot)
            assertEquals(24.takeIf { version == Version.BB_V4 }, spawns[1].ownerSlot)
            assertEquals(24.takeIf { version != Version.BB_V4 }, spawns[1].state)

            assertEquals(35, spawns[2].templateIndex)
            assertEquals(34, spawns[2].state)
            assertEquals(if (version == Version.BB_V4) 3 else 1, spawns[2].npcSlot)

            assertEquals(46, spawns[3].templateIndex)
            assertEquals(45, spawns[3].npcSlot)
            assertEquals(44.takeIf { version == Version.BB_V4 }, spawns[3].ownerSlot)
            assertEquals(44.takeIf { version != Version.BB_V4 }, spawns[3].state)

            assertEquals(55, spawns[4].templateIndex)
            assertEquals(54, spawns[4].state)
            assertEquals(56, spawns[4].npcSlot)

            assertEquals(63, spawns[5].visibilityRadius)
            assertEquals(57, spawns[5].templateIndex)
            assertEquals(66, spawns[5].state)
            assertEquals(67.takeIf { version == Version.BB_V4 }, spawns[5].controllerToken)
            assertEquals(if (version == Version.BB_V4) 3 else 67, spawns[5].npcSlot)
            assertTrue(spawns.all { it.executionFloorIds == setOf(0) })
        }
    }

    @Test
    fun resolves_follower_and_local_talk_npcs_on_their_execution_floors() {
        val segments = toInstructions("""
            0:
                leti r0, 113
                leti r1, 0
                leti r2, 64
                leti r3, 60
                leti r4, 0
                leti r5, 27
                npc_crptalk_v3 r0
                set_floor_handler 1, 100
                ret
            100:
                leti r10, 24
                leti r11, 23
                leti r12, -22
                leti r13, 330
                leti r14, 0
                leti r15, 15
                npc_crp_v3 r10
                ret
        """.trimIndent())

        val spawns = getScriptNpcSpawns(Version.BB_V4, Episode.I, segments) { ControlFlowGraph.create(segments) }

        assertEquals(2, spawns.size)
        assertEquals(
            ScriptNpcSpawn(
                opcode = ScriptNpcCreationOpcode.NpcCrptalk,
                x = 113,
                y = 0,
                z = 64,
                angle = 60,
                templateIndex = 27,
                npcSlot = 3,
                state = 0,
                executionFloorIds = setOf(0),
            ),
            spawns[0],
        )
        assertEquals(
            ScriptNpcSpawn(
                opcode = ScriptNpcCreationOpcode.NpcCrp,
                x = 24,
                y = 23,
                z = -22,
                angle = 330,
                templateIndex = 15,
                ownerSlot = 0,
                executionFloorIds = setOf(1),
            ),
            spawns[1],
        )
    }

    @Test
    fun associates_targetable_and_talk_interactions_by_floor_and_trigger_radius() {
        val segments = toInstructions("""
            0:
                leti r0, 100
                leti r1, 20
                leti r2, 300
                leti r3, 90
                leti r4, 0
                leti r5, 27
                npc_crptalk_v3 r0

                leti r10, 100
                leti r11, 19
                leti r12, 300
                leti r13, 25
                leti r14, 320
                leti r15, 10
                set_obj_param r10, r20

                leti r30, 105
                leti r31, 20
                leti r32, 300
                leti r33, 10
                leti r34, 321
                at_coords_talk r30
                ret
            320:
                ret
            321:
                ret
        """.trimIndent())

        for (version in listOf(Version.DC_V2, Version.PC_V2, Version.GC_V3, Version.BB_V4)) {
            val spawn = getScriptNpcSpawns(version, Episode.I, segments) {
                ControlFlowGraph.create(segments)
            }.single()

            assertEquals(
                setOf(
                    ScriptNpcInteraction(320, ScriptNpcInteractionKind.Target),
                    ScriptNpcInteraction(321, ScriptNpcInteractionKind.Talk),
                ),
                spawn.interactions,
                version.toString(),
            )
        }
    }

    @Test
    fun does_not_associate_interactions_from_another_floor() {
        val segments = toInstructions("""
            0:
                leti r0, 100
                leti r1, 20
                leti r2, 300
                leti r3, 90
                leti r4, 0
                leti r5, 27
                npc_crptalk_v3 r0
                set_floor_handler 1, 100
                ret
            100:
                leti r10, 100
                leti r11, 20
                leti r12, 300
                leti r13, 25
                leti r14, 200
                leti r15, 10
                set_obj_param r10, r20
                ret
            200:
                ret
        """.trimIndent())

        val spawn = getScriptNpcSpawns(Version.BB_V4, Episode.I, segments) {
            ControlFlowGraph.create(segments)
        }.single()

        assertTrue(spawn.interactions.isEmpty())
    }

    @Test
    fun resolves_explicit_attacker_and_proximity_controller_semantics() {
        val segments = toInstructions("""
            0:
                leti r0, 10
                leti r1, 20
                leti r2, 30
                leti r3, 40
                leti r4, 2
                leti r5, 12
                leti r6, 1
                npc_crppk_v3 r0

                leti r20, 100
                leti r21, 200
                leti r22, 300
                leti r23, 250
                leti r24, 90
                leti r25, 27
                leti r26, 1
                leti r27, 77
                npc_talk_pl_v3 r20
                ret
        """.trimIndent())

        val spawns = getScriptNpcSpawns(Version.BB_V4, Episode.I, segments) { ControlFlowGraph.create(segments) }

        assertEquals(2, spawns.size)
        assertEquals(ScriptNpcSpawnKind.Attacker, spawns[0].kind)
        assertEquals(2, spawns[0].ownerSlot)
        assertEquals(12, spawns[0].templateIndex)
        assertEquals(1, spawns[0].npcSlot)

        assertEquals(ScriptNpcSpawnKind.ProximityTalk, spawns[1].kind)
        assertEquals(250, spawns[1].visibilityRadius)
        assertEquals(27, spawns[1].templateIndex)
        assertEquals(1, spawns[1].state)
        assertEquals(77, spawns[1].controllerToken)
        assertEquals(3, spawns[1].npcSlot)
    }

    @Test
    fun ignores_unreachable_creations() {
        val segments = toInstructions("""
            0:
                ret
            100:
                leti r0, 1
                leti r1, 2
                leti r2, 3
                leti r3, 4
                leti r4, 0
                leti r5, 15
                npc_crp_v3 r0
                ret
        """.trimIndent())

        assertEquals(
            emptyList(),
            getScriptNpcSpawns(Version.BB_V4, Episode.I, segments) { ControlFlowGraph.create(segments) },
        )
    }

    @Test
    fun retains_a_preview_when_runtime_control_slots_are_not_constant() {
        val segments = toInstructions("""
            0:
                leti r0, 1150
                leti r1, 0
                leti r2, 800
                leti r3, 0
                get_slotnumber r4
                get_slotnumber r5
                leti r6, 4
                npc_crp_id_v3 r0
                ret
        """.trimIndent())

        val spawn = getScriptNpcSpawns(Version.BB_V4, Episode.I, segments) { ControlFlowGraph.create(segments) }.single()

        assertEquals(ScriptNpcCreationOpcode.NpcCrpId, spawn.opcode)
        assertEquals(4, spawn.templateIndex)
        assertEquals(null, spawn.ownerSlot)
        assertEquals(null, spawn.npcSlot)
    }

    @Test
    fun unresolved_optional_runtime_fields_never_hide_any_creation_kind() {
        val segments = toInstructions("""
            0:
                leti r0, 1
                leti r1, 2
                leti r2, 3
                leti r3, 4
                get_slotnumber r4
                leti r5, 5
                npc_crp_v3 r0

                leti r10, 11
                leti r11, 12
                leti r12, 13
                leti r13, 14
                get_slotnumber r14
                leti r15, 15
                get_slotnumber r16
                npc_crppk_v3 r10

                leti r20, 21
                leti r21, 22
                leti r22, 23
                leti r23, 24
                get_slotnumber r24
                leti r25, 25
                npc_crptalk_v3 r20

                leti r30, 31
                leti r31, 32
                leti r32, 33
                leti r33, 34
                get_slotnumber r34
                get_slotnumber r35
                leti r36, 36
                npc_crp_id_v3 r30

                leti r40, 41
                leti r41, 42
                leti r42, 43
                leti r43, 44
                get_slotnumber r44
                leti r45, 45
                get_slotnumber r46
                npc_crptalk_id_v3 r40

                leti r50, 51
                leti r51, 52
                leti r52, 53
                get_slotnumber r53
                leti r54, 54
                leti r55, 55
                get_slotnumber r56
                get_slotnumber r57
                npc_talk_pl_v3 r50
                ret
        """.trimIndent())

        for (version in listOf(Version.DC_V2, Version.PC_V2, Version.GC_V3, Version.BB_V4)) {
            val spawns = getScriptNpcSpawns(version, Episode.I, segments) { ControlFlowGraph.create(segments) }
            assertEquals(ScriptNpcCreationOpcode.entries, spawns.map { it.opcode }, version.toString())
            assertEquals(null, spawns[0].ownerSlot)
            assertEquals(null, spawns[0].state)
            assertEquals(1.takeIf { version != Version.BB_V4 }, spawns[0].npcSlot)
            assertEquals(null, spawns[1].ownerSlot)
            assertEquals(null, spawns[1].state)
            assertEquals(null, spawns[1].npcSlot)
            assertEquals(null, spawns[2].state)
            assertEquals(if (version == Version.BB_V4) 3 else 1, spawns[2].npcSlot)
            assertEquals(null, spawns[3].ownerSlot)
            assertEquals(null, spawns[3].state)
            assertEquals(null, spawns[3].npcSlot)
            assertEquals(null, spawns[4].state)
            assertEquals(null, spawns[4].npcSlot)
            assertEquals(null, spawns[5].visibilityRadius)
            assertEquals(null, spawns[5].state)
            assertEquals(null, spawns[5].controllerToken)
            assertEquals(3.takeIf { version == Version.BB_V4 }, spawns[5].npcSlot)
        }
    }

    @Test
    fun exposes_exact_stock_v4_template_classes_used_by_magnitude_of_metal() {
        assertEquals("ELENOR", scriptNpcTemplate(0x0F)?.name)
        assertEquals(ScriptNpcClass.RAcaseal, scriptNpcTemplate(0x0F)?.characterClass)
        assertEquals("DACCI", scriptNpcTemplate(0x1B)?.name)
        assertEquals(ScriptNpcClass.RAmar, scriptNpcTemplate(0x1B)?.characterClass)
    }

    @Test
    fun parses_magnitude_of_metal_script_npcs_end_to_end() = testAsync {
        val quest = parseQstToQuest(
            readFile("/quests/solo/ep1/03 magnitude of metal.qst")
        ).unwrap().quest

        assertTrue(quest.scriptNpcSpawns.any { spawn ->
            spawn.kind == ScriptNpcSpawnKind.LocalTalk &&
                spawn.templateIndex == 0x1B &&
                0 in spawn.executionFloorIds &&
                ScriptNpcInteraction(0x140, ScriptNpcInteractionKind.Target) in spawn.interactions
        })
        assertTrue(quest.scriptNpcSpawns.any { spawn ->
            spawn.kind == ScriptNpcSpawnKind.Follower &&
                spawn.templateIndex == 0x0F &&
                1 in spawn.executionFloorIds &&
                ScriptNpcInteraction(0x154, ScriptNpcInteractionKind.Target) in spawn.interactions
        })
    }

    @Test
    fun parses_v2_32_bit_register_operands_from_newserv_golden_quests() = testAsync {
        val quest = parseBinDatToQuest(
            readFile("/quests/npc-opcodes-v2-v3/q051-dc-e.bin"),
            readFile("/quests/npc-opcodes-v2-v3/q051-dc.dat"),
            version = Version.DC_V2,
        ).unwrap()
        val creations = quest.bytecodeIr.instructionSegments()
            .flatMap { it.instructions }
            .filter { it.opcode.code == 0x66 || it.opcode.code == 0x7D }

        assertEquals(2, creations.size)
        assertEquals(listOf(60, 6), creations.first { it.opcode.code == 0x7D }.args.map { (it as IntArg).value })
        val floorTwoHandler = quest.bytecodeIr.instructionSegments()
            .flatMap { it.instructions }
            .single { instruction ->
                instruction.opcode.code == 0x95 &&
                    (instruction.args.firstOrNull() as? IntArg)?.value == 2
            }
        assertEquals(listOf(2, 152), floorTwoHandler.args.map { (it as IntArg).value })
        assertTrue(quest.bytecodeIr.instructionSegments().any { 152 in it.labels })
        assertEquals(1, (OP_NPC_CRP_V0_V2.params.single { it.type is RegType }.type as RegType).inlineWidthBytes)
        assertEquals(4, (OP_NPC_CRPTALK_V0_V2.params.single { it.type is RegType }.type as RegType).inlineWidthBytes)
        val expected = ScriptNpcSpawn(
            opcode = ScriptNpcCreationOpcode.NpcCrptalk,
            x = 364,
            y = 19,
            z = 440,
            angle = -28,
            templateIndex = 29,
            npcSlot = 1,
            state = 0,
            executionFloorIds = setOf(2),
        )
        assertEquals(expected, quest.scriptNpcSpawns.single())

        val pcQuest = parseBinDatToQuest(
            readFile("/quests/npc-opcodes-v2-v3/q051-pc-e.bin"),
            readFile("/quests/npc-opcodes-v2-v3/q051-pc.dat"),
            version = Version.PC_V2,
        ).unwrap()
        assertTrue(expected in pcQuest.scriptNpcSpawns)
    }

    @Test
    fun parses_v3_positioned_npc_from_a_newserv_golden_quest() = testAsync {
        val v3 = parseBinDatToQuest(
            readFile("/quests/npc-opcodes-v2-v3/q082-gc-e.bin"),
            readFile("/quests/npc-opcodes-v2-v3/q082-gc.dat"),
            version = Version.GC_V3,
        ).unwrap()
        val creationCodes = setOf(0x66, 0x79, 0x7C, 0x7D, 0x7F, 0xCE)

        assertEquals(1, v3.bytecodeIr.instructionSegments().sumOf { segment ->
            segment.instructions.count { it.opcode.code in creationCodes }
        })
        assertEquals(
            ScriptNpcSpawn(
                opcode = ScriptNpcCreationOpcode.NpcCrp,
                x = 246,
                y = 0,
                z = 357,
                angle = 175,
                templateIndex = 16,
                state = 0,
                npcSlot = 1,
                executionFloorIds = setOf(0),
            ),
            v3.scriptNpcSpawns.single(),
        )
    }

    @Test
    fun stock_v2_and_v3_golden_quests_preserve_script_npcs_through_full_bin_dat_round_trip() = testAsync {
        data class Fixture(
            val version: Version,
            val bin: String,
            val dat: String,
        )

        val fixtures = listOf(
            Fixture(Version.DC_V2, "/quests/npc-opcodes-v2-v3/q051-dc-e.bin", "/quests/npc-opcodes-v2-v3/q051-dc.dat"),
            Fixture(Version.PC_V2, "/quests/npc-opcodes-v2-v3/q051-pc-e.bin", "/quests/npc-opcodes-v2-v3/q051-pc.dat"),
            Fixture(Version.GC_V3, "/quests/npc-opcodes-v2-v3/q082-gc-e.bin", "/quests/npc-opcodes-v2-v3/q082-gc.dat"),
        )

        for (fixture in fixtures) {
            val original = parseBinDatToQuest(
                readFile(fixture.bin),
                readFile(fixture.dat),
                version = fixture.version,
            ).unwrap()
            val (writtenBin, writtenDat) = writeQuestToBinDat(original, fixture.version)
            val reparsed = parseBinDatToQuest(
                writtenBin.cursor(),
                writtenDat.cursor(),
                compressed = false,
                version = fixture.version,
            ).unwrap()

            assertEquals(original.scriptNpcSpawns, reparsed.scriptNpcSpawns, fixture.version.toString())
            fun creationInstructions(quest: world.phantasmal.psolib.fileFormats.quest.Quest) =
                quest.bytecodeIr.instructionSegments().flatMap { it.instructions }
                    .filter { it.opcode.code in ScriptNpcCreationOpcode.entries.map { entry -> entry.code } }
                    .map { instruction ->
                        instruction.opcode.mnemonic to instruction.args.map { (it as IntArg).value }
                    }
            assertEquals(creationInstructions(original), creationInstructions(reparsed), fixture.version.toString())
        }
    }

    @Test
    fun all_supported_episode_tables_have_eighteen_logical_floor_slots() {
        val segments = toInstructions("""
            0:
                set_floor_handler 17, 100
                set_floor_handler 18, 200
                ret
            100:
                leti r0, 1
                leti r1, 2
                leti r2, 3
                leti r3, 4
                leti r4, 0
                leti r5, 16
                npc_crp_v3 r0
                ret
            200:
                leti r10, 11
                leti r11, 12
                leti r12, 13
                leti r13, 14
                leti r14, 0
                leti r15, 16
                npc_crp_v3 r10
                ret
        """.trimIndent())

        for ((version, episode) in listOf(
            Version.DC_V2 to Episode.I,
            Version.PC_V2 to Episode.I,
            Version.GC_V3 to Episode.I,
            Version.GC_V3 to Episode.II,
            Version.BB_V4 to Episode.I,
            Version.BB_V4 to Episode.II,
            Version.BB_V4 to Episode.IV,
        )) {
            val spawns = getScriptNpcSpawns(version, episode, segments) { ControlFlowGraph.create(segments) }
            assertEquals(1, spawns.size, "$version $episode")
            assertEquals(setOf(17), spawns.single().executionFloorIds, "$version $episode")
        }
        assertTrue(
            getScriptNpcSpawns(Version.DC_V2, Episode.II, segments) { ControlFlowGraph.create(segments) }.isEmpty()
        )
        assertTrue(
            getScriptNpcSpawns(Version.GC_V3, Episode.IV, segments) { ControlFlowGraph.create(segments) }.isEmpty()
        )
    }

    @Test
    fun preserves_separate_creation_sites_with_identical_resolved_values() {
        val segments = toInstructions("""
            0:
                leti r0, 1
                leti r1, 2
                leti r2, 3
                leti r3, 4
                leti r4, 0
                leti r5, 16
                npc_crp_v3 r0
                npc_crp_v3 r0
                ret
        """.trimIndent())

        val spawns = getScriptNpcSpawns(Version.BB_V4, Episode.I, segments) {
            ControlFlowGraph.create(segments)
        }

        assertEquals(2, spawns.size)
        assertEquals(spawns[0], spawns[1])
    }

    @Test
    fun lifecycle_opcodes_do_not_remove_or_move_creation_position_previews() {
        val segments = toInstructions("""
            0:
                leti r0, 10
                leti r1, 20
                leti r2, 30
                leti r3, 90
                leti r4, 0
                leti r5, 16
                npc_crp_v3 r0
                npc_stop 1
                npc_play 0
                npc_kill 1
                ret
        """.trimIndent())

        val spawn = getScriptNpcSpawns(Version.BB_V4, Episode.I, segments) {
            ControlFlowGraph.create(segments)
        }.single()

        assertEquals(10, spawn.x)
        assertEquals(20, spawn.y)
        assertEquals(30, spawn.z)
        assertEquals(90, spawn.angle)
    }

    @Test
    fun entity_entry_points_floor_scope_script_npcs() {
        val segments = toInstructions("""
            0:
                ret
            100:
                leti r0, 1
                leti r1, 2
                leti r2, 3
                leti r3, 4
                leti r4, 0
                leti r5, 16
                npc_crp_v3 r0
                ret
        """.trimIndent())

        val spawn = getScriptNpcSpawns(
            Version.GC_V3,
            Episode.I,
            segments,
            entityEntryPointFloorIds = mapOf(100 to setOf(7)),
        ) { ControlFlowGraph.create(segments) }.single()

        assertEquals(setOf(7), spawn.executionFloorIds)
    }

    @Test
    fun cleared_handlers_and_floor_scoped_threads_are_respected() {
        val cleared = toInstructions("""
            0:
                set_floor_handler 4, 100
                clr_floor_handler 4
                ret
            100:
                leti r0, 1
                leti r1, 2
                leti r2, 3
                leti r3, 4
                leti r4, 0
                leti r5, 16
                npc_crp_v3 r0
                ret
        """.trimIndent())
        assertTrue(
            getScriptNpcSpawns(Version.BB_V4, Episode.I, cleared) { ControlFlowGraph.create(cleared) }
                .isEmpty()
        )

        val threaded = toInstructions("""
            0:
                set_floor_handler 5, 100
                ret
            100:
                thread_stg 200
                ret
            200:
                sync
                leti r0, 1
                leti r1, 2
                leti r2, 3
                leti r3, 4
                leti r4, 0
                leti r5, 16
                npc_crp_v3 r0
                ret
        """.trimIndent())
        assertEquals(
            setOf(5),
            getScriptNpcSpawns(Version.BB_V4, Episode.I, threaded) { ControlFlowGraph.create(threaded) }
                .single().executionFloorIds,
        )
    }

    @Test
    fun rejects_unsupported_versions_invalid_register_ranges_and_unrenderable_values() {
        val valid = toInstructions("""
            0:
                leti r0, 1
                leti r1, 2
                leti r2, 3
                leti r3, 4
                leti r4, 0
                leti r5, 16
                npc_crp_v3 r0
                ret
        """.trimIndent())
        assertTrue(
            getScriptNpcSpawns(Version.DC_V1, Episode.I, valid) { ControlFlowGraph.create(valid) }
                .isEmpty()
        )

        val invalid = toInstructions("""
            0:
                npc_crp_v3 r252
                get_slotnumber r0
                leti r1, 2
                leti r2, 3
                leti r3, 4
                leti r4, 0
                leti r5, 16
                npc_crp_v3 r0
                leti r10, 1
                leti r11, 2
                leti r12, 3
                leti r13, 4
                leti r14, 0
                leti r15, 64
                npc_crp_v3 r10
                ret
        """.trimIndent())
        assertTrue(
            getScriptNpcSpawns(Version.BB_V4, Episode.I, invalid) { ControlFlowGraph.create(invalid) }
                .isEmpty()
        )
    }

    @Test
    fun template_table_has_all_64_valid_entries_and_strict_bounds() {
        assertEquals(64, SCRIPT_NPC_TEMPLATES.size)
        assertEquals((0 until 64).toList(), SCRIPT_NPC_TEMPLATES.map { it.index })
        assertTrue(SCRIPT_NPC_TEMPLATES.all { it.name.isNotBlank() })
        assertEquals(null, scriptNpcTemplate(-1))
        assertEquals(null, scriptNpcTemplate(64))
    }
}
