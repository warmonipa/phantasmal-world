package world.phantasmal.psolib.asm.dataFlowAnalysis

import mu.KotlinLogging
import world.phantasmal.psolib.asm.Instruction
import world.phantasmal.psolib.asm.InstructionSegment
import world.phantasmal.psolib.asm.IntArg
import world.phantasmal.psolib.asm.OP_AT_COORDS_CALL
import world.phantasmal.psolib.asm.OP_AT_COORDS_TALK
import world.phantasmal.psolib.asm.OP_GET_FLOOR_NUMBER
import world.phantasmal.psolib.asm.OP_JMPI_E
import world.phantasmal.psolib.asm.OP_JMPI_NE
import world.phantasmal.psolib.asm.OP_LET
import world.phantasmal.psolib.asm.OP_LETI
import world.phantasmal.psolib.asm.OP_PARTICLE_V3_V3_V4
import world.phantasmal.psolib.asm.OP_SET_FLOOR_HANDLER_V3_V4
import world.phantasmal.psolib.asm.OP_SWITCH_CALL
import world.phantasmal.psolib.asm.OP_SWITCH_JMP
import world.phantasmal.psolib.asm.OP_THREAD
import world.phantasmal.psolib.asm.OP_THREAD_STG
import world.phantasmal.psolib.asm.RegType

private val logger = KotlinLogging.logger {}

/**
 * A `particle_v3` invocation whose arguments could be statically resolved to constant values.
 *
 * Coordinates are world-space integers as written by the script (PSO bytecode uses raw integer
 * literals for these positions).
 *
 * @property x World-space X coordinate.
 * @property y World-space Y coordinate.
 * @property z World-space Z coordinate.
 * @property particleId Particle effect ID; index into `particleentry.dat` (or area-specific
 *   `particleentryaXX.dat`).
 * @property frames Number of frames the effect should last.
 * @property floorIds The set of floor IDs whose `set_floor_handler` chain transitively reaches
 *   this invocation. Empty when the spawn site is not reachable from any registered floor
 *   handler — typically dead code in the bytecode (e.g. a self-looping ambient thread that
 *   was never `thread_stg`d, or a `thread_stg` placed after an unconditional `jmp`). Callers
 *   should hide spawns with an empty set; [getParticleSpawns] emits a one-shot warning listing
 *   them at parse time.
 */
data class ParticleSpawn(
    val x: Int,
    val y: Int,
    val z: Int,
    val particleId: Int,
    val frames: Int,
    val floorIds: Set<Int> = emptySet(),
)

/**
 * Scans every [InstructionSegment] for `particle_v3` (opcode 0xC0) invocations and resolves the 5
 * consecutive registers passed as arguments (X, Y, Z, particle ID, # of frames) via constant
 * propagation on the [ControlFlowGraph].
 *
 * Each resolved spawn is also tagged with the floor IDs whose [OP_SET_FLOOR_HANDLER] entry can
 * reach the spawn site, so the editor can filter markers per floor view.
 *
 * Invocations whose arguments can't be reduced to a single value are skipped (logged at debug
 * level).
 */
fun getParticleSpawns(
    instructionSegments: List<InstructionSegment>,
    createCfg: () -> ControlFlowGraph,
): List<ParticleSpawn> {
    val spawns = mutableListOf<ParticleSpawn>()
    var cfg: ControlFlowGraph? = null
    var blockToFloors: Map<BasicBlock, Set<Int>>? = null

    for (segment in instructionSegments) {
        for (inst in segment.instructions) {
            if (inst.opcode.code != OP_PARTICLE_V3_V3_V4.code) continue

            if (cfg == null) cfg = createCfg()
            if (blockToFloors == null) blockToFloors = computeBlockToFloors(cfg, instructionSegments)

            val firstReg = (inst.args[0] as IntArg).value

            // particle_v3 needs 5 consecutive registers (firstReg..firstReg+4).
            if (firstReg !in 0..251) continue

            val x = getRegisterValue(cfg, inst, firstReg)
            val y = getRegisterValue(cfg, inst, firstReg + 1)
            val z = getRegisterValue(cfg, inst, firstReg + 2)
            val pid = getRegisterValue(cfg, inst, firstReg + 3)
            val frames = getRegisterValue(cfg, inst, firstReg + 4)

            if (x.size != 1L || y.size != 1L || z.size != 1L || pid.size != 1L ||
                frames.size != 1L
            ) {
                logger.debug {
                    "Couldn't determine constant arguments for particle_v3 in segment with " +
                            "labels ${segment.labels}."
                }
                continue
            }

            val block = cfg.getBlockForInstruction(inst)
            val floorIds = blockToFloors[block] ?: emptySet()

            spawns.add(
                ParticleSpawn(
                    x = x[0]!!,
                    y = y[0]!!,
                    z = z[0]!!,
                    particleId = pid[0]!!,
                    frames = frames[0]!!,
                    floorIds = floorIds,
                )
            )
        }
    }

    val unattributed = spawns.filter { it.floorIds.isEmpty() }
    if (unattributed.isNotEmpty()) {
        logger.warn {
            buildString {
                append("Hiding ${unattributed.size} unreachable particle_v3 spawn(s) — ")
                append("the containing segments are not reachable from any set_floor_handler ")
                append("entry (likely dead bytecode):")
                for (s in unattributed) {
                    append("\n  particleId=${s.particleId} pos=(${s.x}, ${s.y}, ${s.z}) frames=${s.frames}")
                }
            }
        }
    }

    return spawns
}

