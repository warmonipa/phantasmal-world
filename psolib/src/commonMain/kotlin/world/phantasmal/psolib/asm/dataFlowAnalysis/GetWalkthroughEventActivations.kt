package world.phantasmal.psolib.asm.dataFlowAnalysis

import world.phantasmal.psolib.asm.BytecodeIr
import world.phantasmal.psolib.asm.Instruction
import world.phantasmal.psolib.asm.IntArg
import world.phantasmal.psolib.asm.RegType

/** A DAT set-event activation reachable from one quest-script entry label. */
data class WalkthroughEventActivation(
    val floorId: Int,
    val eventId: Int,
)

/** Client-specific script reachability used to build one walkthrough route. */
data class WalkthroughScriptAnalysis(
    val eventActivations: Set<WalkthroughEventActivation>,
    val reachableInstructionFloors: Map<Instruction, Set<Int>>,
)

/**
 * Finds every set-event activation reachable from [entryLabel] for one local client.
 *
 * Calls are followed context-sensitively: a `ret` resumes only at the matching call site instead
 * of using the CFG's conservative return edges to every caller. Branches which directly compare
 * a value originating from `get_client_id`/`get_slotnumber` with an immediate are pruned for
 * [clientId]. Other runtime-dependent branches remain conservative and contribute every possible
 * activation, which is appropriate for a walkthrough that assumes each reachable trigger occurs.
 */
fun getWalkthroughEventActivations(
    bytecodeIr: BytecodeIr,
    entryLabel: Int,
    entryFloorId: Int,
    clientId: Int,
    controlFlowGraph: ControlFlowGraph = ControlFlowGraph.create(bytecodeIr),
): Set<WalkthroughEventActivation> = analyzeWalkthroughScript(
    bytecodeIr,
    entryLabel,
    entryFloorId,
    clientId,
    controlFlowGraph,
).eventActivations

/**
 * Analyses event activations and exact instruction reachability for one local client.
 *
 * Runtime-dependent branches remain conservative, while direct client-ID branches are pruned.
 */
