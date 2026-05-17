package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.core.Success
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.test.LibTestSuite
import world.phantasmal.psolib.test.readFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for two GC-quest round-trip bugs surfaced by sweeping
 * D:/PSO/Quests on 2026-05-17 (see project_quest_test_coverage_todo.md).
 */
class GcRoundTripRegressionTests : LibTestSuite {

    /**
     * 'PSO/Maximum Attack 4th Stage -1B-' is exactly 32 characters, so it fills the
     * QST-header name field with no padding, putting a printable char at byte 35.
     * The original DC_V2/GC_V3 disambiguator (Qst.kt) read byte 35 — content-dependent —
     * and mis-detected this GC quest as DC_V2. After writing back with the parsed
     * (shorter) name, byte 35 became padding 0 and the next read returned GC_V3,
     * flipping the version across round-trip. Fix: use byte 39 (qedit's approach;
     * structural — padding 0 in GC layout, filename first char in DC layout).
     */
    @Test
    fun parses_gc_quest_with_max_length_name_as_GC_V3() = testAsync {
        val cursor = readFile("/quests/gc_long_qst_name_misdetect.qst")
        val qst = parseQst(cursor).unwrap()
        assertEquals(Version.GC_V3, qst.version,
            "GC quest with 32-char header name must not be mis-detected as DC_V2")
    }

    @Test
    fun gc_quest_with_max_length_name_round_trips_version() = testAsync {
        val cursor = readFile("/quests/gc_long_qst_name_misdetect.qst")
        val r1 = parseQstToQuest(cursor, lenient = true)
        assertTrue(r1 is Success, "first parse failed")
        val q1 = r1.value

        val rewritten = writeQuestToQst(q1.quest, "gc_long_qst_name_misdetect.qst", q1.version, q1.online)
        val r2 = parseQstToQuest(rewritten.cursor(), lenient = true)
        assertTrue(r2 is Success, "second parse failed")

        assertEquals(q1.version, r2.value.version,
            "version must survive round-trip; expected stable GC_V3")
    }

    /**
     * 博士のVR has labels 252, 278, 404 that are referenced by thread_stg / jmpi_= and
     * reside in segments adjacent to label-less gaps. The original "label points into
     * merged parent segment" code path in Bytecode.kt:332-342 logs Severity.Info and
     * drops the label silently. Each round-trip loses more labels (trip 1: 3 labels,
     * trip 2: 2 more), eventually breaking thread_stg(278) into a dangling reference.
     */
    @Test
    fun referenced_labels_survive_round_trip_in_doctors_vr() = testAsync {
        val cursor = readFile("/quests/gc_label_loss_doctors_vr.qst")
        val r1 = parseQstToQuest(cursor, lenient = true)
        assertTrue(r1 is Success, "first parse failed")
        val q1 = r1.value
        val labels1 = q1.quest.bytecodeIr.segments.flatMapTo(mutableSetOf()) { it.labels }

        val rewritten = writeQuestToQst(q1.quest, "gc_label_loss_doctors_vr.qst", q1.version, q1.online)
        val r2 = parseQstToQuest(rewritten.cursor(), lenient = true)
        assertTrue(r2 is Success, "second parse failed")
        val labels2 = r2.value.quest.bytecodeIr.segments.flatMapTo(mutableSetOf()) { it.labels }

        val lost = labels1 - labels2
        assertTrue(lost.isEmpty(),
            "labels lost across round-trip: ${lost.sorted()}; thread_stg(278) and similar references would dangle")
    }
}
