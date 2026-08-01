package world.phantasmal.web.questEditor.rendering

import kotlinx.browser.window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import world.phantasmal.core.disposable.DisposableSupervisedScope
import world.phantasmal.psolib.fileFormats.fog.FogEntry
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.web.core.rendering.disposeObject3DResources
import world.phantasmal.web.externals.three.Color
import world.phantasmal.web.externals.three.DoubleSide
import world.phantasmal.web.externals.three.Mesh
import world.phantasmal.web.externals.three.MeshBasicMaterial
import world.phantasmal.web.externals.three.SphereGeometry
import world.phantasmal.web.questEditor.loading.FogAssetLoader
import world.phantasmal.web.questEditor.models.QuestObjectModel
import world.phantasmal.web.questEditor.stores.QuestEditorStore
import world.phantasmal.webui.DisposableContainer
import world.phantasmal.webui.obj

internal data class FogRenderValues(
    val color: Int,
    val near: Double,
    val far: Double,
    val opacity: Double,
)

internal fun selectedFogIndex(type: ObjectType?, rawFogIndex: Int): Int? = rawFogIndex.takeIf {
    type == ObjectType.FogCollision || type == ObjectType.FogCollisionSW
}

internal fun normalizeFogIndex(rawFogIndex: Int): Int =
    (if (rawFogIndex >= 0x1000) rawFogIndex - 0x1000 else rawFogIndex).coerceIn(0, 0xFF)

internal fun fogRenderValues(entry: FogEntry, frame: Double): FogRenderValues {
    fun pulse(amplitude: Float, phase: Int): Double =
        amplitude * sin((frame * entry.animationSpeed + phase) * PI / 180.0)

    val animatedStart = entry.start + pulse(entry.startPulseDistance, entry.startPulsePhase)
    val animatedEnd = entry.end + pulse(entry.endPulseDistance, entry.endPulsePhase)
    val near = max(0.0, animatedStart)
    val far = max(near + 0.01, animatedEnd)
    val span = max(0.0, far - near)
    val densityOpacity = if (entry.density > 0f) {
        1.0 - exp(-entry.density.toDouble() * span)
    } else {
        0.0
    }
    val linearOpacity = if (far > 0.0) span / far * 0.55 else 0.55

    // PSOBB uses right-handed view-space Z and submits -start/-end to D3D. Three.js expects
    // positive camera distances, so the stored values map back to start/end here. A non-positive
    // end is the client's full-fog-at-the-camera case.
    return FogRenderValues(
        color = entry.color and 0xFFFFFF,
        near = near,
        far = far,
        opacity = max(densityOpacity, linearOpacity).coerceIn(0.08, 0.78),
    )
}

/** Previews fog only inside the selected PSOBB Fog Collision object's radius. */
class FogPreviewManager(
    private val renderContext: QuestRenderContext,
    fogAssetLoader: FogAssetLoader,
    private val questEditorStore: QuestEditorStore,
    private val nowMs: () -> Double = { window.performance.now() },
    loadEntries: suspend () -> List<FogEntry> = fogAssetLoader::load,
) : DisposableContainer() {
    private val scope = addDisposable(DisposableSupervisedScope(this::class, Dispatchers.Default))
    private var entries: List<FogEntry>? = null
    private var volume: Mesh? = null
    private var material: MeshBasicMaterial? = null
    private var currentRawFogIndex: Int? = null
    private var transitionStartedAt = 0.0
    private var transitionFrom: FogRenderValues? = null
    private var lastValues: FogRenderValues? = null

    init {
        scope.launch { entries = loadEntries() }
    }

    fun beforeRender() {
        val fogEntries = entries ?: return
        val selected = questEditorStore.selectedEntity.value as? QuestObjectModel
        val rawIndex = selected?.let {
            selectedFogIndex(it.type, it.entity.data.getInt(52))
        }
        val radius = selected?.entity?.data?.getFloat(40)?.toDouble() ?: 0.0
        val now = nowMs()
        val frame = now * 30.0 / 1000.0
        val target = rawIndex?.takeIf { radius > 0.0 }?.let {
            fogRenderValues(fogEntries[normalizeFogIndex(it)], frame)
        }
        val transitionInstantly = rawIndex != null && selected?.entity?.data?.getInt(56) != 0

        if (rawIndex != currentRawFogIndex) {
            currentRawFogIndex = rawIndex
            transitionStartedAt = now
            transitionFrom = lastValues ?: target?.let {
                it.copy(opacity = 0.0)
            }

            if (transitionInstantly) {
                transitionFrom = target
            }
        }

        if (target == null) {
            clearPreview()
            return
        }

        val progress = if (transitionFrom === target || transitionInstantly) {
            1.0
        } else {
            ((now - transitionStartedAt) / FADE_DURATION_MS).coerceIn(0.0, 1.0)
        }
        val values = transitionFrom?.let { from -> lerp(from, target, progress) } ?: target
        apply(selected ?: return, radius, values)
    }

    override fun dispose() {
        clearPreview()
        super.dispose()
    }

    private fun apply(selected: QuestObjectModel, radius: Double, values: FogRenderValues) {
        val previewMaterial = material ?: MeshBasicMaterial(obj {
            color = Color(values.color)
            transparent = true
            opacity = values.opacity
            side = DoubleSide
        }).also {
            it.asDynamic().depthWrite = false
            material = it
        }
        val previewVolume = volume ?: Mesh(
            SphereGeometry(radius = 1.0, widthSegments = 48, heightSegments = 24),
            previewMaterial,
        ).also {
            it.name = "Selected Fog Volume"
            it.renderOrder = 900
            renderContext.helpers.add(it)
            volume = it
        }

        previewMaterial.color.set(values.color)
        previewMaterial.opacity = values.opacity
        previewVolume.position.copy(selected.worldPosition.value)
        previewVolume.scale.set(radius, radius, radius)
        lastValues = values
    }

    private fun clearPreview() {
        volume?.let {
            renderContext.helpers.remove(it)
            disposeObject3DResources(it)
        }
        volume = null
        material = null
        lastValues = null
        transitionFrom = null
    }

    private fun lerp(from: FogRenderValues, to: FogRenderValues, t: Double): FogRenderValues =
        FogRenderValues(
            color = lerpColor(from.color, to.color, t),
            near = from.near + (to.near - from.near) * t,
            far = from.far + (to.far - from.far) * t,
            opacity = from.opacity + (to.opacity - from.opacity) * t,
        )

    private fun lerpColor(from: Int, to: Int, t: Double): Int {
        fun channel(shift: Int): Int {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 0xFF)
        }
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private companion object {
        const val FADE_DURATION_MS = 500.0
    }
}
