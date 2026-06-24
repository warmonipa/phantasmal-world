package world.phantasmal.web.assetsGeneration

import mu.KotlinLogging
import world.phantasmal.core.Failure
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.compression.prs.prsDecompress
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.fileFormats.parseBml
import world.phantasmal.psolib.fileFormats.parseGsl
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Mapping from GSL entry name to a list of (BML entry index, output NpcType name) pairs.
 *
 * PSO BB's data.gsl contains BML archives, each of which bundles one or more models. The BML
 * entry index selects which model within the BML to extract. The output name determines the
 * filename written to the assets directory (e.g. "Migium" -> Migium.nj + Migium.xvm).
 */
private data class BmlModelMapping(
    val gslEntryName: String,
    /** Index of the model entry within the BML archive. */
    val bmlEntryIndex: Int,
    /** NpcType name used as the output filename. */
    val outputName: String,
    /** File extension for the geometry file. */
    val geomExtension: String = "nj",
)

/**
 * Mapping for extracting animation (.njm) files from BML archives.
 */
private data class BmlAnimMapping(
    val gslEntryName: String,
    /** Index of the animation entry within the BML archive. */
    val bmlEntryIndex: Int,
    /** Output filename (e.g. "PanArms_wait.njm"). */
    val outputFilename: String,
)

/**
 * Known mappings from data.gsl BML entries to NpcType names.
 *
 * These were determined by inspecting the GSL entry listing and parsing each BML to see internal
 * entry names. BML files bundle PRS-compressed model (.nj/.xj) and texture (.xvm) data.
 */
private val MODEL_MAPPINGS = listOf(
    // Recobox spawned enemy — inside the Recobox BML
    // [0] me7_all.nj = Recon (frog-like robot creature released by Recobox)
    // [1] me7_box_all.nj = Recobox (box form)
    // [2] bomb_body.nj = bomb projectile
    BmlModelMapping("bm_ene_recobox.bml", 0, "Recon"),

    // Dark Gunner family — bm_ene_darkgunner.bml
    // [0] re5_b_cly_cy_line.nj (cylinder effect)
    // [1] re5_b_gunner_body.nj (Dark/Death Gunner body)
    // [2] re5_b_rcly_cy_line.nj (reverse cylinder - DarkGunnerCenter pillar)
    BmlModelMapping("bm_ene_darkgunner.bml", 1, "DeathGunner"),
    BmlModelMapping("bm_ene_darkgunner.bml", 0, "DarkGunnerCenter"),

    // Seasonal Rappies — each in their own BML
    // Entry [0] = generic Rappy base, entry [1] = seasonal variant model
    BmlModelMapping("bm_ene_lappy_xs.bml", 1, "StRappy"),
    BmlModelMapping("bm_ene_lappy_hw.bml", 1, "HalloRappy"),
    BmlModelMapping("bm_ene_lappy_es.bml", 1, "EggRappy"),

    // Vol Opt parts — inside bm_boss3_volopt.bml
    // [17] me5p01_y_all.nj = Vol Opt Phase 1
    // [19] me5p02_y_all.nj = Vol Opt Phase 2 main body
    // [24] me5_y_all.nj = Vol Opt combined/core
    // [16] fs_obj_hiraishin_a.nj = Lightning rod (避雷針)
    // Monitor entries [1-12] are XJ format display/screen objects
    BmlModelMapping("bm_boss3_volopt.bml", 17, "VolOptPart1"),
    BmlModelMapping("bm_boss3_volopt.bml", 20, "VolOptPart1Sub"),
    BmlModelMapping("bm_boss3_volopt.bml", 19, "VolOptCore"),
    BmlModelMapping("bm_boss3_volopt.bml", 1, "VolOptMonitor", geomExtension = "xj"),
    BmlModelMapping("bm_boss3_volopt.bml", 16, "VolOptHiraisin"),
)

