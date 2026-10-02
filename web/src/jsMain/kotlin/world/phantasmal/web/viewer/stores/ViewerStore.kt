package world.phantasmal.web.viewer.stores

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import mu.KotlinLogging
import world.phantasmal.core.enumValueOfOrNull
import world.phantasmal.core.math.clamp
import world.phantasmal.cell.Cell
import world.phantasmal.cell.and
import world.phantasmal.cell.list.ListCell
import world.phantasmal.cell.list.mutableListCell
import world.phantasmal.cell.map
import world.phantasmal.cell.mutableCell
import world.phantasmal.cell.mutate
import world.phantasmal.psolib.fileFormats.AreaGeometry
import world.phantasmal.psolib.fileFormats.CollisionGeometry
import world.phantasmal.psolib.fileFormats.ninja.NinjaObject
import world.phantasmal.psolib.fileFormats.ninja.NjMotion
import world.phantasmal.psolib.fileFormats.ninja.NjObject
import world.phantasmal.psolib.fileFormats.ninja.XvrTexture
import world.phantasmal.web.core.PwToolType
import world.phantasmal.web.core.rendering.conversion.PSO_FRAME_RATE
import world.phantasmal.web.core.stores.Param
import world.phantasmal.web.core.stores.UiStore
import world.phantasmal.web.shared.dto.SectionId
import world.phantasmal.web.viewer.ViewerUrls
import world.phantasmal.web.viewer.loading.AnimationAssetLoader
import world.phantasmal.web.viewer.loading.CharacterClassAssetLoader
import world.phantasmal.web.viewer.loading.ItemAssetLoader
import world.phantasmal.web.viewer.loading.NpcAssetLoader
import world.phantasmal.web.viewer.loading.ObjectAssetLoader
import world.phantasmal.web.viewer.models.AnimationModel
import world.phantasmal.web.viewer.models.CharacterClass
import world.phantasmal.web.viewer.models.ViewerModel
import world.phantasmal.webui.stores.Store

private val logger = KotlinLogging.logger {}

sealed class NinjaGeometry {
    class Object(val obj: NinjaObject<*, *>) : NinjaGeometry()
    class Render(val geometry: AreaGeometry) : NinjaGeometry()
    class Collision(val geometry: CollisionGeometry) : NinjaGeometry()
}

