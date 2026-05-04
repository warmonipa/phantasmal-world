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
}