/**
 * Ultimate-difficulty enemy skins. PSO ships recolored Ultimate variants of some enemies in
 * `_a` / `_u` suffixed BML archives in data.gsl. Each is extracted as `<Name>.ult.nj` +
 * `<Name>.ult.xvm`, loaded by the quest editor when the Ultimate toggle is on (entities not
 * listed here keep their normal skin).
 *
 * Each (BML, entry index) was determined by fingerprinting the normal extracted `<Name>.xvm`
 * texture-id set against the matching normal BML entry; the parallel `_a`/`_u` BML reuses the
 * same entry index. Several enemies share a source entry (e.g. Poison/Nar Lily, the Sinows).
 */
private val ULTIMATE_MODEL_MAPPINGS = listOf(
    // Sharks — bm_ene_bm1_shark_a.bml
    BmlModelMapping("bm_ene_bm1_shark_a.bml", 0, "PalShark.ult"),
    BmlModelMapping("bm_ene_bm1_shark_a.bml", 1, "GuilShark.ult"),
    BmlModelMapping("bm_ene_bm1_shark_a.bml", 2, "EvilShark.ult"),

    // Hildebear family — bm_ene_bm2_moja_a.bml
    BmlModelMapping("bm_ene_bm2_moja_a.bml", 1, "Hildebear.ult"),
    BmlModelMapping("bm_ene_bm2_moja_a.bml", 2, "Hildeblue.ult"),

    // Mothmant — bm_ene_bm3_fly_a.bml (entry 1, the Monest nest, has no texture in the Ultimate
    // archive, so Monest keeps its normal skin).
    BmlModelMapping("bm_ene_bm3_fly_a.bml", 0, "Mothmant.ult"),

    // Grass Assassin — bm_ene_grass_a.bml
    BmlModelMapping("bm_ene_grass_a.bml", 0, "GrassAssassin.ult"),

    // Chaos Bringer — bm_ene_df2_bringer_a.bml
    BmlModelMapping("bm_ene_df2_bringer_a.bml", 0, "ChaosBringer.ult"),

    // Dimenian family — bm_ene_df3_dimedian_a.bml
    BmlModelMapping("bm_ene_df3_dimedian_a.bml", 0, "SoDimenian.ult"),
    BmlModelMapping("bm_ene_df3_dimedian_a.bml", 1, "LaDimenian.ult"),
    BmlModelMapping("bm_ene_df3_dimedian_a.bml", 2, "Dimenian.ult"),

    // Dubchic — bm_ene_dubchik_a.bml
    BmlModelMapping("bm_ene_dubchik_a.bml", 0, "Dubchic.ult"),

    // Garanz — bm_ene_gyaranzo_a.bml (entry 3 is the body; others are fragments/projectiles)
    BmlModelMapping("bm_ene_gyaranzo_a.bml", 3, "Garanz.ult"),

    // Canadine — bm_ene_me1_mb_a.bml (entry 0 'me1n' is a textureless variant; entry 1 carries
    // the recolored body+texture).
    BmlModelMapping("bm_ene_me1_mb_a.bml", 1, "Canadine.ult"),

    // Sinow Berill / Spigell share the EP2 sinow body — bm_ene_me3_shinowa_a.bml
    BmlModelMapping("bm_ene_me3_shinowa_a.bml", 0, "SinowBerill.ult"),
    BmlModelMapping("bm_ene_me3_shinowa_a.bml", 0, "SinowSpigell.ult"),

    // Poison Lily / Nar Lily share the flower body — bm_ene_re2_flower_a.bml
    BmlModelMapping("bm_ene_re2_flower_a.bml", 0, "PoisonLily.ult"),
    BmlModelMapping("bm_ene_re2_flower_a.bml", 0, "NarLily.ult"),

    // Chaos Sorcerer — bm_ene_re4_sorcerer_a.bml
    BmlModelMapping("bm_ene_re4_sorcerer_a.bml", 0, "ChaosSorcerer.ult"),

    // Dark Belra — bm_ene_re7_berura_a.bml
    BmlModelMapping("bm_ene_re7_berura_a.bml", 0, "DarkBelra.ult"),

    // Booma family — bm_ene_re8_b_beast_a.bml
    BmlModelMapping("bm_ene_re8_b_beast_a.bml", 0, "Booma.ult"),
    BmlModelMapping("bm_ene_re8_b_beast_a.bml", 1, "Gigobooma.ult"),
    BmlModelMapping("bm_ene_re8_b_beast_a.bml", 2, "Gobooma.ult"),
)

