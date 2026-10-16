package world.phantasmal.web.questEditor.rendering

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlin.math.abs
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
import world.phantasmal.psolib.asm.dataFlowAnalysis.scanScriptFloorTransitions
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.activeScriptLabelOrNull
import world.phantasmal.psolib.fileFormats.quest.walkthrough.FloorTransition
import world.phantasmal.psolib.fileFormats.quest.walkthrough.FloorVisit
import world.phantasmal.psolib.fileFormats.quest.walkthrough.exitDestinationFloor
import world.phantasmal.psolib.fileFormats.quest.walkthrough.planQuestJourney
import world.phantasmal.psolib.fileFormats.quest.walkthrough.playerSetReturnFlag
import world.phantasmal.psolib.fileFormats.quest.walkthrough.playerSetsForClient
import world.phantasmal.psolib.fileFormats.quest.walkthrough.questWarpPlayerSetId
import world.phantasmal.psolib.fileFormats.quest.walkthrough.usesChallengeModeScript
import world.phantasmal.web.questEditor.models.QuestEventActionModel
import world.phantasmal.web.questEditor.models.QuestModel
import world.phantasmal.web.questEditor.models.QuestObjectModel

internal data class WalkthroughPoint(val x: Double, val y: Double, val z: Double)

internal data class WalkthroughSegment(
    val floorId: Int,
    val from: WalkthroughPoint,
    val to: WalkthroughPoint,
    val endsLeg: Boolean = true,
    /** A warp, InstaWarp or scripted relocation: drawn as a jump instead of along the ground. */
    val jump: Boolean = false,
)

internal data class WalkthroughRoute(
    val segments: List<WalkthroughSegment>,
    val diagnostics: List<String>,
)

private enum class NodeKind {
    Entrance, Objective, WarpSource, WarpDestination, DoorSide,
    /** Next to a doorway, so that routes can go around a closed door or fence. */
    Waypoint,
    Exit,
}

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
    /**
     * Doors and fences this edge passes: one set of objectives per switch flag they need, of which
     * at least one must have been visited. An empty set is a flag nothing sets.
     */
    val requirements: List<Set<Int>> = emptyList(),
    val jump: Boolean = false,
)

private class Gate(
    val obj: QuestObjectModel,
    val requirements: List<Set<Int>>,
    /** Blocking segments in the horizontal plane, empty for gates crossed only via door sides. */
    val segments: List<Pair<WalkthroughPoint, WalkthroughPoint>>,
)

private data class InstructionFloor(val instruction: Instruction, val floorId: Int)

/**
 * Plans [clientId]'s journey through the quest and builds the route of each visit to one of the
 * [visibleFloorIds]: from where the client appears to the exit it takes next.
 */
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
    val includedEntries = linkedSetOf<Pair<Int, Int>>()
    fun includeAnalysis(label: Int, floorId: Int): Boolean {
        includedEntries += label to floorId
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
    val warpInstructionsByFloor = mutableMapOf<Int, MutableSet<String>>()
    for ((instruction, floorId) in reachableInstructionFloors) {
        if (instruction.opcode.mnemonic == "warp_off" || instruction.opcode.mnemonic == "warp_on") {
            warpInstructionsByFloor.getOrPut(floorId, ::mutableSetOf) += instruction.opcode.mnemonic
        }
    }
    val scriptGatedFloorIds = warpInstructionsByFloor
        .filterValues { "warp_off" in it && "warp_on" in it }
        .keys
    val journey = planQuestJourney(
        quest.episode,
        quest.objects.value.map { it.entity },
        quest.floorMappings,
        includedEntries.map { (label, floorId) -> analysis(label, floorId) },
        isChallengeMode = quest.events.value.any { it.cmWaveSettings.value != null } ||
            usesChallengeModeScript(quest.bytecodeIr),
        clientId,
        scanScriptFloorTransitions(
            quest.bytecodeIr,
            clientId,
            controlFlowGraph,
            excluded = reachableInstructionFloors.mapTo(mutableSetOf()) { it.instruction },
        ),
    )
    diagnostics += journey.diagnostics
    for (floorId in visibleFloorIds.sorted()) {
        val visits = journey.visits.filter { it.floorId == floorId }
        if (visits.isEmpty()) {
            diagnostics += "Client $clientId does not visit floor $floorId."
            continue
        }
        for (visit in visits) {
            coroutineContext.ensureActive()
            val floorRoute = planFloor(
                quest,
                visit,
                clientId,
                journey.mainWarpLandings,
                analysis(0, 0),
                scriptNpcSpawns,
                scriptSpatialInteractions,
                ::isReachable,
                ::analysis,
                pathfinder,
                floorId in scriptGatedFloorIds,
            )
            segments += floorRoute.segments
            diagnostics += floorRoute.diagnostics
            yield()
        }
    }
    return WalkthroughRoute(segments, diagnostics)
}

