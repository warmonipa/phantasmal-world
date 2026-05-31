package world.phantasmal.web.viewer.controllers

import world.phantasmal.cell.Cell
import world.phantasmal.cell.map
import world.phantasmal.cell.mutableCell
import world.phantasmal.web.core.PwToolType
import world.phantasmal.web.core.controllers.PathAwareTab
import world.phantasmal.web.core.controllers.PathAwareTabContainerController
import world.phantasmal.web.core.stores.UiStore
import world.phantasmal.web.viewer.ViewerUrls
import world.phantasmal.web.viewer.models.AnimationModel
import world.phantasmal.web.viewer.models.ViewerModel
import world.phantasmal.web.viewer.stores.ViewerStore

sealed class ViewerTab(
    override val title: String,
    override val path: String,
) : PathAwareTab {
    object Mesh : ViewerTab("Model", ViewerUrls.mesh)
    object Texture : ViewerTab("Textures", ViewerUrls.texture)
}

class ViewerController(
    uiStore: UiStore,
    private val store: ViewerStore,
) : PathAwareTabContainerController<ViewerTab>(
    uiStore,
    PwToolType.Viewer,
    tabs = listOf(ViewerTab.Mesh, ViewerTab.Texture),
) {
    private val _assetSearch = mutableCell("")

    val assetSearch: Cell<String> = _assetSearch
    val modelGroups: Cell<List<ViewerModel.Group>> = _assetSearch.map { query ->
        val normalizedQuery = query.trim().lowercase()

        if (normalizedQuery.isEmpty()) {
            ViewerModel.GROUPS
        } else {
            ViewerModel.GROUPS.mapNotNull { group ->
                val items = group.items.filter {
                    it.uiName.lowercase().contains(normalizedQuery) ||
                            it.slug.lowercase().contains(normalizedQuery)
                }

                if (items.isEmpty()) null else group.copy(items = items)
            }
        }
    }
    val currentModel: Cell<ViewerModel?> = store.currentModel

    val animations: Cell<List<AnimationModel>> = store.animations
    val currentAnimation: Cell<AnimationModel?> = store.currentAnimation

    suspend fun setCurrentModel(model: ViewerModel?) {
        store.setCurrentModel(model)
    }

    fun setAssetSearch(query: String) {
        _assetSearch.value = query
    }

    suspend fun setCurrentAnimation(animation: AnimationModel) {
        store.setCurrentAnimation(animation)
    }
}
