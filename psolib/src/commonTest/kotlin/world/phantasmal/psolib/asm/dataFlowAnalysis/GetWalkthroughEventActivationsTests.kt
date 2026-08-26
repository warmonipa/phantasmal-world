package world.phantasmal.psolib.asm.dataFlowAnalysis

import kotlin.test.Test
import kotlin.test.assertEquals
import world.phantasmal.psolib.asm.BytecodeIr
import world.phantasmal.psolib.test.toInstructions

class GetWalkthroughEventActivationsTests {
    @Test
    fun collects_progression_effects_and_resolves_door_arguments_from_registers() {
        val bytecode = bytecode("""
            0:
                leti r10, 3
                leti r11, 7
                unlock_door2 r10, r11
                masterkey_off
                warp_on
                ret
        """)

        val analysis = analyzeWalkthroughScript(bytecode, 0, 5, 0)

        assertEquals(setOf(WalkthroughDoorUnlock(3, 7)), analysis.doorUnlocks)
        assertEquals(setOf(5), analysis.allDoorsUnlockedFloorIds)
        assertEquals(setOf(5), analysis.warpsEnabledFloorIds)
    }

    @Test
    fun collects_current_and_explicit_floor_activations_through_calls() {
        val bytecode = bytecode("""
            0:
                call 100
                setevt 12
                ret
            100:
                start_setevt 3, 34
                ret
        """)

        assertEquals(
            setOf(
                WalkthroughEventActivation(2, 12),
                WalkthroughEventActivation(3, 34),
            ),
            getWalkthroughEventActivations(bytecode, 0, 2, 0),
        )
    }

    @Test
    fun follows_call_continuations_across_label_boundaries() {
        val bytecode = bytecode("""
            0:
                call 100
            1:
                setevt 12
                ret
            100:
                ret
        """)

        assertEquals(
            setOf(WalkthroughEventActivation(2, 12)),
            getWalkthroughEventActivations(bytecode, 0, 2, 0),
        )
    }

    @Test
    fun resolves_event_activation_arguments_from_registers() {
        val bytecode = bytecode("""
            0:
                leti r10, 12
                setevt r10
                leti r20, 3
                leti r21, 34
                start_setevt r20, r21
                ret
        """)

        assertEquals(
            setOf(
                WalkthroughEventActivation(2, 12),
                WalkthroughEventActivation(3, 34),
            ),
            getWalkthroughEventActivations(bytecode, 0, 2, 0),
        )
    }

    @Test
    fun prunes_direct_client_id_branches() {
        val bytecode = bytecode("""
            0:
                get_slotnumber r10
                jmpi_= r10, 1, 100
                setevt 10
                ret
            100:
                setevt 11
                ret
        """)

        assertEquals(
            setOf(WalkthroughEventActivation(4, 10)),
            getWalkthroughEventActivations(bytecode, 0, 4, 0),
        )
        assertEquals(
            setOf(WalkthroughEventActivation(4, 11)),
            getWalkthroughEventActivations(bytecode, 0, 4, 1),
        )
    }

    @Test
    fun preserves_selected_client_values_after_branches_merge() {
        val bytecode = bytecode("""
            0:
                get_slotnumber r20
                jmpi_= r20, 1, 100
                leti r10, 10
                jmp 200
            100:
                leti r10, 20
            200:
                setevt r10
                ret
        """)

        assertEquals(
            setOf(WalkthroughEventActivation(4, 10)),
            getWalkthroughEventActivations(bytecode, 0, 4, 0),
        )
        assertEquals(
            setOf(WalkthroughEventActivation(4, 20)),
            getWalkthroughEventActivations(bytecode, 0, 4, 1),
        )
    }

