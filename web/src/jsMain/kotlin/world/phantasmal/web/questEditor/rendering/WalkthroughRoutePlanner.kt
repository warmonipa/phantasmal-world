package world.phantasmal.web.questEditor.rendering

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.coroutines.coroutineContext
import world.phantasmal.psolib.asm.Instruction
import world.phantasmal.psolib.asm.dataFlowAnalysis.ControlFlowGraph
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptNpcSpawn
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptSpatialInteraction
import world.phantasmal.psolib.asm.dataFlowAnalysis.WalkthroughScriptAnalysis
import world.phantasmal.psolib.asm.dataFlowAnalysis.analyzeWalkthroughScript
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.activeScriptLabelOrNull
import world.phantasmal.psolib.fileFormats.quest.getNormalBossTeleporterDestinationFloor
import world.phantasmal.web.questEditor.models.QuestEventActionModel
import world.phantasmal.web.questEditor.models.QuestModel
import world.phantasmal.web.questEditor.models.QuestObjectModel

internal data class WalkthroughPoint(val x: Double, val y: Double, val z: Double)

internal data class WalkthroughSegment(
    val floorId: Int,
    val from: WalkthroughPoint,
    val to: WalkthroughPoint,
    val endsLeg: Boolean = true,
)

internal data class WalkthroughRoute(
    val segments: List<WalkthroughSegment>,
    val diagnostics: List<String>,
)

private enum class NodeKind { Entrance, Objective, WarpSource, WarpDestination, DoorSide, Exit }

private data class RouteNode(
    val id: Int,
    val point: WalkthroughPoint,
    val kind: NodeKind,
    val order: Int,
    val goalPriority: Int = 0,
)

private data class RouteEdge(
    val fromId: Int,
    val toId: Int,
    val points: List<WalkthroughPoint>,
    val length: Double,
    val render: Boolean,
    val requiredObjectiveIds: Set<Int> = emptySet(),
)

private data class InstructionFloor(val instruction: Instruction, val floorId: Int)

/** Builds one primary physical route for each selected logical floor. */
internal suspend fun planWalkthroughRoute(
    quest: QuestModel,
    visibleFloorIds: Set<Int>,
    clientId: Int,
    scriptNpcSpawns: List<ScriptNpcSpawn> = quest.scriptNpcSpawns.value,
    scriptSpatialInteractions: List<ScriptSpatialInteraction> = quest.scriptSpatialInteractions.value,
    pathfinder: WalkthroughPathfinder,
): WalkthroughRoute {
    require(clientId in 0..3)
    val controlFlowGraph = ControlFlowGraph.create(quest.bytecodeIr)
    val analysisCache = mutableMapOf<Pair<Int, Int>, WalkthroughScriptAnalysis>()
    fun analysis(label: Int, floorId: Int): WalkthroughScriptAnalysis =
        analysisCache.getOrPut(label to floorId) {
            analyzeWalkthroughScript(
                quest.bytecodeIr, label, floorId, clientId, controlFlowGraph,
            )
        }

    val reachableInstructionFloors = mutableSetOf<InstructionFloor>()
    fun includeAnalysis(label: Int, floorId: Int): Boolean {
        var changed = false
        for ((instruction, floors) in analysis(label, floorId).reachableInstructionFloors) {
            for (reachableFloorId in floors) {
                if (reachableInstructionFloors.add(
                        InstructionFloor(instruction, reachableFloorId),
                    )
                ) changed = true
            }
        }
        return changed
    }
    fun isReachable(instruction: Instruction?, floorId: Int): Boolean =
        instruction == null || InstructionFloor(instruction, floorId) in reachableInstructionFloors

    includeAnalysis(0, 0)
    for (obj in quest.objects.value) {
        obj.entity.activeScriptLabel?.let { includeAnalysis(it, obj.floorId) }
    }
    for (npc in quest.npcs.value) {
        npc.entity.activeScriptLabelOrNull()?.let { includeAnalysis(it, npc.floorId) }
    }

    var reachabilityChanged: Boolean
    do {
        reachabilityChanged = false
        for (interaction in scriptSpatialInteractions) {
            for (floorId in interaction.executionFloorIds) {
                if (!isReachable(interaction.sourceInstruction, floorId)) continue
                if (includeAnalysis(interaction.event.label, floorId)) reachabilityChanged = true
            }
        }
        for (spawn in scriptNpcSpawns) {
            for (interaction in spawn.interactions) {
                for (floorId in spawn.executionFloorIds.intersect(interaction.executionFloorIds)) {
                    if (!isReachable(spawn.sourceInstruction, floorId) ||
                        !isReachable(interaction.sourceInstruction, floorId)
                    ) continue
                    if (includeAnalysis(interaction.label, floorId)) reachabilityChanged = true
                }
            }
        }
    } while (reachabilityChanged)

    val segments = mutableListOf<WalkthroughSegment>()
    val diagnostics = mutableListOf<String>()
    val warpsAreScriptGated = quest.bytecodeIr.instructionSegments()
        .flatMap { it.instructions }
        .mapTo(mutableSetOf()) { it.opcode.mnemonic }
        .let { "warp_off" in it && "warp_on" in it }
    for (floorId in visibleFloorIds.sorted()) {
        coroutineContext.ensureActive()
        val floorRoute = planFloor(
            quest,
            floorId,
            clientId,
            scriptNpcSpawns,
            scriptSpatialInteractions,
            ::isReachable,
            pathfinder,
            warpsAreScriptGated,
        )
        segments += floorRoute.segments
        diagnostics += floorRoute.diagnostics
        yield()
    }
    return WalkthroughRoute(segments, diagnostics)
}

