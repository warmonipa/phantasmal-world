package world.phantasmal.psolib.fileFormats.quest.walkthrough

import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.BytecodeIr
import world.phantasmal.psolib.asm.dataFlowAnalysis.FloorMapping
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptFloorTransitions
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

/**
 * The Player Sets of [clientId]. Most quests number slots 0–3, but some floors number them 1–4;
 * when a floor's Player Sets with one Return flag use exactly slots 1–4, client c uses slot c + 1.
 */
fun playerSetsForClient(objects: List<QuestObject>, clientId: Int): List<QuestObject> =
    objects
        .filter { it.type == ObjectType.PlayerSet }
        .groupBy { it.floorId to it.playerSetReturnFlag() }
        .values
        .flatMap { group ->
            val slots = group.mapTo(mutableSetOf()) { it.playerSetSlot() }
            val slot = if (slots == ONE_BASED_SLOTS) clientId + 1 else clientId
            group.filter { it.playerSetSlot() == slot }
        }

private val ONE_BASED_SLOTS = setOf(1, 2, 3, 4)

/** The Player set ID a Quest Warp lands on (`float@40`). */
fun QuestObject.questWarpPlayerSetId(): Int = data.getFloat(40).roundToInt()

/**
 * The floor this object leads to, or null if it is not a floor exit. [areaId] is the map area the
 * object's floor is designated with.
 */
fun QuestObject.exitDestinationFloor(episode: Episode, areaId: Int = floorId): Int? {
    val destination = when (type) {
        ObjectType.BossTeleporter -> getNormalBossTeleporterDestinationFloor(episode, floorId)
        in FLOOR_EXIT_TYPES -> destinationFloor
        // newserv: in Vol Opt's arena 0x0160 is TObjWarpBoss03, the invisible warp behind the door
        // to Ruins (param4 = destination floor); everywhere else it is a poison fog collision.
        ObjectType.Poison -> data.getInt(52).takeIf { episode == Episode.I && areaId == VOL_OPT_AREA }
        else -> null
    }
    return destination?.takeIf { it != floorId }
}

private const val VOL_OPT_AREA = 13

private val FLOOR_EXIT_TYPES = setOf(
    ObjectType.Teleporter,
    ObjectType.TeleporterEp2,
    ObjectType.RuinsTeleporter,
    ObjectType.QuestWarp,
    ObjectType.MainRagolTeleporterBattleInNextArea,
    ObjectType.WarpInBarbaRayRoom,
)

/**
 * Areas the Pioneer 2 Ragol warp offers once game progress unlocks them: the first area of each
 * region. Quests add destinations with `set_mainwarp` and close them with `clear_mainwarp`.
 */
private val RAGOL_WARP_REGION_ENTRIES: Map<Episode, Set<Int>> = mapOf(
    // Forest 1, Cave 1, Mine 1, Ruins 1.
    Episode.I to setOf(1, 3, 6, 8),
    // VR Temple Alpha, VR Spaceship Alpha, Central Control Area, Seabed Upper Levels.
    Episode.II to setOf(1, 3, 5, 10),
    // Crater routes (East, West, South, North) and Desert 1.
    Episode.IV to setOf(1, 2, 3, 4, 6),
)

private const val DEAD_END_COST = 1000

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
 * @param fallback floor transitions anywhere in the script; used only for floors that the
 * reachable transitions cannot place.
 */
