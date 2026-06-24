package world.phantasmal.web.assetsGeneration

import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Episode I area base names that have an Ultimate-difficulty recolor. PSO ships these as a
 * parallel set of scene files with an "a" prefix (e.g. `map_aforest01n.rel`). Episodes II and
 * IV reuse the same area textures across difficulties, so they have no Ultimate variant.
 *
 * Must stay in sync with `ULTIMATE_AREA_BASE_NAMES` in the quest editor's AreaAssetLoader.
 */
private val ULTIMATE_AREA_BASE_NAMES = listOf(
    "city00",
    "forest01",
    "forest02",
    "cave01",
    "cave02",
    "cave03",
    "machine01",
    "machine02",
    "ancient01",
    "ancient02",
    "ancient03",
    "boss01",
    "boss02",
    "boss03",
)

/**
 * Copies Ultimate-difficulty area assets into the quest editor's area asset directory.
 *
 * The quest editor requests Ultimate areas under an `a`-prefixed name (e.g. `map_aforest01n.rel`).
 * For every normal area file already present for an Ultimate base (e.g. `map_forest01n.rel`), this
 * copies the matching `a`-prefixed file from `<data-dir>/scene/`, producing exactly the file names
 * the loader expects (including `_NN` variant files), and only the asset types the editor uses
 * (`n.rel`, `c.rel`, `.xvm`).
 *
 * Usage: ExtractUltimateAreas <pso-data-dir> <areas-output-dir>
 *   pso-data-dir:     Path to PSO BB data directory (must contain a `scene` subdirectory)
 *   areas-output-dir: Path to the area assets (e.g. web/src/jsMain/resources/assets/areas)
 */
fun main(args: Array<String>) {
    require(args.size >= 2) {
        "Usage: ExtractUltimateAreas <pso-data-dir> <areas-output-dir>"
    }

    val sceneDir = File(args[0], "scene")
    val areasDir = File(args[1])

    require(sceneDir.isDirectory) { "scene directory not found: $sceneDir" }
    require(areasDir.isDirectory) { "areas output directory not found: $areasDir" }

    var copied = 0
    var missing = 0

    for (base in ULTIMATE_AREA_BASE_NAMES) {
        // Drive off the normal files already present so we copy exactly the names (and variant
        // suffixes) the loader will request, for the asset types the editor actually uses.
        val normalFiles = areasDir.listFiles { f ->
            val n = f.name
            f.isFile &&
                n.startsWith("map_$base") &&
                (n.endsWith("n.rel") || n.endsWith("c.rel") || n.endsWith(".xvm"))
        }?.sortedBy { it.name } ?: emptyList()

        for (normal in normalFiles) {
            // map_forest01n.rel -> map_aforest01n.rel
            val ultName = "map_a" + normal.name.removePrefix("map_")
            val source = File(sceneDir, ultName)

            if (!source.isFile) {
                logger.warn { "No Ultimate source for ${normal.name} (expected $ultName)" }
                missing++
                continue
            }

            source.copyTo(File(areasDir, ultName), overwrite = true)
            logger.info { "Copied $ultName (${source.length()} bytes)" }
            copied++
        }
    }

    logger.info { "Done. Copied $copied Ultimate area file(s), $missing missing." }
}
