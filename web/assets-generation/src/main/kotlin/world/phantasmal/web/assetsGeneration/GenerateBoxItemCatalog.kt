package world.phantasmal.web.assetsGeneration

import java.io.File
import world.phantasmal.core.removeAt
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.compression.prs.prsDecompress
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.fileFormats.parseItemPmt
import world.phantasmal.psolib.fileFormats.parseRel
import world.phantasmal.psolib.fileFormats.parseUnitxt

/** Named BB item codes, including Mags and technique disks absent from the hunt catalog. */
fun main(args: Array<String>) {
    require(args.size == 1)
    val names = parseUnitxt(prsDecompress(Buffer.fromResource(
        "/ephinea/client/data/unitxt_j.prs").cursor()).unwrap()).categories[1].toMutableList().apply {
        removeAt(177, 50)
        removeAt(639, 59)
    }
    val buffer = Buffer.fromResource("/ephinea/ship-config/param/ItemPMT.bin")
    val pmt = parseItemPmt(buffer.cursor())
    val entries = sortedMapOf<Int, String>()
    fun add(code: Int, nameId: Int) {
        val name = names[nameId]
        if (name.isNotBlank() && name != "????") entries[code] = name
    }
    pmt.weapons.forEachIndexed { kind, items -> items.forEachIndexed { subtype, item ->
        if (kind != 0) add((kind shl 8) or subtype, item.id)
    } }
    pmt.frames.forEachIndexed { subtype, item -> add(0x010100 or subtype, item.id) }
    pmt.barriers.forEachIndexed { subtype, item -> add(0x010200 or subtype, item.id) }
    pmt.units.forEachIndexed { subtype, item -> add(0x010300 or subtype, item.id) }
    pmt.tools.forEachIndexed { kind, items -> items.forEachIndexed { subtype, item ->
        if (kind != 2) add(0x030000 or (kind shl 8) or subtype, item.id)
    } }
    // BB MagV4 records are 0x1C bytes; REL index 10 is the Mag count/reference.
    val cursor = buffer.cursor()
    val mags = parseRel(cursor, parseIndex = true).index[10]
    repeat(mags.size) { kind ->
        cursor.seekStart(mags.offset + kind * 0x1C)
        add(0x020000 or (kind shl 8), cursor.int())
    }
    listOf("Foie", "Gifoie", "Rafoie", "Barta", "Gibarta", "Rabarta", "Zonde", "Gizonde",
        "Razonde", "Grants", "Deband", "Jellen", "Zalure", "Shifta", "Ryuker", "Resta",
        "Anti", "Reverser", "Megid").forEachIndexed { i, name -> entries[0x030200 or i] = "$name disk" }
    entries[0x040000] = "Meseta"
    File(args.single()).apply {
        parentFile.mkdirs()
        writeText(buildString {
            appendLine("package world.phantasmal.web.questEditor.models")
            appendLine()
            appendLine("// Generated from bundled BB ItemPMT and Unitxt by generateBoxItemCatalog.")
            appendLine("internal val BOX_ITEM_NAMES: Map<Int, String> = mapOf(")
            entries.forEach { (code, name) ->
                val quoted = name.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
                appendLine("    0x${code.toString(16).uppercase().padStart(6, '0')} to \"$quoted\",")
            }
            appendLine(")")
        })
    }
}