/**
 * Returns a mapping from each reachable [BasicBlock] to the set of floor IDs whose
 * [OP_SET_FLOOR_HANDLER]-registered entry blocks transitively reach it.
 *
 * The analysis is **path-sensitive on the floor register**. Each floor handler entry is BFSed
 * once per floor it is registered for; during the walk we track the set of registers known to
 * hold the current floor value (introduced via [OP_GET_FLOOR_NUMBER] and propagated through
 * [OP_LET]). When a [OP_SWITCH_JMP] / [OP_SWITCH_CALL] / [OP_JMPI_E] / [OP_JMPI_NE] terminator
 * dispatches on one of those registers, only the branch consistent with the BFS instance's
 * current floor is followed. This is what makes shared handlers like
 *
 * ```
 * set_floor_handler 1, 152
 * set_floor_handler 2, 152
 * ...
 * set_floor_handler 12, 152
 * 152:
 *   get_floor_number r250, r28
 *   let r53, r28
 *   switch_call r53, ..., L21, L22, L23, ..., L1032
 * ```
 *
 * resolve to per-floor attribution instead of "this code can be reached on floors 1..12, so
 * tag everything with all twelve."
 *
 * Reachability also follows callback-registration edges where the callback body is associated
 * with the registration floor:
 * - [OP_AT_COORDS_CALL] / [OP_AT_COORDS_TALK] — the trigger geometry (radius around an XYZ
 *   point) is created on the current floor, so the registered label only fires on that floor.
 * - [OP_THREAD] / [OP_THREAD_STG] — many quests start a per-floor "ambience" thread from the
 *   floor handler whose body spawns particles at coordinates in that floor's local space.
 *   Threads technically outlive floor transitions, but the registration site is the strongest
 *   signal of authorial intent we have, and the alternative (no propagation) leaves heavy-thread
 *   quests with all spawns unattributed.
 *
 * Callback registrations whose bodies fire in a context unrelated to the registration floor
 * are deliberately NOT followed: `set_qt_*` and `set_quest_board_handler` fire at the Hunter's
 * Guild / quest board on Pioneer 2, `set_chat_callback*` and `set_palettex_callback` are
 * per-client global handlers triggered on whichever floor the player is on at input time.
 *
 * Blocks not reachable from any floor handler are absent from the map (treated as "unknown floor"
 * by callers).
 */
