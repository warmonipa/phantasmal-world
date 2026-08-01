package world.phantasmal.web.viewer.rendering

import org.w3c.dom.HTMLCanvasElement
import world.phantasmal.core.disposable.TrackedDisposable
import world.phantasmal.core.math.degToRad
import world.phantasmal.psolib.fileFormats.ninja.NinjaObject
import world.phantasmal.psolib.fileFormats.ninja.NjMotion
import world.phantasmal.psolib.fileFormats.ninja.NjObject
import world.phantasmal.web.core.boundingSphere
import world.phantasmal.web.core.isSkinnedMesh
import world.phantasmal.web.core.rendering.DisposableThreeRenderer
import world.phantasmal.web.core.rendering.OrbitalCameraInputManager
import world.phantasmal.web.core.rendering.RenderContext
import world.phantasmal.web.core.rendering.Renderer
import world.phantasmal.web.core.rendering.conversion.PSO_FRAME_RATE_DOUBLE
import world.phantasmal.web.core.rendering.conversion.collisionGeometryToGroup
import world.phantasmal.web.core.rendering.conversion.createAnimationClip
import world.phantasmal.web.core.rendering.conversion.ninjaObjectToMesh
import world.phantasmal.web.core.rendering.conversion.ninjaObjectToSkinnedMesh
import world.phantasmal.web.core.rendering.conversion.renderGeometryToGroup
import world.phantasmal.web.core.rendering.disposeObject3DResources
import world.phantasmal.web.core.times
import world.phantasmal.web.externals.three.AnimationAction
import world.phantasmal.web.externals.three.AnimationClip
import world.phantasmal.web.externals.three.AnimationMixer
import world.phantasmal.web.externals.three.Clock
import world.phantasmal.web.externals.three.Group
import world.phantasmal.web.externals.three.LineBasicMaterial
import world.phantasmal.web.externals.three.Object3D
import world.phantasmal.web.externals.three.PerspectiveCamera
import world.phantasmal.web.externals.three.SkeletonHelper
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.shared.Throttle
import world.phantasmal.web.viewer.models.ViewerModel
import world.phantasmal.web.viewer.stores.NinjaGeometry
import world.phantasmal.web.viewer.stores.ViewerStore
import kotlin.math.roundToInt
import kotlin.math.tan

