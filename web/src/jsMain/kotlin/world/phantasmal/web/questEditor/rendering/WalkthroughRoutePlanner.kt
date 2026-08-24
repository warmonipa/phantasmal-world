package world.phantasmal.web.questEditor.rendering

import kotlin.math.roundToInt
import world.phantasmal.psolib.asm.Instruction
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptNpcSpawn
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptSpatialInteraction
import world.phantasmal.psolib.asm.dataFlowAnalysis.ControlFlowGraph
import world.phantasmal.psolib.asm.dataFlowAnalysis.WalkthroughEventActivation
import world.phantasmal.psolib.asm.dataFlowAnalysis.WalkthroughScriptAnalysis
import world.phantasmal.psolib.asm.dataFlowAnalysis.analyzeWalkthroughScript
import world.phantasmal.psolib.fileFormats.quest.ChallengeModeSeedSimulation
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.activeScriptLabelOrNull
import world.phantasmal.psolib.fileFormats.quest.getNormalBossTeleporterDestinationFloor
import world.phantasmal.web.questEditor.models.QuestEventActionModel
import world.phantasmal.web.questEditor.models.QuestEventModel
import world.phantasmal.web.questEditor.models.QuestModel
import world.phantasmal.web.questEditor.models.QuestObjectModel
import world.phantasmal.web.questEditor.models.effectiveQuestEvents

internal data class WalkthroughPoint(val x: Double, val y: Double, val z: Double)

internal enum class WalkthroughRelation { Explicit, Inferred }

internal data class WalkthroughSegment(
    val floorId: Int,
    val from: WalkthroughPoint,
    val to: WalkthroughPoint,
    val relation: WalkthroughRelation,
)

internal data class WalkthroughRoute(
    val segments: List<WalkthroughSegment>,
    val diagnostics: List<String>,
)

private enum class NodeKind { Entrance, Interaction, Event, WarpDestination, Exit }

private data class RouteNode(
    val id: Int,
    val point: WalkthroughPoint,
    val sectionId: Int?,
    val kind: NodeKind,
    val order: Int,
)

private data class InstructionFloor(val instruction: Instruction, val floorId: Int)

/** Builds independent, continuous directed walkthroughs for the selected logical floors. */
internal fun planWalkthroughRoute(
    quest: QuestModel,
    visibleFloorIds: Set<Int>,
    clientId: Int,
    scriptNpcSpawns: List<ScriptNpcSpawn> = quest.scriptNpcSpawns.value,
    scriptSpatialInteractions: List<ScriptSpatialInteraction> = quest.scriptSpatialInteractions.value,
    challengeSimulation: ChallengeModeSeedSimulation? = null,
): WalkthroughRoute {
    require(clientId in 0..3)
    val events = effectiveQuestEvents(challengeSimulation, quest.events.value)
    val segments = mutableListOf<WalkthroughSegment>()
    val diagnostics = mutableListOf<String>()
    val controlFlowGraph = ControlFlowGraph.create(quest.bytecodeIr)
    val analysisCache = mutableMapOf<Pair<Int, Int>, WalkthroughScriptAnalysis>()
    fun analysis(label: Int, floorId: Int): WalkthroughScriptAnalysis =
        analysisCache.getOrPut(label to floorId) {
            analyzeWalkthroughScript(
                quest.bytecodeIr, label, floorId, clientId, controlFlowGraph,
            )
        }
    fun activations(label: Int, floorId: Int): Set<WalkthroughEventActivation> =
        analysis(label, floorId).eventActivations

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

    // Spatial callbacks and script-NPC callbacks can create further interactions. Expand the
    // selected client's reachable instruction/floor pairs until this callback graph reaches a
    // fixed point.
    var reachabilityChanged: Boolean
    do {
        reachabilityChanged = false
        for (interaction in scriptSpatialInteractions) {
            for (floorId in interaction.executionFloorIds) {
                if (!isReachable(interaction.sourceInstruction, floorId)) continue
                if (includeAnalysis(interaction.event.label, floorId)) {
                    reachabilityChanged = true
                }
            }
        }
        for (spawn in scriptNpcSpawns) {
            for (interaction in spawn.interactions) {
                for (floorId in spawn.executionFloorIds.intersect(interaction.executionFloorIds)) {
                    if (!isReachable(spawn.sourceInstruction, floorId) ||
                        !isReachable(interaction.sourceInstruction, floorId)
                    ) continue
                    if (includeAnalysis(interaction.label, floorId)) {
                        reachabilityChanged = true
                    }
                }
            }
        }
    } while (reachabilityChanged)

    val externalActivationsByFloor = mutableMapOf<Int, MutableSet<Int>>()
    fun collectExternal(label: Int, sourceFloorId: Int) {
        for (activation in activations(label, sourceFloorId)) {
            if (activation.floorId != sourceFloorId) {
                externalActivationsByFloor
                    .getOrPut(activation.floorId, ::mutableSetOf)
                    .add(activation.eventId)
            }
        }
    }
    for (obj in quest.objects.value) {
        obj.entity.activeScriptLabel?.let { collectExternal(it, obj.floorId) }
    }
    for (npc in quest.npcs.value) {
        npc.entity.activeScriptLabelOrNull()?.let { collectExternal(it, npc.floorId) }
    }
    for (interaction in scriptSpatialInteractions) {
        for (sourceFloorId in interaction.executionFloorIds) {
            if (!isReachable(interaction.sourceInstruction, sourceFloorId)) continue
            collectExternal(interaction.event.label, sourceFloorId)
        }
    }
    for (spawn in scriptNpcSpawns) {
        for (sourceFloorId in spawn.executionFloorIds) {
            if (!isReachable(spawn.sourceInstruction, sourceFloorId)) continue
            for (interaction in spawn.interactions) {
                if (sourceFloorId !in interaction.executionFloorIds ||
                    !isReachable(interaction.sourceInstruction, sourceFloorId)
                ) continue
                collectExternal(interaction.label, sourceFloorId)
            }
        }
    }

    for (floorId in visibleFloorIds.sorted()) {
        val floorRoute = planFloor(
            quest, events, floorId, clientId, scriptNpcSpawns,
            scriptSpatialInteractions,
            externalActivationsByFloor[floorId].orEmpty(),
            ::activations,
            ::isReachable,
        )
        segments += floorRoute.segments
        diagnostics += floorRoute.diagnostics
    }
    return WalkthroughRoute(segments, diagnostics)
}

