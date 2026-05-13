package world.phantasmal.psolib.fileFormats.quest

enum class Version {
    DC_NTE,
    DC_V1,
    DC_V2,
    PC_NTE,
    PC_V2,
    GC_NTE,
    GC_V3,
    BB_V4,
    ;

    val dialect: Dialect
        get() = when (this) {
            DC_NTE, DC_V1, DC_V2, PC_NTE, PC_V2, GC_NTE -> Dialect.V0_V2
            GC_V3, BB_V4 -> Dialect.V3_V4
        }

    /**
     * Power-of-two bitmask bit for this version (`1 shl ordinal`).
     *
     * WARNING: this depends on declaration order. Do not reorder entries
     * without also updating any callers that compare bit values.
     */
    val bit: Int
        get() = 1 shl ordinal
}