/**
 * Animation mappings. BML animation entries contain only model data (PRS-compressed NJM),
 * no texture data.
 */
private val ANIM_MAPPINGS = listOf(
    // PanArms animations (bone animations for ps_ma_body skeleton)
    BmlAnimMapping("bm4_ps_ma_body.bml", 7, "PanArms_apper.njm"),
    BmlAnimMapping("bm4_ps_ma_body.bml", 8, "PanArms_atack.njm"),
    BmlAnimMapping("bm4_ps_ma_body.bml", 9, "PanArms_damage.njm"),
    BmlAnimMapping("bm4_ps_ma_body.bml", 10, "PanArms_kie.njm"),
    BmlAnimMapping("bm4_ps_ma_body.bml", 14, "PanArms_nodam.njm"),
    BmlAnimMapping("bm4_ps_ma_body.bml", 16, "PanArms_wait2.njm"),
    BmlAnimMapping("bm4_ps_ma_body.bml", 17, "PanArms_wait.njm"),
)

/**
 * Try to PRS-decompress a buffer. If decompression fails, return the raw data as-is
 * (some BML entries store uncompressed data).
 */
private fun tryDecompress(data: Buffer): ByteArray {
    val result = prsDecompress(data.cursor())

    return if (result is Failure) {
        logger.info { "    PRS decompression failed, using raw data" }
        data.byteArray.copyOf(data.size)
    } else {
        val cursor = result.unwrap()
        cursor.buffer().let { buf -> buf.byteArray.copyOf(buf.size) }
    }
}

/**
 * Extracts NPC models from PSO BB game data (data.gsl) and writes them as .nj/.xvm files.
 *
 * Usage: ExtractNpcModels <pso-data-dir> <output-dir>
 *   pso-data-dir: Path to PSO BB data directory (e.g. D:/PSO/EphineaPSO2/data)
 *   output-dir:   Path to write extracted assets (e.g. web/src/jsMain/resources/assets/npcs)
 */
