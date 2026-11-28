package world.phantasmal.web.questEditor.asm

import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.fileFormats.quest.Version
import world.phantasmal.web.test.WebTestSuite
import kotlin.test.*

class FloorDesignationEditorTests : WebTestSuite {
    private fun inspectScript(
        script: String,
        version: Version = Version.BB_V4,
        isLossy: Boolean = false,
        usedFloorIds: Set<Int> = emptySet(),
        datEntryFloorIds: Map<Int, Set<Int>> = emptyMap(),
    ): FloorDesignationSnapshot =
        FloorDesignationEditor.inspect(
            lines = script.trimIndent().split("\n"),
            version = version,
            isLossy = isLossy,
            usedFloorIds = usedFloorIds,
            datEntryFloorIds = datEntryFloorIds,
        )

    @Test
    fun five_arg_bb_map_designate_updates_variation_preserving_decimal_and_hex() = test {
        val decSnap = inspectScript("""
            0:
                set_episode 0
                bb_map_designate 3, 3, 0, 1, 0
                ret
        """)
        val decRes = FloorDesignationEditor.prepareSetFloorVariantEdit(
            decSnap, floorId = 3, expectedMapId = 3, newVariant = 4, preferHex = false,
        )
        assertTrue(decRes is PrepareFloorVariantResult.Ready)
        val updatedDec = FloorDesignationEditor.applyTextEdit(decSnap.lines, decRes.edit)
        assertEquals("    bb_map_designate 3, 3, 0, 4, 0", updatedDec[2])

        val hexSnap = inspectScript("""
            0:
                set_episode 0
                bb_map_designate 0x3, 0x3, 0x0, 0x1, 0x0
                ret
        """)
        val hexRes = FloorDesignationEditor.prepareSetFloorVariantEdit(
            hexSnap, floorId = 3, expectedMapId = 3, newVariant = 5, preferHex = false,
        )
        assertTrue(hexRes is PrepareFloorVariantResult.Ready)
        val updatedHex = FloorDesignationEditor.applyTextEdit(hexSnap.lines, hexRes.edit)
        assertEquals("    bb_map_designate 0x3, 0x3, 0x0, 0x5, 0x0", updatedHex[2])
    }

    @Test
    fun four_arg_legacy_bb_map_designate_replaces_variation_without_clobbering_packed_map_type() = test {
        val snap = inspectScript("""
            0:
                set_episode 0
                bb_map_designate 3, 3, 1, 0
                ret
        """)
        val res = FloorDesignationEditor.prepareSetFloorVariantEdit(
            snap, floorId = 3, expectedMapId = 3, newVariant = 4, preferHex = false,
        )
        assertTrue(res is PrepareFloorVariantResult.Ready)
        val updated = FloorDesignationEditor.applyTextEdit(snap.lines, res.edit)
        assertEquals("    bb_map_designate 3, 3, 4, 0", updated[2])
        assertEquals(4, res.candidateMappings.single { it.floorId == 3 }.mapVariation)
        assertEquals(3, res.candidateMappings.single { it.floorId == 3 }.mapId)
    }

    @Test
    fun default_floor_inserts_after_set_episode_or_splits_inline_ret() = test {
        val withSetEp = inspectScript("""
            0:
                set_episode 0
                ret
        """)
        val res1 = FloorDesignationEditor.prepareSetFloorVariantEdit(
            withSetEp, floorId = 3, expectedMapId = 3, newVariant = 2, preferHex = false,
        )
        assertTrue(res1 is PrepareFloorVariantResult.Ready)
        assertEquals(
            listOf("0:", "    set_episode 0", "    bb_map_designate 3, 3, 0, 2, 0", "    ret"),
            FloorDesignationEditor.applyTextEdit(withSetEp.lines, res1.edit),
        )

        val inlineRet = inspectScript("0: ret")
        val res2 = FloorDesignationEditor.prepareSetFloorVariantEdit(
            inlineRet, floorId = 3, expectedMapId = 3, newVariant = 1, preferHex = false,
        )
        assertTrue(res2 is PrepareFloorVariantResult.Ready)
        val updatedInline = FloorDesignationEditor.applyTextEdit(inlineRet.lines, res2.edit)
        assertTrue(updatedInline.indexOfFirst { "bb_map_designate" in it } < updatedInline.indexOfFirst { "ret" in it })
    }

