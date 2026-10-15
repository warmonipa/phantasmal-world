package world.phantasmal.psolib.fileFormats.quest.walkthrough

import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.BytecodeIr
import world.phantasmal.psolib.asm.dataFlowAnalysis.FloorMapping
import world.phantasmal.psolib.asm.dataFlowAnalysis.WalkthroughScriptAnalysis
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.QuestObject
import world.phantasmal.psolib.fileFormats.quest.getNormalBossTeleporterDestinationFloor
import kotlin.math.roundToInt

/**
 * The floors one client plays through, in order, from the quest's start to its completion.
 *
 * Built from quest data only (no collision geometry), so it can be computed for every floor while
 * the editor only has the visible floor's geometry.
 */
data class QuestJourney(
    val visits: List<FloorVisit>,
    val diagnostics: List<String>,
    /** Landing floor of each raw `set_mainwarp` operand that leads to a journey floor. */
    val mainWarpLandings: Map<Int, Int>,
)

data class FloorVisit(
    val floorId: Int,
    /** The Player Set where the client appears on this floor, null if the quest has none. */
    val arrival: QuestObject?,
    /** How the client leaves this floor, null on the final visit. */
    val departure: FloorTransition?,
    /** False for floors the journey could not place in order; they are appended at the end. */
    val orderKnown: Boolean = true,
)

sealed class FloorTransition {
    abstract val toFloorId: Int

    /** Objects on the floor that lead to [toFloorId] (teleporters, Quest Warps, boss teleporters). */
    data class Exit(val exits: List<QuestObject>, override val toFloorId: Int) : FloorTransition()

    /** The Pioneer 2 Ragol warp, enabled by `set_mainwarp`. */
    data class MainWarp(override val toFloorId: Int) : FloorTransition()

    /** A client-scoped `go_floor` run by a quest script. */
    data class Script(override val toFloorId: Int) : FloorTransition()
}

fun QuestObject.playerSetSlot(): Int = data.getFloat(40).roundToInt()

/** Only the low 16 bits are meaningful: some quests store 65536 for a forward Player Set. */
fun QuestObject.playerSetReturnFlag(): Int = data.getInt(52) and 0xFFFF

/** The Player set ID a Quest Warp lands on (`float@40`). */
fun QuestObject.questWarpPlayerSetId(): Int = data.getFloat(40).roundToInt()

/** The floor this object leads to, or null if it is not a floor exit. */
fun QuestObject.exitDestinationFloor(episode: Episode): Int? {
    val destination = when (type) {
        ObjectType.BossTeleporter -> getNormalBossTeleporterDestinationFloor(episode, floorId)
        in FLOOR_EXIT_TYPES -> destinationFloor
        else -> null
    }
    return destination?.takeIf { it != floorId }
}

private val FLOOR_EXIT_TYPES = setOf(
    ObjectType.Teleporter,
    ObjectType.TeleporterEp2,
    ObjectType.RuinsTeleporter,
    ObjectType.QuestWarp,
    ObjectType.MainRagolTeleporterBattleInNextArea,
    ObjectType.WarpInBarbaRayRoom,
)

/** Episode 2 Challenge quests lack the DAT Challenge flag but use Challenge opcodes. */
fun usesChallengeModeScript(bytecodeIr: BytecodeIr): Boolean =
    bytecodeIr.instructionSegments().any { segment ->
        segment.instructions.any {
            val mnemonic = it.opcode.mnemonic
            mnemonic.startsWith("chl_") || mnemonic.startsWith("disp_chl")
        }
    }

/**
 * Plans the floor visits of [clientId].
 *
 * @param analyses script analyses of every script entry reachable for [clientId].
 */
