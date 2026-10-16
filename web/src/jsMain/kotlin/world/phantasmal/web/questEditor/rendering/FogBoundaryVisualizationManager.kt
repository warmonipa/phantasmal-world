package world.phantasmal.web.questEditor.rendering

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.browser.document
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import world.phantasmal.core.disposable.DisposableSupervisedScope
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.fileFormats.fog.FOG_ENTRY_COUNT
import world.phantasmal.psolib.fileFormats.fog.FogEntry
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.web.externals.three.Color
import world.phantasmal.web.externals.three.DoubleSide
import world.phantasmal.web.externals.three.Fog
import world.phantasmal.web.externals.three.Group
import world.phantasmal.web.externals.three.Mesh
import world.phantasmal.web.externals.three.MeshBasicMaterial
import world.phantasmal.web.externals.three.SphereGeometry
import world.phantasmal.web.externals.three.Sprite
import world.phantasmal.web.externals.three.SpriteMaterial
import world.phantasmal.web.externals.three.Texture
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.questEditor.loading.FogAssetLoader
import world.phantasmal.web.questEditor.models.AreaModel
import world.phantasmal.web.questEditor.models.QuestObjectModel
import world.phantasmal.web.questEditor.stores.QuestEditorRenderState
import world.phantasmal.web.questEditor.stores.QuestEditorUiStore
import world.phantasmal.webui.DisposableContainer
import world.phantasmal.webui.obj

/**
 * A fog trigger as newserv's object table (src/Map.cc) defines it:
 * - 0x0007 TObjFogCollision: param1 = radius, param4 = fog entry number, param5 = transition
 *   type (0 = fade in, 1 = switch instantly).
 * - 0x0018 TObjFogCollisionSwitch: as 0x0007, plus param3 (>= 1: fog is on while the switch flag
 *   is on; otherwise while it is off) and param6 = switch flag number.
 * - 0x0160 TObjFogCollisionPoison: as 0x0018, plus param2 = poison power. On the Episode I Vol
 *   Opt floor, 0x0160 constructs TObjWarpBoss03 instead and is not fog.
 * - 0x0391 Heat: a poison fog with the same parameters as 0x0160.
 *
 * A fog entry number lower than the current fog number does nothing. If it is 0x1000 or more,
 * the game compares it as is and then subtracts 0x1000, which lets an earlier fog override a
 * later one.
 */
internal class FogTrigger(
    val type: ObjectType,
    val radius: Double,
    val rawFogNumber: Int,
    /** Index into the fog entry table, or null if [rawFogNumber] doesn't name an entry. */
    val fogIndex: Int?,
    val instantTransition: Boolean,
    val switchFlag: Int?,
    val activeWhileSwitchOn: Boolean,
    val poisonPower: Float?,
)

internal fun fogTrigger(obj: QuestObjectModel, area: AreaModel?): FogTrigger? {
    val type = obj.type
    val data = obj.entity.data
    val switched: Boolean
    val poison: Boolean
    when (type) {
        ObjectType.FogCollision -> {
            switched = false
            poison = false
        }
        ObjectType.FogCollisionSW -> {
            switched = true
            poison = false
        }
        ObjectType.Poison -> {
            if (area?.episode == Episode.I && area.id == VOL_OPT_AREA_ID) return null
            switched = true
            poison = true
        }
        ObjectType.Heat -> {
            switched = true
            poison = true
        }
        else -> return null
    }
    val rawFogNumber = data.getInt(52)
    return FogTrigger(
        type = type,
        radius = data.getFloat(40).toDouble(),
        rawFogNumber = rawFogNumber,
        fogIndex = normalizeFogIndex(rawFogNumber),
        instantTransition = data.getInt(56) == 1,
        switchFlag = if (switched) data.getInt(60) else null,
        activeWhileSwitchOn = switched && data.getFloat(48) >= 1f,
        poisonPower = if (poison) data.getFloat(44) else null,
    )
}