    @Test
    fun aliased_label_0_is_read_only_for_default_insertion() = test {
        val snap = inspectScript("""
            0:
            1:
                set_episode 0
                ret
        """)
        assertTrue(snap.statusForFloor(3) is FloorDesignationStatus.ReadOnly)
    }

    @Test
    fun unconditional_cross_label_call_continuation_is_editable_but_conditionally_skipped_is_rejected() = test {
        val unconditional = inspectScript("""
            0:
                set_episode 0
                call 10
            1:
                bb_map_designate 3, 3, 0, 1, 0
                ret
            10:
                ret
        """)
        assertTrue(unconditional.statusForFloor(3) is FloorDesignationStatus.EditableBbDesignate)

        val conditional = inspectScript("""
            0:
                set_episode 0
                jmp_> r1, r2, 2
                call 10
            1:
                bb_map_designate 3, 3, 0, 0, 0
                ret
            2:
                ret
            10:
                ret
        """)
        val res = FloorDesignationEditor.prepareSetFloorVariantEdit(
            conditional, floorId = 3, expectedMapId = 3, newVariant = 2, preferHex = false,
        )
        assertTrue(res is PrepareFloorVariantResult.Rejected)
    }

    @Test
    fun dead_code_after_ret_and_unreachable_shared_subroutine_call_site_are_read_only() = test {
        val afterRet = inspectScript("""
            0:
                set_episode 0
                ret
                bb_map_designate 3, 3, 0, 1, 0
        """)
        assertTrue(afterRet.statusForFloor(3) is FloorDesignationStatus.ReadOnly)

        val sharedSub = inspectScript("""
            0:
                set_episode 0
                call 10
                ret
            10:
                ret
            20:
                call 10
                bb_map_designate 3, 3, 0, 1, 0
                ret
        """)
        assertTrue(sharedSub.statusForFloor(3) is FloorDesignationStatus.ReadOnly)
    }

    @Test
    fun cleared_floor_handler_and_out_of_range_switch_call_are_read_only() = test {
        val clearedHandler = inspectScript("""
            0:
                set_episode 0
                set_floor_handler 4, 100
                clr_floor_handler 4
                ret
            100:
                bb_map_designate 3, 3, 0, 1, 0
                ret
        """)
        assertTrue(clearedHandler.statusForFloor(3) is FloorDesignationStatus.ReadOnly)

        val switchScript = """
            0:
                set_episode 0
                ret
            100:
                get_floor_number 0, r1
                switch_call r1, 200, 200, 200, 201
                ret
            200:
                ret
            201:
                bb_map_designate 3, 3, 0, 1, 0
                ret
        """
        // Floor 4 is out of range of the 4-entry switch_call (0..3) -> unreachable -> ReadOnly.
        val floor4Only = inspectScript(switchScript, datEntryFloorIds = mapOf(100 to setOf(4)))
        assertTrue(floor4Only.statusForFloor(3) is FloorDesignationStatus.ReadOnly)

        // Floor 3 hits index 3 (label 201) -> deterministically reachable -> Editable!
        val floor3Only = inspectScript(switchScript, datEntryFloorIds = mapOf(100 to setOf(3)))
        assertTrue(floor3Only.statusForFloor(3) is FloorDesignationStatus.EditableBbDesignate)

        // Both Floor 3 and Floor 4 -> ambiguous (Floor 3 calls 201, Floor 4 falls through) -> Rejected on edit.
        val bothFloors = inspectScript(switchScript, datEntryFloorIds = mapOf(100 to setOf(3, 4)))
        assertTrue(bothFloors.statusForFloor(3) is FloorDesignationStatus.ReadOnly)
    }

