package world.phantasmal.psolib.asm.dataFlowAnalysis

import world.phantasmal.psolib.test.LibTestSuite
import world.phantasmal.psolib.test.toInstructions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GetParticleSpawnsTests : LibTestSuite {
    @Test
    fun particle_inside_floor_handler_is_attributed_to_that_floor() {
        val segments = toInstructions("""
            0:
                set_floor_handler 5, 100
                ret
            100:
                leti r0, 1000
                leti r1, 0
                leti r2, 2000
                leti r3, 42
                leti r4, 60
                particle_v3 r0
                ret
        """.trimIndent())

        val spawns = getParticleSpawns(segments) { ControlFlowGraph.create(segments) }

        assertEquals(1, spawns.size)
        val spawn = spawns[0]
        assertEquals(1000, spawn.x)
        assertEquals(0, spawn.y)
        assertEquals(2000, spawn.z)
        assertEquals(42, spawn.particleId)
        assertEquals(60, spawn.frames)
        assertEquals(setOf(5), spawn.floorIds)
    }

    @Test
    fun particle_inside_at_coords_call_callback_is_attributed_to_registration_floor() {
        // Floor 7's handler registers at_coords_call with label 200 as the body. Because
        // the trigger geometry (XYZ + radius) is created on the registration floor, the
        // body fires only on that floor — so the spawn must inherit floor 7.
        val segments = toInstructions("""
            0:
                set_floor_handler 7, 100
                ret
            100:
                leti r0, 1000
                leti r1, 0
                leti r2, 2000
                leti r3, 50
                leti r4, 200
                at_coords_call r0
                ret
            200:
                leti r10, 5000
                leti r11, 0
                leti r12, 6000
                leti r13, 1
                leti r14, 30
                particle_v3 r10
                ret
        """.trimIndent())

        val spawns = getParticleSpawns(segments) { ControlFlowGraph.create(segments) }

        assertEquals(1, spawns.size)
        assertEquals(setOf(7), spawns[0].floorIds)
    }

    @Test
    fun particle_inside_at_coords_talk_callback_is_attributed_to_registration_floor() {
        // Same expectation as at_coords_call: at_coords_talk's trigger is per-floor.
        val segments = toInstructions("""
            0:
                set_floor_handler 4, 100
                ret
            100:
                leti r0, 1000
                leti r1, 0
                leti r2, 2000
                leti r3, 50
                leti r4, 250
                at_coords_talk r0
                ret
            250:
                leti r10, 5000
                leti r11, 0
                leti r12, 6000
                leti r13, 1
                leti r14, 30
                particle_v3 r10
                ret
        """.trimIndent())

        val spawns = getParticleSpawns(segments) { ControlFlowGraph.create(segments) }

        assertEquals(1, spawns.size)
        assertEquals(setOf(4), spawns[0].floorIds)
    }

    @Test
    fun particle_inside_set_qt_success_callback_is_not_attributed_to_registration_floor() {
        // set_qt_success bodies fire at the Hunter's Guild on Pioneer 2, not on the floor
        // where the registration happened. We deliberately do NOT propagate floor tags
        // through this edge, so the spawn ends up with empty floorIds (and the renderer's
        // "show everywhere" fallback takes over).
        val segments = toInstructions("""
            0:
                set_floor_handler 11, 100
                ret
            100:
                set_qt_success 300
                ret
            300:
                leti r10, 100
                leti r11, 0
                leti r12, 200
                leti r13, 1
                leti r14, 30
                particle_v3 r10
                ret
        """.trimIndent())

        val spawns = getParticleSpawns(segments) { ControlFlowGraph.create(segments) }

        assertEquals(1, spawns.size)
        assertTrue(
            spawns[0].floorIds.isEmpty(),
            "Expected empty floorIds (no propagation via set_qt_success), got ${spawns[0].floorIds}",
        )
    }

    @Test
    fun set_floor_handler_in_nested_segment_is_recognized() {
        // Quests don't have to register handlers from label 0. Label 0 calls a helper
        // that does the registration; the scan must cover all segments to discover it.
        val segments = toInstructions("""
            0:
                call 500
                ret
            500:
                set_floor_handler 9, 100
                ret
            100:
                leti r0, 1000
                leti r1, 0
                leti r2, 2000
                leti r3, 1
                leti r4, 30
                particle_v3 r0
                ret
        """.trimIndent())

        val spawns = getParticleSpawns(segments) { ControlFlowGraph.create(segments) }

        assertEquals(1, spawns.size)
        assertEquals(setOf(9), spawns[0].floorIds)
    }

    @Test
    fun particle_inside_thread_callback_is_attributed_to_registration_floor() {
        // Floor 4's handler starts a thread whose body spawns a particle. Threads outlive
        // the handler that started them, but the registration site is the strongest signal
        // of authorial intent we have, so the spawn inherits floor 4.
        val segments = toInstructions("""
            0:
                set_floor_handler 4, 100
                ret
            100:
                thread_stg 200
                ret
            200:
                leti r0, 1000
                leti r1, 0
                leti r2, 2000
                leti r3, 1
                leti r4, 30
                particle_v3 r0
                ret
        """.trimIndent())

        val spawns = getParticleSpawns(segments) { ControlFlowGraph.create(segments) }

        assertEquals(1, spawns.size)
        assertEquals(setOf(4), spawns[0].floorIds)
    }

    @Test
    fun shared_handler_label_propagates_all_registered_floors() {
        // Many quests share one handler across multiple floors via repeated set_floor_handler
        // calls (e.g. an "ambience" handler used for floors 1..3). All registered floors must
        // be propagated to particles reachable from that handler — not just the last one
        // written.
        val segments = toInstructions("""
            0:
                set_floor_handler 1, 100
                set_floor_handler 2, 100
                set_floor_handler 3, 100
                ret
            100:
                leti r0, 1000
                leti r1, 0
                leti r2, 2000
                leti r3, 1
                leti r4, 30
                particle_v3 r0
                ret
        """.trimIndent())

        val spawns = getParticleSpawns(segments) { ControlFlowGraph.create(segments) }

        assertEquals(1, spawns.size)
        assertEquals(setOf(1, 2, 3), spawns[0].floorIds)
    }

    @Test
    fun shared_helper_does_not_pollute_floors_through_callee_return_edges() {
        // Floor 1 calls helper L500 then runs floor-1-specific code (containing a particle).
        // Floor 2 calls L500 then runs floor-2-specific code (containing a different particle).
        // The CFG's linkReturningBlocks would normally let BFS from floor 1's handler walk
        // through L500's ret block back into floor 2's after-call code; the analysis must
        // exclude those edges and use the call site's own segment-sequential successor.
        val segments = toInstructions("""
            0:
                set_floor_handler 1, 100
                set_floor_handler 2, 200
                ret
            100:
                call 500
                leti r0, 1000
                leti r1, 0
                leti r2, 2000
                leti r3, 1
                leti r4, 30
                particle_v3 r0
                ret
            200:
                call 500
                leti r10, 5000
                leti r11, 0
                leti r12, 6000
                leti r13, 2
                leti r14, 60
                particle_v3 r10
                ret
            500:
                ret
        """.trimIndent())

        val spawns = getParticleSpawns(segments) { ControlFlowGraph.create(segments) }

        assertEquals(2, spawns.size)
        val floor1Spawn = spawns.firstOrNull { it.x == 1000 }
        assertEquals(setOf(1), floor1Spawn?.floorIds, "Floor 1 spawn should not be tagged with floor 2")
        val floor2Spawn = spawns.firstOrNull { it.x == 5000 }
        assertEquals(setOf(2), floor2Spawn?.floorIds, "Floor 2 spawn should not be tagged with floor 1")
    }

    @Test
    fun particle_reachable_from_two_floor_handlers_gets_union_of_floors() {
        // A helper called from two different floor handlers should be tagged with both
        // floors — at runtime the helper can fire on either floor.
        val segments = toInstructions("""
            0:
                set_floor_handler 2, 100
                set_floor_handler 5, 200
                ret
            100:
                call 999
                ret
            200:
                call 999
                ret
            999:
                leti r0, 1000
                leti r1, 0
                leti r2, 2000
                leti r3, 1
                leti r4, 30
                particle_v3 r0
                ret
        """.trimIndent())

        val spawns = getParticleSpawns(segments) { ControlFlowGraph.create(segments) }

        assertEquals(1, spawns.size)
        assertEquals(setOf(2, 5), spawns[0].floorIds)
    }

    @Test
    fun particle_in_label_0_init_code_has_empty_floorIds() {
        // Label 0 runs once at quest start, before any floor handler. A particle there
        // can't be tagged with a specific floor.
        val segments = toInstructions("""
            0:
                set_floor_handler 3, 100
                leti r0, 1000
                leti r1, 0
                leti r2, 2000
                leti r3, 1
                leti r4, 30
                particle_v3 r0
                ret
            100:
                ret
        """.trimIndent())

        val spawns = getParticleSpawns(segments) { ControlFlowGraph.create(segments) }

        assertEquals(1, spawns.size)
        assertTrue(
            spawns[0].floorIds.isEmpty(),
            "Particle in label-0 init code should have empty floorIds, got ${spawns[0].floorIds}",
        )
    }
}