private fun computeBlockToFloors(
    cfg: ControlFlowGraph,
    instructionSegments: List<InstructionSegment>,
): Map<BasicBlock, Set<Int>> {
    // Step 1: extract label -> floors from every set_floor_handler in the script.
    // Scanning all segments (not just label 0) matches what GetFloorMappings does and
    // covers quests that register handlers from nested segments.
    //
    // The map is multi-valued because a single handler label can be registered for many
    // floors (e.g. quests that share one ambience handler across floors 1..12). All those
    // floors must be propagated when we BFS from that label.
    val labelToFloors = mutableMapOf<Int, MutableSet<Int>>()
    for (segment in instructionSegments) {
        for (inst in segment.instructions) {
            if (inst.opcode.code != OP_SET_FLOOR_HANDLER_V3_V4.code) continue
            val floorArg = inst.args.getOrNull(0) as? IntArg ?: continue
            val labelArg = inst.args.getOrNull(1) as? IntArg ?: continue
            labelToFloors.getOrPut(labelArg.value) { mutableSetOf() }.add(floorArg.value)
        }
    }

    if (labelToFloors.isEmpty()) return emptyMap()

    // Step 2: build label -> entry block (the first block of the segment carrying that label).
    val labelToEntryBlock = mutableMapOf<Int, BasicBlock>()
    for (block in cfg.blocks) {
        // The first block of a segment starts at index 0 and is the only one whose labels are
        // the segment's labels.
        if (block.start != 0) continue
        for (label in block.segment.labels) {
            labelToEntryBlock[label] = block
        }
    }

    // Pre-compute next-in-segment lookup. cfg.blocks is in segment-traversal order, so the
    // following block in the global list is the segment-sequential successor — but only when
    // it shares the same segment.
    val nextInSegment = mutableMapOf<BasicBlock, BasicBlock>()
    for (i in cfg.blocks.indices) {
        val b = cfg.blocks[i]
        val n = cfg.blocks.getOrNull(i + 1) ?: continue
        if (n.segment === b.segment) nextInSegment[b] = n
    }

    // Step 2.5: pre-compute callback edges. For each block that registers a callback whose
    // body is associated with the registration floor (at_coords_call/at_coords_talk and
    // thread/thread_stg), resolve the target label and record an extra edge
    // `block -> labelEntry` for the BFS to follow.
    val callbackEdges = mutableMapOf<BasicBlock, MutableList<BasicBlock>>()
    for (block in cfg.blocks) {
        for (i in block.start until block.end) {
            val inst = block.segment.instructions[i]
            when (inst.opcode.code) {
                OP_AT_COORDS_CALL.code, OP_AT_COORDS_TALK.code -> {
                    // Single RegType[5] operand: (X, Y, Z, radius, label) at firstReg+0..+4.
                    val firstReg = (inst.args.getOrNull(0) as? IntArg)?.value ?: continue
                    if (firstReg !in 0..251) continue
                    val labelValues = getRegisterValue(cfg, inst, firstReg + 4)
                    if (labelValues.size != 1L) continue
                    val label = labelValues[0] ?: continue
                    val target = labelToEntryBlock[label] ?: continue
                    callbackEdges.getOrPut(block) { mutableListOf() }.add(target)
                }
                OP_THREAD.code, OP_THREAD_STG.code -> {
                    // Single inline ILabelType arg.
                    val label = (inst.args.getOrNull(0) as? IntArg)?.value ?: continue
                    val target = labelToEntryBlock[label] ?: continue
                    callbackEdges.getOrPut(block) { mutableListOf() }.add(target)
                }
            }
        }
    }

    // Step 3: forward, path-sensitive, **interprocedural** reachability from each floor
    // handler entry. We BFS once per (entry label, single floor) pair so that branch pruning
    // can use a single, known current floor.
    //
    // Edge model — we deliberately do NOT use `block.to` directly because the CFG, via
    // linkReturningBlocks, gives every callee's Return block edges to *all* of its callers'
    // after-call blocks. Following those would let BFS from one floor handler walk into
    // another floor handler's after-call code through a shared helper, polluting the floor
    // tag for every spawn downstream. So:
    // - Return blocks contribute no forward edges within the BFS body. Instead, the floor
    //   register state at every Return block reached during a callee walk is *unioned* into
    //   the callee's "exit state". The exit state is then propagated to the caller's
    //   after-call block (segment-sequential successor of the call) — not the call-site
    //   pre-call state. This is what makes "helper sets r52 = r53; ret" propagate to the
    //   caller's switch_jmp(r52) and let path-sensitivity prune it.
    // - Call blocks recurse into callee entries; the recursive call returns the merged exit
    //   state which becomes the after-call block's incoming state.
    // - All other block types use `block.to` (filtered by branch pruning when the terminator
    //   dispatches on a floor-tracking register).
    //
    // Visited keys are (block, floorRegs) pairs (per (handler, floor) instance). To prevent
    // infinite recursion on mutually-recursive callees, we track in-progress (entry, regs)
    // contexts and break cycles by returning the entry state. Per-instance exit-state
    // memoization keeps the recursion linear in distinct (entry, regs) contexts.
    val result = mutableMapOf<BasicBlock, MutableSet<Int>>()
    for ((entryLabel, floorIds) in labelToFloors) {
        val handlerEntry = labelToEntryBlock[entryLabel] ?: continue
        for (currentFloor in floorIds) {
            val visited = mutableSetOf<Pair<BasicBlock, Set<Int>>>()
            val exitStateCache = mutableMapOf<Pair<BasicBlock, Set<Int>>, Set<Int>>()
            val inProgress = mutableSetOf<Pair<BasicBlock, Set<Int>>>()

            // Recursive BFS that returns the merged register state at all Return blocks
            // reached from `start` with `startRegs`. The depth limit guards against
            // pathological call chains; PSO scripts rarely exceed ~20 levels.
            fun bfs(start: BasicBlock, startRegs: Set<Int>, depth: Int): Set<Int> {
                val key = start to startRegs
                exitStateCache[key]?.let { return it }
                if (key in inProgress) return startRegs
                if (depth >= 64) return startRegs
                inProgress.add(key)

                val queue = ArrayDeque<Pair<BasicBlock, Set<Int>>>()
                queue.add(key)
                var mergedExit: Set<Int>? = null

                while (queue.isNotEmpty()) {
                    val (b, regs) = queue.removeFirst()
                    if (!visited.add(b to regs)) continue
                    result.getOrPut(b) { mutableSetOf() }.add(currentFloor)

                    // Walk the block (excluding terminator) to compute the post-block state
                    // even for Return blocks — pre-`ret` instructions like `let`/`leti` still
                    // contribute to the exit state propagated to the caller.
                    val outgoing = walkBlockTrackingFloorRegs(b, regs, currentFloor)
                    val terminator = if (b.end > b.start) b.segment.instructions[b.end - 1] else null

                    if (b.branchType == BranchType.Return) {
                        mergedExit = (mergedExit ?: emptySet()) + outgoing
                        // Still follow callback edges registered within this block (a `ret`
                        // block can contain `thread_stg` / `at_coords_call` before the ret).
                        callbackEdges[b]?.forEach { queue.add(it to outgoing) }
                        continue
                    }

                    when (b.branchType) {
                        BranchType.Call -> {
                            val pruned = tryPruneSwitch(
                                terminator, outgoing, currentFloor, labelToEntryBlock,
                            )
                            val callees = pruned ?: b.to
                            // Recurse into each candidate callee, union their exit states.
                            // The after-call block inherits this union (not the pre-call state).
                            val mergedCalleeExit = mutableSetOf<Int>()
                            var anyCallee = false
                            for (callee in callees) {
                                anyCallee = true
                                mergedCalleeExit.addAll(bfs(callee, outgoing, depth + 1))
                            }
                            val afterCallState = if (anyCallee) mergedCalleeExit.toSet() else outgoing
                            nextInSegment[b]?.let { queue.add(it to afterCallState) }
                        }
                        BranchType.Jump -> {
                            val pruned = tryPruneSwitch(
                                terminator, outgoing, currentFloor, labelToEntryBlock,
                            )
                            val succs = pruned ?: b.to
                            for (s in succs) queue.add(s to outgoing)
                        }
                        BranchType.ConditionalJump -> {
                            val pruned = tryPruneConditional(
                                b, terminator, outgoing, currentFloor,
                                labelToEntryBlock, nextInSegment,
                            )
                            val succs = pruned ?: b.to
                            for (s in succs) queue.add(s to outgoing)
                        }
                        BranchType.None -> {
                            for (s in b.to) queue.add(s to outgoing)
                        }
                        BranchType.Return -> { /* handled above */ }
                    }
                    callbackEdges[b]?.forEach { queue.add(it to outgoing) }
                }

                inProgress.remove(key)
                val exit = mergedExit ?: startRegs
                exitStateCache[key] = exit
                return exit
            }

            bfs(handlerEntry, emptySet(), 0)
        }
    }

    return result
}