    @Test
    fun read_only_guards_cover_lossy_non_bb_types_registers_and_stale_map() = test {
        val simple = """
            0:
                set_episode 0
                bb_map_designate 3, 3, 0, 1, 0
                ret
        """
        assertTrue(inspectScript(simple, isLossy = true).statusForFloor(3) is FloorDesignationStatus.ReadOnly)
        assertTrue(inspectScript(simple, version = Version.GC_V3).statusForFloor(3) is FloorDesignationStatus.ReadOnly)

        // Stale expectedMapId rejected (e.g. menu had Cave 1 = 3, script changed to EP4 Crater Route 3 = 0x26).
        val ep4Snap = inspectScript("""
            0:
                set_episode 2
                ret
        """)
        assertEquals(Episode.IV, ep4Snap.scriptEpisode)
        val staleRes = FloorDesignationEditor.prepareSetFloorVariantEdit(
            ep4Snap, floorId = 3, expectedMapId = 3, newVariant = 5, preferHex = false,
        )
        assertTrue(staleRes is PrepareFloorVariantResult.Rejected)

        // Type 1 (OfflineTemplate) ignores mapVariation -> ReadOnly.
        val type1 = inspectScript("""
            0:
                set_episode 0
                bb_map_designate 3, 3, 1, 0, 0
                ret
        """)
        assertTrue(type1.statusForFloor(3) is FloorDesignationStatus.ReadOnly)

        // Unresolved register map_designate -> ReadOnly.
        val dynReg = inspectScript("""
            0:
                set_episode 0
                map_designate r10
                ret
        """)
        assertTrue(dynReg.statusForFloor(3) is FloorDesignationStatus.ReadOnly)
    }

    @Test
    fun gc_v3_map_designate_ex_edits_leti_and_clear_in_place() = test {
        val script = """
            0:
                set_episode 1
                leti r60, 3
                leti r61, 20
                leti r62, 0
                leti r63, 0
                map_designate_ex r60
                leti r60, 4
                leti r61, 21
                leti r62, 0
                clear r63
                map_designate_ex r60
                ret
        """
        val snap = inspectScript(script, version = Version.GC_V3)

        // Floor 3 (VR Spaceship Alpha, mapId=20): r63 is overwritten before Floor 4 -> in-place leti edit.
        val res3 = FloorDesignationEditor.prepareSetFloorVariantEdit(
            snap, floorId = 3, expectedMapId = 20, newVariant = 2, preferHex = false,
        )
        assertTrue(res3 is PrepareFloorVariantResult.Ready)
        val updated3 = FloorDesignationEditor.applyTextEdit(snap.lines, res3.edit)
        assertEquals("    leti r63, 2", updated3[5])
        assertEquals(2, res3.candidateMappings.single { it.floorId == 3 }.mapVariation)
        assertEquals(0, res3.candidateMappings.single { it.floorId == 4 }.mapVariation)

        // Floor 4 (VR Spaceship Beta, mapId=21): clear r63 replaced in place with leti r63, 1.
        val res4 = FloorDesignationEditor.prepareSetFloorVariantEdit(
            snap, floorId = 4, expectedMapId = 21, newVariant = 1, preferHex = false,
        )
        assertTrue(res4 is PrepareFloorVariantResult.Ready)
        val updated4 = FloorDesignationEditor.applyTextEdit(snap.lines, res4.edit)
        assertEquals("    leti r63, 1", updated4[10])
        assertEquals(1, res4.candidateMappings.single { it.floorId == 4 }.mapVariation)
        assertEquals(0, res4.candidateMappings.single { it.floorId == 3 }.mapVariation)
    }

