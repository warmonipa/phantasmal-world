package world.phantasmal.psolib.compatibility

import world.phantasmal.psolib.fileFormats.quest.Version

/**
 * PSO version for compatibility checking.
 * More granular than [Version] to distinguish between DC V1 and V2.
 */
enum class PSOVersion(
    val verId: Int,
    val displayName: String,
) {
    DC_V1(0, "Phantasy Star Online DC V1"),
    DC_V2(1, "Phantasy Star Online DC V2"),
    PC(2, "Phantasy Star Online PC"),
    GC_EP12(3, "Phantasy Star Online GC Ep1&2"),
    BLUE_BURST(4, "Phantasy Star Online Blue Burst");

    companion object {
        fun fromId(id: Int): PSOVersion? = entries.firstOrNull { it.verId == id }

        /**
         * Convert from file format [Version] to compatibility [PSOVersion].
         * NTE variants collapse into the nearest retail sub-version for compatibility
         * purposes (e.g. [Version.GC_NTE] → [GC_EP12]).
         */
        fun fromVersion(version: Version): PSOVersion = when (version) {
            Version.DC_NTE, Version.DC_V1 -> DC_V1
            Version.DC_V2 -> DC_V2
            Version.PC_NTE, Version.PC_V2 -> PC
            Version.GC_NTE, Version.GC_V3 -> GC_EP12
            Version.BB_V4 -> BLUE_BURST
        }
    }
}