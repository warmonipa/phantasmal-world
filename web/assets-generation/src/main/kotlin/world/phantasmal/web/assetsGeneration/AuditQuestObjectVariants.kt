package world.phantasmal.web.assetsGeneration

import java.io.File
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.compression.prs.prsDecompress
import world.phantasmal.psolib.fileFormats.quest.*

/** DAT inventory only: script-created entities and runtime model changes are not inferred. */
fun main(args: Array<String>) {
    val root = File(args.single())
    val output = File(root, "tmp/asset-audit").apply { mkdirs() }
    val sources = listOf(
        File(root, "web/assets-generation/src/main/resources/ephinea/ship-config/quest"),
        File(root, "web/src/jsMain/resources/assets/quests"),
    ).flatMap { base -> base.walkTopDown().filter { it.isFile && it.extension in setOf("qst", "dat") }.toList() }
        .sortedBy { it.path }
    File(output, "object-occurrences.tsv").bufferedWriter().use { rows ->
        File(output, "corpus.tsv").bufferedWriter().use { corpus ->
            rows.appendLine("source\ttype\ttype_id\tmodel\tfloor\tsection\tobject_index")
            corpus.appendLine("source\tstatus\tobjects\tdetail")
            for (file in sources) {
                val source = file.relativeTo(root).invariantSeparatorsPath
                try {
                    val bytes = Buffer.fromByteArray(file.readBytes())
                    val objects = if (file.extension == "qst") {
                        val qst = parseQst(bytes.cursor()).unwrap()
                        check(qst.version == Version.BB_V4) { "Unsupported corpus version ${qst.version}" }
                        val data = qst.files.last { it.filename.trim().lowercase().endsWith(".dat") }.data
                        parseDat(prsDecompress(data.cursor()).unwrap()).objs
                    } else {
                        // Lobby o.dat is a raw object set, without quest DAT section headers.
                        check(file.name.endsWith("o.dat") && bytes.size % OBJECT_BYTE_SIZE == 0)
                        val cursor = bytes.cursor()
                        List(bytes.size / OBJECT_BYTE_SIZE) { DatEntity(15, cursor.buffer(OBJECT_BYTE_SIZE)) }
                    }
                    objects.forEachIndexed { index, entry ->
                        val obj = QuestObject(entry.floorId, entry.data)
                        val model = if (obj.modelOffset >= 0) obj.model.toString() else "-"
                        rows.appendLine(listOf(source, obj.type.name, obj.typeId, model,
                            obj.floorId, obj.sectionId, index).joinToString("\t"))
                    }
                    corpus.appendLine("$source\tOK\t${objects.size}\t-")
                } catch (e: Exception) {
                    corpus.appendLine("$source\tERROR\t0\t${e.message?.replace('\n', ' ')?.replace('\t', ' ')}")
                }
            }
        }
    }
    println("Audited ${sources.size} corpus files; results: $output")
}