fun planQuestJourney(
    episode: Episode,
    objects: List<QuestObject>,
    floorMappings: List<FloorMapping>,
    analyses: List<WalkthroughScriptAnalysis>,
    isChallengeMode: Boolean,
    clientId: Int,
    fallback: ScriptFloorTransitions? = null,
): QuestJourney {
    val playerSets = playerSetsForClient(objects, clientId)
    val anyPlayerSetFloors = objects
        .filter { it.type == ObjectType.PlayerSet }
        .mapTo(mutableSetOf()) { it.floorId }
    if (playerSets.isEmpty() && anyPlayerSetFloors.isNotEmpty()) {
        return QuestJourney(emptyList(), listOf("No Player Set for client $clientId."), emptyMap())
    }
    val diagnostics = mutableListOf<String>()
    val clientPlayerSetFloors = playerSets.mapTo(mutableSetOf()) { it.floorId }
    // A floor with Player Sets for other clients only is a floor this client does not visit:
    // that is how quests split players. Floors without any Player Set are played by everyone.
    val requiredFloors: List<Int> = objects
        .map { it.floorId }
        .distinct()
        .filter { it in clientPlayerSetFloors || it !in anyPlayerSetFloors }
        .sorted()

    fun playerSet(floorId: Int, returnFlag: Int): QuestObject? =
        playerSets.firstOrNull { it.floorId == floorId && it.playerSetReturnFlag() == returnFlag }

    val exitsByFloor: Map<Int, List<Pair<QuestObject, Int>>> = objects
        .mapNotNull { obj ->
            val areaId = floorMappings.firstOrNull { it.floorId == obj.floorId }?.mapAreaId
                ?: obj.floorId
            obj.exitDestinationFloor(episode, areaId)?.let { obj to it }
        }
        .groupBy { it.first.floorId }

    fun arrival(floorId: Int, returnFlag: Int, fromFloorId: Int?): QuestObject? {
        playerSet(floorId, returnFlag)?.let { return it }
        if (floorId !in requiredFloors) return null
        if (floorId in clientPlayerSetFloors) {
            // Quests that disable telepipes (p_disablewarp) often omit return Player Sets.
            diagnostics += "Floor $floorId: no Player Set with Return flag $returnFlag for client " +
                "$clientId; using the other Return flag."
            return playerSet(floorId, if (returnFlag == 0) 1 else 0)
                ?: playerSets.first { it.floorId == floorId }
        }
        // No Player Set at all: players appear next to the floor's teleporter back.
        val exits = exitsByFloor[floorId].orEmpty()
        val proxy = exits.firstOrNull { it.second == fromFloorId } ?: exits.firstOrNull()
        diagnostics += if (proxy == null) {
            "Floor $floorId: no Player Set for client $clientId; arrival unknown."
        } else {
            "Floor $floorId: no Player Set for client $clientId; arriving at its exit to " +
                "floor ${proxy.second}."
        }
        return proxy?.first
    }

    if (isChallengeMode) {
        val floors = requiredFloors.filter { it != 0 }
        val visits = floors.mapIndexed { index, floorId ->
            val next = floors.getOrNull(index + 1)
            FloorVisit(
                floorId,
                arrival(floorId, 0, floors.getOrNull(index - 1)),
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
    for (operand in analyses.flatMap { it.mainWarpAreas }.distinct().sorted()) {
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
    // head cannot be reached otherwise, not even from the warp's own landing floor (e.g. VR
    // Temple Alpha before Beta).
    for ((operand, target) in mainWarpLandings.toMap()) {
        val reachable = reachableFrom(0, edges) + reachableFrom(target, edges)
        var head = target
        val seen = mutableSetOf(target)
        while (true) {
            // Only a single unreachable predecessor forms a chain; several (a hub that other
            // floors return to) mean the warp lands on the hub itself.
            val predecessor = edges.entries
                .filter { (from, to) -> head in to && from != 0 && from !in reachable }
                .map { it.key }
                .singleOrNull() ?: break
            if (!seen.add(predecessor)) break
            head = predecessor
        }
        mainWarpLandings[operand] = head
    }
    for (landing in mainWarpLandings.values.distinct().sorted()) {
        edges.getOrPut(0, ::mutableMapOf).getOrPut(landing) { FloorTransition.MainWarp(landing) }
    }

    val initialFloor = fallback?.initialFloors?.singleOrNull()?.takeIf { it in requiredFloors }
    val start = if (initialFloor != null) {
        initialFloor
    } else if (playerSets.any { it.floorId == 0 } && !edges[0].isNullOrEmpty()) {
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
    // Edges added by the fallbacks, and the floors first entered through them.
    val fallbackEdges = mutableSetOf<Pair<Int, Int>>()
    val progressEdges = mutableSetOf<Pair<Int, Int>>()
    val inferredFloors = mutableSetOf<Int>()
    val progressFloors = mutableSetOf<Int>()
    fun walk(path: List<Int>) {
        for ((from, to) in path.zipWithNext()) {
            transitions += edges.getValue(from).getValue(to)
            order += to
            if (to !in visited) {
                if (from to to in fallbackEdges) inferredFloors += to
                if (from to to in progressEdges) progressFloors += to
            }
            visited += to
        }
    }
    fun addFallbackEdges(): Boolean {
        if (fallback == null) return false
        val unvisited = requiredFloors.filter { it != 0 && it !in visited }
        var added = false
        for (target in fallback.goFloorTargets) {
            // Targets already visited (e.g. Pioneer 2) still connect the remaining floors.
            if (target != 0 && target !in requiredFloors) continue
            // The script's floor is unknown: it can run on any floor the client is on.
            for (from in requiredFloors + 0) {
                if (from == target) continue
                edges.getOrPut(from, ::mutableMapOf).getOrPut(target) {
                    added = true
                    fallbackEdges += from to target
                    FloorTransition.Script(target)
                }
            }
        }
        for (operand in fallback.mainWarpAreas) {
            val landing = mainWarpFloor(operand, floorMappings, requiredFloors) ?: continue
            if (landing !in unvisited) continue
            mainWarpLandings.getOrPut(operand) { landing }
            edges.getOrPut(0, ::mutableMapOf).getOrPut(landing) {
                added = true
                fallbackEdges += 0 to landing
                FloorTransition.MainWarp(landing)
            }
        }
        return added
    }

    fun addProgressWarpEdges(): Boolean {
        val cleared = fallback?.clearedMainWarpAreas.orEmpty()
            .mapNotNull { mainWarpFloor(it, floorMappings, requiredFloors) }
        val entries = RAGOL_WARP_REGION_ENTRIES[episode].orEmpty()
        var added = false
        for (floorId in requiredFloors) {
            if (floorId == 0 || floorId in visited || floorId in cleared) continue
            val area = floorMappings.firstOrNull { it.floorId == floorId }?.mapAreaId ?: floorId
            if (area !in entries) continue
            edges.getOrPut(0, ::mutableMapOf).getOrPut(floorId) {
                added = true
                progressEdges += 0 to floorId
                FloorTransition.MainWarp(floorId)
            }
        }
        return added
    }

    var current = start
    var fallbackStage = 0
    while (true) {
        val paths = shortestPaths(current)
        val unvisited = requiredFloors.filter { it !in visited && it in paths }
        // Look one floor ahead so that a dead end (e.g. a boss arena that returns to Pioneer 2)
        // is left for last when another floor is equally near.
        fun lookahead(candidate: Int): Int {
            val others = unvisited.filter { it != candidate }
            if (others.isEmpty()) return 0
            val onward = shortestPaths(candidate)
            return others
                .filter { it in onward }
                .minOfOrNull { onward.getValue(it).size }
                // Nothing else is reachable from there: a dead end.
                ?: DEAD_END_COST
        }
        // Equally near floors are ordered by the complete journey they start, e.g. the tower
        // whose top sends players on to the other tower is played first.
        val tourCost = remainingTourCost(unvisited, ::shortestPaths, returnFloor = 0.takeIf {
            it in requiredFloors
        })
        val next = unvisited.minWithOrNull(compareBy(
            { paths.getValue(it).size + lookahead(it) },
            { tourCost(current, it) },
            { paths.getValue(it).size },
            { it },
        ))
        if (next == null) {
            // Unplaced floors: first scripts the analysis cannot trace, then the Ragol warp
            // destinations that game progress opens (quests only add or clear them).
            if (fallbackStage == 0) {
                fallbackStage = 1
                if (addFallbackEdges()) continue
            }
            if (fallbackStage == 1) {
                fallbackStage = 2
                if (addProgressWarpEdges()) continue
            }
            break
        }
        walk(paths.getValue(next))
        current = next
    }
    for (floorId in requiredFloors) {
        if (floorId !in visited) continue
        if (floorId in inferredFloors) {
            diagnostics += "Floor $floorId: entered through a script the analysis cannot trace."
        } else if (floorId in progressFloors) {
            diagnostics += "Floor $floorId: entered through the Ragol warp opened by game progress."
        }
    }
    // The quest is reported at the Hunter's Guild counter on Pioneer 2, when Pioneer 2 is part of
    // this client's quest data.
    if (current != 0 && 0 in requiredFloors) shortestPaths(current)[0]?.let(::walk)

    val visits = order.mapIndexed { index, floorId ->
        val arrival = if (index == 0) {
            // The quest start may use any of the client's Player Sets on its floor (some quests
            // only place return sets on Pioneer 2).
            playerSet(floorId, 0)
                ?: playerSets.firstOrNull { it.floorId == floorId }
                ?: arrival(floorId, 0, null)
        } else {
            val from = order[index - 1]
            val questWarp = (transitions[index - 1] as? FloorTransition.Exit)
                ?.exits?.firstOrNull { it.type == ObjectType.QuestWarp }
            val returnFlag = when {
                questWarp != null -> questWarp.questWarpPlayerSetId()
                floorId == 0 || (from != 0 && from > floorId) -> 1
                else -> 0
            }
            arrival(floorId, returnFlag, from)
        }
        FloorVisit(floorId, arrival, transitions.getOrNull(index))
    }.toMutableList()

    for (floorId in requiredFloors.filter { it != 0 && it !in visited }) {
        // A floor the journey passed the entrance of, but could not come back to, is an
        // alternative branch (e.g. a menu choice between courses).
        val branchFrom = order.firstOrNull { from -> floorId in shortestPaths(from) }
        diagnostics += if (branchFrom != null) {
            "Floor $floorId: alternative branch from floor $branchFrom."
        } else {
            "Floor $floorId: order unknown for client $clientId."
        }
        visits += FloorVisit(floorId, arrival(floorId, 0, null), null, orderKnown = false)
    }

    return QuestJourney(visits, diagnostics, mainWarpLandings)
}

/**
 * Returns the number of floor transitions of the shortest journey that goes from a floor to
 * `first`, then visits the rest of [floors] and ends on [returnFloor] (when given). Floors passed on
 * the way count as visited. A floor that cannot be reached any more costs [DEAD_END_COST].
 */
private fun remainingTourCost(
    floors: List<Int>,
    shortestPaths: (from: Int) -> Map<Int, List<Int>>,
    returnFloor: Int?,
): (from: Int, first: Int) -> Int {
    val index = floors.withIndex().associate { (i, floor) -> floor to i }
    val pathsFrom = mutableMapOf<Int, Map<Int, List<Int>>>()
    fun paths(from: Int) = pathsFrom.getOrPut(from) { shortestPaths(from) }
    fun maskOf(path: List<Int>): Long =
        path.fold(0L) { mask, floor -> index[floor]?.let { mask or (1L shl it) } ?: mask }

    val exact = floors.size <= MAX_EXACT_TOUR_FLOORS
    val memo = mutableMapOf<Pair<Int, Long>, Int>()
    fun cost(from: Int, remaining: Long): Int {
        if (remaining == 0L) {
            if (returnFloor == null || from == returnFloor) return 0
            return paths(from)[returnFloor]?.let { it.size - 1 } ?: 0
        }
        memo[from to remaining]?.let { return it }
        val reachable = paths(from)
        var best = Int.MAX_VALUE
        for ((i, floor) in floors.withIndex()) {
            if (remaining and (1L shl i) == 0L) continue
            val path = reachable[floor] ?: continue
            val rest = remaining and maskOf(path).inv()
            // Beyond the exact limit, only the nearest floor is followed (greedy).
            val onward = if (exact) cost(floor, rest) else 0
            best = minOf(best, path.size - 1 + onward)
        }
        if (best == Int.MAX_VALUE) {
            // Nothing else is reachable from here: a dead end.
            best = DEAD_END_COST * remaining.countOneBits()
        }
        memo[from to remaining] = best
        return best
    }

    val all = (1L shl floors.size) - 1
    return { from, first ->
        val path = paths(from).getValue(first)
        path.size - 1 + cost(first, all and maskOf(path).inv())
    }
}

private const val MAX_EXACT_TOUR_FLOORS = 12

/**
 * Maps a raw `set_mainwarp` operand to the logical floor it lands on.
 *
 * The operand is a client map number: it first matches the map a floor is designated with
 * (`bb_map_designate`). Otherwise it is converted to an episode area (Episode IV counts Ragol
 * areas from 36, Episode II areas from 18) and mapped to the quest floor with that area, or the
 * next quest floor with a higher area when the area itself is not in the quest.
 */
private fun mainWarpFloor(
    operand: Int,
    floorMappings: List<FloorMapping>,
    requiredFloors: List<Int>,
): Int? {
    val candidates = requiredFloors.filter { it != 0 }
    floorMappings
        .filter { it.mapId == operand && it.floorId in candidates }
        .minOfOrNull { it.floorId }
        ?.let { return it }
    val area = when {
        operand >= 36 -> operand - 35
        operand >= 18 -> operand - 18
        else -> operand
    }
    if (area == 0) return null
    fun areaOf(floorId: Int): Int =
        floorMappings.firstOrNull { it.floorId == floorId }?.mapAreaId ?: floorId
    candidates.filter { areaOf(it) == area }.minOrNull()?.let { return it }
    return candidates
        .filter { areaOf(it) > area }
        .minWithOrNull(compareBy({ areaOf(it) }, { it }))
}