private fun planFloor(
    quest: QuestModel,
    events: List<QuestEventModel>,
    floorId: Int,
    clientId: Int,
    scriptNpcSpawns: List<ScriptNpcSpawn>,
    scriptSpatialInteractions: List<ScriptSpatialInteraction>,
    externalRootEvents: Set<Int>,
    activations: (label: Int, floorId: Int) -> Set<WalkthroughEventActivation>,
    isReachable: (instruction: Instruction?, floorId: Int) -> Boolean,
): WalkthroughRoute {
    val objects = quest.objects.value.filter { it.floorId == floorId }
    val npcs = quest.npcs.value.filter { it.floorId == floorId }
    val floorEvents = events.filter { it.floorId == floorId }
    val nodes = mutableListOf<RouteNode>()
    val explicit = mutableMapOf<Int, MutableSet<Int>>()
    val diagnostics = mutableListOf<String>()
    val activationSources = mutableMapOf<Int, MutableList<Int>>()
    var nextId = 0
    var order = 0

    fun addNode(point: WalkthroughPoint, sectionId: Int?, kind: NodeKind): Int {
        val id = nextId++
        nodes += RouteNode(id, point, sectionId, kind, order++)
        return id
    }
    fun connect(from: Int, to: Int) {
        explicit.getOrPut(from, ::mutableSetOf).add(to)
    }
    fun registerActivations(sourceId: Int, activations: Iterable<Int>) {
        for (eventId in activations) activationSources.getOrPut(eventId, ::mutableListOf).add(sourceId)
    }

    val entrance = objects.firstOrNull { obj ->
        obj.type == ObjectType.PlayerSet &&
            obj.entity.data.getFloat(40).roundToInt() == clientId &&
            obj.entity.data.getInt(52) == 0
    }
    val entranceId = if (entrance == null) {
        diagnostics += "Floor $floorId: no Player Set entrance for client $clientId."
        null
    } else {
        addNode(entrance.worldPoint(), entrance.sectionId.value, NodeKind.Entrance)
    }
    if (entranceId == null) return WalkthroughRoute(emptyList(), diagnostics)

    // Root script events occur after entering the floor but have no independent spatial anchor.
    val rootEvents = activations(0, 0)
        .filter { it.floorId == floorId }
        .map { it.eventId }
        .plus(externalRootEvents)

    for (obj in objects) {
        when {
            obj.type == ObjectType.EventCollision -> {
                val node = addNode(obj.worldPoint(), obj.sectionId.value, NodeKind.Interaction)
                registerActivations(node, listOf(obj.entity.data.getInt(52)))
            }
            obj.entity.activeScriptLabel != null -> {
                val label = obj.entity.activeScriptLabel!!
                val node = addNode(obj.worldPoint(), obj.sectionId.value, NodeKind.Interaction)
                val eventIds = activations(label, floorId)
                    .filter { it.floorId == floorId }.map { it.eventId }
                registerActivations(node, eventIds)
            }
        }
    }


    for (npc in npcs) {
        val label = npc.entity.activeScriptLabelOrNull() ?: continue
        val node = addNode(npc.worldPoint(), npc.sectionId.value, NodeKind.Interaction)
        val eventIds = activations(label, floorId)
            .filter { it.floorId == floorId }.map { it.eventId }
        registerActivations(node, eventIds)
    }

    for (interaction in scriptSpatialInteractions.filter {
        floorId in it.executionFloorIds && isReachable(it.sourceInstruction, floorId)
    }) {
        val origin = interaction.origin
        val node = addNode(
            WalkthroughPoint(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble()),
            sectionId = null,
            kind = NodeKind.Interaction,
        )
        val eventIds = activations(interaction.event.label, floorId)
            .filter { it.floorId == floorId }.map { it.eventId }
        registerActivations(node, eventIds)
    }

    for (spawn in scriptNpcSpawns.filter {
        floorId in it.executionFloorIds && isReachable(it.sourceInstruction, floorId)
    }) {
        for (interaction in spawn.interactions.sortedBy { it.label }) {
            if (floorId !in interaction.executionFloorIds ||
                !isReachable(interaction.sourceInstruction, floorId)
            ) continue
            val node = addNode(
                WalkthroughPoint(spawn.x.toDouble(), spawn.y.toDouble(), spawn.z.toDouble()),
                sectionId = null,
                kind = NodeKind.Interaction,
            )
            val eventIds = activations(interaction.label, floorId)
                .filter { it.floorId == floorId }.map { it.eventId }
            registerActivations(node, eventIds)
        }
    }

    val eventNodeIds = mutableMapOf<Int, MutableList<Int>>()
    val eventNodeByRecord = mutableMapOf<QuestEventModel, Int>()
    for ((eventIndex, event) in floorEvents.withIndex()) {
        val matchingNpcs = npcs.filter {
            it.sectionId.value == event.sectionId.value && it.wave.value.id == event.wave.value.id
        }
        val sectionObjects = objects.filter { it.sectionId.value == event.sectionId.value }
        val sourceNodes = activationSources[event.id.value].orEmpty().mapNotNull { sourceId ->
            nodes.firstOrNull { it.id == sourceId }
        }
        val point = when {
            matchingNpcs.isNotEmpty() -> matchingNpcs.map { it.worldPoint() }.average()
            sourceNodes.isNotEmpty() -> sourceNodes.map { it.point }.average()
            sectionObjects.isNotEmpty() -> sectionObjects.map { it.worldPoint() }.average()
            else -> null
        }
        if (point == null) {
            diagnostics += "Floor $floorId: event ${event.id.value} record $eventIndex has no spatial anchor."
            continue
        }
        val node = addNode(point, event.sectionId.value, NodeKind.Event)
        eventNodeByRecord[event] = node
        eventNodeIds.getOrPut(event.id.value, ::mutableListOf).add(node)
    }

    for ((eventId, sources) in activationSources) {
        val targets = eventNodeIds[eventId]
        if (targets == null) {
            diagnostics += "Floor $floorId: activation targets missing event $eventId."
        } else {
            for (source in sources) for (target in targets) connect(source, target)
        }
    }
    for (eventId in rootEvents) {
        val targets = eventNodeIds[eventId]
        if (targets == null) diagnostics += "Floor $floorId: root script targets missing event $eventId."
        else for (target in targets) connect(entranceId, target)
    }
    for (event in floorEvents) {
        val source = eventNodeByRecord[event] ?: continue
        for (action in event.actions.value) {
            when (action) {
                is QuestEventActionModel.TriggerEvent -> {
                    val targets = eventNodeIds[action.eventId.value]
                    if (targets == null) {
                        diagnostics += "Floor $floorId: event ${event.id.value} targets missing event ${action.eventId.value}."
                    } else {
                        for (target in targets) connect(source, target)
                    }
                }
                is QuestEventActionModel.SpawnNpcs -> {
                    val spawned = npcs.filter {
                        it.sectionId.value == action.sectionId.value &&
                            it.wave.value.id == action.appearFlag.value
                    }
                    if (spawned.isEmpty()) {
                        diagnostics += "Floor $floorId: event ${event.id.value} spawn action has no NPC anchor."
                    } else {
                        val target = addNode(
                            spawned.map { it.worldPoint() }.average(),
                            action.sectionId.value,
                            NodeKind.Interaction,
                        )
                        connect(source, target)
                    }
                }
                is QuestEventActionModel.Door -> {
                    val doors = objects.filter { it.controlsDoorId(action.doorId.value) }
                    if (doors.isEmpty()) {
                        diagnostics += "Floor $floorId: event ${event.id.value} door ${action.doorId.value} has no object anchor."
                    } else {
                        for (door in doors) {
                            val target = addNode(
                                door.worldPoint(), door.sectionId.value, NodeKind.Interaction,
                            )
                            connect(source, target)
                        }
                    }
                }
            }
        }
    }

    for (obj in objects) {
        if (obj.type == ObjectType.Warp) {
            val source = addNode(obj.worldPoint(), obj.sectionId.value, NodeKind.Interaction)
            val destination = addNode(obj.destinationPoint(), obj.sectionId.value, NodeKind.WarpDestination)
            connect(source, destination)
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
                val source = addNode(obj.worldPoint(), obj.sectionId.value, NodeKind.Interaction)
                val destination = addNode(
                    destinationPlayerSet.worldPoint(),
                    destinationPlayerSet.sectionId.value,
                    NodeKind.WarpDestination,
                )
                connect(source, destination)
            }
            continue
        }
        val destinationFloor = when (obj.type) {
            ObjectType.Teleporter, ObjectType.QuestWarp -> obj.entity.data.getInt(52)
            ObjectType.BossTeleporter -> getNormalBossTeleporterDestinationFloor(quest.episode, floorId)
            else -> null
        }
        if (destinationFloor != null && destinationFloor != floorId) {
            addNode(obj.worldPoint(), obj.sectionId.value, NodeKind.Exit)
        }
    }

    if (nodes.isEmpty()) return WalkthroughRoute(emptyList(), diagnostics)
    val unvisited = nodes.associateByTo(mutableMapOf()) { it.id }
    var current = entranceId?.let(unvisited::remove)
        ?: unvisited.values.minByOrNull { it.order }?.also { unvisited.remove(it.id) }
    val segments = mutableListOf<WalkthroughSegment>()
    val renderedExplicitEdges = mutableSetOf<Pair<Int, Int>>()
    while (current != null && unvisited.isNotEmpty()) {
        val explicitNext = explicit[current.id].orEmpty()
            .mapNotNull(unvisited::get)
            .minWithOrNull(nodeComparator(current))
        val candidates = unvisited.values.filter { candidate ->
            candidate.kind != NodeKind.Exit || unvisited.values.all { it.kind == NodeKind.Exit }
        }.ifEmpty { unvisited.values }
        val next = explicitNext ?: candidates.minWithOrNull(nodeComparator(current)) ?: break
        val relation = if (next.id in explicit[current.id].orEmpty()) {
            renderedExplicitEdges += current.id to next.id
            WalkthroughRelation.Explicit
        } else {
            WalkthroughRelation.Inferred
        }
        segments += WalkthroughSegment(
            floorId,
            current.point,
            next.point,
            relation,
        )
        unvisited.remove(next.id)
        current = next
    }
    // A branching event graph cannot be reduced to one Hamiltonian line without losing causal
    // edges. Keep the continuous inferred traversal above and overlay every remaining real edge.
    val nodesById = nodes.associateBy { it.id }
    for ((fromId, targets) in explicit) {
        val from = nodesById[fromId] ?: continue
        for (toId in targets) {
            if ((fromId to toId) in renderedExplicitEdges) continue
            val to = nodesById[toId] ?: continue
            segments += WalkthroughSegment(
                floorId, from.point, to.point, WalkthroughRelation.Explicit,
            )
        }
    }
    return WalkthroughRoute(segments, diagnostics)
}

