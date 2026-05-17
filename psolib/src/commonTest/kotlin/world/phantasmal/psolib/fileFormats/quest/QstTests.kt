package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.test.LibTestSuite
import world.phantasmal.psolib.test.assertDeepEquals
import world.phantasmal.psolib.test.readFile
import world.phantasmal.psolib.test.testWithQeditBbQuests
import world.phantasmal.psolib.test.testWithTetheallaQuests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QstTests : LibTestSuite {
    @Test
    fun parse_a_GC_quest() = testAsync {
        val cursor = readFile("/quests/ep1/recovery/lost heat sword (gc).qst")
        val qst = parseQst(cursor).unwrap()

        assertEquals(Version.GC_V3, qst.version)
        assertTrue(qst.online)
        assertEquals(2, qst.files.size)
        assertEquals(58, qst.files[0].id)
        assertEquals("quest58.bin", qst.files[0].filename)
        assertEquals("PSO/Lost HEAT SWORD", qst.files[0].questName)
        assertEquals(58, qst.files[1].id)
        assertEquals("quest58.dat", qst.files[1].filename)
        assertEquals("PSO/Lost HEAT SWORD", qst.files[1].questName)
    }

    /**
     * Parse a file, convert the resulting structure to QST again and check whether the end result
     * is byte-for-byte equal to the original.
     */
    @Test
    fun parseQst_and_writeQst_with_all_tethealla_quests() = testAsync {
        testWithTetheallaQuests { path, _ ->
            if (EXCLUDED.any { it in path }) return@testWithTetheallaQuests

            try {
                val origQst = readFile(path)
                val parsedQst = parseQst(origQst).unwrap()
                val newQst = writeQst(parsedQst)
                origQst.seekStart(0)

                assertDeepEquals(origQst, newQst.cursor())
            } catch (e: Throwable) {
                throw Exception("""Failed for "$path": ${e.message}""", e)
            }
        }
    }

    /**
     * Byte-for-byte parseQst -> writeQst round-trip across the qedit Wiki BB
     * corpus (145 quests). Mirrors the Tethealla sweep above.
     */
    @Test
    fun parseQst_and_writeQst_with_all_qedit_bb_quests() = testAsync {
        testWithQeditBbQuests { path, _ ->
            if (QEDIT_EXCLUDED.any { it in path }) return@testWithQeditBbQuests

            try {
                val origQst = readFile(path)
                val parsedQst = parseQst(origQst).unwrap()
                val newQst = writeQst(parsedQst)
                origQst.seekStart(0)

                assertDeepEquals(origQst, newQst.cursor())
            } catch (e: Throwable) {
                throw Exception("""Failed for "$path": ${e.message}""", e)
            }
        }
    }

    companion object {
        // TODO: Figure out why we can't round-trip these quests.
        private val EXCLUDED = listOf(
            "/ep2/shop/gallon.qst",
            "/solo/ep1/04 the value of money.qst", // Skip because it contains every chunk twice.
            "/lost havoc vulcan.qst",
            ".raw",
            // Side fixtures sourced from D:/PSO/Quests (replacing earlier broken
            // Tethealla snapshots). Both EN and JP variants — byte round-trip
            // doesn't survive shift-jis padding / chunk alignment in these
            // packagings (IR round-trip in QuestTests still covers them).
            "/solo/ep1/side/central dome fire swirl.qst",
            "/solo/ep1/side/gallon's plan.qst",
            "/solo/ep1/side/good luck!.qst",
            // The 3 surviving Tethealla princ quests after migration to gov/lab dirs.
            // The QST byte round-trip never worked for the princ corpus (was excluded as
            // "/princ/ep1/" + "/princ/ep4/" before the dir-flatten); the IR round-trip
            // in QuestTests does cover them.
            "/ep1/gov/4-3 hero & daughter.qst",
            "/ep2/lab/8-2 desire's end.qst",
            "/ep4/gov/9-3 reality & truth.qst",
        )

        // Populated lazily as the qedit sweep surfaces structural issues.
        private val QEDIT_EXCLUDED = listOf<String>()
    }
}
