package world.phantasmal.psolib.fileFormats.quest

enum class Dialect {
    /** DC NTE / DC v1 / DC v2 / PC NTE / PC v2 / GC NTE: arguments encoded inline after the opcode. */
    V0_V2,
    /** GC v3 / Xbox / BB: arguments pushed onto an arg stack by `arg_push*` opcodes before the consumer opcode. */
    V3_V4,
}