fun analyzeWalkthroughScript(
    bytecodeIr: BytecodeIr,
    entryLabel: Int,
    entryFloorId: Int,
    clientId: Int,
    controlFlowGraph: ControlFlowGraph = ControlFlowGraph.create(bytecodeIr),
): WalkthroughScriptAnalysis {
    require(clientId in 0..3) { "clientId must be in 0..3 but was $clientId." }

    val cfg = controlFlowGraph
    val labelToBlock = mutableMapOf<Int, BasicBlock>()
    for (block in cfg.blocks) {
        if (block.start != 0) continue
        for (label in block.segment.labels) labelToBlock[label] = block
    }
    val entry = labelToBlock[entryLabel]
        ?: return WalkthroughScriptAnalysis(emptySet(), emptyMap())

    val sequentialNext = mutableMapOf<BasicBlock, BasicBlock>()
    for (i in cfg.blocks.indices) {
        val block = cfg.blocks[i]
        val next = cfg.blocks.getOrNull(i + 1) ?: continue
        sequentialNext[block] = next
    }

    data class State(
        val block: BasicBlock,
        val floorId: Int,
        val clientIdRegisters: Set<Int>,
        val floorIdRegisters: Set<Int>,
        val knownRegisters: Map<Int, Int>,
        val returnStack: List<BasicBlock>,
        val floorHandlers: Map<Int, Set<Int>>,
        val callbackHandlers: Map<String, Set<Int>>,
    )

    val activations = linkedSetOf<WalkthroughEventActivation>()
    val reachableInstructionFloors = mutableMapOf<Instruction, MutableSet<Int>>()
    val queue = ArrayDeque<State>()
    val visited = mutableSetOf<State>()
    queue.add(State(
        entry,
        entryFloorId,
        emptySet(),
        emptySet(),
        emptyMap(),
        emptyList(),
        emptyMap(),
        emptyMap(),
    ))

    while (queue.isNotEmpty()) {
        val state = queue.removeFirst()
        if (!visited.add(state)) continue

        var clientRegs = state.clientIdRegisters
        var floorRegs = state.floorIdRegisters
        var knownRegs = state.knownRegisters
        var floorHandlers = state.floorHandlers
        var callbackHandlers = state.callbackHandlers
        for (i in state.block.start until state.block.end) {
            val instruction = state.block.segment.instructions[i]
            reachableInstructionFloors
                .getOrPut(instruction, ::mutableSetOf)
                .add(state.floorId)
            when (instruction.opcode.mnemonic) {
                "setevt" -> {
                    for (eventId in resolveInstructionInts(cfg, instruction, 0, knownRegs)) {
                        activations.add(WalkthroughEventActivation(state.floorId, eventId))
                    }
                }
                "start_setevt" -> {
                    for (floorId in resolveInstructionInts(cfg, instruction, 0, knownRegs)) {
                        for (eventId in resolveInstructionInts(cfg, instruction, 1, knownRegs)) {
                            activations.add(WalkthroughEventActivation(floorId, eventId))
                        }
                    }
                }
            }
            clientRegs = updateClientIdRegisters(instruction, clientRegs)
            floorRegs = updateFloorIdRegisters(instruction, floorRegs)
            when (instruction.opcode.mnemonic) {
                "set_floor_handler" -> {
                    val labels = resolveInstructionInts(cfg, instruction, 1, knownRegs)
                    val floors = resolveInstructionInts(cfg, instruction, 0, knownRegs)
                    if (labels.isNotEmpty()) {
                        for (floor in floors) {
                            val possibleLabels = if (floors.size == 1) {
                                labels
                            } else {
                                floorHandlers[floor].orEmpty() + labels
                            }
                            floorHandlers = floorHandlers + (floor to possibleLabels)
                        }
                    }
                }
                "clr_floor_handler" -> {
                    val floors = resolveInstructionInts(cfg, instruction, 0, knownRegs)
                    if (floors.size == 1) {
                        floorHandlers = floorHandlers - floors.single()
                    }
                }
                "set_qt_failure", "set_qt_success", "set_qt_cancel", "set_qt_exit" -> {
                    val labels = resolveInstructionInts(cfg, instruction, 0, knownRegs)
                    if (labels.isNotEmpty()) callbackHandlers = callbackHandlers +
                        (instruction.opcode.mnemonic.removePrefix("set_") to labels)
                }
                "clr_qt_failure", "clr_qt_success", "clr_qt_cancel", "clr_qt_exit" -> {
                    callbackHandlers = callbackHandlers -
                        instruction.opcode.mnemonic.removePrefix("clr_")
                }
                "set_quest_board_handler" -> {
                    val labels = resolveInstructionInts(cfg, instruction, 1, knownRegs)
                    val indices = resolveInstructionInts(cfg, instruction, 0, knownRegs)
                    for (index in indices) {
                        if (labels.isNotEmpty()) {
                            val key = "quest_board:$index"
                            val possibleLabels = if (indices.size == 1) {
                                labels
                            } else {
                                callbackHandlers[key].orEmpty() + labels
                            }
                            callbackHandlers = callbackHandlers + (key to possibleLabels)
                        }
                    }
                }
                "clear_quest_board_handler" -> {
                    val indices = resolveInstructionInts(cfg, instruction, 0, knownRegs)
                    if (indices.size == 1) {
                        callbackHandlers = callbackHandlers - "quest_board:${indices.single()}"
                    }
                }
            }
            for ((label, floorId) in lifecycleEntries(
                cfg, instruction, state.floorId, clientId, knownRegs,
            )) {
                val target = labelToBlock[label] ?: continue
                // New quest threads have their own register file and return context.
                queue.add(State(
                    target, floorId, emptySet(), emptySet(), emptyMap(), emptyList(), floorHandlers,
                    callbackHandlers,
                ))
            }
            knownRegs = updateKnownRegisters(instruction, knownRegs, clientId, state.floorId)
        }

        val block = state.block
        when (block.branchType) {
            BranchType.Return -> {
                val continuation = state.returnStack.lastOrNull()
                if (continuation == null) {
                    for ((floorId, labels) in floorHandlers) {
                        for (label in labels) {
                            val handler = labelToBlock[label] ?: continue
                            queue.add(State(
                                handler, floorId, emptySet(), emptySet(), emptyMap(), emptyList(),
                                floorHandlers, callbackHandlers,
                            ))
                        }
                    }
                    for (labels in callbackHandlers.values) {
                        for (label in labels) {
                            val handler = labelToBlock[label] ?: continue
                            queue.add(State(
                                handler, 0, emptySet(), emptySet(), emptyMap(), emptyList(),
                                floorHandlers, callbackHandlers,
                            ))
                        }
                    }
                    continue
                }
                queue.add(State(
                    continuation,
                    state.floorId,
                    clientRegs,
                    floorRegs,
                    knownRegs,
                    state.returnStack.dropLast(1),
                    floorHandlers,
                    callbackHandlers,
                ))
            }
            BranchType.Call -> {
                val callees = walkthroughSpecificSuccessors(
                    block, clientRegs, clientId, floorRegs, state.floorId, labelToBlock,
                    sequentialNext,
                )
                val continuation = sequentialNext[block]
                if (callees.isEmpty()) {
                    if (continuation != null) {
                        queue.add(State(
                            continuation, state.floorId, clientRegs, floorRegs, knownRegs,
                            state.returnStack, floorHandlers, callbackHandlers,
                        ))
                    }
                } else {
                    // Malformed or recursive quest scripts must not grow the context stack forever.
                    if (state.returnStack.size >= MAX_CALL_DEPTH) continue
                    val returnStack = if (continuation == null) {
                        state.returnStack
                    } else {
                        state.returnStack + continuation
                    }
                    for (callee in callees) {
                        queue.add(State(
                            callee, state.floorId, clientRegs, floorRegs, knownRegs, returnStack,
                            floorHandlers, callbackHandlers,
                        ))
                    }
                }
            }
            BranchType.ConditionalJump,
            BranchType.Jump,
            BranchType.None,
            -> {
                for (successor in walkthroughSpecificSuccessors(
                    block,
                    clientRegs,
                    clientId,
                    floorRegs,
                    state.floorId,
                    labelToBlock,
                    sequentialNext,
                )) {
                    queue.add(State(
                        successor, state.floorId, clientRegs, floorRegs, knownRegs,
                        state.returnStack, floorHandlers, callbackHandlers,
                    ))
                }
            }
        }
    }

    return WalkthroughScriptAnalysis(
        activations,
        reachableInstructionFloors.mapValues { it.value.toSet() },
    )
}

