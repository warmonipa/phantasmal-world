package world.phantasmal.psolib.asm.dataFlowAnalysis

import mu.KotlinLogging
import world.phantasmal.psolib.asm.*
import kotlin.math.max
import kotlin.math.min

private val logger = KotlinLogging.logger {}

/**
 * Computes the possible values of a register right before a specific instruction.
 */
fun getRegisterValue(cfg: ControlFlowGraph, instruction: Instruction, register: Int): ValueSet {
    require(register in 0..255) {
        "register should be between 0 and 255, inclusive but was $register."
    }

    val block = cfg.getBlockForInstruction(instruction)

    return RegisterValueFinder(cfg).find(
        mutableSetOf(),
        block,
        block.indexOfInstruction(instruction),
        register
    )
}

private class RegisterValueFinder(private val cfg: ControlFlowGraph) {
    private var iterations = 0
    private val switchCallFallthroughFrom: Map<BasicBlock, BasicBlock> by lazy {
        buildMap {
            for (i in 0 until cfg.blocks.lastIndex) {
                val b = cfg.blocks[i]
                if (b.branchType == BranchType.Call && b.end > b.start) {
                    val term = b.segment.instructions[b.end - 1]
                    if (term.opcode.code == OP_SWITCH_CALL.code) {
                        put(cfg.blocks[i + 1], b)
                    }
                }
            }
        }
    }