fun main(args: Array<String>) {
    require(args.size >= 2) {
        "Usage: ExtractNpcModels <pso-data-dir> <output-dir>"
    }

    val dataDir = File(args[0])
    val outputDir = File(args[1])

    require(dataDir.isDirectory) { "PSO data directory not found: $dataDir" }
    outputDir.mkdirs()

    val gslFile = File(dataDir, "data.gsl")
    require(gslFile.isFile) { "data.gsl not found: $gslFile" }

    logger.info { "Parsing data.gsl from $gslFile..." }
    val gslData = Buffer.fromByteArray(gslFile.readBytes())
    val gslEntries = parseGsl(gslData.cursor()).unwrap()

    // List all GSL entries for inspection.
    logger.info { "GSL entries (${gslEntries.size} total):" }
    for (entry in gslEntries) {
        logger.info { "  ${entry.name} (${entry.data.size} bytes)" }
    }

    // Build a lookup from GSL entry name to GSL entry.
    val gslEntryByName = gslEntries.associateBy { it.name }

    // Group mappings by GSL entry name to avoid parsing the same BML multiple times.
    val mappingsByGslEntry = (MODEL_MAPPINGS + ULTIMATE_MODEL_MAPPINGS).groupBy { it.gslEntryName }

    var extracted = 0
    var failed = 0

    for ((gslEntryName, mappings) in mappingsByGslEntry) {
        val gslEntry = gslEntryByName[gslEntryName]

        if (gslEntry == null) {
            logger.warn { "GSL entry '$gslEntryName' not found in data.gsl — skipping ${mappings.size} mapping(s)" }
            failed += mappings.size
            continue
        }

        logger.info { "Parsing BML: $gslEntryName (${gslEntry.data.size} bytes)..." }
        val bmlEntries = parseBml(gslEntry.data.cursor()).unwrap()
        logger.info { "  BML contains ${bmlEntries.size} entries:" }

        for ((i, bmlEntry) in bmlEntries.withIndex()) {
            logger.info { "    [$i] ${bmlEntry.name} (model: ${bmlEntry.data.size} bytes, texture: ${bmlEntry.textureData.size} bytes)" }
        }

        for (mapping in mappings) {
            if (mapping.bmlEntryIndex >= bmlEntries.size) {
                logger.warn { "  BML entry index ${mapping.bmlEntryIndex} out of range for $gslEntryName (has ${bmlEntries.size} entries) — skipping ${mapping.outputName}" }
                failed++
                continue
            }

            val bmlEntry = bmlEntries[mapping.bmlEntryIndex]
            logger.info { "  Extracting ${mapping.outputName} from BML entry [${mapping.bmlEntryIndex}] '${bmlEntry.name}'..." }

            try {
                // Decompress and write model data.
                val modelBytes = tryDecompress(bmlEntry.data)
                val geomFile = File(outputDir, "${mapping.outputName}.${mapping.geomExtension}")
                geomFile.writeBytes(modelBytes)
                logger.info { "    Wrote ${geomFile.name} (${modelBytes.size} bytes)" }

                // Decompress and write texture data (.xvm) if present.
                if (bmlEntry.textureData.size > 0) {
                    val textureBytes = tryDecompress(bmlEntry.textureData)
                    val xvmFile = File(outputDir, "${mapping.outputName}.xvm")
                    xvmFile.writeBytes(textureBytes)
                    logger.info { "    Wrote ${xvmFile.name} (${textureBytes.size} bytes)" }
                } else {
                    logger.info { "    No texture data for ${mapping.outputName}" }
                }

                extracted++
            } catch (e: Exception) {
                logger.error(e) { "  Failed to extract ${mapping.outputName}" }
                failed++
            }
        }
    }

    // Extract animations from BML archives.
    val animMappingsByGslEntry = ANIM_MAPPINGS.groupBy { it.gslEntryName }

    for ((gslEntryName, mappings) in animMappingsByGslEntry) {
        val gslEntry = gslEntryByName[gslEntryName]

        if (gslEntry == null) {
            logger.warn { "GSL entry '$gslEntryName' not found in data.gsl — skipping ${mappings.size} animation(s)" }
            failed += mappings.size
            continue
        }

        logger.info { "Parsing BML for animations: $gslEntryName..." }
        val bmlEntries = parseBml(gslEntry.data.cursor()).unwrap()

        for (mapping in mappings) {
            if (mapping.bmlEntryIndex >= bmlEntries.size) {
                logger.warn { "  BML entry index ${mapping.bmlEntryIndex} out of range — skipping ${mapping.outputFilename}" }
                failed++
                continue
            }

            val bmlEntry = bmlEntries[mapping.bmlEntryIndex]
            logger.info { "  Extracting ${mapping.outputFilename} from BML entry [${mapping.bmlEntryIndex}] '${bmlEntry.name}'..." }

            try {
                val animBytes = tryDecompress(bmlEntry.data)
                val animFile = File(outputDir, mapping.outputFilename)
                animFile.writeBytes(animBytes)
                logger.info { "    Wrote ${animFile.name} (${animBytes.size} bytes)" }
                extracted++
            } catch (e: Exception) {
                logger.error(e) { "  Failed to extract ${mapping.outputFilename}" }
                failed++
            }
        }
    }

    logger.info { "Done. Extracted $extracted model(s) and animation(s), $failed failure(s)." }
}