/**
 * Walks `block` instruction by instruction, updating which registers currently hold the BFS
 * instance's "current floor" value. Skips the terminator: the terminator is examined separately
 * for branch pruning and never writes to the floor-tracking lineage.
 */
private fun walkBlockTrackingFloorRegs(
    block: BasicBlock,
    incoming: Set<Int>,
    currentFloor: Int,
): Set<Int> {
    var regs = incoming
    val end = if (block.branchType != BranchType.None) block.end - 1 else block.end
    for (i in block.start until end) {
        regs = updateFloorRegsForInstruction(block.segment.instructions[i], regs, currentFloor)
    }
    return regs
}

private fun updateFloorRegsForInstruction(
    inst: Instruction,
    regs: Set<Int>,
    currentFloor: Int,
): Set<Int> {
    when (inst.opcode.code) {
        OP_GET_FLOOR_NUMBER.code -> {
            // get_floor_number slot, baseReg writes floor at +0 and room at +1.
            val baseReg = (inst.args.getOrNull(1) as? IntArg)?.value ?: return regs
            return (regs + baseReg) - (baseReg + 1)
        }
        OP_LET.code -> {
            // let dest, src copies src's value into dest.
            val dest = (inst.args.getOrNull(0) as? IntArg)?.value ?: return regs
            val src = (inst.args.getOrNull(1) as? IntArg)?.value ?: return regs
            return if (src in regs) regs + dest else regs - dest
        }
        OP_LETI.code -> {
            // leti dest, K writes literal K. If K equals the BFS's current floor, treat dest
            // as a floor register (a script may stash a per-branch floor sentinel that way).
            // Otherwise dest no longer holds the floor value.
            val dest = (inst.args.getOrNull(0) as? IntArg)?.value ?: return regs
            val value = (inst.args.getOrNull(1) as? IntArg)?.value ?: return regs - dest
            return if (value == currentFloor) regs + dest else regs - dest
        }
        else -> {
            // Default: any opcode whose declared RegType params include a writable register
            // clears that register from the floor-tracking set.
            var result = regs
            val params = inst.opcode.params
            val argLen = minOf(inst.args.size, params.size)
            for (j in 0 until argLen) {
                val type = params[j].type
                if (type !is RegType) continue
                val regs2 = type.registers ?: continue
                val regRef = (inst.args[j] as? IntArg)?.value ?: continue
                for ((k, regParam) in regs2.withIndex()) {
                    if (regParam.write) result = result - (regRef + k)
                }
            }
            return result
        }
    }
}