private const val MAX_CALL_DEPTH = 64

private fun lifecycleEntries(
    cfg: ControlFlowGraph,
    instruction: Instruction,
    currentFloorId: Int,
    clientId: Int,
    knownRegisters: Map<Int, Int>,
): List<Pair<Int, Int>> =
    when (instruction.opcode.mnemonic) {
        "thread", "thread_stg" -> {
            val label = (instruction.args.getOrNull(0) as? IntArg)?.value ?: return emptyList()
            listOf(label to currentFloorId)
        }
        "set_palettex_callback" -> {
            val slots = resolveInstructionInts(cfg, instruction, 0, knownRegisters)
            val labels = resolveInstructionInts(cfg, instruction, 1, knownRegisters)
            // A runtime-dependent slot may refer to the selected client, so retain it.
            if (slots.isEmpty() || clientId in slots) {
                labels.map { it to currentFloorId }
            } else {
                emptyList()
            }
        }
        else -> emptyList()
    }

private fun resolveInstructionInts(
    cfg: ControlFlowGraph,
    instruction: Instruction,
    argumentIndex: Int,
    knownRegisters: Map<Int, Int>,
): Set<Int> {
    val argument = instruction.args.getOrNull(argumentIndex) as? IntArg ?: return emptySet()
    if (!argument.isRegRef) return setOf(argument.value)
    knownRegisters[argument.value]?.let { return setOf(it) }
    val values = getRegisterValue(cfg, instruction, argument.value)
    if (values.size > MAX_STATIC_VALUE_ALTERNATIVES) return emptySet()
    return values.toSet()
}