    @Test
    fun gc_v3_shared_variation_register_wraps_without_affecting_adjacent_floor() = test {
        // Floor 4 reuses r62=0 and r63=0 from Floor 3 without redefining them.
        val script = """
            0:
                set_episode 1
                leti r60, 3
                leti r61, 20
                leti r62, 0
                leti r63, 0
                map_designate_ex r60
                leti r60, 4
                leti r61, 21
                map_designate_ex r60
                ret
        """
        val snap = inspectScript(script, version = Version.GC_V3)

        // Editing Floor 3 must wrap Floor 3's map_designate_ex and restore r63=0 for Floor 4.
        val res3 = FloorDesignationEditor.prepareSetFloorVariantEdit(
            snap, floorId = 3, expectedMapId = 20, newVariant = 1, preferHex = false,
        )
        assertTrue(res3 is PrepareFloorVariantResult.Ready)
        assertEquals(1, res3.candidateMappings.single { it.floorId == 3 }.mapVariation)
        assertEquals(0, res3.candidateMappings.single { it.floorId == 4 }.mapVariation)

        // Editing Floor 4 inserts leti r63, 2 right before Floor 4 without touching Floor 3.
        val res4 = FloorDesignationEditor.prepareSetFloorVariantEdit(
            snap, floorId = 4, expectedMapId = 21, newVariant = 2, preferHex = false,
        )
        assertTrue(res4 is PrepareFloorVariantResult.Ready)
        assertEquals(0, res4.candidateMappings.single { it.floorId == 3 }.mapVariation)
        assertEquals(2, res4.candidateMappings.single { it.floorId == 4 }.mapVariation)
    }

    @Test
    fun gc_v3_and_dc_v1_default_floor_insert_free_scratch_register_designations() = test {
        // GC V3 Episode II: VR Temple Alpha (floor 1, default mapId 19) inserts map_designate_ex
        // using free scratch registers avoiding r60..r64.
        val gcSnap = inspectScript(
            """
                0:
                    set_episode 1
                    leti r60, 3
                    leti r61, 20
                    leti r62, 0
                    leti r63, 0
                    map_designate_ex r60
                    ret
            """,
            version = Version.GC_V3,
        )
        val gcRes = FloorDesignationEditor.prepareSetFloorVariantEdit(
            gcSnap, floorId = 1, expectedMapId = 19, newVariant = 1, preferHex = false,
        )
        assertTrue(gcRes is PrepareFloorVariantResult.Ready)
        val gcUpdated = FloorDesignationEditor.applyTextEdit(gcSnap.lines, gcRes.edit).joinToString("\n")
        assertTrue("map_designate_ex r65" in gcUpdated, "Expected free scratch base r65, got:\n$gcUpdated")
        assertEquals(1, gcRes.candidateMappings.single { it.floorId == 1 }.mapVariation)
        assertEquals(0, gcRes.candidateMappings.single { it.floorId == 3 }.mapVariation)

        // DC V1 Episode I: Cave 1 (floor 3, mapId 3) inserts legacy map_designate (not _ex).
        val dcSnap = inspectScript(
            """
                0:
                    ret
            """,
            version = Version.DC_V1,
        )
        val dcRes = FloorDesignationEditor.prepareSetFloorVariantEdit(
            dcSnap, floorId = 3, expectedMapId = 3, newVariant = 2, preferHex = false,
        )
        assertTrue(dcRes is PrepareFloorVariantResult.Ready)
        val dcUpdated = FloorDesignationEditor.applyTextEdit(dcSnap.lines, dcRes.edit).joinToString("\n")
        assertTrue("map_designate r60" in dcUpdated, "Expected legacy map_designate r60, got:\n$dcUpdated")
        assertFalse("map_designate_ex" in dcUpdated)
        assertEquals(2, dcRes.candidateMappings.single { it.floorId == 3 }.mapVariation)
    }

