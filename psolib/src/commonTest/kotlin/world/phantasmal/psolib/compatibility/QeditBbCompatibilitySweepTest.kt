package world.phantasmal.psolib.compatibility

import world.phantasmal.core.Success
import world.phantasmal.psolib.fileFormats.quest.parseQstToQuest
import world.phantasmal.psolib.test.LibTestSuite
import world.phantasmal.psolib.test.readFile
import world.phantasmal.psolib.test.testWithQeditBbQuests
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs [CompatibilityChecker] against every fixture in the qedit Wiki BB
 * corpus and asserts that no quest produces a parser-flagged invalid
 * instruction (`Instruction.valid == false`) — that is, every byte parses
 * cleanly into the opcode table.
 *
 * Compatibility-checker errors/warnings are merely tallied: this corpus
 * intentionally includes ports, unofficial translations, and quests with
 * known issues, so a per-problem hard-fail would be noise. The summary
 * line surfaces the count for awareness.
 */
class QeditBbCompatibilitySweepTest : LibTestSuite {

    @Test
    fun all_qedit_bb_quests_parse_clean_and_check_compat() = testAsync(slow = true) {
        val checker = CompatibilityChecker()
        var checked = 0
        var totalErrors = 0
        var totalWarnings = 0
        val parseFailures = mutableListOf<String>()
        val withInvalidInstr = mutableListOf<String>()

        testWithQeditBbQuests { path, _ ->
            val r = parseQstToQuest(readFile(path), lenient = true)
            if (r !is Success) {
                parseFailures += path
                return@testWithQeditBbQuests
            }
            val quest = r.value.quest
            val invalidCount = quest.bytecodeIr.segments
                .filterIsInstance<world.phantasmal.psolib.asm.InstructionSegment>()
                .sumOf { seg -> seg.instructions.count { !it.valid } }
            if (invalidCount > 0) {
                withInvalidInstr += "$path ($invalidCount)"
            }
            val result = checker.checkCompatibility(PSOVersion.BLUE_BURST, quest)
            totalErrors += result.errors.size
            totalWarnings += result.warnings.size
            checked++
        }

        println("qedit BB compat sweep: $checked quests parsed, $totalErrors errors, $totalWarnings warnings")
        assertTrue(parseFailures.isEmpty(), "parse failed: $parseFailures")
        assertTrue(
            withInvalidInstr.isEmpty(),
            "fixtures contain invalid instructions (parser couldn't decode some opcodes): $withInvalidInstr",
        )
    }
}