class ViewerStore(
    private val characterClassAssetLoader: CharacterClassAssetLoader,
    private val npcAssetLoader: NpcAssetLoader,
    private val itemAssetLoader: ItemAssetLoader,
    private val objectAssetLoader: ObjectAssetLoader,
    private val animationAssetLoader: AnimationAssetLoader,
    uiStore: UiStore,
) : Store() {
    // Geometry, textures, and animation can also be replaced independently by local files.
    private var modelLoadRevision = 0
    private var textureLoadRevision = 0
    private var animationLoadRevision = 0
    private var contentRevision = 0

    // Ninja concepts.
    private val _currentNinjaGeometry = mutableCell<NinjaGeometry?>(null)
    private val _currentTextures = mutableListCell<XvrTexture?>()
    private val _currentNinjaMotion = mutableCell<NjMotion?>(null)

    // High-level concepts.
    private val _currentModel =
        mutableCell<ViewerModel?>(ViewerModel.Character(CharacterClass.VALUES.random()))
    private val _currentSectionId = mutableCell(SectionId.VALUES.random())
    private val _currentBody =
        mutableCell(
            (0 until ((_currentModel.value as? ViewerModel.Character)
                ?.characterClass?.bodyStyleCount ?: 1)).random()
        )
    private val _currentAnimation = mutableCell<AnimationModel?>(null)

    // Params.
    private val modelParams = mutableListOf<Param>()
    private val sectionIdParams = mutableListOf<Param>()
    private val bodyParams = mutableListOf<Param>()

    // Settings.
    private val _applyTextures = mutableCell(true)
    private val _showSkeleton = mutableCell(false)
    private val _ultimate = mutableCell(false)
    private val _animationPlaying = mutableCell(true)
    private val _frameRate = mutableCell(PSO_FRAME_RATE)
    private val _frame = mutableCell(0)

    // Ninja concepts.
    val currentNinjaGeometry: Cell<NinjaGeometry?> = _currentNinjaGeometry
    val currentTextures: ListCell<XvrTexture?> = _currentTextures
    val currentNinjaMotion: Cell<NjMotion?> = _currentNinjaMotion

    // High-level concepts.
    val currentModel: Cell<ViewerModel?> = _currentModel
    val currentCharacterClass: Cell<CharacterClass?> = _currentModel.map {
        (it as? ViewerModel.Character)?.characterClass
    }
    val currentSectionId: Cell<SectionId> = _currentSectionId
    val currentBody: Cell<Int> = _currentBody
    private val playerAnimations: List<AnimationModel> = (0 until 572).map {
        AnimationModel(
            "Animation ${it + 1}",
            "/player/animation/animation_${it.toString().padStart(3, '0')}.njm",
        )
    }
    val animations: Cell<List<AnimationModel>> = _currentModel.map { model ->
        when (model) {
            is ViewerModel.Character -> playerAnimations
            is ViewerModel.Npc -> NpcAssetLoader.getAnimations(model.npcType)
            is ViewerModel.Item -> emptyList()
            is ViewerModel.Object -> emptyList()
            null -> emptyList()
        }
    }
    val currentAnimation: Cell<AnimationModel?> = _currentAnimation

    // Settings.
    val applyTexturesEnabled: Cell<Boolean> = _currentNinjaGeometry.map {
        it == null || it !is NinjaGeometry.Collision
    }
    val applyTextures: Cell<Boolean> = applyTexturesEnabled and _applyTextures
    val showSkeletonEnabled: Cell<Boolean> = _currentNinjaGeometry.map {
        it is NinjaGeometry.Object && it.obj is NjObject
    }
    val showSkeleton: Cell<Boolean> = showSkeletonEnabled and _showSkeleton

    /** Only NPCs have Ultimate skins, so the toggle is only meaningful for an NPC model. */
    val ultimateEnabled: Cell<Boolean> = _currentModel.map { it is ViewerModel.Npc }
    val ultimate: Cell<Boolean> = _ultimate
    val animationPlaying: Cell<Boolean> = _animationPlaying
    val frameRate: Cell<Int> = _frameRate
    val frame: Cell<Int> = _frame

    init {
        for (path in listOf(ViewerUrls.mesh, ViewerUrls.texture)) {
            val modelParam = addDisposable(
                uiStore.registerParameter(
                    PwToolType.Viewer,
                    path,
                    MODEL_PARAM,
                    onChange = { newValue ->
                        scope.launch {
                            setCurrentModel(
                                newValue?.let { ViewerModel.findBySlug(it) },
                            )
                        }
                    },
                ),
            )
            modelParams.add(modelParam)

            val sectionIdParam = addDisposable(
                uiStore.registerParameter(
                    PwToolType.Viewer,
                    path,
                    SECTION_ID_PARAM,
                    onChange = { newValue ->
                        scope.launch {
                            setCurrentSectionId(
                                newValue?.let { enumValueOfOrNull<SectionId>(it) }
                                    ?: SectionId.VALUES.random()
                            )
                        }
                    },
                ),
            )
            sectionIdParams.add(sectionIdParam)

            val bodyParam = addDisposable(
                uiStore.registerParameter(
                    PwToolType.Viewer,
                    path,
                    BODY_PARAM,
                    onChange = { newValue ->
                        scope.launch {
                            setCurrentBody((newValue?.toIntOrNull() ?: 1) - 1)
                        }
                    },
                ),
            )
            bodyParams.add(bodyParam)

            // Try to initialize settings from parameters.
            if (uiStore.currentTool.value == PwToolType.Viewer &&
                uiStore.path.value == path
            ) {
                modelParam.value?.let { paramValue ->
                    ViewerModel.findBySlug(paramValue)?.let {
                        _currentModel.value = it
                    }
                }

                sectionIdParam.value?.let { enumValueOfOrNull<SectionId>(it) }?.let {
                    _currentSectionId.value = it
                }

                val maxBody = (_currentModel.value as? ViewerModel.Character)
                    ?.characterClass?.bodyStyleCount ?: 1
                bodyParam.value?.toIntOrNull()?.let {
                    _currentBody.value = clamp(it, 1, maxBody) - 1
                }
            }
        }

        // Initialize parameters from settings.
        setCurrentModelValue(_currentModel.value)
        setCurrentSectionIdValue(_currentSectionId.value)
        setCurrentBodyValue(_currentBody.value)

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            loadCurrentModel(clearAnimation = true)
        }
    }

    fun setCurrentNinjaGeometry(geometry: NinjaGeometry?) {
        contentRevision++
        modelLoadRevision++
        textureLoadRevision++
        animationLoadRevision++
        mutate {
            if (_currentModel.value != null) {
                setCurrentModelValue(null)
                _currentTextures.clear()
            }

            _currentAnimation.value = null
            _currentNinjaMotion.value = null
            _currentNinjaGeometry.value = geometry
        }
    }

    fun setCurrentTextures(textures: List<XvrTexture>) {
        contentRevision++
        textureLoadRevision++
        _currentTextures.replaceAll(textures)
    }

    suspend fun setCurrentModel(model: ViewerModel?) {
        contentRevision++
        val prevWasCharacter = _currentModel.value is ViewerModel.Character
        val newIsCharacter = model is ViewerModel.Character

        // Only preserve animation when switching between character classes.
        val clearAnimation = !(prevWasCharacter && newIsCharacter)

        setCurrentModelValue(model)

        if (model is ViewerModel.Character) {
            val char = model.characterClass

            if (_currentBody.value >= char.bodyStyleCount) {
                setCurrentBodyValue(char.bodyStyleCount - 1)
            }
        }

        loadCurrentModel(clearAnimation)
    }

    suspend fun setCurrentSectionId(sectionId: SectionId) {
        contentRevision++
        setCurrentSectionIdValue(sectionId)
        if (_currentModel.value is ViewerModel.Character) {
            loadCurrentModel(clearAnimation = false)
        }
    }

    suspend fun setCurrentBody(body: Int) {
        contentRevision++
        setCurrentBodyValue(body)
        if (_currentModel.value is ViewerModel.Character) {
            loadCurrentModel(clearAnimation = false)
        }
    }

    fun setCurrentNinjaMotion(njm: NjMotion) {
        contentRevision++
        animationLoadRevision++
        mutate {
            _currentAnimation.value = null
            _currentNinjaMotion.value = njm
            _animationPlaying.value = true
        }
    }

    suspend fun setCurrentAnimation(animation: AnimationModel?) {
        contentRevision++
        val revision = ++animationLoadRevision
        mutate {
            _currentAnimation.value = animation
            _currentNinjaMotion.value = null
        }

        if (animation != null) {
            loadAnimation(animation, revision)
        }
    }

    /** A local import may commit only while no newer content selection or import has started. */
    internal fun beginFileLoad(): Int = ++contentRevision

    internal fun isCurrentFileLoad(revision: Int): Boolean =
        !disposed && revision == contentRevision

    fun setApplyTextures(apply: Boolean) {
        _applyTextures.value = apply
    }

    fun setShowSkeleton(show: Boolean) {
        _showSkeleton.value = show
    }

    suspend fun setUltimate(ultimate: Boolean) {
        if (_ultimate.value == ultimate) return
        _ultimate.value = ultimate

        // Only NPC skins change with difficulty; reload the current NPC so the new skin loads.
        if (_currentModel.value is ViewerModel.Npc) {
            contentRevision++
            loadCurrentModel(clearAnimation = false)
        }
    }

    fun setAnimationPlaying(playing: Boolean) {
        _animationPlaying.value = playing
    }

    fun setFrameRate(frameRate: Int) {
        _frameRate.value = frameRate
    }

    fun setFrame(frame: Int) {
        val maxFrame = currentNinjaMotion.value?.frameCount ?: Int.MAX_VALUE

        _frame.value = when {
            frame > maxFrame -> 1
            frame < 1 -> maxFrame
            else -> frame
        }
    }

    private suspend fun loadCurrentModel(clearAnimation: Boolean) {
        val revision = ++modelLoadRevision
        val texturesRevision = ++textureLoadRevision
        // Capture every load input before the first suspension, including the NPC skin.
        val model = _currentModel.value
        val sectionId = _currentSectionId.value
        val body = _currentBody.value
        val ultimate = _ultimate.value

        if (clearAnimation) {
            animationLoadRevision++
            mutate {
                _currentAnimation.value = null
                _currentNinjaMotion.value = null
            }
        }
        val animationRevision = animationLoadRevision

        if (model == null) {
            mutate {
                _currentNinjaGeometry.value = null
                _currentTextures.clear()
            }
            return
        }

        try {
            val ninjaObject = when (model) {
                is ViewerModel.Character -> characterClassAssetLoader.loadNinjaObject(model.characterClass)
                is ViewerModel.Npc -> npcAssetLoader.loadNinjaObject(model.npcType, ultimate)
                is ViewerModel.Item -> itemAssetLoader.loadNinjaObject(model.index)
                is ViewerModel.Object -> objectAssetLoader.loadNinjaObject(model.objectType)
            }
            if (!isCurrentModelLoad(revision)) return

            val textures = try {
                if (texturesRevision != textureLoadRevision) null else when (model) {
                    is ViewerModel.Character -> characterClassAssetLoader.loadXvrTextures(
                        model.characterClass, sectionId, body,
                    )
                    is ViewerModel.Npc -> npcAssetLoader.loadXvrTextures(model.npcType, ultimate)
                    is ViewerModel.Item -> itemAssetLoader.loadXvrTextures(model.textureIndex)
                    is ViewerModel.Object -> objectAssetLoader.loadXvrTextures(model.objectType)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // An imported texture supersedes this fetch without superseding its geometry.
                if (texturesRevision == textureLoadRevision) throw e
                null
            }
            if (!isCurrentModelLoad(revision)) return

            mutate {
                _currentNinjaGeometry.value = NinjaGeometry.Object(ninjaObject)
                if (texturesRevision == textureLoadRevision && textures != null) {
                    _currentTextures.replaceAll(textures)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (!isCurrentModelLoad(revision)) return
            logger.error(e) { "Couldn't load Ninja model for ${model.uiName}." }

            mutate {
                _currentNinjaGeometry.value = null
                if (texturesRevision == textureLoadRevision) _currentTextures.clear()
                if (animationRevision == animationLoadRevision) {
                    animationLoadRevision++
                    _currentAnimation.value = null
                    _currentNinjaMotion.value = null
                }
            }
        }
    }

    private fun isCurrentModelLoad(revision: Int): Boolean =
        !disposed && revision == modelLoadRevision

    private suspend fun loadAnimation(animation: AnimationModel, revision: Int) {
        try {
            val ninjaMotion = animationAssetLoader.loadAnimation(animation.filePath)
            if (disposed || revision != animationLoadRevision) return

            mutate {
                _currentNinjaMotion.value = ninjaMotion
                _animationPlaying.value = true
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (disposed || revision != animationLoadRevision) return
            logger.error(e) {
                "Couldn't load Ninja motion for ${animation.name} (path: ${animation.filePath})."
            }

            _currentNinjaMotion.value = null
        }
    }

    private fun setCurrentModelValue(model: ViewerModel?) {
        _currentModel.value = model

        for (param in modelParams) {
            param.set(model?.slug)
        }

        val characterParamsEnabled = model is ViewerModel.Character
        for (param in sectionIdParams) {
            param.set(if (characterParamsEnabled) _currentSectionId.value.name else null)
        }
        for (param in bodyParams) {
            param.set(if (characterParamsEnabled) (_currentBody.value + 1).toString() else null)
        }
    }

    private fun setCurrentSectionIdValue(sectionId: SectionId) {
        _currentSectionId.value = sectionId

        for (param in sectionIdParams) {
            param.set(if (_currentModel.value is ViewerModel.Character) sectionId.name else null)
        }
    }

    private fun setCurrentBodyValue(body: Int) {
        _currentBody.value = body
        val paramValue = (body + 1).toString()

        for (param in bodyParams) {
            param.set(if (_currentModel.value is ViewerModel.Character) paramValue else null)
        }
    }

    companion object {
        const val MODEL_PARAM = "model"
        const val BODY_PARAM = "body"
        const val SECTION_ID_PARAM = "section_id"
    }
}