    @Test
    fun reg_var_type_and_switch_call_continuation_protect_live_and_scratch_registers() = test {
        // jmp_on uses RegVarType for r63: liveness must wrap/restore r63 instead of mutating leti in place,
        // and scratch allocation for default floors must skip r63.
        val jmpOnSnap = inspectScript(
            """
                0:
                    set_episode 1
                    leti r60, 3
                    leti r61, 20
                    leti r62, 0
                    leti r63, 0
                    map_designate_ex r60
                    jmp_on 100, r63
                    ret
                100:
                    ret
            """,
            version = Version.GC_V3,
        )
        val status3 = jmpOnSnap.statusForFloor(3)
        assertTrue(status3 is FloorDesignationStatus.EditableRegisterDesignate)
        assertTrue(
            status3.strategy is RegisterEditStrategy.WrapDesignateLine && status3.strategy.restoreOldValue,
            "Expected WrapDesignateLine(restoreOldValue=true) due to jmp_on r63, got ${status3.strategy}",
        )

        // switch_call whose callee overwrites r63 can still fall through out-of-range to jmp_on r63.
        val switchContSnap = inspectScript(
            """
                0:
                    set_episode 1
                    leti r60, 3
                    leti r61, 20
                    leti r62, 0
                    leti r63, 0
                    map_designate_ex r60
                    switch_call r1, 200
                    jmp_on 100, r63
                    ret
                100:
                    ret
                200:
                    leti r63, 99
                    ret
            """,
            version = Version.GC_V3,
        )
        val switchStatus = switchContSnap.statusForFloor(3)
        assertTrue(switchStatus is FloorDesignationStatus.EditableRegisterDesignate)
        assertTrue(
            switchStatus.strategy is RegisterEditStrategy.WrapDesignateLine && switchStatus.strategy.restoreOldValue,
            "Expected WrapDesignateLine(restoreOldValue=true) across switch_call fallthrough, got ${switchStatus.strategy}",
        )

        // Scratch allocation must skip registers referenced via RegVarType (jmp_on 100, r60).
        val scratchSnap = inspectScript(
            """
                0:
                    set_episode 1
                    jmp_on 100, r60
                    ret
                100:
                    ret
            """,
            version = Version.GC_V3,
        )
        val defaultStatus = scratchSnap.statusForFloor(1)
        assertTrue(defaultStatus is FloorDesignationStatus.EditableDefault)
        assertTrue(
            defaultStatus.scratchBaseReg != null && defaultStatus.scratchBaseReg != 60,
            "Expected scratch allocation to skip r60 used by jmp_on, got r${defaultStatus.scratchBaseReg}",
        )
    }

    @Test
    fun dc_v2_script_with_v0_v2_opcode_assembles_and_edits_in_native_dialect() = test {
        val snap = inspectScript(
            """
                0:
                    nop_42 0
                    leti r60, 3
                    leti r61, 3
                    leti r62, 0
                    leti r63, 0
                    map_designate_ex r60
                    ret
            """,
            version = Version.DC_V2,
        )
        assertFalse(snap.hasAssemblyErrors, "DC V2 script with nop_42 should assemble without errors")
        val res = FloorDesignationEditor.prepareSetFloorVariantEdit(
            snap, floorId = 3, expectedMapId = 3, newVariant = 2, preferHex = false,
        )
        assertTrue(res is PrepareFloorVariantResult.Ready)
        assertEquals(2, res.candidateMappings.single { it.floorId == 3 }.mapVariation)
    }