private const val MAX_STATIC_VALUE_ALTERNATIVES = 256L

private fun walkthroughSpecificSuccessors(
    block: BasicBlock,
    clientIdRegisters: Set<Int>,
    clientId: Int,
    floorIdRegisters: Set<Int>,
    floorId: Int,
    labelToBlock: Map<Int, BasicBlock>,
    sequentialNext: Map<BasicBlock, BasicBlock>,
): List<BasicBlock> {
    val instruction = block.segment.instructions.getOrNull(block.end - 1) ?: return block.to
    val mnemonic = instruction.opcode.mnemonic

    if ((mnemonic == "jmpi_=" || mnemonic == "jmpi_!=") &&
        (instruction.args.getOrNull(0) as? IntArg)?.value in clientIdRegisters
    ) {
        val comparedId = (instruction.args.getOrNull(1) as? IntArg)?.value ?: return block.to
        val targetLabel = (instruction.args.getOrNull(2) as? IntArg)?.value ?: return block.to
        val target = labelToBlock[targetLabel]
        val branchTaken = if (mnemonic == "jmpi_=") clientId == comparedId else clientId != comparedId
        return if (branchTaken) {
            listOfNotNull(target)
        } else {
            block.to.filter { it !== target }
        }
    }

    if ((mnemonic == "switch_jmp" || mnemonic == "switch_call") &&
        (instruction.args.getOrNull(0) as? IntArg)?.value in clientIdRegisters
    ) {
        val targetLabel = (instruction.args.getOrNull(clientId + 1) as? IntArg)?.value
            ?: return switchFallthrough(block, mnemonic, sequentialNext)
        val target = labelToBlock[targetLabel]
            ?: return switchFallthrough(block, mnemonic, sequentialNext)
        return listOf(target)
    }

    if ((mnemonic == "jmpi_=" || mnemonic == "jmpi_!=") &&
        (instruction.args.getOrNull(0) as? IntArg)?.value in floorIdRegisters
    ) {
        val comparedFloor = (instruction.args.getOrNull(1) as? IntArg)?.value ?: return block.to
        val targetLabel = (instruction.args.getOrNull(2) as? IntArg)?.value ?: return block.to
        val target = labelToBlock[targetLabel]
        val branchTaken = if (mnemonic == "jmpi_=") {
            floorId == comparedFloor
        } else {
            floorId != comparedFloor
        }
        return if (branchTaken) listOfNotNull(target) else block.to.filter { it !== target }
    }

    if ((mnemonic == "switch_jmp" || mnemonic == "switch_call") &&
        (instruction.args.getOrNull(0) as? IntArg)?.value in floorIdRegisters
    ) {
        val targetLabel = (instruction.args.getOrNull(floorId + 1) as? IntArg)?.value
            ?: return switchFallthrough(block, mnemonic, sequentialNext)
        val target = labelToBlock[targetLabel]
            ?: return switchFallthrough(block, mnemonic, sequentialNext)
        return listOf(target)
    }

    return block.to
}

private fun switchFallthrough(
    block: BasicBlock,
    mnemonic: String,
    sequentialNext: Map<BasicBlock, BasicBlock>,
): List<BasicBlock> =
    if (mnemonic == "switch_call") {
        // The caller handles an empty callee set by resuming at its continuation.
        emptyList()
    } else {
        listOfNotNull(sequentialNext[block])
    }