    // TODO: Deal with incorrect argument types.
    fun find(
        path: MutableSet<BasicBlock>,
        block: BasicBlock,
        end: Int,
        register: Int,
    ): ValueSet {
        if (++iterations > 100) {
            logger.debug { "Register value analysis exceeded its iteration limit." }
            return ValueSet.all()
        }

        for (i in end - 1 downTo block.start) {
            val instruction = block.segment.instructions[i]
            val args = instruction.args

            when (instruction.opcode.code) {
                OP_SYNC.code -> {
                    // After a sync call, concurrent code could have modified the register. We don't
                    // check whether concurrent code *ever* writes to the register to possibly
                    // continue the analysis.
                    return ValueSet.all()
                }

                OP_VA_CALL_V3_V4.code -> {
                    val value = vaCall(path, block, i, register)
                    if (value.isNotEmpty()) return value
                }

                OP_LET.code -> {
                    if (args[0].value == register) {
                        return find(LinkedHashSet(path), block, i, (args[1] as IntArg).value)
                    }
                }

                OP_LETI.code,
                OP_LETB_V3_V4.code,
                OP_LETW.code,
                OP_SYNC_REGISTER_V3_V4.code,
                -> {
                    // V1/V2's stack-based sync_register shares its opcode byte with the
                    // V3/V4 inline-argument form. During discovery it can therefore have no
                    // decoded arguments; conservatively stop the analysis in that case.
                    if (args.size < 2) return ValueSet.all()

                    if (args[0].value == register) {
                        return ValueSet.of((args[1] as IntArg).value)
                    }
                }

                OP_SET.code -> {
                    if (args[0].value == register) {
                        return ValueSet.of(1)
                    }
                }

                OP_CLEAR.code -> {
                    if (args[0].value == register) {
                        return ValueSet.of(0)
                    }
                }

                OP_REV.code -> {
                    if (args[0].value == register) {
                        val prevVals = find(LinkedHashSet(path), block, i, register)

                        return if (prevVals.size == 1L && prevVals[0] == 0) {
                            ValueSet.of(1)
                        } else if (0 in prevVals) {
                            ValueSet.ofInterval(0, 1)
                        } else {
                            ValueSet.of(0)
                        }
                    }
                }

                OP_ADDI.code -> {
                    if (args[0].value == register) {
                        val prevVals = find(LinkedHashSet(path), block, i, register)
                        prevVals += (args[1] as IntArg).value
                        return prevVals
                    }
                }

                OP_SUBI.code -> {
                    if (args[0].value == register) {
                        val prevVals = find(LinkedHashSet(path), block, i, register)
                        prevVals -= (args[1] as IntArg).value
                        return prevVals
                    }
                }

                OP_MULI.code -> {
                    if (args[0].value == register) {
                        val prevVals = find(LinkedHashSet(path), block, i, register)
                        prevVals *= (args[1] as IntArg).value
                        return prevVals
                    }
                }

                OP_DIVI.code -> {
                    if (args[0].value == register) {
                        val prevVals = find(LinkedHashSet(path), block, i, register)
                        prevVals /= (args[1] as IntArg).value
                        return prevVals
                    }
                }

                OP_IF_ZONE_CLEAR.code -> {
                    if (args[0].value == register) {
                        return ValueSet.ofInterval(0, 1)
                    }
                }

                OP_GET_DIFFLVL.code -> {
                    if (args[0].value == register) {
                        return ValueSet.ofInterval(0, 2)
                    }
                }

                OP_GET_SLOTNUMBER.code -> {
                    if (args[0].value == register) {
                        return ValueSet.ofInterval(0, 3)
                    }
                }

                OP_GET_RANDOM.code -> {
                    if (args[1].value == register) {
                        // TODO: undefined values.
                        val min = find(
                            LinkedHashSet(path),
                            block,
                            i,
                            (args[0] as IntArg).value
                        ).minOrNull()!!

                        val max = max(
                            find(
                                LinkedHashSet(path),
                                block,
                                i,
                                (args[0] as IntArg).value + 1
                            ).maxOrNull()!!,
                            min + 1,
                        )

                        return ValueSet.ofInterval(min, max - 1)
                    }
                }

                OP_STACK_PUSHM_V3_V4.code,
                OP_STACK_POPM_V3_V4.code,
                -> {
                    val minReg = (args[0] as IntArg).value
                    val maxReg = (args[0] as IntArg).value + (args[1] as IntArg).value

                    if (register in minReg until maxReg) {
                        return ValueSet.all()
                    }
                }

                else -> {
                    // Assume any other opcodes that write to the register can produce any value.
                    val params = instruction.opcode.params
                    val argLen = min(args.size, params.size)

                    for (j in 0 until argLen) {
                        val param = params[j]

                        if (param.type is RegType && param.type.registers != null) {
                            val regRef = (args[j] as IntArg).value

                            for ((k, regParam) in param.type.registers.withIndex()) {
                                if (regParam.write && regRef + k == register) {
                                    return ValueSet.all()
                                }
                            }
                        }
                    }
                }
            }
        }

        val values = ValueSet.empty()
        path.add(block)

        val predecessors = buildList(block.from.size + 1) {
            addAll(block.from)
            switchCallFallthroughFrom[block]?.let { fallthroughFrom ->
                if (fallthroughFrom !in block.from && canSkipSwitchCall(path, fallthroughFrom)) {
                    add(fallthroughFrom)
                }
            }
        }

        for (from in predecessors) {
            // Bail out from loops.
            if (from in path) {
                return ValueSet.all()
            }

            values.union(find(LinkedHashSet(path), from, from.end, register))
        }

        // If values is empty at this point, we know nothing ever sets the register's value from
        // this thread or handler. Concurrent code could have modified it, we don't yet try to
        // exclude this possibility so we just return all values.
        if (values.isEmpty()) {
            return ValueSet.all()
        }

        return values
    }

    /**
     * Returns `true` when [block]'s `switch_call` terminator may select an out-of-range index
     * and therefore skip all callees and fall straight through to the next block.
     *
     * Only rules out fallthrough when the selector register is provably assigned an in-range
     * constant earlier in the same basic block (avoiding interprocedural CFG return-edge
     * approximations such as a nested skipped `switch_call`).
     */
    private fun canSkipSwitchCall(path: MutableSet<BasicBlock>, block: BasicBlock): Boolean {
        if (block in path || block.end <= block.start) return true
        val term = block.segment.instructions[block.end - 1]
        if (term.opcode.code != OP_SWITCH_CALL.code) return false
        val tableSize = term.args.size - 1
        if (tableSize <= 0) return true
        val selReg = (term.args.getOrNull(0) as? IntArg)?.value ?: return true
        if (selReg !in 0..255) return true
        val localIdx = findLocalConstantInBlock(block, selReg) ?: return true
        return localIdx !in 0 until tableSize || term.args.getOrNull(localIdx + 1) !is IntArg
    }