private suspend fun planFloor(
    quest: QuestModel,
    visit: FloorVisit,
    clientId: Int,
    mainWarpLandings: Map<Int, Int>,
    /** The quest's start script, including floor handlers and the threads it starts. */
    startAnalysis: WalkthroughScriptAnalysis,
    scriptNpcSpawns: List<ScriptNpcSpawn>,
    scriptSpatialInteractions: List<ScriptSpatialInteraction>,
    isReachable: (instruction: Instruction?, floorId: Int) -> Boolean,
    analyzeScript: (label: Int, floorId: Int) -> WalkthroughScriptAnalysis,
    pathfinder: WalkthroughPathfinder,
    warpsAreScriptGated: Boolean,
): WalkthroughRoute {
    val floorId = visit.floorId
    val departure = visit.departure
    // The quest is reported at the Hunter's Guild counter, which is not part of the quest data.
    if (departure == null && floorId == 0) return WalkthroughRoute(emptyList(), emptyList())
    val objects = quest.objects.value.filter { it.floorId == floorId }
    val npcs = quest.npcs.value.filter { it.floorId == floorId }
    val diagnostics = mutableListOf<String>()
    val nodes = mutableListOf<RouteNode>()
    val warpEdges = mutableListOf<Pair<Int, Int>>()
    val doorEdges = mutableListOf<Triple<Int, Int, List<Set<Int>>>>()
    val eventCollisionNodes = mutableMapOf<Int, MutableSet<Int>>()
    val scriptProgressionObjectiveIds = mutableSetOf<Int>()
    val scriptUnlockingObjectivesByDoorId = mutableMapOf<Int, MutableSet<Int>>()
    var nextId = 0

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

    fun addNode(
        objectPoint: WalkthroughPoint,
        kind: NodeKind,
        goalPriority: Int = 0,
    ): Int {
        // Door sides are sampled on walkable ground already; snapping could move one across.
        val point = if (kind == NodeKind.DoorSide) {
            objectPoint
        } else {
            pathfinder.nearestWalkable(objectPoint) ?: objectPoint
        }
        if (kind == NodeKind.Objective) {
            nodes.firstOrNull { it.kind == kind && it.point == point }?.let { return it.id }
        }
        val id = nextId++
        nodes += RouteNode(id, point, kind, nodes.size, goalPriority)
        return id
    }

    val mainWarpObjectiveIds = mutableSetOf<Int>()
    val goFloorTerminalPoints = mutableListOf<WalkthroughPoint>()
    val relocations = mutableListOf<Pair<Int, WalkthroughPoint>>()
    fun registerScriptProgression(nodeId: Int, label: Int, entryFloorId: Int) {
        val analysis = analyzeScript(label, entryFloorId)
        if (departure is FloorTransition.MainWarp &&
            analysis.mainWarpAreas.any { mainWarpLandings[it] == departure.toFloorId }
        ) {
            mainWarpObjectiveIds += nodeId
        }
        if (departure is FloorTransition.Script && analysis.goFloors.any {
                it.sourceFloorId == floorId && it.targetFloorId == departure.toFloorId
            }
        ) {
            goFloorTerminalPoints += nodes.first { it.id == nodeId }.point
        }
        for (relocation in analysis.relocations) {
            if (relocation.floorId == floorId) {
                relocations += nodeId to WalkthroughPoint(
                    relocation.x.toDouble(), relocation.y.toDouble(), relocation.z.toDouble(),
                )
            }
        }
        val scriptUnlockedDoorIds = analysis.doorUnlocks.asSequence()
            .filter { it.floorId == floorId }
            .mapTo(mutableSetOf()) { it.doorId }
        for (activation in analysis.eventActivations) {
            if (activation.floorId == floorId) {
                scriptUnlockedDoorIds += unlockedDoorIds(activation.eventId)
            }
        }
        for (doorId in scriptUnlockedDoorIds) {
            scriptUnlockingObjectivesByDoorId.getOrPut(doorId, ::mutableSetOf) += nodeId
        }
        if (scriptUnlockedDoorIds.isNotEmpty() ||
            floorId in analysis.warpsEnabledFloorIds ||
            nodeId in mainWarpObjectiveIds
        ) {
            scriptProgressionObjectiveIds += nodeId
        }
    }

    val clientPlayerSets = playerSetsForClient(objects.map { it.entity }, clientId)
    val entrance = visit.arrival?.let { arrival -> objects.firstOrNull { it.entity === arrival } }
    if (entrance == null) {
        return WalkthroughRoute(
            emptyList(),
            listOf("Floor $floorId: no Player Set entrance for client $clientId."),
        )
    }
    val entranceId = addNode(entrance.worldPoint(), NodeKind.Entrance)

    // A collision trigger fires as soon as a player is within its radius. When that radius reaches
    // past a door or fence, the trigger can be fired from the other side, so that side gets a
    // stand-in node too (often the trigger opens the very door it lies behind).
    val triggerGroups = mutableListOf<Set<Int>>()
    fun triggerNodes(obj: QuestObjectModel, priority: Int): List<Int> {
        val center = obj.worldPoint()
        val nodeIds = mutableListOf(addNode(center, NodeKind.Objective, priority))
        val radius = obj.triggerRadius() ?: return nodeIds
        // A trigger whose center isn't on walkable ground still fires anywhere within its radius.
        if (pathfinder.nearestWalkable(center) == null) {
            for (fraction in listOf(0.5, 0.9)) {
                for (step in 0 until TRIGGER_RING_DIRECTIONS) {
                    val angle = 2 * kotlin.math.PI * step / TRIGGER_RING_DIRECTIONS
                    val point = pathfinder.nearestWalkable(
                        WalkthroughPoint(
                            center.x + cos(angle) * radius * fraction,
                            center.y,
                            center.z + sin(angle) * radius * fraction,
                        ),
                    ) ?: continue
                    if (horizontalDistance(center, point) <= radius) {
                        nodeIds += addNode(point, NodeKind.Objective, priority)
                    }
                }
            }
        }
        for (door in objects) {
            if (!door.isDoorObject()) continue
            for ((a, b) in gateSegments(door)) {
                val closest = closestPointOnSegment(center, a, b)
                val distance = horizontalDistance(center, closest)
                if (distance < 1e-6 || distance >= radius) continue
                // Step past the gate, away from the trigger's center.
                val dx = (closest.x - center.x) / distance
                val dz = (closest.z - center.z) / distance
                val outside = WalkthroughPoint(
                    closest.x + dx * TRIGGER_STAND_IN_OFFSET,
                    center.y,
                    closest.z + dz * TRIGGER_STAND_IN_OFFSET,
                )
                if (horizontalDistance(center, outside) > radius) continue
                val point = pathfinder.nearestWalkable(outside) ?: continue
                if (horizontalDistance(center, point) > radius) continue
                nodeIds += addNode(point, NodeKind.Objective, priority)
            }
        }
        if (nodeIds.size > 1) triggerGroups += nodeIds.toSet()
        return nodeIds
    }

    for (obj in objects) {
        when {
            obj.type == ObjectType.EventCollision -> {
                val nodeIds = triggerNodes(obj, EVENT_COLLISION_PRIORITY)
                eventCollisionNodes.getOrPut(obj.entity.data.getInt(52), ::mutableSetOf) += nodeIds
            }
            // Opening an enemy box is optional, unlike walking through an Event Collision, so a
            // box is only an objective when its event opens a door.
            obj.entity.triggeredEventId?.let { unlockedDoorIds(it).isNotEmpty() } == true -> {
                val node = addNode(
                    obj.worldPoint(), NodeKind.Objective, EVENT_COLLISION_PRIORITY,
                )
                eventCollisionNodes.getOrPut(obj.entity.triggeredEventId!!, ::mutableSetOf)
                    .add(node)
            }
            obj.entity.activeScriptLabel != null -> {
                for (node in triggerNodes(obj, INTERACTION_PRIORITY)) {
                    registerScriptProgression(node, obj.entity.activeScriptLabel!!, floorId)
                }
            }
        }
    }
    for (npc in npcs) {
        val label = npc.entity.activeScriptLabelOrNull()
        if (label != null) {
            val node = addNode(npc.worldPoint(), NodeKind.Objective, INTERACTION_PRIORITY)
            registerScriptProgression(node, label, floorId)
        }
    }
    for (interaction in scriptSpatialInteractions) {
        if (floorId !in interaction.executionFloorIds ||
            !isReachable(interaction.sourceInstruction, floorId)
        ) continue
        val origin = interaction.origin
        val node = addNode(
            WalkthroughPoint(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble()),
            NodeKind.Objective,
            INTERACTION_PRIORITY,
        )
        registerScriptProgression(node, interaction.event.label, floorId)
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
        val node = addNode(
            WalkthroughPoint(spawn.x.toDouble(), spawn.y.toDouble(), spawn.z.toDouble()),
            NodeKind.Objective,
            INTERACTION_PRIORITY,
        )
        for (interaction in spawn.interactions) {
            if (floorId in interaction.executionFloorIds &&
                isReachable(interaction.sourceInstruction, floorId)
            ) {
                registerScriptProgression(node, interaction.label, floorId)
            }
        }
    }

    // Switches, floor buttons and sensors turn on the switch flag that opens doors and fences.
    val switchesByFlag = mutableMapOf<Int, MutableSet<Int>>()
    for (obj in objects) {
        val flag = obj.setSwitchFlag() ?: continue
        val node = addNode(obj.worldPoint(), NodeKind.Objective, INTERACTION_PRIORITY)
        switchesByFlag.getOrPut(flag, ::mutableSetOf) += node
    }

    for (obj in objects) {
        if (obj.hasDestination) {
            val source = addNode(obj.worldPoint(), NodeKind.WarpSource)
            val destination = addNode(obj.destinationPoint(), NodeKind.WarpDestination)
            warpEdges += source to destination
            continue
        }
        if (obj.type == ObjectType.QuestWarp && obj.entity.data.getInt(52) == floorId) {
            val sourceType = obj.entity.questWarpPlayerSetId()
            val destinationPlayerSet = clientPlayerSets
                .firstOrNull { it.playerSetReturnFlag() == sourceType }
                ?.let { set -> objects.first { it.entity === set } }
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

        val isExit = when (departure) {
            is FloorTransition.Exit -> departure.exits.any { it === obj.entity }
            is FloorTransition.MainWarp -> obj.type == ObjectType.MainRagolTeleporter
            is FloorTransition.Script -> false
            // Final and order-unknown visits end at any floor exit, as before journeys.
            null -> obj.entity.exitDestinationFloor(
                quest.episode,
                quest.floorMappings.firstOrNull { it.floorId == floorId }?.mapAreaId ?: floorId,
            ) != null
        }
        if (isExit) addNode(obj.worldPoint(), NodeKind.Exit, EXIT_PRIORITY)
    }
    for (point in goFloorTerminalPoints) addNode(point, NodeKind.Exit, EXIT_PRIORITY)
    for ((sourceId, point) in relocations) {
        warpEdges += sourceId to addNode(point, NodeKind.WarpDestination)
    }

    val unlockingObjectivesByDoorId = mutableMapOf<Int, MutableSet<Int>>()
    val datProgressionObjectiveIds = mutableSetOf<Int>()
    for ((eventId, objectiveIds) in eventCollisionNodes) {
        for (doorId in unlockedDoorIds(eventId)) {
            unlockingObjectivesByDoorId.getOrPut(doorId, ::mutableSetOf) += objectiveIds
            datProgressionObjectiveIds += objectiveIds
        }
    }
    for ((doorId, objectiveIds) in scriptUnlockingObjectivesByDoorId) {
        unlockingObjectivesByDoorId.getOrPut(doorId, ::mutableSetOf) += objectiveIds
    }
    for ((flag, objectiveIds) in switchesByFlag) {
        unlockingObjectivesByDoorId.getOrPut(flag, ::mutableSetOf) += objectiveIds
    }
    // Flags the start script turns on, e.g. from a floor handler, need no objective.
    val startFlags = startAnalysis.doorUnlocks.asSequence()
        .filter { it.floorId == floorId }
        .mapTo(mutableSetOf()) { it.doorId }
    for (activation in startAnalysis.eventActivations) {
        if (activation.floorId == floorId) startFlags += unlockedDoorIds(activation.eventId)
    }

    val gates = mutableListOf<Gate>()
    for (obj in objects) {
        val requirements = when (val state = obj.gateState() ?: continue) {
            GateState.Open -> continue
            GateState.Closed -> {
                diagnostics += "Floor $floorId: ${obj.type.uniqueName} at ${obj.describePosition()} " +
                    "is always locked."
                listOf(emptySet())
            }
            is GateState.Needs -> state.flags.filter { it !in startFlags }.map { flag ->
                val setters = unlockingObjectivesByDoorId[flag].orEmpty().toSet()
                if (setters.isEmpty()) {
                    diagnostics += "Floor $floorId: ${obj.type.uniqueName} at " +
                        "${obj.describePosition()} needs switch flag $flag, which nothing on " +
                        "this floor sets."
                }
                setters
            }
        }
        if (requirements.isEmpty()) continue
        gates += Gate(obj, requirements, gateSegments(obj))
    }
    // Objectives that open a door or fence are on the way, like Event Collisions.
    val gateProgressionObjectiveIds = gates.flatMapTo(mutableSetOf()) { gate ->
        gate.requirements.flatten()
    }
    val progressionObjectiveIds =
        datProgressionObjectiveIds + scriptProgressionObjectiveIds + gateProgressionObjectiveIds
    val completionGated = warpsAreScriptGated || progressionObjectiveIds.isNotEmpty()

    // Doors whose two sides the walkable geometry doesn't connect are crossed via door sides.
    for (door in objects) {
        if (!door.isDoorObject()) continue
        val requirements = gates.firstOrNull { it.obj === door }?.requirements.orEmpty()
        val (firstPoint, secondPoint) = doorPassagePoints(door, pathfinder) ?: continue
        val first = addNode(firstPoint, NodeKind.DoorSide)
        val second = addNode(secondPoint, NodeKind.DoorSide)
        doorEdges += Triple(first, second, requirements)
    }

    // Each pair of nodes is joined by one shortest path, which may pass a closed door or fence
    // that another way avoids. Waypoints on both sides of every doorway let the route go around.
    for (obj in objects) {
        if (!obj.isDoorObject() || obj.isSquareGate()) continue
        val center = obj.worldPoint()
        val (crossX, crossZ) = obj.gateCrossingDirection()
        for (sign in listOf(-1.0, 1.0)) {
            val side = WalkthroughPoint(
                center.x + sign * crossX * WAYPOINT_OFFSET,
                center.y,
                center.z + sign * crossZ * WAYPOINT_OFFSET,
            )
            val point = pathfinder.nearestWalkable(side) ?: continue
            nodes += RouteNode(nextId++, point, NodeKind.Waypoint, nodes.size)
        }
    }

    fun crossedRequirements(points: List<WalkthroughPoint>): List<Set<Int>> {
        val result = mutableListOf<Set<Int>>()
        for (gate in gates) {
            if (gate.segments.isEmpty()) continue
            val crosses = points.windowed(2).any { (from, to) ->
                gate.segments.any { (a, b) -> crossesGate(from, to, a, b) }
            }
            if (crosses) result += gate.requirements
        }
        return result
    }

    // Neighbouring collision pieces that touch without sharing vertices are joined at seams.
    for ((a, b) in pathfinder.seams) {
        val first = nextId++
        nodes += RouteNode(first, a, NodeKind.Waypoint, nodes.size)
        val second = nextId++
        nodes += RouteNode(second, b, NodeKind.Waypoint, nodes.size)
        doorEdges += Triple(first, second, crossedRequirements(listOf(a, b)))
    }

    val edgesBySource = nodes.associate { it.id to mutableListOf<RouteEdge>() }.toMutableMap()
    val pathOrigins = nodes.filter { node ->
        node.kind == NodeKind.Entrance || node.kind == NodeKind.Objective ||
            node.kind == NodeKind.WarpDestination || node.kind == NodeKind.DoorSide ||
            node.kind == NodeKind.Waypoint
    }
    val pathTargets = nodes.filter { node ->
        node.kind == NodeKind.Objective || node.kind == NodeKind.WarpSource ||
            node.kind == NodeKind.DoorSide || node.kind == NodeKind.Exit ||
            node.kind == NodeKind.Waypoint
    }
    for (from in pathOrigins) {
        for (to in pathTargets) {
            if (from.id == to.id) continue
            val viaWaypoint = from.kind == NodeKind.Waypoint || to.kind == NodeKind.Waypoint
            // Waypoints only join nearby doorways; going around a door never needs far jumps.
            if (viaWaypoint && horizontalDistance(from.point, to.point) > WAYPOINT_REACH) continue
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
                // A small detour cost keeps routes on direct paths unless those are closed.
                points.pathLength() + if (viaWaypoint) WAYPOINT_COST else 0.0,
                render = true,
                requirements = crossedRequirements(points),
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
            jump = true,
        )
    }
    for ((firstId, secondId, requirements) in doorEdges) {
        val first = nodes.first { it.id == firstId }
        val second = nodes.first { it.id == secondId }
        val points = listOf(first.point, second.point)
        val length = points.pathLength()
        edgesBySource.getValue(firstId) += RouteEdge(
            firstId, secondId, points, length, render = true,
            requirements = requirements,
        )
        edgesBySource.getValue(secondId) += RouteEdge(
            secondId, firstId, points.reversed(), length, render = true,
            requirements = requirements,
        )
    }

    // Doors and fences whose switch flag nothing known sets (or that are always locked) block
    // the route, unless that leaves this floor's exit out of reach: then the quest must open them
    // in a way this planner doesn't model, and they are passed.
    var passUnopenedGates = false

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
                if (edge.requirements.any { flagSetters ->
                        if (flagSetters.isEmpty()) {
                            !passUnopenedGates
                        } else {
                            flagSetters.none { it in visitedObjectiveIds }
                        }
                    }
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
    val allGatedObjectiveIds = if (warpsAreScriptGated) {
        allEventObjectiveIds + scriptProgressionObjectiveIds + gateProgressionObjectiveIds
    } else {
        progressionObjectiveIds
    }
    var (entranceDistances, entrancePrevious) = shortestPathsFrom(
        entranceId,
        if (completionGated) allGatedObjectiveIds else emptySet(),
    )
    val exitIds = nodes.filter { it.kind == NodeKind.Exit }.map { it.id }
    if (exitIds.isNotEmpty() && exitIds.none { entranceDistances.getValue(it).isFinite() } &&
        gates.any { gate -> gate.requirements.any { it.isEmpty() } }
    ) {
        passUnopenedGates = true
        val relaxed = shortestPathsFrom(
            entranceId,
            if (completionGated) allGatedObjectiveIds else emptySet(),
        )
        if (exitIds.any { relaxed.first.getValue(it).isFinite() }) {
            diagnostics += "Floor $floorId: the exit is only reachable through doors or fences " +
                "that nothing known opens; the route passes them."
            entranceDistances = relaxed.first
            entrancePrevious = relaxed.second
        } else {
            passUnopenedGates = false
        }
    }
    val reachableGoals = nodes.filter { node ->
        node.goalPriority > 0 && entranceDistances.getValue(node.id).isFinite()
    }
    if (reachableGoals.isEmpty()) {
        diagnostics += "Floor $floorId: no route objective is reachable on the walkable collision geometry."
        return WalkthroughRoute(emptyList(), diagnostics)
    }
    val routeEdges = mutableListOf<RouteEdge>()
    val reachableExits = reachableGoals.filter { it.kind == NodeKind.Exit }
    // A Return flag 1 Player Set marks where the client appears when coming back to this floor,
    // which quests place next to the exit that client takes forward.
    val returnMarker = clientPlayerSets
        .firstOrNull { it.playerSetReturnFlag() == 1 }
        ?.let { set -> objects.first { it.entity === set }.worldPoint() }
    val markedExit = returnMarker?.let { marker ->
        reachableExits
            .minByOrNull { listOf(it.point, marker).pathLength() }
            ?.takeIf { listOf(it.point, marker).pathLength() <= RETURN_MARKER_RADIUS }
    }
    val preferredExit = markedExit ?: reachableExits.maxWithOrNull(compareBy<RouteNode>(
        { entranceDistances.getValue(it.id) },
        { -it.order },
    ))
    val gatedObjectives = if (completionGated) {
        reachableGoals.filter { it.id in allGatedObjectiveIds }.toMutableList()
    } else {
        mutableListOf()
    }
    // Objectives with the same effect are alternatives: several Event Collisions of one event, or
    // several switches of one flag. Reaching any of them does what the others would.
    val alternatives = mutableMapOf<Int, Set<Int>>()
    for (group in eventCollisionNodes.values + switchesByFlag.values + triggerGroups) {
        for (id in group) alternatives[id] = alternatives[id].orEmpty() + group
    }
    fun alternativesOf(id: Int): Set<Int> = alternatives[id] ?: setOf(id)

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
            val done = alternativesOf(candidate.id)
            reachable.all { other ->
                other.id in done || alternativesOf(other.id).any {
                    candidateDistances.getValue(it).isFinite()
                }
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
        val done = alternativesOf(next.id)
        visitedObjectiveIds += done
        gatedObjectives.removeAll { it.id in done }
    }
    if (gatedObjectives.isNotEmpty()) {
        val groups = gatedObjectives.map { alternativesOf(it.id) }.toSet().size
        diagnostics += "Floor $floorId: $groups gated route objectives are unreachable."
    }

    if (departure != null && preferredExit == null) {
        // The journey knows where the client goes next; without a reachable exit the route stops
        // after the required objectives instead of guessing an endpoint.
        diagnostics += "Floor $floorId: no reachable exit to floor ${departure.toFloorId} for " +
            "client $clientId."
    }
    val goal = if (
        preferredExit != null || departure != null || (completionGated && currentId != entranceId)
    ) {
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
        if (edge.jump) {
            val from = edge.points.first()
            val to = edge.points.last()
            if (from != to) segments += WalkthroughSegment(floorId, from, to, jump = true)
            continue
        }
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
private const val RETURN_MARKER_RADIUS = 100.0
private const val GATE_HEIGHT_TOLERANCE = 20.0
private const val GATE_END_MARGIN = 8.0
private const val TRIGGER_STAND_IN_OFFSET = 8.0
private const val TRIGGER_RING_DIRECTIONS = 8
private const val WAYPOINT_OFFSET = 16.0
private const val WAYPOINT_REACH = 500.0
private const val WAYPOINT_COST = 1.0
private const val SHORTEST_PATH_YIELD_INTERVAL = 32
private val DOOR_SIDE_SAMPLE_DISTANCES = listOf(4.0, 8.0, 12.0, 16.0, 24.0, 32.0, 48.0, 64.0)

private fun QuestObjectModel.worldPoint(): WalkthroughPoint = worldPosition.value.toPoint()
private fun QuestObjectModel.destinationPoint(): WalkthroughPoint = destinationPosition.value.toPoint()
private fun world.phantasmal.web.questEditor.models.QuestNpcModel.worldPoint(): WalkthroughPoint =
    worldPosition.value.toPoint()
private fun world.phantasmal.web.externals.three.Vector3.toPoint() = WalkthroughPoint(x, y, z)

private fun QuestObjectModel.describePosition(): String {
    val point = worldPoint()
    return "(${point.x.roundToInt()}, ${point.z.roundToInt()})"
}

/** The segments a door or fence blocks: across its direction of travel, at its height. */
private fun gateSegments(gate: QuestObjectModel): List<Pair<WalkthroughPoint, WalkthroughPoint>> {
    val halfWidth = gate.gateHalfWidth() ?: return emptyList()
    val center = gate.worldPoint()
    // Across the gate (the direction of travel through it) and along it.
    val (nx, nz) = gate.gateCrossingDirection()
    val ux = nz
    val uz = -nx
    fun segment(cx: Double, cz: Double, dx: Double, dz: Double) = WalkthroughPoint(
        cx - dx * halfWidth, center.y, cz - dz * halfWidth,
    ) to WalkthroughPoint(cx + dx * halfWidth, center.y, cz + dz * halfWidth)
    if (!gate.isSquareGate()) return listOf(segment(center.x, center.z, ux, uz))
    return listOf(
        segment(center.x + nx * halfWidth, center.z + nz * halfWidth, ux, uz),
        segment(center.x - nx * halfWidth, center.z - nz * halfWidth, ux, uz),
        segment(center.x + ux * halfWidth, center.z + uz * halfWidth, nx, nz),
        segment(center.x - ux * halfWidth, center.z - uz * halfWidth, nx, nz),
    )
}

/** Whether walking from [from] to [to] passes through the gate segment [a]–[b]. */
private fun crossesGate(
    from: WalkthroughPoint,
    to: WalkthroughPoint,
    a: WalkthroughPoint,
    b: WalkthroughPoint,
): Boolean {
    if (abs((from.y + to.y) / 2 - a.y) > GATE_HEIGHT_TOLERANCE) return false
    fun side(px: Double, pz: Double, qx: Double, qz: Double, rx: Double, rz: Double) =
        (qx - px) * (rz - pz) - (qz - pz) * (rx - px)
    val d1 = side(a.x, a.z, b.x, b.z, from.x, from.z)
    val d2 = side(a.x, a.z, b.x, b.z, to.x, to.z)
    val d3 = side(from.x, from.z, to.x, to.z, a.x, a.z)
    val d4 = side(from.x, from.z, to.x, to.z, b.x, b.z)
    if (d1 * d2 >= 0 || d3 * d4 >= 0) return false
    // Paths hug corners: one that only clips the end of a fence (e.g. the corner of a square
    // fence) walks around it, so only crossings away from the ends block.
    val t = d3 / (d3 - d4)
    val margin = GATE_END_MARGIN / horizontalDistance(a, b)
    return t > margin && t < 1 - margin
}

private fun doorPassagePoints(
    door: QuestObjectModel,
    pathfinder: WalkthroughPathfinder,
): Pair<WalkthroughPoint, WalkthroughPoint>? {
    val center = door.worldPoint()
    val (dx, dz) = door.gateCrossingDirection()
    // A door's collision gap can be wide enough to leave small islands of floor inside the door
    // frame, which don't lead anywhere. Each side uses the sample nearest the door that is
    // connected to the outermost walkable sample on that side.
    fun side(sign: Double): WalkthroughPoint? {
        val walkable = DOOR_SIDE_SAMPLE_DISTANCES
            .map { distance ->
                WalkthroughPoint(
                    center.x + sign * dx * distance, center.y, center.z + sign * dz * distance,
                )
            }
            .filter { pathfinder.findPath(it, it) != null }
        val outermost = walkable.lastOrNull() ?: return null
        return walkable.first { it == outermost || pathfinder.findPath(it, outermost) != null }
    }
    val first = side(-1.0) ?: return null
    val second = side(1.0) ?: return null
    return (first to second).takeIf { pathfinder.findPath(first, second) == null }
}

/** The radius of a collision trigger (Event Collision, script collision), or null. */
private fun QuestObjectModel.triggerRadius(): Double? = when (type.typeId?.toInt()) {
    // newserv: param1 is the radius of TObjEvtCollision (0x0008), TObjQuestCol (0x0012) and
    // TObjQuestColA (0x0015, same params as TObjQuestCol).
    0x0008, 0x0012, 0x0015 -> entity.data.getFloat(40).toDouble()
    else -> null
}?.takeIf { it > 0.0 }

private fun closestPointOnSegment(
    point: WalkthroughPoint,
    a: WalkthroughPoint,
    b: WalkthroughPoint,
): WalkthroughPoint {
    val dx = b.x - a.x
    val dz = b.z - a.z
    val lengthSquared = dx * dx + dz * dz
    val t = if (lengthSquared == 0.0) 0.0 else
        (((point.x - a.x) * dx + (point.z - a.z) * dz) / lengthSquared).coerceIn(0.0, 1.0)
    return WalkthroughPoint(a.x + dx * t, a.y, a.z + dz * t)
}

private fun horizontalDistance(from: WalkthroughPoint, to: WalkthroughPoint): Double {
    val dx = from.x - to.x
    val dz = from.z - to.z
    return sqrt(dx * dx + dz * dz)
}

private fun List<WalkthroughPoint>.pathLength(): Double = windowed(2).sumOf { (from, to) ->
    val dx = from.x - to.x
    val dy = from.y - to.y
    val dz = from.z - to.z
    sqrt(dx * dx + dy * dy + dz * dz)
}