private fun updateKnownRegisters(
    instruction: Instruction,
    incoming: Map<Int, Int>,
    clientId: Int,
    floorId: Int,
): Map<Int, Int> {
    when (instruction.opcode.mnemonic) {
        "get_client_id", "get_slotnumber" -> {
            val destination = (instruction.args.getOrNull(0) as? IntArg)?.value ?: return incoming
            return incoming + (destination to clientId)
        }
        "get_floor_number" -> {
            val destination = (instruction.args.getOrNull(1) as? IntArg)?.value ?: return incoming
            return (incoming + (destination to floorId)) - (destination + 1)
        }
        "leti" -> {
            val destination = (instruction.args.getOrNull(0) as? IntArg)?.value ?: return incoming
            val value = (instruction.args.getOrNull(1) as? IntArg)?.value ?: return incoming
            return incoming + (destination to value)
        }
        "let" -> {
            val destination = (instruction.args.getOrNull(0) as? IntArg)?.value ?: return incoming
            val source = (instruction.args.getOrNull(1) as? IntArg)?.value ?: return incoming
            val value = incoming[source]
            return if (value == null) incoming - destination else incoming + (destination to value)
        }
    }

    var result = incoming
    val count = minOf(instruction.opcode.params.size, instruction.args.size)
    for (i in 0 until count) {
        val type = instruction.opcode.params[i].type as? RegType ?: continue
        val registers = type.registers ?: continue
        val base = (instruction.args[i] as? IntArg)?.value ?: continue
        for ((offset, register) in registers.withIndex()) {
            if (register.write) result = result - (base + offset)
        }
    }
    return result
}

private fun updateFloorIdRegisters(
    instruction: Instruction,
    incoming: Set<Int>,
): Set<Int> {
    when (instruction.opcode.mnemonic) {
        "get_floor_number" -> {
            val destination = (instruction.args.getOrNull(1) as? IntArg)?.value ?: return incoming
            return (incoming + destination) - (destination + 1)
        }
        "let" -> {
            val destination = (instruction.args.getOrNull(0) as? IntArg)?.value ?: return incoming
            val source = (instruction.args.getOrNull(1) as? IntArg)?.value ?: return incoming
            return if (source in incoming) incoming + destination else incoming - destination
        }
        "leti" -> {
            val destination = (instruction.args.getOrNull(0) as? IntArg)?.value ?: return incoming
            return incoming - destination
        }
    }

    var result = incoming
    val count = minOf(instruction.opcode.params.size, instruction.args.size)
    for (i in 0 until count) {
        val type = instruction.opcode.params[i].type as? RegType ?: continue
        val registers = type.registers ?: continue
        val base = (instruction.args[i] as? IntArg)?.value ?: continue
        for ((offset, register) in registers.withIndex()) {
            if (register.write) result = result - (base + offset)
        }
    }
    return result
}

private fun updateClientIdRegisters(
    instruction: Instruction,
    incoming: Set<Int>,
): Set<Int> {
    when (instruction.opcode.mnemonic) {
        "get_client_id", "get_slotnumber" -> {
            val destination = (instruction.args.getOrNull(0) as? IntArg)?.value ?: return incoming
            return incoming + destination
        }
        "let" -> {
            val destination = (instruction.args.getOrNull(0) as? IntArg)?.value ?: return incoming
            val source = (instruction.args.getOrNull(1) as? IntArg)?.value ?: return incoming
            return if (source in incoming) incoming + destination else incoming - destination
        }
        "leti" -> {
            val destination = (instruction.args.getOrNull(0) as? IntArg)?.value ?: return incoming
            return incoming - destination
        }
    }

    var result = incoming
    val params = instruction.opcode.params
    val count = minOf(params.size, instruction.args.size)
    for (i in 0 until count) {
        val type = params[i].type as? RegType ?: continue
        val registers = type.registers ?: continue
        val base = (instruction.args[i] as? IntArg)?.value ?: continue
        for ((offset, register) in registers.withIndex()) {
            if (register.write) result = result - (base + offset)
        }
    }
    return result
}
