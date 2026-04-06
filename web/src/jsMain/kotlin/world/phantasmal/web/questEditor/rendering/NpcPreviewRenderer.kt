package world.phantasmal.web.questEditor.rendering

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.w3c.dom.HTMLCanvasElement
import world.phantasmal.cell.Cell
import world.phantasmal.core.math.degToRad
import world.phantasmal.psolib.fileFormats.ninja.NjObject
import world.phantasmal.psolib.fileFormats.ninja.XvrTexture
import world.phantasmal.web.core.boundingSphere
import world.phantasmal.web.core.times
import world.phantasmal.web.core.rendering.DisposableThreeRenderer
import world.phantasmal.web.core.rendering.OrbitalCameraInputManager
import world.phantasmal.web.core.rendering.RenderContext
import world.phantasmal.web.core.rendering.Renderer
import world.phantasmal.web.core.rendering.conversion.ninjaObjectToSkinnedMesh
import world.phantasmal.web.core.rendering.disposeObject3DResources
import world.phantasmal.web.externals.three.Object3D
import world.phantasmal.web.externals.three.PerspectiveCamera
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.shared.dto.SectionId
import world.phantasmal.web.viewer.loading.CharacterClassAssetLoader
import world.phantasmal.web.viewer.models.CharacterClass
import world.phantasmal.web.viewer.models.CharacterClass.*
import kotlin.math.tan

/** Map PSO char_class byte (0-11) to CharacterClass enum. */
private val CHAR_CLASS_BY_INDEX = arrayOf(
    HUmar, HUnewearl, HUcast, RAmar, RAcast, RAcaseal,
    FOmarl, FOnewm, FOnewearl, HUcaseal, FOmar, RAmarl,
)

private val logger = KotlinLogging.logger {}

class NpcPreviewRenderer(
    private val charClassAssetLoader: CharacterClassAssetLoader,
    createThreeRenderer: (HTMLCanvasElement) -> DisposableThreeRenderer,
    private val charClassCell: Cell<Int>,
    private val sectionIdCell: Cell<Int>,   // 1-indexed
    private val costumeCell: Cell<Int>,     // 1-indexed (used as body style for textures)
    private val headCell: Cell<Int>,        // 1-indexed
    private val hairCell: Cell<Int>,        // 1-indexed
    private val v2FlagsCell: Cell<Int>,     // 0 = player model, != 0 = NPC model
) : Renderer() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loadJob: Job? = null
    private var currentMesh: Object3D? = null

    override val context = addDisposable(
        RenderContext(
            createCanvas(),
            PerspectiveCamera(
                fov = 45.0,
                aspect = 1.0,
                near = 1.0,
                far = 5_000.0,
            ),
        )
    )

    override val threeRenderer = addDisposable(createThreeRenderer(context.canvas)).renderer

    override val inputManager = addDisposable(
        OrbitalCameraInputManager(
            context.canvas,
            context.camera,
            position = Vector3(.0, .0, .0),
            screenSpacePanning = true,
        )
    )

    /**
     * Call this whenever the NPC appearance fields change to reload the model.
     */
    fun refresh() {
        rebuildModel()
    }

    override fun render() {
        context.lightHolder.quaternion.copy(context.camera.quaternion)
        super.render()
    }

    override fun dispose() {
        loadJob?.cancel()
        removeMesh()
        super.dispose()
    }

    private fun removeMesh() {
        currentMesh?.let {
            disposeObject3DResources(it)
            context.scene.remove(it)
            currentMesh = null
        }
    }

    private fun rebuildModel() {
        loadJob?.cancel()

        // NPC models (v2Flags != 0) don't use player character assets.
        if (v2FlagsCell.value != 0) {
            removeMesh()
            return
        }

        val classIdx = charClassCell.value
        val charClass = CHAR_CLASS_BY_INDEX.getOrNull(classIdx) ?: return
        val headStyle = (headCell.value - 1).coerceAtLeast(0)
        val hairStyle = (hairCell.value - 1).coerceAtLeast(0)
        val sectionId = SectionId.VALUES.getOrNull((sectionIdCell.value - 1).coerceAtLeast(0))
            ?: SectionId.Viridia
        val body = (costumeCell.value - 1).coerceAtLeast(0)

        loadJob = scope.launch {
            try {
                val njObject = charClassAssetLoader.loadNinjaObject(charClass, headStyle, hairStyle)
                val textures = charClassAssetLoader.loadXvrTextures(charClass, sectionId, body)

                removeMesh()

                val mesh = ninjaObjectToSkinnedMesh(
                    njObject,
                    textures,
                    boundingVolumes = true,
                    anisotropy = threeRenderer.capabilities.getMaxAnisotropy() / 2,
                )

                // Position camera in front of the model.
                val bSphere = boundingSphere(mesh)
                val center = bSphere.center
                val dist = bSphere.radius * 3.0
                val cameraPos = Vector3(center.x, center.y, center.z + dist)
                inputManager.lookAt(cameraPos, center)

                context.scene.add(mesh)
                currentMesh = mesh
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load NPC preview model for $charClass." }
            }
        }
    }

    companion object {
        private val CAMERA_POS = Vector3(0.0, 0.7, 2.0).normalize()
    }
}