private suspend fun planFloor(
    quest: QuestModel,
    floorId: Int,
    clientId: Int,
    scriptNpcSpawns: List<ScriptNpcSpawn>,
    scriptSpatialInteractions: List<ScriptSpatialInteraction>,
    isReachable: (instruction: Instruction?, floorId: Int) -> Boolean,
    pathfinder: WalkthroughPathfinder,
    warpsAreScriptGated: Boolean,
): WalkthroughRoute {
    val objects = quest.objects.value.filter { it.floorId == floorId }
    val npcs = quest.npcs.value.filter { it.floorId == floorId }
    val diagnostics = mutableListOf<String>()
    val nodes = mutableListOf<RouteNode>()
    val warpEdges = mutableListOf<Pair<Int, Int>>()
    val doorEdges = mutableListOf<Triple<Int, Int, Set<Int>>>()
    val eventCollisionNodes = mutableMapOf<Int, MutableSet<Int>>()
    var nextId = 0

    fun addNode(
        point: WalkthroughPoint,
        kind: NodeKind,
        goalPriority: Int = 0,
    ): Int {
        if (kind == NodeKind.Objective) {
            nodes.firstOrNull { it.kind == kind && it.point == point }?.let { return it.id }
        }
        val id = nextId++
        nodes += RouteNode(id, point, kind, nodes.size, goalPriority)
        return id
    }

    val entrance = objects.firstOrNull { obj ->
        obj.type == ObjectType.PlayerSet &&
            obj.entity.data.getFloat(40).roundToInt() == clientId &&
            obj.entity.data.getInt(52) == 0
    }
    if (entrance == null) {
        return WalkthroughRoute(
            emptyList(),
            listOf("Floor $floorId: no Player Set entrance for client $clientId."),
        )
    }
    val entranceId = addNode(entrance.worldPoint(), NodeKind.Entrance)

    for (obj in objects) {
        when {
            obj.type == ObjectType.EventCollision -> {
                val node = addNode(
                    obj.worldPoint(), NodeKind.Objective, EVENT_COLLISION_PRIORITY,
                )
                eventCollisionNodes.getOrPut(obj.entity.data.getInt(52), ::mutableSetOf).add(node)
            }
            obj.entity.activeScriptLabel != null ->
                addNode(obj.worldPoint(), NodeKind.Objective, INTERACTION_PRIORITY)
        }
    }
    for (npc in npcs) {
        if (npc.entity.activeScriptLabelOrNull() != null) {
            addNode(npc.worldPoint(), NodeKind.Objective, INTERACTION_PRIORITY)
        }
    }
    for (interaction in scriptSpatialInteractions) {
        if (floorId !in interaction.executionFloorIds ||
            !isReachable(interaction.sourceInstruction, floorId)
        ) continue
        val origin = interaction.origin
        addNode(
            WalkthroughPoint(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble()),
            NodeKind.Objective,
            INTERACTION_PRIORITY,
        )
    }
    for (spawn in scriptNpcSpawns) {
        if (floorId !in spawn.executionFloorIds ||
            !isReachable(spawn.sourceInstruction, floorId)
        ) continue
        if (spawn.interactions.none { interaction ->
                floorId in interaction.executionFloorIds &&
                    isReachable(interaction.sourceInstruction, floorId)
            }
        ) continue
        addNode(
            WalkthroughPoint(spawn.x.toDouble(), spawn.y.toDouble(), spawn.z.toDouble()),
            NodeKind.Objective,
            INTERACTION_PRIORITY,
        )
    }

    for (obj in objects) {
        if (obj.hasDestination) {
            val source = addNode(obj.worldPoint(), NodeKind.WarpSource)
            val destination = addNode(obj.destinationPoint(), NodeKind.WarpDestination)
            warpEdges += source to destination
            continue
        }
        if (obj.type == ObjectType.QuestWarp && obj.entity.data.getInt(52) == floorId) {
            val sourceType = obj.entity.data.getFloat(40).roundToInt()
            val destinationPlayerSet = objects.firstOrNull { candidate ->
                candidate.type == ObjectType.PlayerSet &&
                    candidate.entity.data.getFloat(40).roundToInt() == clientId &&
                    candidate.entity.data.getInt(52) == sourceType
            }
            if (destinationPlayerSet == null) {
                diagnostics += "Floor $floorId: Quest Warp has no Player Set source type $sourceType for client $clientId."
            } else {
                val source = addNode(obj.worldPoint(), NodeKind.WarpSource)
                val destination = addNode(
                    destinationPlayerSet.worldPoint(), NodeKind.WarpDestination,
                )
                warpEdges += source to destination
            }
            continue
        }

        val destinationFloor = when {
            obj.entity.destinationFloorOffset >= 0 -> obj.entity.destinationFloor
            obj.type == ObjectType.BossTeleporter ->
                getNormalBossTeleporterDestinationFloor(quest.episode, floorId)
            else -> null
        }
        if (destinationFloor != null && destinationFloor != floorId) {
            addNode(obj.worldPoint(), NodeKind.Exit, EXIT_PRIORITY)
        }
    }

    if (warpsAreScriptGated) {
        val floorEventsById = quest.events.value.filter { it.floorId == floorId }.groupBy {
            it.id.value
        }
        fun unlockedDoorIds(rootEventId: Int): Set<Int> {
            val result = mutableSetOf<Int>()
            val pending = ArrayDeque<Int>()
            val visited = mutableSetOf<Int>()
            pending += rootEventId
            while (pending.isNotEmpty()) {
                val eventId = pending.removeFirst()
                if (!visited.add(eventId)) continue
                for (event in floorEventsById[eventId].orEmpty()) {
                    for (action in event.actions.value) {
                        when (action) {
                            is QuestEventActionModel.Door.Unlock -> result += action.doorId.value
                            is QuestEventActionModel.TriggerEvent -> pending += action.eventId.value
                            else -> Unit
                        }
                    }
                }
            }
            return result
        }
        val unlockingObjectivesByDoorId = mutableMapOf<Int, MutableSet<Int>>()
        for ((eventId, objectiveIds) in eventCollisionNodes) {
            for (doorId in unlockedDoorIds(eventId)) {
                unlockingObjectivesByDoorId.getOrPut(doorId, ::mutableSetOf) += objectiveIds
            }
        }
        for (door in objects) {
            if (!door.isDoorObject()) continue
            val controlledIds = door.controlledDoorIds()
            val isAlwaysOpen = controlledIds == null && door.entity.data.getInt(52) == -1
            val requiredObjectives = controlledIds?.toList().orEmpty().flatMapTo(mutableSetOf()) { doorId ->
                unlockingObjectivesByDoorId[doorId].orEmpty()
            }
            if (!isAlwaysOpen && requiredObjectives.isEmpty()) continue
            val (firstPoint, secondPoint) = doorPassagePoints(door, pathfinder) ?: continue
            val first = addNode(firstPoint, NodeKind.DoorSide)
            val second = addNode(secondPoint, NodeKind.DoorSide)
            doorEdges += Triple(first, second, requiredObjectives)
        }
    }

    val edgesBySource = nodes.associate { it.id to mutableListOf<RouteEdge>() }.toMutableMap()
    val pathOrigins = nodes.filter { node ->
        node.kind == NodeKind.Entrance || node.kind == NodeKind.Objective ||
            node.kind == NodeKind.WarpDestination || node.kind == NodeKind.DoorSide
    }
    val pathTargets = nodes.filter { node ->
        node.kind == NodeKind.Objective || node.kind == NodeKind.WarpSource ||
            node.kind == NodeKind.DoorSide || node.kind == NodeKind.Exit
    }
    for (from in pathOrigins) {
        for (to in pathTargets) {
            val points = if (from.point == to.point) {
                listOf(from.point, to.point)
            } else {
                pathfinder.findPath(from.point, to.point)
            } ?: continue
            if (points.size < 2) continue
            edgesBySource.getValue(from.id) += RouteEdge(
                from.id,
                to.id,
                points,
                points.pathLength(),
                render = true,
            )
        }
        // All-pairs navigation is the dominant cost. Yield after each origin so rendering and
        // newer editor input can run before the next chunk starts.
        yield()
    }
    for ((source, destination) in warpEdges) {
        val from = nodes.first { it.id == source }
        val to = nodes.first { it.id == destination }
        edgesBySource.getValue(source) += RouteEdge(
            source,
            destination,
            listOf(from.point, to.point),
            length = 0.0,
            render = false,
        )
    }
    for ((firstId, secondId, requiredObjectives) in doorEdges) {
        val first = nodes.first { it.id == firstId }
        val second = nodes.first { it.id == secondId }
        val points = listOf(first.point, second.point)
        val length = points.pathLength()
        edgesBySource.getValue(firstId) += RouteEdge(
            firstId, secondId, points, length, render = true,
            requiredObjectiveIds = requiredObjectives,
        )
        edgesBySource.getValue(secondId) += RouteEdge(
            secondId, firstId, points.reversed(), length, render = true,
            requiredObjectiveIds = requiredObjectives,
        )
    }

    suspend fun shortestPathsFrom(
        sourceId: Int,
        visitedObjectiveIds: Set<Int> = emptySet(),
    ): Pair<Map<Int, Double>, Map<Int, RouteEdge>> {
        val distances = nodes.associate { it.id to Double.POSITIVE_INFINITY }.toMutableMap()
        val previous = mutableMapOf<Int, RouteEdge>()
        val unvisited = nodes.mapTo(mutableSetOf()) { it.id }
        distances[sourceId] = 0.0
        var visitedCount = 0
        while (unvisited.isNotEmpty()) {
            val currentId = unvisited.minWithOrNull(
                compareBy<Int>({ distances.getValue(it) }, { it }),
            ) ?: break
            val currentDistance = distances.getValue(currentId)
            if (!currentDistance.isFinite()) break
            unvisited.remove(currentId)
            for (edge in edgesBySource.getValue(currentId)) {
                if (edge.requiredObjectiveIds.isNotEmpty() &&
                    edge.requiredObjectiveIds.none { it in visitedObjectiveIds }
                ) continue
                if (edge.toId !in unvisited) continue
                val newDistance = currentDistance + edge.length
                if (newDistance < distances.getValue(edge.toId)) {
                    distances[edge.toId] = newDistance
                    previous[edge.toId] = edge
                }
            }
            if (++visitedCount % SHORTEST_PATH_YIELD_INTERVAL == 0) yield()
        }
        return distances to previous
    }

    fun routeEdgesTo(
        sourceId: Int,
        targetId: Int,
        previous: Map<Int, RouteEdge>,
    ): List<RouteEdge> {
        val result = mutableListOf<RouteEdge>()
        var cursor = targetId
        while (cursor != sourceId) {
            val edge = previous[cursor] ?: error("Reachable route goal has no predecessor.")
            result += edge
            cursor = edge.fromId
        }
        result.reverse()
        return result
    }

    val allEventObjectiveIds = nodes.asSequence()
        .filter { it.goalPriority == EVENT_COLLISION_PRIORITY }
        .mapTo(mutableSetOf()) { it.id }
    val (entranceDistances, entrancePrevious) = shortestPathsFrom(
        entranceId,
        if (warpsAreScriptGated) allEventObjectiveIds else emptySet(),
    )
    val reachableGoals = nodes.filter { node ->
        node.goalPriority > 0 && entranceDistances.getValue(node.id).isFinite()
    }
    if (reachableGoals.isEmpty()) {
        diagnostics += "Floor $floorId: no route objective is reachable on the walkable collision geometry."
        return WalkthroughRoute(emptyList(), diagnostics)
    }
    val routeEdges = mutableListOf<RouteEdge>()
    val preferredExit = reachableGoals.asSequence()
        .filter { it.kind == NodeKind.Exit }
        .maxWithOrNull(compareBy<RouteNode>(
            { entranceDistances.getValue(it.id) },
            { -it.order },
        ))
    val gatedObjectives = if (warpsAreScriptGated) {
        reachableGoals.filter { it.goalPriority == EVENT_COLLISION_PRIORITY }.toMutableList()
    } else {
        mutableListOf()
    }
    var currentId = entranceId
    val visitedObjectiveIds = mutableSetOf<Int>()
    while (gatedObjectives.isNotEmpty()) {
        val (distances, previous) = shortestPathsFrom(currentId, visitedObjectiveIds)
        val reachable = gatedObjectives.filter { distances.getValue(it.id).isFinite() }
        val candidates = buildList {
            for (candidate in reachable) {
                add(candidate to shortestPathsFrom(
                    candidate.id, visitedObjectiveIds + candidate.id,
                ).first)
            }
        }
        val viableCandidates = candidates.filter { (candidate, candidateDistances) ->
            reachable.all { other ->
                other.id == candidate.id || candidateDistances.getValue(other.id).isFinite()
            } && (preferredExit == null ||
                !distances.getValue(preferredExit.id).isFinite() ||
                candidateDistances.getValue(preferredExit.id).isFinite())
        }
        val next = (viableCandidates.ifEmpty { candidates }).asSequence()
            .map { it.first }
            .filter { distances.getValue(it.id).isFinite() }
            .minWithOrNull(compareBy<RouteNode>({ distances.getValue(it.id) }, { it.order }))
            ?: break
        routeEdges += routeEdgesTo(currentId, next.id, previous)
        currentId = next.id
        visitedObjectiveIds += next.id
        gatedObjectives.remove(next)
    }
    if (gatedObjectives.isNotEmpty()) {
        diagnostics += "Floor $floorId: ${gatedObjectives.size} gated route objectives are unreachable."
    }

    val goal = if (warpsAreScriptGated && currentId != entranceId) {
        preferredExit
    } else {
        val highestPriority = reachableGoals.maxOf { it.goalPriority }
        reachableGoals.asSequence()
            .filter { it.goalPriority == highestPriority }
            .maxWith(compareBy<RouteNode>(
                { entranceDistances.getValue(it.id) },
                { -it.order },
            ))
    }
    if (goal != null && goal.id != currentId) {
        val (distances, previous) = if (currentId == entranceId) {
            entranceDistances to entrancePrevious
        } else {
            shortestPathsFrom(currentId, visitedObjectiveIds)
        }
        if (distances.getValue(goal.id).isFinite()) {
            routeEdges += routeEdgesTo(currentId, goal.id, previous)
        } else {
            diagnostics += "Floor $floorId: selected route exit is unreachable after gated objectives."
        }
    }

    val segments = mutableListOf<WalkthroughSegment>()
    for ((edgeIndex, edge) in routeEdges.withIndex()) {
        if (!edge.render) continue
        val endsLeg = edgeIndex == routeEdges.lastIndex || !routeEdges[edgeIndex + 1].render
        for (pointIndex in 0 until edge.points.lastIndex) {
            val from = edge.points[pointIndex]
            val to = edge.points[pointIndex + 1]
            if (from == to) continue
            segments += WalkthroughSegment(
                floorId,
                from,
                to,
                endsLeg = endsLeg && pointIndex == edge.points.lastIndex - 1,
            )
        }
    }
    return WalkthroughRoute(segments, diagnostics)
}