/**
 * For [OP_SWITCH_JMP] / [OP_SWITCH_CALL] terminators that dispatch on a register currently
 * holding the BFS's current floor value, returns the single branch consistent with that floor
 * (an empty list if the floor is out of range). Returns `null` when no pruning is possible.
 */
private fun tryPruneSwitch(
    inst: Instruction?,
    floorRegs: Set<Int>,
    currentFloor: Int,
    labelToEntryBlock: Map<Int, BasicBlock>,
): List<BasicBlock>? {
    if (inst == null) return null
    if (inst.opcode.code != OP_SWITCH_JMP.code && inst.opcode.code != OP_SWITCH_CALL.code) {
        return null
    }
    val reg = (inst.args.getOrNull(0) as? IntArg)?.value ?: return null
    if (reg !in floorRegs) return null
    // switch_jmp/switch_call args: args[0]=reg, args[1+i]=label_i.
    // If currentFloor is out of range of the label table, the switch is a no-op at runtime
    // and execution falls through. Return null so the caller follows the default edges
    // (b.to + sequential next-in-segment) rather than dropping all successors.
    val labelArg = inst.args.getOrNull(currentFloor + 1) as? IntArg ?: return null
    val target = labelToEntryBlock[labelArg.value] ?: return null
    return listOf(target)
}

/**
 * Pruning for [BranchType.ConditionalJump] terminators. Handles [OP_SWITCH_JMP],
 * [OP_JMPI_E], [OP_JMPI_NE] when the discriminant is a floor-tracking register.
 * Returns `null` when no pruning is possible.
 */
private fun tryPruneConditional(
    b: BasicBlock,
    inst: Instruction?,
    floorRegs: Set<Int>,
    currentFloor: Int,
    labelToEntryBlock: Map<Int, BasicBlock>,
    nextInSegment: Map<BasicBlock, BasicBlock>,
): List<BasicBlock>? {
    if (inst == null) return null

    if (inst.opcode.code == OP_SWITCH_JMP.code) {
        return tryPruneSwitch(inst, floorRegs, currentFloor, labelToEntryBlock)
    }

    if (inst.opcode.code == OP_JMPI_E.code || inst.opcode.code == OP_JMPI_NE.code) {
        val reg = (inst.args.getOrNull(0) as? IntArg)?.value ?: return null
        if (reg !in floorRegs) return null
        val constVal = (inst.args.getOrNull(1) as? IntArg)?.value ?: return null
        val labelArg = inst.args.getOrNull(2) as? IntArg ?: return null
        val takeBranch = when (inst.opcode.code) {
            OP_JMPI_E.code -> currentFloor == constVal
            OP_JMPI_NE.code -> currentFloor != constVal
            else -> error("unreachable")
        }
        return if (takeBranch) {
            listOfNotNull(labelToEntryBlock[labelArg.value])
        } else {
            listOfNotNull(nextInSegment[b])
        }
    }

    return null
}