    @Test
    fun get_floor_number_room_overwrite_and_invalid_switch_reg_are_handled_safely() = test {
        // get_floor_number 1, r19 writes floor->r19 and room->r20, overwriting r20!
        // So switch_call r20 is NOT driven by execution floor and must stay ambiguous (ReadOnly).
        val overwrittenSnap = inspectScript(
            """
                0:
                    set_episode 0
                    ret
                100:
                    get_floor_number 0, r20
                    get_floor_number 1, r19
                    switch_call r20, 200, 200, 200, 201
                    ret
                200:
                    ret
                201:
                    bb_map_designate 3, 3, 0, 1, 0
                    ret
            """,
            datEntryFloorIds = mapOf(100 to setOf(3)),
        )
        assertTrue(overwrittenSnap.statusForFloor(3) is FloorDesignationStatus.ReadOnly)

        // Callee with one exit keeping r10=floor and another setting r10=0 must not prune index 0 on Floor 4.
        val multiExitSnap = inspectScript(
            """
                0:
                    set_episode 0
                    ret
                100:
                    get_floor_number 0, r10
                    call 150
                    switch_call r10, 200
                    ret
                150:
                    jmp_> r1, r2, 151
                    leti r10, 0
                151:
                    ret
                200:
                    bb_map_designate 3, 3, 0, 1, 0
                    ret
            """,
            datEntryFloorIds = mapOf(100 to setOf(4)),
        )
        assertTrue(
            multiExitSnap.floorMappings.any { it.floorId == 3 },
            "Reachable index-0 target from multi-exit callee must not be pruned on Floor 4",
        )

        // Invalid register operand switch_call r256 must not throw during inspection.
        val badRegSnap = inspectScript(
            """
                0:
                    set_episode 0
                    switch_call r256, 200
                    ret
                200:
                    bb_map_designate 3, 3, 0, 1, 0
                    ret
            """,
        )
        assertTrue(badRegSnap.hasAssemblyErrors)
        assertTrue(badRegSnap.statusForFloor(3) is FloorDesignationStatus.ReadOnly)

        // Stack-passed register read (set_floor_handler r63, 200) must keep r63 live (wrap/restore).
        val stackReadSnap = inspectScript(
            """
                0:
                    set_episode 1
                    leti r60, 3
                    leti r61, 20
                    leti r62, 0
                    leti r63, 0
                    map_designate_ex r60
                    set_floor_handler r63, 200
                    ret
                200:
                    ret
            """,
            version = Version.GC_V3,
        )
        val stackReadStatus = stackReadSnap.statusForFloor(3)
        assertTrue(stackReadStatus is FloorDesignationStatus.EditableRegisterDesignate)
        assertTrue(
            stackReadStatus.strategy is RegisterEditStrategy.WrapDesignateLine &&
                stackReadStatus.strategy.restoreOldValue,
        )

        // get_floor_number 99, r10 queries an empty/non-executing slot (-1 at runtime), so
        // switch_call r10, 200 can fall through -> ambiguous (ReadOnly).
        val otherSlotSnap = inspectScript(
            """
                0:
                    set_episode 0
                    get_floor_number 99, r10
                    switch_call r10, 200
                    ret
                200:
                    bb_map_designate 3, 3, 0, 1, 0
                    ret
            """,
        )
        assertTrue(otherSlotSnap.statusForFloor(3) is FloorDesignationStatus.ReadOnly)

        // stack_popm r19, 2 invalidates r20 so switch_call r20 is not pruned using stale floor.
        val popmSnap = inspectScript(
            """
                0:
                    set_episode 0
                    ret
                100:
                    get_floor_number 0, r20
                    stack_popm r19, 2
                    switch_call r20, 200
                    ret
                200:
                    bb_map_designate 3, 3, 0, 1, 0
                    ret
            """,
            datEntryFloorIds = mapOf(100 to setOf(4)),
        )
        assertTrue(
            popmSnap.floorMappings.any { it.floorId == 3 },
            "stack_popm overwriting r20 must prevent pruning switch_call r20 on Floor 4",
        )

        // Skipped unknown switch_call preserves pre-call r10=1, so switch_call r10, 200 is ambiguous.
        val skippedSwitchSnap = inspectScript(
            """
                0:
                    set_episode 0
                    leti r10, 1
                    switch_call r1, 150
                    switch_call r10, 200
                    ret
                150:
                    leti r10, 0
                    ret
                200:
                    bb_map_designate 3, 3, 0, 1, 0
                    ret
            """,
        )
        assertTrue(skippedSwitchSnap.statusForFloor(3) is FloorDesignationStatus.ReadOnly)

        // Constant out-of-range switch_call (index 1 or -1 on 1-entry table) is unreachable dead code.
        val outOfRangeConstSnap = inspectScript(
            """
                0:
                    set_episode 0
                    leti r10, 1
                    switch_call r10, 200
                    ret
                200:
                    bb_map_designate 3, 3, 0, 1, 0
                    ret
            """,
        )
        assertTrue(outOfRangeConstSnap.statusForFloor(3) is FloorDesignationStatus.ReadOnly)

        val negativeConstSnap = inspectScript(
            """
                0:
                    set_episode 0
                    leti r10, -1
                    switch_call r10, 200
                    ret
                200:
                    bb_map_designate 3, 3, 0, 1, 0
                    ret
            """,
        )
        assertTrue(negativeConstSnap.statusForFloor(3) is FloorDesignationStatus.ReadOnly)
    }
}