    @Test
    fun prunes_client_id_switches() {
        val bytecode = bytecode("""
            0:
                get_slotnumber r10
                switch_jmp r10, 100, 200, 300, 400
            100:
                setevt 10
                ret
            200:
                setevt 20
                ret
            300:
                setevt 30
                ret
            400:
                setevt 40
                ret
        """)

        assertEquals(
            setOf(WalkthroughEventActivation(4, 10)),
            analyzeWalkthroughScript(bytecode, 0, 4, 0).eventActivations,
        )
        assertEquals(
            setOf(WalkthroughEventActivation(4, 40)),
            analyzeWalkthroughScript(bytecode, 0, 4, 3).eventActivations,
        )
    }

    @Test
    fun keeps_runtime_dependent_branches_conservative() {
        val bytecode = bytecode("""
            0:
                jmpi_= r10, 1, 100
                setevt 20
                ret
            100:
                setevt 21
                ret
        """)

        assertEquals(
            setOf(
                WalkthroughEventActivation(5, 20),
                WalkthroughEventActivation(5, 21),
            ),
            getWalkthroughEventActivations(bytecode, 0, 5, 0),
        )
    }

    @Test
    fun keeps_finite_event_values_from_merged_runtime_branches() {
        val bytecode = bytecode("""
            0:
                jmpi_= r20, 1, 100
                leti r10, 10
                jmp 200
            100:
                leti r10, 20
            200:
                setevt r10
                ret
        """)

        assertEquals(
            setOf(
                WalkthroughEventActivation(5, 10),
                WalkthroughEventActivation(5, 20),
            ),
            getWalkthroughEventActivations(bytecode, 0, 5, 0),
        )
    }

    @Test
    fun keeps_old_and_new_handlers_when_the_target_floor_is_runtime_dependent() {
        val bytecode = bytecode("""
            0:
                set_floor_handler 1, 100
                jmpi_= r20, 1, 10
                leti r10, 1
                jmp 20
            10:
                leti r10, 2
            20:
                leti r11, 200
                set_floor_handler r10, r11
                ret
            100:
                setevt 10
                ret
            200:
                setevt 20
                ret
        """)

        assertEquals(
            setOf(
                WalkthroughEventActivation(1, 10),
                WalkthroughEventActivation(1, 20),
                WalkthroughEventActivation(2, 20),
            ),
            getWalkthroughEventActivations(bytecode, 0, 0, 0),
        )
    }

    @Test
    fun follows_floor_handlers_and_floor_scoped_threads() {
        val bytecode = bytecode("""
            0:
                set_floor_handler 6, 100
                ret
            100:
                thread_stg 200
                setevt 60
                ret
            200:
                setevt 61
                ret
        """)

        assertEquals(
            setOf(
                WalkthroughEventActivation(6, 60),
                WalkthroughEventActivation(6, 61),
            ),
            getWalkthroughEventActivations(bytecode, 0, 0, 0),
        )
    }

    @Test
    fun resolves_floor_handler_arguments_from_registers() {
        val bytecode = bytecode("""
            0:
                leti r10, 6
                leti r11, 100
                set_floor_handler r10, r11
                ret
            100:
                setevt 60
                ret
        """)

        assertEquals(
            setOf(WalkthroughEventActivation(6, 60)),
            getWalkthroughEventActivations(bytecode, 0, 0, 0),
        )
    }

    @Test
    fun jointly_prunes_floor_and_client_dispatch() {
        val bytecode = bytecode("""
            0:
                set_floor_handler 1, 100
                set_floor_handler 2, 100
                ret
            100:
                get_floor_number 0, r10
                switch_jmp r10, 900, 200, 300
            200:
                get_slotnumber r20
                jmpi_= r20, 1, 210
                setevt 11
                ret
            210:
                setevt 12
                ret
            300:
                get_slotnumber r30
                jmpi_= r30, 0, 310
                setevt 21
                ret
            310:
                setevt 22
                ret
            900:
                ret
        """)

        assertEquals(
            setOf(
                WalkthroughEventActivation(1, 11),
                WalkthroughEventActivation(2, 22),
            ),
            getWalkthroughEventActivations(bytecode, 0, 0, 0),
        )
        assertEquals(
            setOf(
                WalkthroughEventActivation(1, 12),
                WalkthroughEventActivation(2, 21),
            ),
            getWalkthroughEventActivations(bytecode, 0, 0, 1),
        )
    }