    private fun findLocalConstantInBlock(block: BasicBlock, reg: Int): Int? {
        for (i in (block.end - 2) downTo block.start) {
            val prev = block.segment.instructions[i]
            val args = prev.args
            when (prev.opcode) {
                OP_SYNC -> return null
                OP_LETI,
                OP_LETB_V3_V4,
                OP_LETW -> {
                    if (args.size >= 2 && (args[0] as? IntArg)?.value == reg) {
                        return (args[1] as? IntArg)?.value
                    }
                }
                OP_CLEAR -> if ((args.getOrNull(0) as? IntArg)?.value == reg) return 0
                OP_SET -> if ((args.getOrNull(0) as? IntArg)?.value == reg) return 1
                OP_SYNC_REGISTER_V1_V2,
                OP_SYNC_REGISTER_V3_V4 -> {
                    if (args.size < 2) return null
                    if ((args[0] as? IntArg)?.value == reg) {
                        val valArg = args[1] as? IntArg
                        return if (valArg != null && !valArg.isRegRef) valArg.value else null
                    }
                }
                OP_STACK_POPM_V3_V4 -> {
                    val base = (args.getOrNull(0) as? IntArg)?.value ?: return null
                    val count = (args.getOrNull(1) as? IntArg)?.value ?: return null
                    if (reg in base until (base + count)) return null
                }
                else -> {
                    val params = prev.opcode.params
                    val argLen = min(args.size, params.size)
                    for (j in 0 until argLen) {
                        val paramType = params[j].type
                        if (paramType is RegType) {
                            val regRef = (args[j] as? IntArg)?.value ?: continue
                            val regParams = paramType.registers
                            if (regParams != null) {
                                for ((k, rp) in regParams.withIndex()) {
                                    if (rp.write && regRef + k == reg) return null
                                }
                            } else if (params[j].write && regRef == reg) {
                                return null
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * After a va_start instruction, 0 or more arg_push instructions can be used. When va_call is
     * executed the values on the stack will become the values of registers r1..r7 (inclusive) in
     * the order that they were pushed.
     *
     * E.g.:
     *
     * va_start
     * arg_pushl 10
     * arg_pushl 20
     * va_call 777
     * va_end
     *
     * This means call 777 with r1 = 10 and r2 = 20.
     */
    private fun vaCall(
        path: MutableSet<BasicBlock>,
        block: BasicBlock,
        vaCallIdx: Int,
        register: Int,
    ): ValueSet {
        if (register !in 1..7) return ValueSet.empty()

        var vaStartIdx = -1
        val stack = mutableListOf<Instruction>()

        for (i in block.start until vaCallIdx) {
            val instruction = block.segment.instructions[i]
            val opcode = instruction.opcode

            if (opcode.code == OP_VA_START_V3_V4.code) {
                vaStartIdx = i
            } else if (vaStartIdx != -1) {
                when (opcode.code) {
                    OP_ARG_PUSHR_V3_V4.code,
                    OP_ARG_PUSHL_V3_V4.code,
                    OP_ARG_PUSHB_V3_V4.code,
                    OP_ARG_PUSHW_V3_V4.code,
                    OP_ARG_PUSHA_V3_V4.code,
                    OP_ARG_PUSHO_V3_V4.code,
                    OP_ARG_PUSHS_V3_V4.code,
                    -> stack.add(instruction)
                }
            }
        }

        return if (register in 1..stack.size) {
            val instruction = stack[register - 1]
            val arg = instruction.args.first()

            when (instruction.opcode.code) {
                OP_ARG_PUSHR_V3_V4.code ->
                    find(LinkedHashSet(path), block, vaStartIdx, (arg as IntArg).value)

                OP_ARG_PUSHL_V3_V4.code,
                OP_ARG_PUSHB_V3_V4.code,
                OP_ARG_PUSHW_V3_V4.code,
                -> ValueSet.of((arg as IntArg).value)

                // TODO: Deal with strings.
                else -> ValueSet.all() // String or pointer
            }
        } else {
            ValueSet.of(0)
        }
    }
}
