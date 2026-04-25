package world.phantasmal.psolib.asm.dataFlowAnalysis

import mu.KotlinLogging
import world.phantasmal.psolib.asm.InstructionSegment
import world.phantasmal.psolib.asm.IntArg
import world.phantasmal.psolib.asm.OP_PARTICLE_V3
import world.phantasmal.psolib.asm.OP_SET_FLOOR_HANDLER

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
 * [OP_SET_FLOOR_HANDLER]-registered entry blocks transitively reach it via `to` edges.
 *
 * Blocks not reachable from any floor handler are absent from the map (treated as "unknown floor"
 * by callers).
 */
private fun computeBlockToFloors(
    cfg: ControlFlowGraph,
    instructionSegments: List<InstructionSegment>,
): Map<BasicBlock, Set<Int>> {
    // Step 1: extract label -> floor from set_floor_handler in label 0 segment.
    val labelToFloor = mutableMapOf<Int, Int>()
    val label0Segment = instructionSegments.find { 0 in it.labels }
    if (label0Segment != null) {
        for (inst in label0Segment.instructions) {
            if (inst.opcode.code != OP_SET_FLOOR_HANDLER.code) continue
            val floorArg = inst.args.getOrNull(0) as? IntArg ?: continue
            val labelArg = inst.args.getOrNull(1) as? IntArg ?: continue
            labelToFloor[labelArg.value] = floorArg.value
        }
    }

    if (labelToFloor.isEmpty()) return emptyMap()

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

    // Step 3: forward BFS from each floor handler entry, accumulate floors per visited block.
    val result = mutableMapOf<BasicBlock, MutableSet<Int>>()
    for ((entryLabel, floorId) in labelToFloor) {
        val entry = labelToEntryBlock[entryLabel] ?: continue
        val visited = mutableSetOf<BasicBlock>()
        val queue = ArrayDeque<BasicBlock>()
        queue.add(entry)
        while (queue.isNotEmpty()) {
            val b = queue.removeFirst()
            if (!visited.add(b)) continue
            result.getOrPut(b) { mutableSetOf() }.add(floorId)
            for (next in b.to) queue.add(next)
        }
    }

    return result
}