class MeshRenderer(
    private val viewerStore: ViewerStore,
    createThreeRenderer: (HTMLCanvasElement) -> DisposableThreeRenderer,
) : Renderer() {
    private val clock = Clock()
    private val throttleRebuildMesh = Throttle(wait = 10, leading = false, trailing = true)

    private var obj3d: Object3D? = null
    private var skeletonHelper: SkeletonHelper? = null
    private var animation: Animation? = null
    private var updateAnimationTime = true
    private var charClassActive = false
    private var resetCamera = true

    internal val renderedObject: Object3D? get() = obj3d

    override val context = addDisposable(
        RenderContext(
            createCanvas(),
            PerspectiveCamera(
                fov = 45.0,
                aspect = 1.0,
                near = 10.0,
                far = 5_000.0,
            )
        )
    )

    override val disposableThreeRenderer = addDisposable(createThreeRenderer(context.canvas))

    override val inputManager = addDisposable(
        OrbitalCameraInputManager(
            context.canvas,
            context.camera,
            position = Vector3(.0, .0, .0),
            screenSpacePanning = true,
        )
    )

    init {
        observeNow(viewerStore.currentNinjaGeometry, viewerStore.currentTextures) { _, _ ->
            resetCamera = true
            rebuildMesh()
        }
        observeNow(viewerStore.applyTextures) { rebuildMesh() }
        observeNow(viewerStore.currentNinjaMotion, ::ninjaMotionChanged)
        observeNow(viewerStore.showSkeleton) { skeletonHelper?.visible = it }
        observeNow(viewerStore.animationPlaying, ::animationPlayingChanged)
        observeNow(viewerStore.frameRate, ::frameRateChanged)
        observeNow(viewerStore.frame, ::frameChanged)
    }

    override fun dispose() {
        animation?.dispose()
        super.dispose()
    }

    override fun render() {
        animation?.mixer?.update(clock.getDelta())

        context.lightHolder.quaternion.copy(context.camera.quaternion)

        super.render()

        animation?.let {
            if (!it.action.paused) {
                updateAnimationTime = false
                viewerStore.setFrame((it.action.time * PSO_FRAME_RATE_DOUBLE + 1).roundToInt())
                updateAnimationTime = true
            }
        }
    }

    private fun rebuildMesh() {
        throttleRebuildMesh {
            // Remove the previous mesh.
            obj3d?.let { mesh ->
                disposeObject3DResources(mesh)
                context.scene.remove(mesh)
            }

            // Remove the previous skeleton.
            skeletonHelper?.let {
                context.scene.remove(it)
                skeletonHelper = null
            }

            val ninjaGeometry = viewerStore.currentNinjaGeometry.value
            val textures =
                if (viewerStore.applyTextures.value) viewerStore.currentTextures.value
                else emptyList()

            // Stop and clean up previous animation and store animation time.
            var animationTime: Double? = null

            animation?.let {
                animationTime = it.action.time
                it.dispose()
                this.animation = null
            }

            // Create a new mesh if necessary.
            if (ninjaGeometry != null) {
                val mesh = when (ninjaGeometry) {
                    is NinjaGeometry.Object -> {
                        val obj = ninjaGeometry.obj

                        if (obj is NjObject) {
                            ninjaObjectToSkinnedMesh(
                                obj,
                                textures,
                                boundingVolumes = true,
                                anisotropy = threeRenderer.capabilities.getMaxAnisotropy() / 2,
                            )
                        } else {
                            ninjaObjectToMesh(
                                obj,
                                textures,
                                boundingVolumes = true,
                                anisotropy = threeRenderer.capabilities.getMaxAnisotropy() / 2,
                            )
                        }
                    }

                    is NinjaGeometry.Render -> renderGeometryToGroup(
                        ninjaGeometry.geometry,
                        textures,
                        anisotropy = threeRenderer.capabilities.getMaxAnisotropy() / 2,
                    )

                    is NinjaGeometry.Collision -> collisionGeometryToGroup(ninjaGeometry.geometry)
                }

                // Determine whether camera needs to be reset. Resets should always happen when the
                // Ninja geometry changes except when we're switching between character class models.
                val charClassActive = viewerStore.currentCharacterClass.value != null
                val cameraResetNecessary = !charClassActive || !this.charClassActive
                this.charClassActive = charClassActive

                val obj3d = applyPresentationTransform(mesh)
                obj3d.updateMatrixWorld(true)

                if (resetCamera && cameraResetNecessary) {
                    // Compute camera position.
                    val bSphere = boundingSphere(obj3d)
                    val cameraDistFactor =
                        1.5 / tan(degToRad((context.camera as PerspectiveCamera).fov) / 2)
                    val cameraPos = bSphere.center.clone().add(
                        CAMERA_POS * (bSphere.radius * cameraDistFactor)
                    )
                    inputManager.lookAt(cameraPos, bSphere.center)
                    resetCamera = false
                }

                context.scene.add(obj3d)
                this.obj3d = obj3d

                if (obj3d.isSkinnedMesh() && ninjaGeometry is NinjaGeometry.Object) {
                    // Add skeleton.
                    val skeletonHelper = SkeletonHelper(obj3d)
                    skeletonHelper.visible = viewerStore.showSkeleton.value
                    skeletonHelper.material.unsafeCast<LineBasicMaterial>().linewidth = 3.0

                    context.scene.add(skeletonHelper)
                    this.skeletonHelper = skeletonHelper

                    // Create a new animation mixer and clip.
                    viewerStore.currentNinjaMotion.value?.let { njMotion ->
                        animation = Animation(ninjaGeometry.obj, njMotion, obj3d).also {
                            it.mixer.timeScale = viewerStore.frameRate.value / PSO_FRAME_RATE_DOUBLE
                            it.action.time = animationTime ?: .0
                            it.action.play()
                        }
                    }
                }
            }
        }
    }

    private fun applyPresentationTransform(obj3d: Object3D): Object3D {
        val model = viewerStore.currentModel.value

        if (model is ViewerModel.Item && model.index in CARD_FAN_MODELS) {
            val rotation = presentationRotation(model) ?: return obj3d
            return applyCardPresentation(
                obj3d,
                rotation,
                cardCount = if (model.index == 151) 5 else 4,
            )
        }

        if (model is ViewerModel.Item && model.index in PAIRED_ITEM_MODELS) {
            val second = obj3d.clone(true)
            val rotation = presentationRotation(model) ?: return obj3d

            applyRotation(obj3d, rotation)
            applyRotation(second, rotation)
            obj3d.updateMatrixWorld(true)
            second.updateMatrixWorld(true)

            val offset =
                boundingSphere(obj3d).radius * presentationSeparationFactor(model.index)
            val spreadRightDown =
                if (model.index in DAGGER_MODELS) DAGGER_SPREAD_RIGHT
                else PAIR_SPREAD_RIGHT_DOWN
            val spreadLeftUp =
                if (model.index in DAGGER_MODELS) DAGGER_SPREAD_LEFT
                else PAIR_SPREAD_LEFT_UP

            val firstHolder = Group().apply { add(obj3d) }
            val secondHolder = Group().apply { add(second) }
            pairDepthRotation(model.index, first = true)?.let {
                firstHolder.rotateOnWorldAxis(PAIR_DEPTH_AXIS, it)
            }
            pairDepthRotation(model.index, first = false)?.let {
                secondHolder.rotateOnWorldAxis(PAIR_DEPTH_AXIS, it)
            }
            pairScreenRotation(model.index, first = true)?.let {
                firstHolder.rotateOnWorldAxis(CAMERA_POS, it)
            }
            pairScreenRotation(model.index, first = false)?.let {
                secondHolder.rotateOnWorldAxis(CAMERA_POS, it)
            }
            firstHolder.position.add(spreadRightDown * offset)
            secondHolder.position.add(spreadLeftUp * offset)
            val pair = Group().apply { add(firstHolder, secondHolder) }
            return reversePresentationIfNecessary(pair, model)
        }

        presentationRotation(model)?.let { applyRotation(obj3d, it) }
        return reversePresentationIfNecessary(obj3d, model)
    }

    private fun applyCardPresentation(
        obj3d: Object3D,
        rotation: PresentationRotation,
        cardCount: Int,
    ): Object3D {
        val cards =
            List(cardCount) { index -> if (index == 0) obj3d else obj3d.clone(true) }
        cards.forEach {
            applyRotation(it, rotation)
            it.updateMatrixWorld(true)
        }

        val radius = boundingSphere(obj3d).radius
        val screenRotations =
            if (cardCount == 5) listOf(95.0, 70.0, 45.0, 20.0, -35.0)
            else listOf(75.0, 45.0, 15.0, -35.0)
        val horizontalOffsets =
            if (cardCount == 5) listOf(-.9, -.3, .3, .9, .7)
            else listOf(-.8, .0, .8, 1.5)
        val verticalOffsets =
            if (cardCount == 5) listOf(.3, .5, .5, .3, -.8)
            else listOf(.3, .5, .3, -1.2)

        return Group().apply {
            cards.forEachIndexed { index, card ->
                add(
                    Group().apply {
                        add(card)
                        rotateOnWorldAxis(CAMERA_POS, degToRad(screenRotations[index]))
                        position.add(CARD_SCREEN_RIGHT * (radius * horizontalOffsets[index]))
                        position.add(CARD_SCREEN_UP * (radius * verticalOffsets[index]))
                    }
                )
            }
        }
    }

    private fun reversePresentationIfNecessary(
        obj3d: Object3D,
        model: ViewerModel?,
    ): Object3D {
        if (model !is ViewerModel.Item) {
            return obj3d
        }

        val screenRotation = when {
            model.index in SCREEN_REVERSED_MODELS -> kotlin.math.PI
            model.index == 93 -> degToRad(22.5)
            model.index == 95 -> degToRad(135.0)
            model.index == 110 -> degToRad(225.0)
            model.index == 128 -> degToRad(-20.0)
            model.index in 132..134 -> degToRad(15.0)
            model.index == 139 -> degToRad(215.0)
            model.index in 138..141 -> degToRad(35.0)
            model.index in 161..163 -> degToRad(-30.0)
            model.index == 170 -> degToRad(120.0)
            model.index in 171..172 -> degToRad(120.0)
            model.index == 174 -> degToRad(35.0)
            model.index == 176 -> degToRad(45.0)
            model.index == 181 -> degToRad(-30.0)
            model.index == 187 -> degToRad(-30.0)
            model.index == 188 -> kotlin.math.PI
            model.index == 191 -> degToRad(10.0)
            model.index == 192 -> degToRad(220.0)
            model.index == 193 -> degToRad(225.0)
            model.index == 198 -> degToRad(120.0)
            model.index == 210 -> degToRad(210.0)
            model.index in setOf(215, 236, 245) -> kotlin.math.PI
            model.index == 253 -> degToRad(-100.0)
            model.index in SCREEN_ROTATED_45_MODELS -> degToRad(45.0)
            else -> return obj3d
        }

        return Group().apply {
            add(obj3d)
            rotateOnWorldAxis(CAMERA_POS, screenRotation)
        }
    }

    private fun applyRotation(obj3d: Object3D, rotation: PresentationRotation) {
        obj3d.rotation.set(rotation.x, rotation.y, rotation.z)
    }

    private fun ninjaMotionChanged(njMotion: NjMotion?) {
        animation?.let {
            it.dispose()
            animation = null
        }

        val mesh = obj3d
        val njObject = (viewerStore.currentNinjaGeometry.value as? NinjaGeometry.Object)?.obj

        if (mesh == null || !mesh.isSkinnedMesh() || njObject == null || njMotion == null) {
            return
        }

        animation = Animation(njObject, njMotion, mesh).also {
            it.mixer.timeScale = viewerStore.frameRate.value / PSO_FRAME_RATE_DOUBLE
            it.action.play()
        }

        clock.start()
    }

    private fun animationPlayingChanged(playing: Boolean) {
        animation?.let {
            it.action.paused = !playing

            if (playing) {
                clock.start()
            } else {
                clock.stop()
            }
        }
    }

    private fun frameRateChanged(frameRate: Int) {
        animation?.let {
            it.mixer.timeScale = frameRate / PSO_FRAME_RATE_DOUBLE
        }
    }

    private fun frameChanged(frame: Int) {
        if (updateAnimationTime) {
            animation?.let {
                it.action.time = (frame - 1) / PSO_FRAME_RATE_DOUBLE
            }
        }
    }

    private class Animation(
        njObject: NinjaObject<*, *>,
        njMotion: NjMotion,
        root: Object3D,
    ) : TrackedDisposable() {
        private val clip: AnimationClip = createAnimationClip(njObject, njMotion)

        val mixer = AnimationMixer(root)
        val action: AnimationAction = mixer.clipAction(clip)

        override fun dispose() {
            mixer.stopAllAction()
            mixer.uncacheAction(clip)
            super.dispose()
        }
    }

    companion object {
        private val CAMERA_POS = Vector3(1.0, 1.0, 2.0).normalize()
        private val CATALOG_ROTATION =
            PresentationRotation(degToRad(-132.0), degToRad(30.0), degToRad(100.0))
        private val SWORD_PARTISAN_CATALOG_ROTATION =
            PresentationRotation(degToRad(-135.0), degToRad(35.0), degToRad(100.0))
        private val DAGGER_ROTATION =
            PresentationRotation(degToRad(47.0), degToRad(-32.0), degToRad(100.0))
        private val GUN_CATALOG_ROTATION =
            PresentationRotation(degToRad(-132.0), degToRad(-30.0), degToRad(-80.0))
        private val CLAW_CATALOG_ROTATION =
            PresentationRotation(degToRad(-132.0), degToRad(-30.0), degToRad(40.0))
        private val WOK_CATALOG_ROTATION =
            PresentationRotation(degToRad(-160.0), degToRad(-75.0), degToRad(100.0))
        private val DAGGER_SPREAD_RIGHT = Vector3(.908, -.091, -.408)
        private val DAGGER_SPREAD_LEFT = DAGGER_SPREAD_RIGHT * -1.0
        private val PAIR_SPREAD_RIGHT_DOWN = Vector3(.761, -.645, -.058)
        private val PAIR_SPREAD_LEFT_UP = PAIR_SPREAD_RIGHT_DOWN * -1.0
        private val PAIR_DEPTH_AXIS = Vector3(-1.0, 5.0, -2.0).normalize()
        private val CARD_SCREEN_RIGHT = Vector3(2.0, .0, -1.0).normalize()
        private val CARD_SCREEN_UP = Vector3(-1.0, 5.0, -2.0).normalize()
        private val CARD_FAN_MODELS = setOf(151, 175, 177, 178)
        private val DAGGER_MODELS = setOf(2, 161, 162, 163, 187, 229, 266)
        private val CLAW_MODELS = setOf(12, 95, 184, 185, 186, 188, 258)
        private val PAIRED_ITEM_MODELS =
            setOf(
                2, 53, 55, 56, 70, 71, 72, 74, 75, 96, 97, 107, 132, 133, 134,
                161, 162, 163, 170, 171, 172, 173, 187, 198, 213, 256, 261,
                266,
            )
        private val SCREEN_REVERSED_MODELS =
            setOf(52, 55, 56, 98, 99, 100, 101, 102, 103, 105, 106, 108) +
                (78..88)
        private val SCREEN_ROTATED_45_MODELS = setOf(89, 94, 104)
        private val DEFAULT_ITEM_ROTATION = PresentationRotation(.0, .0, degToRad(90.0))
        private val CATALOG_MODELS =
            setOf(0, 4, 9, 10, 11, 13, 16, 21, 28, 30, 70, 71, 73, 94) +
                (31..36) + (38..64) + (132..134) + (138..150) + (152..157) +
                (167..174) + setOf(
                    176, 181, 182, 189, 191, 194, 195, 196, 199, 200, 201, 208,
                    209, 211, 212, 213, 214, 217, 223, 224, 225, 226, 227, 228,
                    230, 231, 232, 233, 236, 237, 238, 240, 242, 243, 244, 245,
                    246, 247, 248, 249, 250, 252, 253, 257, 259, 264, 265, 268,
                )
        private val SWORD_PARTISAN_CATALOG_MODELS =
            setOf(
                1, 3, 179, 180, 203, 207, 215, 216, 218, 219, 235, 241, 263, 269,
                270,
            ) + (158..160) + (164..166)
        private val GUN_CATALOG_MODELS =
            (5..8).toSet() + (17..20) + (65..69) + (72..77) + (126..137) +
                setOf(
                    27, 29, 37, 183, 190, 197, 202, 204, 205, 206, 220, 221, 222,
                    234, 239, 251, 254, 255, 256, 260, 261, 262, 267,
                )

        internal fun presentationProfile(model: ViewerModel?): ItemPresentationProfile? =
            when {
                model !is ViewerModel.Item -> null
                model.index == 54 -> ItemPresentationProfile.Wok
                model.index in CATALOG_MODELS -> ItemPresentationProfile.Catalog
                model.index in SWORD_PARTISAN_CATALOG_MODELS ->
                    ItemPresentationProfile.SwordPartisan
                model.index in DAGGER_MODELS -> ItemPresentationProfile.Dagger
                model.index in GUN_CATALOG_MODELS -> ItemPresentationProfile.Gun
                model.index in CLAW_MODELS -> ItemPresentationProfile.Claw
                else -> ItemPresentationProfile.Default
            }

        internal fun presentationRotation(model: ViewerModel?): PresentationRotation? =
            when (presentationProfile(model)) {
                ItemPresentationProfile.Wok -> WOK_CATALOG_ROTATION
                ItemPresentationProfile.Catalog -> CATALOG_ROTATION
                ItemPresentationProfile.SwordPartisan -> SWORD_PARTISAN_CATALOG_ROTATION
                ItemPresentationProfile.Dagger -> DAGGER_ROTATION
                ItemPresentationProfile.Gun -> GUN_CATALOG_ROTATION
                ItemPresentationProfile.Claw -> CLAW_CATALOG_ROTATION
                ItemPresentationProfile.Default -> DEFAULT_ITEM_ROTATION
                null -> null
            }

        private fun presentationSeparationFactor(index: Int): Double =
            when (index) {
                2, 55 -> .30
                53 -> .65
                56 -> .38
                70 -> .55
                72 -> .60
                71, 74, 75, 96, 97, 107, 132, 133, 134, 161, 162, 163, 170,
                171, 172, 173, 187, 198, 213, 256, 261, 266 -> .45
                else -> .0
            }

        private fun pairScreenRotation(index: Int, first: Boolean): Double? =
            when {
                index == 70 && first -> degToRad(45.0)
                index == 71 && first -> degToRad(135.0)
                index == 71 -> degToRad(90.0)
                index in setOf(72, 74, 75) && first -> degToRad(30.0)
                index == 107 && first -> degToRad(30.0)
                index == 213 && first -> degToRad(-40.0)
                else -> null
            }

        private fun pairDepthRotation(index: Int, first: Boolean): Double? =
            if (index in 132..134 && first) degToRad(35.0) else null

    }

    internal enum class ItemPresentationProfile {
        Catalog,
        SwordPartisan,
        Dagger,
        Gun,
        Claw,
        Wok,
        Default,
    }

    internal data class PresentationRotation(val x: Double, val y: Double, val z: Double)
}
