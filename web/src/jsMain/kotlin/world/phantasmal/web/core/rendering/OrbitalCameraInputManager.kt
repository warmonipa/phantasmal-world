package world.phantasmal.web.core.rendering

import org.w3c.dom.AddEventListenerOptions
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.events.Event
import org.w3c.dom.pointerevents.PointerEvent
import world.phantasmal.core.disposable.Disposable
import world.phantasmal.core.disposable.TrackedDisposable
import world.phantasmal.web.externals.three.*
import world.phantasmal.webui.dom.disposableListener
import world.phantasmal.webui.obj
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

class OrbitalCameraInputManager(
    canvas: HTMLCanvasElement,
    private val camera: Camera,
    position: Vector3,
    screenSpacePanning: Boolean,
    enableRotate: Boolean = true,
) : TrackedDisposable(), InputManager {
    private var _controls: OrbitControls

    val controls: OrbitControls get() = _controls

    // Store the user's preferred offset from section centers
    private var userTargetOffset = Vector3(0.0, 0.0, 0.0)
    private var lastSectionCenter: Vector3? = null
    private var currentFloorId: Int? = null

    @Suppress("JoinDeclarationAndAssignment")
    private val pointerDownListener: Disposable

    @Suppress("JoinDeclarationAndAssignment")
    private val wheelListener: Disposable

    @Suppress("JoinDeclarationAndAssignment")
    private val pointerCaptureListener: Disposable

    /** Whether the browser had captured the current pointerdown's pointer before OrbitControls. */
    private var capturedBeforeControls = false

    var enabled: Boolean
        get() = controls.enabled
        set(enabled) {
            controls.enabled = enabled
        }

    init {
        // Switch mouse button actions when certain modifier keys are pressed to counteract
        // OrbitControls switching left and right click behavior in that case.
        pointerDownListener = canvas.disposableListener<PointerEvent>("pointerdown", {
            // Touch and direct pens are implicitly captured before pointerdown listeners run.
            capturedBeforeControls = canvas.hasPointerCapture(it.pointerId)

            if (it.ctrlKey || it.metaKey || it.shiftKey) {
                controls.mouseButtons = obj {
                    LEFT = MOUSE.ROTATE
                    MIDDLE = MOUSE.DOLLY
                    RIGHT = MOUSE.PAN
                }
            } else {
                controls.mouseButtons = obj {
                    LEFT = MOUSE.PAN
                    MIDDLE = MOUSE.DOLLY
                    RIGHT = MOUSE.ROTATE
                }
            }
        })

        // Ensure OrbitControls is instantiated after the pointerdown event listener is attached.
        _controls = OrbitControls(camera, canvas)

        // three.js r186 OrbitControls scales wheel and middle-drag zoom by the event's delta, so a
        // mouse wheel notch on a high-DPI screen (200 px instead of 100) zooms twice as far. Zoom
        // one fixed step per event, like three.js r128 did.
        _controls.asDynamic()._getZoomScale = { _: Double -> 0.95.pow(_controls.zoomSpeed) }

        // r186 OrbitControls captures the pointer on pointerdown, which keeps routing moves to the
        // canvas during a camera drag (e.g. symbol chat trigger tooltips follow the cursor). r128
        // never captured; it tracks the drag through document listeners either way. Release only
        // the capture OrbitControls added, keeping the browser's implicit capture of touch and
        // direct pens. This listener is added after OrbitControls' own, so it runs after it.
        pointerCaptureListener = canvas.disposableListener<PointerEvent>("pointerdown", {
            if (!capturedBeforeControls && canvas.hasPointerCapture(it.pointerId)) {
                canvas.releasePointerCapture(it.pointerId)
            }
        })

        // r186 OrbitControls ignores the wheel during any drag; r128 still zoomed while rotating.
        val controlsWheelListener = _controls.asDynamic()._onMouseWheel.unsafeCast<(Event) -> Unit>()
        canvas.removeEventListener("wheel", controlsWheelListener)
        wheelListener = canvas.disposableListener<Event>(
            "wheel",
            { event: Event ->
                val controls = _controls.asDynamic()

                if (controls.state == ORBIT_CONTROLS_STATE_ROTATE) {
                    controls.state = ORBIT_CONTROLS_STATE_NONE
                    controlsWheelListener(event)
                    controls.state = ORBIT_CONTROLS_STATE_ROTATE
                } else {
                    controlsWheelListener(event)
                }
            },
            AddEventListenerOptions(passive = false),
        )

        _controls.mouseButtons = obj {
            LEFT = MOUSE.PAN
            MIDDLE = MOUSE.DOLLY
            RIGHT = MOUSE.ROTATE
        }

        _controls.touches = obj {
            ONE = TOUCH.PAN
            TWO = TOUCH.DOLLY_ROTATE
        }

        camera.position.copy(position)
        _controls.screenSpacePanning = screenSpacePanning
        _controls.enableRotate = enableRotate
        _controls.zoomSpeed = 3.0
        _controls.update()
        _controls.saveState()
    }

    override fun dispose() {
        controls.dispose()
        pointerDownListener.dispose()
        wheelListener.dispose()
        pointerCaptureListener.dispose()
        super.dispose()
    }

    fun setTarget(target: Vector3) {
        controls.target.copy(target)
        controls.update()
    }

    fun lookAt(position: Vector3, target: Vector3, floorId: Int? = null) {
        camera.position.copy(position)
        controls.target.copy(target)
        // Record this as the initial section center for future offset calculations
        lastSectionCenter = target.clone()
        userTargetOffset.set(0.0, 0.0, 0.0)
        currentFloorId = floorId
        controls.update()
    }

    fun lookAtPreservingViewpoint(sectionCenter: Vector3, floorId: Int? = null) {
        // Detect floor changes — treat null→non-null and non-null→null as floor changes too,
        // since these represent transitions between floor-mapped and non-floor-mapped areas.
        val isFloorChange = currentFloorId != floorId

        if (isFloorChange) {
            // Reset offset for floor changes - use the original lookAt behavior

            lookAt(
                sectionCenter.clone().add(Vector3(0.0, 600.0, 900.0)),
                sectionCenter,
                floorId
            )
            return
        }

        // Same floor logic - preserve user's viewpoint
        lastSectionCenter?.let { lastCenter ->
            // Update user offset based on current target relative to last section center
            userTargetOffset = controls.target.clone().sub(lastCenter)
        }

        // Apply the same user offset to the new section center
        val newTarget = sectionCenter.clone().add(userTargetOffset)

        // Calculate camera offset relative to current target
        val cameraOffset = camera.position.clone().sub(controls.target)

        // Apply both target and camera position changes to maintain the viewpoint
        val newCameraPosition = newTarget.clone().add(cameraOffset)

        // Update positions
        camera.position.copy(newCameraPosition)
        controls.target.copy(newTarget)

        // Update tracking variables
        lastSectionCenter = sectionCenter.clone()
        currentFloorId = floorId

        controls.update()
    }

    fun updateUserOffset() {
        // Update the user's preferred offset when they manually adjust the view.
        // Avoid allocating — copy target then subtract in place.
        lastSectionCenter?.let { sectionCenter ->
            userTargetOffset.copy(controls.target).sub(sectionCenter)
        }
    }

    override fun resetCamera() {
        controls.reset()
    }

    override fun setSize(width: Int, height: Int) {
        if (width == 0 || height == 0) return

        if (camera is PerspectiveCamera) {
            camera.aspect = width.toDouble() / height
            camera.updateProjectionMatrix()
        } else if (camera is OrthographicCamera) {
            camera.left = -floor(width / 2.0)
            camera.right = ceil(width / 2.0)
            camera.top = ceil(height / 2.0)
            camera.bottom = -floor(height / 2.0)
            camera.updateProjectionMatrix()
        }

        controls.update()
    }

    override fun beforeRender() {
        if (camera is PerspectiveCamera) {
            val distance = camera.position.distanceTo(controls.target)
            camera.near = max(.01, distance / 100)
            camera.far = max(2_000.0, 10 * distance)
            camera.updateProjectionMatrix()
        }
    }
}

/** OrbitControls' internal drag states (`_STATE` in OrbitControls.js). */
private const val ORBIT_CONTROLS_STATE_NONE = -1
private const val ORBIT_CONTROLS_STATE_ROTATE = 0