private const val INTERACTION_PRIORITY = 1
private const val EVENT_COLLISION_PRIORITY = 2
private const val EXIT_PRIORITY = 3
private const val SHORTEST_PATH_YIELD_INTERVAL = 32
private val DOOR_SIDE_SAMPLE_DISTANCES = listOf(4.0, 8.0, 12.0, 16.0, 24.0, 32.0, 48.0)

private fun QuestObjectModel.worldPoint(): WalkthroughPoint = worldPosition.value.toPoint()
private fun QuestObjectModel.destinationPoint(): WalkthroughPoint = destinationPosition.value.toPoint()
private fun world.phantasmal.web.questEditor.models.QuestNpcModel.worldPoint(): WalkthroughPoint =
    worldPosition.value.toPoint()
private fun world.phantasmal.web.externals.three.Vector3.toPoint() = WalkthroughPoint(x, y, z)

private fun doorPassagePoints(
    door: QuestObjectModel,
    pathfinder: WalkthroughPathfinder,
): Pair<WalkthroughPoint, WalkthroughPoint>? {
    val center = door.worldPoint()
    val yaw = door.worldRotation.value.y
    val dx = sin(yaw)
    val dz = cos(yaw)
    for (distance in DOOR_SIDE_SAMPLE_DISTANCES) {
        val first = WalkthroughPoint(
            center.x - dx * distance, center.y, center.z - dz * distance,
        )
        val second = WalkthroughPoint(
            center.x + dx * distance, center.y, center.z + dz * distance,
        )
        if (pathfinder.findPath(first, first) != null &&
            pathfinder.findPath(second, second) != null &&
            pathfinder.findPath(first, second) == null
        ) return first to second
    }
    return null
}

private fun List<WalkthroughPoint>.pathLength(): Double = windowed(2).sumOf { (from, to) ->
    val dx = from.x - to.x
    val dy = from.y - to.y
    val dz = from.z - to.z
    sqrt(dx * dx + dy * dy + dz * dz)
}