private fun nodeComparator(from: RouteNode): Comparator<RouteNode> =
    compareBy<RouteNode>(
        { if (it.sectionId != null && it.sectionId == from.sectionId) 0 else 1 },
        { from.point.distanceSquaredTo(it.point) },
        { it.order },
    )

private fun QuestObjectModel.worldPoint(): WalkthroughPoint = worldPosition.value.toPoint()
private fun QuestObjectModel.destinationPoint(): WalkthroughPoint = destinationPosition.value.toPoint()
private fun QuestObjectModel.controlsDoorId(doorId: Int): Boolean =
    controlledDoorIds()?.contains(doorId) == true
private fun world.phantasmal.web.questEditor.models.QuestNpcModel.worldPoint(): WalkthroughPoint =
    worldPosition.value.toPoint()
private fun world.phantasmal.web.externals.three.Vector3.toPoint() = WalkthroughPoint(x, y, z)

private fun List<WalkthroughPoint>.average(): WalkthroughPoint = WalkthroughPoint(
    sumOf { it.x } / size,
    sumOf { it.y } / size,
    sumOf { it.z } / size,
)

private fun WalkthroughPoint.distanceSquaredTo(other: WalkthroughPoint): Double {
    val dx = x - other.x
    val dy = y - other.y
    val dz = z - other.z
    return dx * dx + dy * dy + dz * dz
}
