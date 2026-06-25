package world.phantasmal.web.viewer.controllers

import world.phantasmal.cell.Cell
import world.phantasmal.cell.cell
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
    private val initialAssetCategory =
        categoryForModel(store.currentModel.value) ?: ViewerModel.CATEGORIES.first()
    private val _activeAssetCategory = mutableCell(initialAssetCategory)
    private val _expandedAssetGroups = mutableCell(
        expandedGroupsForModel(store.currentModel.value, initialAssetCategory)
            ?.let { setOf(it.label) }
            ?: defaultExpandedGroups(initialAssetCategory)
    )

    val assetSearch: Cell<String> = _assetSearch
    val assetCategories: Cell<List<ViewerModel.Category>> = cell(ViewerModel.CATEGORIES)
    val activeAssetCategory: Cell<ViewerModel.Category> = _activeAssetCategory
    val expandedAssetGroups: Cell<Set<String>> = _expandedAssetGroups
    val assetSearchSuggestions: Cell<List<ViewerModel>> =
        _assetSearch.map(::searchSuggestions)
    val modelGroups: Cell<List<ViewerModel.Group>> =
        map(_assetSearch, _activeAssetCategory, ::filterGroups)
    val currentModel: Cell<ViewerModel?> = store.currentModel
    val ultimate: Cell<Boolean> = store.ultimate

    val animations: Cell<List<AnimationModel>> = store.animations
    val currentAnimation: Cell<AnimationModel?> = store.currentAnimation

    init {
        observe(store.currentModel) { model ->
            val category = categoryForModel(model) ?: return@observe

            if (_activeAssetCategory.value != category) {
                _activeAssetCategory.value = category
            }

            if (_assetSearch.value.isBlank()) {
                expandedGroupsForModel(model, category)?.let {
                    _expandedAssetGroups.value = setOf(it.label)
                }
            }
        }
    }

    suspend fun setCurrentModel(model: ViewerModel?) {
        store.setCurrentModel(model)
    }

    fun setAssetSearch(query: String) {
        _assetSearch.value = query
        _expandedAssetGroups.value =
            if (query.isBlank()) {
                defaultExpandedGroups(_activeAssetCategory.value)
            } else {
                modelGroups.value.mapTo(mutableSetOf()) { it.label }
            }
    }

    fun setActiveAssetCategory(category: ViewerModel.Category) {
        _activeAssetCategory.value = category
        _expandedAssetGroups.value =
            if (_assetSearch.value.isBlank()) {
                defaultExpandedGroups(category)
            } else {
                modelGroups.value.mapTo(mutableSetOf()) { it.label }
            }
    }

    suspend fun selectAssetSearchSuggestion(model: ViewerModel) {
        _assetSearch.value = model.displayName(store.ultimate.value)

        val category = categoryForModel(model)
        if (category != null) {
            _activeAssetCategory.value = category
            expandedGroupsForModel(model, category)?.let {
                _expandedAssetGroups.value = setOf(it.label)
            }
        }

        store.setCurrentModel(model)
    }

    fun toggleAssetGroup(label: String) {
        val expanded = _expandedAssetGroups.value.toMutableSet()

        if (!expanded.add(label)) {
            expanded.remove(label)
        }

        _expandedAssetGroups.value = expanded
    }

    fun expandAllAssetGroups() {
        _expandedAssetGroups.value = modelGroups.value.mapTo(mutableSetOf()) { it.label }
    }

    fun collapseAllAssetGroups() {
        _expandedAssetGroups.value = emptySet()
    }

    suspend fun setCurrentAnimation(animation: AnimationModel) {
        store.setCurrentAnimation(animation)
    }

    companion object {
        private const val DEFAULT_EXPANDED_ASSET_GROUP = "Characters"

        /** Matches the normalized query against the normal name, slug and Ultimate name. */
        private fun ViewerModel.matches(normalizedQuery: String): Boolean =
            uiName.lowercase().contains(normalizedQuery) ||
                    slug.lowercase().contains(normalizedQuery) ||
                    displayName(ultimate = true).lowercase().contains(normalizedQuery)

        private fun filterGroups(
            query: String,
            category: ViewerModel.Category,
        ): List<ViewerModel.Group> {
            val normalizedQuery = query.trim().lowercase()

            if (normalizedQuery.isEmpty()) {
                return category.groups
            }

            return category.groups.mapNotNull { group ->
                val items = group.items.filter { it.matches(normalizedQuery) }

                if (items.isEmpty()) null else group.copy(items = items)
            }
        }

        private fun searchSuggestions(query: String): List<ViewerModel> {
            val normalizedQuery = query.trim().lowercase()

            if (normalizedQuery.isEmpty()) {
                return emptyList()
            }

            return ViewerModel.ALL
                .asSequence()
                .filter { it.matches(normalizedQuery) }
                .sortedWith(
                    compareBy<ViewerModel> {
                        !it.uiName.lowercase().startsWith(normalizedQuery)
                    }.thenBy { it.uiName }
                )
                .take(8)
                .toList()
        }

        private fun defaultExpandedGroups(category: ViewerModel.Category): Set<String> =
            if (category.label == DEFAULT_EXPANDED_ASSET_GROUP) {
                setOf(DEFAULT_EXPANDED_ASSET_GROUP)
            } else {
                emptySet()
            }

        private fun categoryForModel(model: ViewerModel?): ViewerModel.Category? =
            model?.let { item ->
                ViewerModel.CATEGORIES.find { category ->
                    category.groups.any { group -> item in group.items }
                }
            }

        private fun expandedGroupsForModel(
            model: ViewerModel?,
            category: ViewerModel.Category,
        ): ViewerModel.Group? =
            model?.let { item ->
                category.groups.find { group -> item in group.items }
            }
    }
}
