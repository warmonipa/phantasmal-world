package world.phantasmal.psolib.asm

import world.phantasmal.core.unsafe.UnsafeMap
import world.phantasmal.psolib.fileFormats.quest.Version

private val MNEMONIC_TO_OPCODES_BY_VERSION: UnsafeMap<Version, UnsafeMap<String, Opcode>> by lazy {
    val outer = UnsafeMap<Version, UnsafeMap<String, Opcode>>()
    for (v in Version.entries) {
        val inner = UnsafeMap<String, Opcode>()
        val tables = opcodesFor(v)
        tables.byCode.forEach { if (it != null) inner.set(it.mnemonic, it) }
        tables.byCodeF8.forEach { if (it != null) inner.set(it.mnemonic, it) }
        tables.byCodeF9.forEach { if (it != null) inner.set(it.mnemonic, it) }
        outer.set(v, inner)
    }
    outer
}

private val UNKNOWN_OPCODE_MNEMONIC_REGEX = Regex("""^unknown_((f8|f9)?[0-9a-f]{2})$""")

/**
 * Abstract super type of all types.
 */
sealed class AnyType {
    object Instance : AnyType()
}

/**
 * Purely abstract super type of all value types.
 */
sealed class ValueType : AnyType()

/**
 * 8-Bit integer.
 */
object ByteType : ValueType()

/**
 * 16-Bit integer.
 */
object ShortType : ValueType()

/**
 * 32-Bit integer.
 */
object IntType : ValueType()

/**
 * 32-Bit floating point number.
 */
object FloatType : ValueType()

/**
 * Abstract super type of all label types.
 */
sealed class LabelType : ValueType() {
    object Instance : LabelType()
}

/**
 * Named reference to an instruction.
 */
object ILabelType : LabelType()

/**
 * Named reference to a data segment.
 */
object DLabelType : LabelType()

/**
 * Named reference to a string segment.
 */
object SLabelType : LabelType()

/**
 * Arbitrary amount of instruction labels (variadic arguments).
 */
object ILabelVarType : LabelType()

/**
 * String of arbitrary size.
 */
object StringType : ValueType()

/**
 * Purely abstract super type of all register reference types.
 */
sealed class RegRefType : AnyType()

/**
 * Register reference. If [registers] is null, references one or more consecutive registers of any
 * type (only stack_pushm and stack_popm use this). If [registers] is not null, references a fixed
 * amount of consecutive registers of specific types. [Param.type] can't be a variadic type.
 */
class RegType(
    val registers: List<Param>?,
    /** Width of an inline register number. V0_V2 has a few opcodes that encode it as 32 bits. */
    val inlineWidthBytes: Int = 1,
) : RegRefType() {
    init {
        require(inlineWidthBytes == 1 || inlineWidthBytes == 4)
    }
}

/**
 * Arbitrary amount of register references (variadic arguments).
 */
object RegVarType : RegRefType()

/**
 * Raw memory pointer.
 */
object PointerType : AnyType()

class Param(
    val type: AnyType,
    val name: String?,
    /**
     * Documentation string.
     */
    val doc: String?,
    /**
     * Whether or not the instruction reads this parameter. Only set when type is a register
     * reference.
     */
    val read: Boolean,
    /**
     * Whether or not the instruction writes this parameter. Only set when type is a register
     * reference.
     */
    val write: Boolean,
) {
    /**
     * Whether or not this parameter takes a variable number of arguments.
     */
    val varargs: Boolean = type === ILabelVarType || type === RegVarType
}

enum class StackInteraction {
    Push,
    Pop,
}

enum class ArgsMode {
    /** Arguments pushed onto the arg stack by `arg_push*` opcodes; consumed by this opcode. (V3_V4 only.) */
    Stack,
    /** Arguments encoded inline immediately after the opcode byte. */
    Inline,
    /** Opcode takes no arguments. */
    None,
}

/**
 * Opcode for script byte code. Invoked by instructions.
 * Don't directly instantiate this class, use the global constants and lookup functions.
 */
class Opcode internal constructor(
    /**
     * 1- Or 2-byte big-endian representation of this opcode as used in byte code.
     */
    val code: Int,
    /**
     * String representation of this opcode as used in assembly.
     */
    val mnemonic: String,
    /**
     * Documentation string.
     */
    val doc: String?,
    /**
     * Parameters passed in directly or via the stack, depending on the value of [stack].
     */
    val params: List<Param>,
    /**
     * Stack interaction.
     */
    val stack: StackInteraction?,
    /**
     * Whether or not the last parameter of this opcode takes a variable number of arguments.
     */
    val varargs: Boolean,
    /**
     * Whether or not the working of this opcode is known.
     */
    val known: Boolean,
    /**
     * Bitmask of [world.phantasmal.psolib.fileFormats.quest.Version] bits indicating which game
     * versions support this opcode.
     */
    val versionMask: Int,
    /**
     * How arguments are passed to this opcode.
     */
    val argsMode: ArgsMode,
) {
    /**
     * Byte size of the opcode, either 1 or 2.
     */
    val size: Int = if (code < 0xFF) 1 else 2

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = code

    override fun toString(): String = mnemonic
}

fun codeToOpcode(code: Int, version: Version = Version.BB_V4): Opcode {
    val tables = opcodesFor(version)
    return when {
        code <= 0xFF -> getOpcode(code, code, tables.byCode)
        code <= 0xF8FF -> getOpcode(code, code and 0xFF, tables.byCodeF8)
        else -> getOpcode(code, code and 0xFF, tables.byCodeF9)
    }
}

fun mnemonicToOpcode(mnemonic: String, version: Version = Version.BB_V4): Opcode? {
    val map = MNEMONIC_TO_OPCODES_BY_VERSION.get(version) ?: return null
    var opcode = map.get(mnemonic)
    if (opcode == null) {
        UNKNOWN_OPCODE_MNEMONIC_REGEX.matchEntire(mnemonic)?.destructured?.let { (codeStr) ->
            val code = codeStr.toInt(16)
            opcode = codeToOpcode(code, version)
            map.set(mnemonic, opcode!!)
        }
    }
    return opcode
}

private fun getOpcode(code: Int, index: Int, opcodes: Array<Opcode?>): Opcode {
    var opcode = opcodes[index]

    if (opcode == null) {
        opcode = Opcode(
            code,
            mnemonic = "unknown_${code.toString(16)}",
            doc = null,
            params = emptyList(),
            stack = null,
            varargs = false,
            known = false,
            versionMask = 0xFF,
            argsMode = ArgsMode.None,
        )
        opcodes[index] = opcode
    }

    return opcode
}