internal fun normalizeFogIndex(rawFogIndex: Int): Int? {
    val index = if (rawFogIndex >= 0x1000) rawFogIndex - 0x1000 else rawFogIndex
    return index.takeIf { it in 0 until FOG_ENTRY_COUNT }
}

/**
 * Label lines for a fog trigger, e.g. ["Fog 17 · end 70 · #000000", "on while SW 99 off"].
 * The first line is drawn after a swatch of the fog colour.
 */
internal fun fogLabelLines(
    trigger: FogTrigger,
    entry: FogEntry?,
    switchFlags: List<Int> = listOfNotNull(trigger.switchFlag),
): List<String> {
    val raw = trigger.rawFogNumber
    val number = when {
        trigger.fogIndex == null -> "Fog 0x${raw.toString(16).uppercase()} (no entry)"
        raw >= 0x1000 -> "Fog ${trigger.fogIndex} (0x${raw.toString(16).uppercase()})"
        else -> "Fog ${trigger.fogIndex}"
    }
    val first = buildList {
        add(number)
        if (entry != null) {
            add("end ${entry.end.toInt()}")
            add(fogColorHex(entry.color))
        }
    }.joinToString(" · ")
    val second = buildList {
        trigger.poisonPower?.let { power ->
            val kind = if (trigger.type == ObjectType.Heat) "Heat" else "Poison"
            add("$kind ${formatPower(power)}")
        }
        if (switchFlags.isNotEmpty()) {
            val state = if (trigger.activeWhileSwitchOn) "on" else "off"
            add("on while SW ${switchFlags.joinToString(", ")} $state")
        }
        if (trigger.instantTransition) add("instant")
    }.joinToString(" · ")
    return if (second.isEmpty()) listOf(first) else listOf(first, second)
}

/**
 * Whether two triggers are the same fog boundary drawn more than once: identical apart from their
 * switch flag, with practically the same centre (quests stack one trigger per switch flag).
 */
internal fun sameFogBoundary(
    a: FogTrigger,
    aPosition: Vector3,
    b: FogTrigger,
    bPosition: Vector3,
): Boolean =
    a.type == b.type &&
        a.rawFogNumber == b.rawFogNumber &&
        a.instantTransition == b.instantTransition &&
        a.activeWhileSwitchOn == b.activeWhileSwitchOn &&
        a.poisonPower == b.poisonPower &&
        kotlin.math.abs(a.radius - b.radius) <= SAME_BOUNDARY_TOLERANCE * a.radius &&
        aPosition.distanceTo(bPosition) <= SAME_BOUNDARY_TOLERANCE * a.radius

private const val SAME_BOUNDARY_TOLERANCE = 0.01

