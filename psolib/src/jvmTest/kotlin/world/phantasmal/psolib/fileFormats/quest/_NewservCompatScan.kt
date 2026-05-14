package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.core.Failure
import world.phantasmal.core.Severity
import world.phantasmal.core.Success
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.compression.prs.prsDecompress
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.test.LibTestSuite
import java.io.File
import kotlin.test.Test

/**
 * One-shot compatibility sweep over the newserv quest archive.
 *
 * NOT a regression test — kept under `_` prefix so it doesn't run unless invoked explicitly.
 * Delete after the compatibility pass is done.
 */
class _NewservCompatScan : LibTestSuite {

    private data class PairKey(val dir: File, val stem: String, val lang: String)

    private data class Outcome(
        val key: PairKey,
        val binSize: Int,
        val datSize: Int,
        val status: String,         // PASS / WARN / FAIL / SKIP
        val detectedVersion: Version?,
        val invalidCount: Int,
        val unknownCount: Int,
        val problemSummary: String,
        val firstError: String? = null,
    )

    @Test
    fun scan() = testAsync {
        val root = File("/Users/wangzhen/study/newserv/system/quests")
        require(root.exists()) { "newserv root not found: $root" }

        // Find bin/dat pairs. Naming patterns:
        //   q058-gc-j.bin + q058-gc.dat      (lang-suffixed bin, lang-less dat)
        //   q058-gc-e.bin + q058-gc.dat
        //   q058-bb.bin   + q058-bb.dat      (no lang)
        // For each bin, find its sibling dat by stripping the language suffix.

        val bins = mutableListOf<File>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".bin") }.forEach { bins.add(it) }
        bins.sort()

        val outcomes = mutableListOf<Outcome>()
        var totalPairs = 0
        var noPair = 0

        for (binFile in bins) {
            val name = binFile.name.removeSuffix(".bin")
            // Examples: q058-gc-j → stem q058-gc, lang j
            //           q058-bb   → stem q058-bb, lang ""
            val lastDash = name.lastIndexOf('-')
            val (stem, lang) = if (lastDash > 0 && name.length - lastDash <= 3) {
                val maybeLang = name.substring(lastDash + 1)
                if (maybeLang.length in 1..2 && maybeLang.all { it.isLetterOrDigit() }) {
                    name.substring(0, lastDash) to maybeLang
                } else name to ""
            } else name to ""

            // Try lang-less dat first (common: q058-gc.dat), then lang-suffixed dat (q411-bb-e.dat).
            val datFile = listOf(
                File(binFile.parentFile, "$stem.dat"),
                File(binFile.parentFile, "$stem${if (lang.isEmpty()) "" else "-$lang"}.dat"),
            ).firstOrNull { it.exists() }
            val key = PairKey(binFile.parentFile, stem, lang)

            if (datFile == null) {
                noPair++
                outcomes.add(Outcome(key, binFile.length().toInt(), 0, "SKIP", null, 0, 0,
                    "no matching .dat (looked for $stem.dat and $stem-$lang.dat)"))
                continue
            }

            totalPairs++

            val outcome = try {
                val binBytes = binFile.readBytes()
                val datBytes = datFile.readBytes()
                val shiftJis = lang == "j"

                val r = parseBinDatToQuestAutoDetect(
                    Buffer.fromByteArray(binBytes).cursor(),
                    Buffer.fromByteArray(datBytes).cursor(),
                    lenient = false,
                    shiftJis = shiftJis,
                )

                when (r) {
                    is Success -> {
                        val q = r.value.quest
                        val invalid = q.bytecodeIr.instructionSegments()
                            .sumOf { seg -> seg.instructions.count { !it.valid } }
                        val unknown = q.bytecodeIr.instructionSegments()
                            .sumOf { seg -> seg.instructions.count { it.opcode.mnemonic.startsWith("unknown_") } }
                        val nonInfo = r.problems.filter { it.severity != Severity.Info }
                        val warnings = nonInfo.filter { it.severity == Severity.Warning }
                        val status = when {
                            invalid > 0 -> "WARN-INVALID"
                            unknown > 0 -> "WARN-UNKNOWN"
                            warnings.isNotEmpty() -> "WARN-PROBLEMS"
                            else -> "PASS"
                        }
                        Outcome(key, binBytes.size, datBytes.size, status, q.version, invalid, unknown,
                            warnings.joinToString("; ") { (it.message ?: "").take(80) })
                    }
                    is Failure -> {
                        val firstErr = r.problems.firstOrNull { it.severity == Severity.Error }
                            ?.let { (it.message ?: "<null>").take(160) }
                            ?: "no error problem"
                        Outcome(key, binBytes.size, datBytes.size, "FAIL", null, 0, 0,
                            r.problems.joinToString("; ") { (it.message ?: "").take(80) },
                            firstError = firstErr)
                    }
                }
            } catch (e: Throwable) {
                Outcome(key, binFile.length().toInt(), datFile.length().toInt(), "EXCEPTION",
                    null, 0, 0, "<exception>", firstError = "${e::class.simpleName}: ${e.message?.take(160)}")
            }

            outcomes.add(outcome)
        }

        // Categorize.
        val byStatus = outcomes.groupBy { it.status }
        println("=== newserv compat scan ===")
        println("total bin files: ${bins.size}")
        println("pairs scanned: $totalPairs")
        println("no matching dat: $noPair")
        println()
        for ((status, items) in byStatus.toSortedMap()) {
            println("$status: ${items.size}")
        }
        println()

        // Detailed: every non-PASS (excluding SKIP) with first 80 chars of failure.
        println("=== non-PASS samples (excluding SKIP) ===")
        for (oc in outcomes.filter { it.status != "PASS" && it.status != "SKIP" }.take(60)) {
            val rel = oc.key.dir.absolutePath.removePrefix("/Users/wangzhen/study/newserv/system/quests/")
            println(" [${oc.status}] $rel/${oc.key.stem}-${oc.key.lang} bin=${oc.binSize} dat=${oc.datSize}")
            println("   detected=${oc.detectedVersion} invalid=${oc.invalidCount} unknown=${oc.unknownCount}")
            if (oc.firstError != null) println("   err: ${oc.firstError}")
            if (oc.problemSummary.isNotBlank()) println("   problems: ${oc.problemSummary.take(160)}")
        }
        println()
        println("(total non-PASS shown: ${outcomes.count { it.status != "PASS" && it.status != "SKIP" }})")

        // Write full CSV for later inspection.
        val csv = File("/tmp/newserv-compat-scan.csv")
        csv.bufferedWriter().use { w ->
            w.write("status,dir,stem,lang,binSize,datSize,detectedVersion,invalidCount,unknownCount,firstError\n")
            for (oc in outcomes) {
                val rel = oc.key.dir.absolutePath.removePrefix("/Users/wangzhen/study/newserv/system/quests/")
                w.write("${oc.status},$rel,${oc.key.stem},${oc.key.lang},${oc.binSize},${oc.datSize},${oc.detectedVersion}," +
                    "${oc.invalidCount},${oc.unknownCount},\"${(oc.firstError ?: "").replace("\"", "'")}\"\n")
            }
        }
        println("wrote full CSV: ${csv.absolutePath}")
    }
}
