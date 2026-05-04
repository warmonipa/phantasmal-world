package world.phantasmal.psolib.asm.dataFlowAnalysis

import mu.KotlinLogging
import world.phantasmal.psolib.asm.InstructionSegment
import world.phantasmal.psolib.asm.IntArg
import world.phantasmal.psolib.asm.OP_AT_COORDS_CALL
import world.phantasmal.psolib.asm.OP_AT_COORDS_TALK
import world.phantasmal.psolib.asm.OP_PARTICLE_V3
import world.phantasmal.psolib.asm.OP_SET_FLOOR_HANDLER
import world.phantasmal.psolib.asm.OP_THREAD
import world.phantasmal.psolib.asm.OP_THREAD_STG

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
 *   this invocation. Empty when the spawn could not be attributed to any floor (e.g. it lives
 *   inside a chat/menu handler reached only from label 0). Callers should treat an empty set
 *   as "unknown floor — show everywhere" rather than hide the marker.
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
            if (inst.opcode.code != OP_PARTICLE_V3.code) continue

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

    return spawns
}

/**
 * Returns a mapping from each reachable [BasicBlock] to the set of floor IDs whose
 * [OP_SET_FLOOR_HANDLER]-registered entry blocks transitively reach it.
 *
 * Reachability follows direct CFG edges (`block.to`) plus a small set of callback-registration
 * edges where the callback body is intended to be associated with the registration floor:
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
            if (inst.opcode.code != OP_SET_FLOOR_HANDLER.code) continue
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

    // Step 3: forward BFS from each floor handler entry, accumulate floors per visited block.
    //
    // Edge model — we deliberately do NOT use `block.to` directly because the CFG, via
    // linkReturningBlocks, gives every callee's Return block edges to *all* of its callers'
    // after-call blocks. Following those would let BFS from one floor handler walk into
    // another floor handler's after-call code through a shared helper, polluting the floor
    // tag for every spawn downstream. So:
    // - Return blocks contribute no forward edges.
    // - Call blocks contribute their callee entries (from `block.to`) plus the
    //   segment-sequential successor (the after-call block) directly.
    // - All other block types use `block.to`.
    val result = mutableMapOf<BasicBlock, MutableSet<Int>>()
    for ((entryLabel, floorIds) in labelToFloors) {
        val entry = labelToEntryBlock[entryLabel] ?: continue
        val visited = mutableSetOf<BasicBlock>()
        val queue = ArrayDeque<BasicBlock>()
        queue.add(entry)
        while (queue.isNotEmpty()) {
            val b = queue.removeFirst()
            if (!visited.add(b)) continue
            result.getOrPut(b) { mutableSetOf() }.addAll(floorIds)
            when (b.branchType) {
                BranchType.Return -> {
                    // Skip — `to` only contains return-to-caller edges.
                }
                BranchType.Call -> {
                    for (next in b.to) queue.add(next)
                    nextInSegment[b]?.let { queue.add(it) }
                }
                else -> for (next in b.to) queue.add(next)
            }
            callbackEdges[b]?.forEach { queue.add(it) }
        }
    }

    return result
}