internal fun fogColorHex(color: Int): String =
    "#" + (color and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

private fun formatPower(power: Float): String =
    if (power == power.toInt().toFloat()) power.toInt().toString() else power.toString()

private const val VOL_OPT_AREA_ID = 13

/**
 * Renders the world-space trigger boundaries of the fog triggers in the current area (see
 * [FogTrigger]) in their fog entry's colour, each labelled with its fog entry and conditions.
 *
 * While a fog trigger is selected, the scene is also rendered with that trigger's fog the way
 * the client applies a fog entry: linear distance fog from the entry's start to its end
 * distance, with the clear colour set to the fog colour. The boundary helpers are exempt.
 */
class FogBoundaryVisualizationManager(
    private val renderContext: QuestRenderContext,
    fogAssetLoader: FogAssetLoader,
    private val questEditorStore: QuestEditorRenderState,
    private val questEditorUiStore: QuestEditorUiStore,
    private val loadEntries: suspend () -> List<FogEntry> = fogAssetLoader::load,
) : DisposableContainer() {
    private val scope = addDisposable(DisposableSupervisedScope(this::class, Dispatchers.Default))
    private val boundaries = mutableMapOf<QuestObjectModel, FogBoundary>()
    private var geometry: SphereGeometry? = null
    private var entries: List<FogEntry>? = null
    private var loadJob: Job? = null
    private var loadRequested = false
    private var loadGeneration = 0
    private var preview: FogPreview? = null

    fun beforeRender() {
        if (!questEditorUiStore.showFogBoundaries.value) {
            if (loadRequested || entries != null || boundaries.isNotEmpty() || geometry != null) {
                clearAll()
            }
            return
        }

        if (!loadRequested) {
            loadRequested = true
            val generation = ++loadGeneration
            loadJob = scope.launch {
                val loadedEntries = loadEntries()
                if (generation == loadGeneration) {
                    entries = loadedEntries
                    loadJob = null
                }
            }
        }

        val fogEntries = entries ?: return
        val area = questEditorStore.currentArea.value
        val triggers = LinkedHashMap<QuestObjectModel, FogTrigger>()
        questEditorStore.currentAreaObjects.value.forEach { obj ->
            fogTrigger(obj, area)?.takeIf { it.radius > 0.0 }?.let { triggers[obj] = it }
        }

        boundaries.keys.toList().forEach { obj ->
            if (obj !in triggers) {
                removeBoundary(obj)
            }
        }

        val selectedEntity = questEditorStore.selectedEntity.value

        // Draw stacked copies of one boundary (differing only in switch flag) once, so their
        // translucent fills don't add up, and list all their switch flags on the one label.
        val stacks = mutableListOf<MutableList<QuestObjectModel>>()
        triggers.forEach { (obj, trigger) ->
            val stack = stacks.firstOrNull { stack ->
                val first = stack.first()
                sameFogBoundary(
                    triggers.getValue(first), first.worldPosition.value,
                    trigger, obj.worldPosition.value,
                )
            }
            if (stack == null) stacks += mutableListOf(obj) else stack += obj
        }

        val labelCandidates = stacks.map { stack ->
            val shown = stack.firstOrNull { it === selectedEntity } ?: stack.first()
            val trigger = triggers.getValue(shown)
            val selected = shown === selectedEntity
            val entry = trigger.fogIndex?.let { fogEntries.getOrNull(it) }
            stack.forEach { obj ->
                val boundary = boundaries.getOrPut(obj) { createBoundary(obj) }
                updateBoundary(boundary, obj, triggers.getValue(obj), entry, selected)
                boundary.group.visible = obj === shown
                if (obj !== shown) removeLabel(boundary)
            }
            val switchFlags = stack.mapNotNull { triggers.getValue(it).switchFlag }.distinct()
            LabelCandidate(
                shown,
                boundaries.getValue(shown),
                LabelContent(fogLabelLines(trigger, entry, switchFlags), entry?.color),
                selected,
            )
        }

        // Quests often cover one room with several overlapping triggers of the same fog. Label such
        // a cluster once (at the selected trigger, if any) instead of stacking identical labels.
        val clusters = mutableListOf<MutableList<LabelCandidate>>()
        labelCandidates.forEach { candidate ->
            val touching = clusters.filter { cluster ->
                cluster.any { it.content == candidate.content && overlaps(it.obj, candidate.obj) }
            }
            val merged = touching.flatten().toMutableList().apply { add(candidate) }
            clusters.removeAll(touching)
            clusters += merged
        }
        clusters.forEach { cluster ->
            val labelled = cluster.firstOrNull { it.selected } ?: cluster.first()
            cluster.forEach { candidate ->
                if (candidate === labelled) {
                    showLabel(candidate.boundary, candidate.obj, candidate.content)
                } else {
                    removeLabel(candidate.boundary)
                }
            }
        }
        boundaries.values.forEach { boundary ->
            if (labelCandidates.none { it.boundary === boundary }) removeLabel(boundary)
        }

        val selectedEntry = (selectedEntity as? QuestObjectModel)
            ?.let { triggers[it] }
            ?.fogIndex
            ?.let { fogEntries.getOrNull(it) }
        if (selectedEntry == null) clearPreview() else showPreview(selectedEntry)
    }

    private fun showPreview(entry: FogEntry) {
        val scene = renderContext.scene
        val current = preview ?: FogPreview(
            fog = Fog(0),
            previousFog = scene.fog,
            previousBackground = scene.background,
        ).also { preview = it }
        val color = entry.color and 0xFFFFFF
        current.fog.color.set(color)
        current.fog.near = entry.start.toDouble()
        current.fog.far = entry.end.toDouble()
        scene.fog = current.fog
        scene.background = Color(color)
    }

    private fun clearPreview() {
        preview?.let {
            renderContext.scene.fog = it.previousFog
            renderContext.scene.background = it.previousBackground
        }
        preview = null
    }

    private fun overlaps(a: QuestObjectModel, b: QuestObjectModel): Boolean =
        a.worldPosition.value.distanceTo(b.worldPosition.value) < radius(a) + radius(b)

    private fun showLabel(boundary: FogBoundary, obj: QuestObjectModel, content: LabelContent) {
        val label = boundary.label?.takeIf { boundary.labelContent == content }
            ?: createLabel(content).also { newLabel ->
                removeLabel(boundary)
                boundary.label = newLabel
                boundary.labelContent = content
                renderContext.helpers.add(newLabel)
            }
        label.position.copy(obj.worldPosition.value)
        // Size the label in world units relative to its boundary, so it zooms with the map.
        val lineHeight = (radius(obj) * LABEL_LINE_HEIGHT_PER_RADIUS)
            .coerceIn(LABEL_MIN_LINE_HEIGHT, LABEL_MAX_LINE_HEIGHT)
        val height = lineHeight * content.lines.size
        label.scale.set(height * label.asDynamic().userData.aspect as Double, height, 1.0)
    }

    override fun dispose() {
        clearAll()
        super.dispose()
    }

    private fun createBoundary(obj: QuestObjectModel): FogBoundary {
        val sphereGeometry = geometry ?: SphereGeometry(
            radius = 1.0,
            widthSegments = 32,
            heightSegments = 16,
        ).also { geometry = it }
        val fillMaterial = MeshBasicMaterial(obj {
            color = Color(0)
            transparent = true
            forceSinglePass = true
            opacity = 0.0
            side = DoubleSide
        }).also {
            it.asDynamic().depthWrite = false
            it.asDynamic().fog = false
        }
        val outlineMaterial = MeshBasicMaterial(obj {
            color = Color(0)
            transparent = true
            opacity = 0.0
            wireframe = true
        }).also {
            it.asDynamic().depthTest = false
            it.asDynamic().depthWrite = false
            it.asDynamic().fog = false
        }
        val fill = Mesh(sphereGeometry, fillMaterial).apply { renderOrder = 900 }
        val outline = Mesh(sphereGeometry, outlineMaterial).apply { renderOrder = 901 }
        val group = Group().apply {
            name = "Fog Boundary"
            add(fill)
            add(outline)
            position.copy(obj.worldPosition.value)
        }

        renderContext.helpers.add(group)
        return FogBoundary(group, fillMaterial, outlineMaterial)
    }

    private fun updateBoundary(
        boundary: FogBoundary,
        obj: QuestObjectModel,
        trigger: FogTrigger,
        entry: FogEntry?,
        selected: Boolean,
    ) {
        val color = entry?.color?.and(0xFFFFFF) ?: INVALID_FOG_COLOR

        boundary.group.position.copy(obj.worldPosition.value)
        boundary.group.scale.set(trigger.radius, trigger.radius, trigger.radius)
        boundary.fillMaterial.color.set(color)
        boundary.outlineMaterial.color.set(color)
        boundary.fillMaterial.opacity = if (selected) SELECTED_FILL_OPACITY else FILL_OPACITY
        boundary.outlineMaterial.opacity =
            if (selected) SELECTED_OUTLINE_OPACITY else OUTLINE_OPACITY
    }

    private fun createLabel(content: LabelContent): Sprite {
        val canvas = document.createElement("CANVAS") as HTMLCanvasElement
        canvas.width = LABEL_CANVAS_WIDTH
        canvas.height = LABEL_LINE_HEIGHT * content.lines.size
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.font = "bold 26px Arial"
        ctx.asDynamic().textBaseline = "middle"
        ctx.lineWidth = 5.0

        content.lines.forEachIndexed { i, line ->
            val y = LABEL_LINE_HEIGHT * (i + 0.5)
            val swatch = i == 0 && content.color != null
            val swatchWidth = if (swatch) LABEL_SWATCH_SIZE + LABEL_SWATCH_GAP else 0.0
            val width = ctx.measureText(line).width + swatchWidth
            var x = (LABEL_CANVAS_WIDTH - width) / 2.0
            if (swatch) {
                val top = y - LABEL_SWATCH_SIZE / 2.0
                ctx.fillStyle = fogColorHex(content.color!!)
                ctx.fillRect(x, top, LABEL_SWATCH_SIZE, LABEL_SWATCH_SIZE)
                ctx.lineWidth = 2.0
                ctx.strokeStyle = "#FFFFFF"
                ctx.strokeRect(x, top, LABEL_SWATCH_SIZE, LABEL_SWATCH_SIZE)
                ctx.lineWidth = 5.0
                x += swatchWidth
            }
            ctx.strokeStyle = "#000000"
            ctx.fillStyle = "#FFFFFF"
            ctx.strokeText(line, x, y)
            ctx.fillText(line, x, y)
        }

        val texture = Texture()
        texture.asDynamic().image = canvas
        texture.needsUpdate = true
        val material = SpriteMaterial(obj {
            map = texture
            transparent = true
        })
        material.asDynamic().depthTest = false
        material.asDynamic().depthWrite = false
        material.asDynamic().fog = false

        return Sprite(material).apply {
            name = "Fog Boundary Label"
            asDynamic().userData.aspect = canvas.width.toDouble() / canvas.height
            renderOrder = 902
            frustumCulled = false
        }
    }

    private fun removeLabel(boundary: FogBoundary) {
        boundary.label?.let { label ->
            renderContext.helpers.remove(label)
            label.material.map?.dispose()
            label.material.dispose()
        }
        boundary.label = null
        boundary.labelContent = null
    }

    private fun removeBoundary(obj: QuestObjectModel) {
        boundaries.remove(obj)?.let { boundary ->
            removeLabel(boundary)
            renderContext.helpers.remove(boundary.group)
            boundary.fillMaterial.dispose()
            boundary.outlineMaterial.dispose()
        }
    }

    private fun clearAll() {
        clearPreview()
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
        loadRequested = false
        entries = null

        boundaries.keys.toList().forEach(::removeBoundary)
        geometry?.dispose()
        geometry = null
    }

    private fun radius(obj: QuestObjectModel): Double =
        obj.entity.data.getFloat(40).toDouble()

    private class FogPreview(val fog: Fog, val previousFog: dynamic, val previousBackground: dynamic)

    private data class LabelContent(val lines: List<String>, val color: Int?)

    private class LabelCandidate(
        val obj: QuestObjectModel,
        val boundary: FogBoundary,
        val content: LabelContent,
        val selected: Boolean,
    )

    private class FogBoundary(
        val group: Group,
        val fillMaterial: MeshBasicMaterial,
        val outlineMaterial: MeshBasicMaterial,
        var label: Sprite? = null,
        var labelContent: LabelContent? = null,
    )

    private companion object {
        const val INVALID_FOG_COLOR = 0xFF00FF
        const val FILL_OPACITY = 0.35
        const val SELECTED_FILL_OPACITY = 0.5
        const val OUTLINE_OPACITY = 0.5
        const val SELECTED_OUTLINE_OPACITY = 0.95
        const val LABEL_CANVAS_WIDTH = 640
        const val LABEL_LINE_HEIGHT = 40
        const val LABEL_SWATCH_SIZE = 22.0
        const val LABEL_SWATCH_GAP = 8.0
        const val LABEL_LINE_HEIGHT_PER_RADIUS = 0.12
        const val LABEL_MIN_LINE_HEIGHT = 10.0
        const val LABEL_MAX_LINE_HEIGHT = 80.0
    }
}
