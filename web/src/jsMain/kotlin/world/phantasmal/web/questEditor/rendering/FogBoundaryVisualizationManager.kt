package world.phantasmal.web.questEditor.rendering

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import world.phantasmal.core.disposable.DisposableSupervisedScope
import world.phantasmal.psolib.fileFormats.fog.FogEntry
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.web.externals.three.Color
import world.phantasmal.web.externals.three.DoubleSide
import world.phantasmal.web.externals.three.Group
import world.phantasmal.web.externals.three.Mesh
import world.phantasmal.web.externals.three.MeshBasicMaterial
import world.phantasmal.web.externals.three.SphereGeometry
import world.phantasmal.web.questEditor.loading.FogAssetLoader
import world.phantasmal.web.questEditor.models.QuestObjectModel
import world.phantasmal.web.questEditor.stores.QuestEditorRenderState
import world.phantasmal.web.questEditor.stores.QuestEditorUiStore
import world.phantasmal.webui.DisposableContainer
import world.phantasmal.webui.obj

internal fun isFogCollision(type: ObjectType?): Boolean =
    type == ObjectType.FogCollision || type == ObjectType.FogCollisionSW

internal fun normalizeFogIndex(rawFogIndex: Int): Int? {
    val index = if (rawFogIndex in 0x1000..0x10FF) rawFogIndex - 0x1000 else rawFogIndex
    return index.takeIf { it in 0..0xFF }
}

/** Renders the world-space trigger boundaries of Fog Collision objects in the current area. */
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
        val fogObjects = questEditorStore.currentAreaObjects.value
            .filterTo(mutableSetOf()) { isFogCollision(it.type) && radius(it) > 0.0 }

        boundaries.keys.toList().forEach { obj ->
            if (obj !in fogObjects) {
                removeBoundary(obj)
            }
        }

        fogObjects.forEach { obj ->
            val boundary = boundaries.getOrPut(obj) { createBoundary(obj) }
            updateBoundary(
                boundary,
                obj,
                fogEntries,
                selected = questEditorStore.selectedEntity.value === obj,
            )
        }
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
            opacity = UNSELECTED_FILL_OPACITY
            side = DoubleSide
        }).also {
            it.asDynamic().depthWrite = false
        }
        val outlineMaterial = MeshBasicMaterial(obj {
            color = Color(0)
            transparent = true
            opacity = UNSELECTED_OUTLINE_OPACITY
            wireframe = true
        }).also {
            it.asDynamic().depthTest = false
            it.asDynamic().depthWrite = false
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
        fogEntries: List<FogEntry>,
        selected: Boolean,
    ) {
        val color = normalizeFogIndex(obj.entity.data.getInt(52))
            ?.let { fogEntries.getOrNull(it) }
            ?.color
            ?.and(0xFFFFFF)
            ?: INVALID_FOG_COLOR
        val radius = radius(obj)

        boundary.group.position.copy(obj.worldPosition.value)
        boundary.group.scale.set(radius, radius, radius)
        boundary.fillMaterial.color.set(color)
        boundary.outlineMaterial.color.set(color)
        boundary.fillMaterial.opacity =
            if (selected) SELECTED_FILL_OPACITY else UNSELECTED_FILL_OPACITY
        boundary.outlineMaterial.opacity =
            if (selected) SELECTED_OUTLINE_OPACITY else UNSELECTED_OUTLINE_OPACITY
    }

    private fun removeBoundary(obj: QuestObjectModel) {
        boundaries.remove(obj)?.let { boundary ->
            renderContext.helpers.remove(boundary.group)
            boundary.fillMaterial.dispose()
            boundary.outlineMaterial.dispose()
        }
    }

    private fun clearAll() {
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

    private data class FogBoundary(
        val group: Group,
        val fillMaterial: MeshBasicMaterial,
        val outlineMaterial: MeshBasicMaterial,
    )

    private companion object {
        const val INVALID_FOG_COLOR = 0xFF00FF
        const val UNSELECTED_FILL_OPACITY = 0.08
        const val SELECTED_FILL_OPACITY = 0.22
        const val UNSELECTED_OUTLINE_OPACITY = 0.35
        const val SELECTED_OUTLINE_OPACITY = 0.95
    }
}