    @Test
    fun out_of_range_floor_switch_jumps_fall_through() {
        val bytecode = bytecode("""
            0:
                get_floor_number 0, r10
                switch_jmp r10, 100, 200
                setevt 30
                ret
            100:
                setevt 10
                ret
            200:
                setevt 20
                ret
        """)

        assertEquals(
            setOf(WalkthroughEventActivation(3, 30)),
            getWalkthroughEventActivations(bytecode, 0, 3, 0),
        )
    }

    @Test
    fun out_of_range_floor_switch_calls_resume_at_the_continuation() {
        val bytecode = bytecode("""
            0:
                get_floor_number 0, r10
                switch_call r10, 100, 200
                setevt 30
                ret
            100:
                setevt 10
                ret
            200:
                setevt 20
                ret
        """)

        assertEquals(
            setOf(WalkthroughEventActivation(3, 30)),
            getWalkthroughEventActivations(bytecode, 0, 3, 0),
        )
    }

    @Test
    fun uses_only_the_last_active_floor_handler() {
        val bytecode = bytecode("""
            0:
                set_floor_handler 6, 100
                set_floor_handler 6, 200
                set_floor_handler 7, 300
                clr_floor_handler 7
                ret
            100:
                setevt 10
                ret
            200:
                setevt 20
                ret
            300:
                setevt 30
                ret
        """)

        assertEquals(
            setOf(WalkthroughEventActivation(6, 20)),
            getWalkthroughEventActivations(bytecode, 0, 0, 0),
        )
    }

    @Test
    fun uses_only_active_lifecycle_callbacks() {
        val bytecode = bytecode("""
            0:
                set_qt_success 100
                set_qt_success 200
                set_qt_failure 300
                clr_qt_failure
                ret
            100:
                setevt 10
                ret
            200:
                setevt 20
                ret
            300:
                setevt 30
                ret
        """)

        assertEquals(
            setOf(WalkthroughEventActivation(0, 20)),
            getWalkthroughEventActivations(bytecode, 0, 0, 0),
        )
    }

    @Test
    fun follows_only_the_selected_players_palette_callback() {
        val bytecode = bytecode("""
            0:
                set_palettex_callback 0, 100
                set_palettex_callback 1, 200
                ret
            100:
                setevt 10
                ret
            200:
                setevt 20
                ret
        """)

        assertEquals(
            setOf(WalkthroughEventActivation(3, 10)),
            getWalkthroughEventActivations(bytecode, 0, 3, 0),
        )
        assertEquals(
            setOf(WalkthroughEventActivation(3, 20)),
            getWalkthroughEventActivations(bytecode, 0, 3, 1),
        )
    }

    @Test
    fun resolves_palette_callback_slots_from_registers() {
        val bytecode = bytecode("""
            0:
                leti r10, 1
                set_palettex_callback r10, 200
                ret
            200:
                setevt 20
                ret
        """)

        assertEquals(
            emptySet(),
            getWalkthroughEventActivations(bytecode, 0, 3, 0),
        )
        assertEquals(
            setOf(WalkthroughEventActivation(3, 20)),
            getWalkthroughEventActivations(bytecode, 0, 3, 1),
        )
    }

    @Test
    fun keeps_runtime_dependent_palette_callback_slots_conservative() {
        val bytecode = bytecode("""
            0:
                set_palettex_callback r10, 200
                ret
            200:
                setevt 20
                ret
        """)

        assertEquals(
            setOf(WalkthroughEventActivation(3, 20)),
            getWalkthroughEventActivations(bytecode, 0, 3, 0),
        )
        assertEquals(
            setOf(WalkthroughEventActivation(3, 20)),
            getWalkthroughEventActivations(bytecode, 0, 3, 1),
        )
    }

    private fun bytecode(assembly: String): BytecodeIr =
        BytecodeIr(toInstructions(assembly.trimIndent()))
}