fun planQuestJourney(
    episode: Episode,
    objects: List<QuestObject>,
    floorMappings: List<FloorMapping>,
    analyses: List<WalkthroughScriptAnalysis>,
    isChallengeMode: Boolean,
    clientId: Int,
): QuestJourney {
    val playerSets = objects.filter {
        it.type == ObjectType.PlayerSet && it.playerSetSlot() == clientId
    }
    if (playerSets.isEmpty()) {
        return QuestJourney(emptyList(), listOf("No Player Set for client $clientId."), emptyMap())
    }
    val diagnostics = mutableListOf<String>()
    // Floors without a Player Set for this client are floors the client does not visit.
    val requiredFloors = playerSets.mapTo(sortedSetOf()) { it.floorId }

    fun playerSet(floorId: Int, returnFlag: Int): QuestObject? =
        playerSets.firstOrNull { it.floorId == floorId && it.playerSetReturnFlag() == returnFlag }

    fun arrival(floorId: Int, returnFlag: Int): QuestObject? {
        playerSet(floorId, returnFlag)?.let { return it }
        if (floorId !in requiredFloors) return null
        diagnostics += "Floor $floorId: no Player Set with Return flag $returnFlag for client $clientId."
        return playerSet(floorId, if (returnFlag == 0) 1 else 0)
            ?: playerSets.first { it.floorId == floorId }
    }

    val exitsByFloor: Map<Int, List<Pair<QuestObject, Int>>> = objects
        .mapNotNull { obj -> obj.exitDestinationFloor(episode)?.let { obj to it } }
        .groupBy { it.first.floorId }

    if (isChallengeMode) {
        val floors = requiredFloors.filter { it != 0 }
        val visits = floors.mapIndexed { index, floorId ->
            val next = floors.getOrNull(index + 1)
            FloorVisit(
                floorId,
                arrival(floorId, 0),
                next?.let {
                    FloorTransition.Exit(exitsByFloor[floorId].orEmpty().map { it.first }, it)
                },
            )
        }
        return QuestJourney(visits, diagnostics, emptyMap())
    }

    // Floor graph: edges[from][to] = how the client gets there.
    val edges = mutableMapOf<Int, MutableMap<Int, FloorTransition>>()
    fun isTarget(from: Int, to: Int) = to != from && (to == 0 || to in requiredFloors)

    for ((floorId, exits) in exitsByFloor) {
        for ((to, objectsToFloor) in exits.groupBy({ it.second }, { it.first })) {
            if (isTarget(floorId, to)) {
                edges.getOrPut(floorId, ::mutableMapOf)[to] =
                    FloorTransition.Exit(objectsToFloor, to)
            }
        }
    }
    for (goFloor in analyses.flatMap { it.goFloors }) {
        if (isTarget(goFloor.sourceFloorId, goFloor.targetFloorId)) {
            edges.getOrPut(goFloor.sourceFloorId, ::mutableMapOf)
                .getOrPut(goFloor.targetFloorId) { FloorTransition.Script(goFloor.targetFloorId) }
        }
    }

    val mainWarpLandings = mutableMapOf<Int, Int>()
    for (operand in analyses.flatMapTo(sortedSetOf()) { it.mainWarpAreas }) {
        val floorId = mainWarpFloor(operand, floorMappings, requiredFloors) ?: continue
        mainWarpLandings[operand] = floorId
    }
    fun reachableFrom(start: Int, graph: Map<Int, Map<Int, FloorTransition>>): Set<Int> {
        val seen = mutableSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            for (next in graph[queue.removeFirst()].orEmpty().keys) {
                if (seen.add(next)) queue += next
            }
        }
        return seen
    }
    // A Ragol warp to the second floor of a teleporter chain lands on the chain's head when the
    // head cannot be reached otherwise (e.g. VR Temple Alpha before Beta).
    val reachableWithoutWarp = reachableFrom(0, edges)
    for ((operand, target) in mainWarpLandings.toMap()) {
        var head = target
        val seen = mutableSetOf(target)
        while (true) {
            val predecessor = edges.entries
                .filter { (from, to) -> head in to && from != 0 && from !in reachableWithoutWarp }
                .map { it.key }
                .filter { it !in seen }
                .minOrNull() ?: break
            seen += predecessor
            head = predecessor
        }
        mainWarpLandings[operand] = head
    }
    for (landing in mainWarpLandings.values.toSortedSet()) {
        edges.getOrPut(0, ::mutableMapOf).getOrPut(landing) { FloorTransition.MainWarp(landing) }
    }

    val start = if (playerSet(0, 0) != null && !edges[0].isNullOrEmpty()) {
        0
    } else {
        requiredFloors.filter { it != 0 && playerSet(it, 0) != null }.minOrNull()
            ?: requiredFloors.firstOrNull { it != 0 }
            ?: 0
    }

    fun shortestPaths(from: Int): Map<Int, List<Int>> {
        val paths = mutableMapOf(from to listOf(from))
        val queue = ArrayDeque(listOf(from))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (next in edges[current].orEmpty().keys.sorted()) {
                if (next !in paths) {
                    paths[next] = paths.getValue(current) + next
                    queue += next
                }
            }
        }
        return paths
    }

    val order = mutableListOf(start)
    val transitions = mutableListOf<FloorTransition>()
    val visited = mutableSetOf(start)
    fun walk(path: List<Int>) {
        for ((from, to) in path.zipWithNext()) {
            transitions += edges.getValue(from).getValue(to)
            order += to
            visited += to
        }
    }
    var current = start
    while (true) {
        val paths = shortestPaths(current)
        val next = (requiredFloors - visited)
            .filter { it in paths }
            .minWithOrNull(compareBy({ paths.getValue(it).size }, { it }))
            ?: break
        walk(paths.getValue(next))
        current = next
    }
    // The quest is reported at the Hunter's Guild counter on Pioneer 2.
    if (current != 0) shortestPaths(current)[0]?.let(::walk)

    val visits = order.mapIndexed { index, floorId ->
        val arrival = if (index == 0) {
            arrival(floorId, 0)
        } else {
            val from = order[index - 1]
            val questWarp = (transitions[index - 1] as? FloorTransition.Exit)
                ?.exits?.firstOrNull { it.type == ObjectType.QuestWarp }
            val returnFlag = when {
                questWarp != null -> questWarp.questWarpPlayerSetId()
                floorId == 0 || (from != 0 && from > floorId) -> 1
                else -> 0
            }
            arrival(floorId, returnFlag)
        }
        FloorVisit(floorId, arrival, transitions.getOrNull(index))
    }.toMutableList()

    for (floorId in requiredFloors - visited - setOf(0)) {
        diagnostics += "Floor $floorId: order unknown for client $clientId."
        visits += FloorVisit(floorId, arrival(floorId, 0), null, orderKnown = false)
    }

    return QuestJourney(visits, diagnostics, mainWarpLandings)
}

/**
 * Maps a raw `set_mainwarp` operand (episode-absolute area) to the logical floor it lands on, or
 * the next quest floor with a higher area when the area itself is not in the quest.
 */
private fun mainWarpFloor(
    operand: Int,
    floorMappings: List<FloorMapping>,
    requiredFloors: Set<Int>,
): Int? {
    val area = when {
        operand >= 36 -> operand - 36
        operand >= 18 -> operand - 18
        else -> operand
    }
    if (area == 0) return null
    fun areaOf(floorId: Int): Int =
        floorMappings.firstOrNull { it.floorId == floorId }?.mapAreaId ?: floorId
    val candidates = requiredFloors.filter { it != 0 }
    candidates.filter { areaOf(it) == area }.minOrNull()?.let { return it }
    return candidates
        .filter { areaOf(it) > area }
        .minWithOrNull(compareBy({ areaOf(it) }, { it }))
}
