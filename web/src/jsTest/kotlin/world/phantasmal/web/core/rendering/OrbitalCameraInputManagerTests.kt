package world.phantasmal.web.core.rendering

import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.events.WheelEvent
import org.w3c.dom.pointerevents.PointerEvent
import org.w3c.dom.pointerevents.PointerEventInit
import org.w3c.dom.events.WheelEventInit
import world.phantasmal.web.externals.three.PerspectiveCamera
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.test.WebTestContext
import world.phantasmal.web.test.WebTestSuite
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals

class OrbitalCameraInputManagerTests : WebTestSuite {
    @Test
    fun each_wheel_step_zooms_by_the_same_factor() = test {
        // Mouse wheels report 100 px per notch at a device pixel ratio of 1 and 200 px at 2;
        // both must zoom one step, as with three.js r128.
        for (deltaY in listOf(-100.0, -200.0, -4.0)) {
            val canvas = document.createElement("canvas") as HTMLCanvasElement
            val camera = PerspectiveCamera(60.0, 1.0, 0.1, 1000.0)
            disposer.add(
                OrbitalCameraInputManager(
                    canvas = canvas,
                    camera = camera,
                    position = Vector3(0.0, 0.0, 10.0),
                    screenSpacePanning = false,
                )
            )

            canvas.dispatchEvent(WheelEvent("wheel", WheelEventInit(deltaY = deltaY, cancelable = true)))

            assertEquals(
                10.0 * 0.95.pow(3.0),
                camera.position.length(),
                absoluteTolerance = 1e-9,
                message = "deltaY $deltaY",
            )
        }
    }

    @Test
    fun wheel_zooms_while_rotating() = test {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        val camera = PerspectiveCamera(60.0, 1.0, 0.1, 1000.0)
        val manager = disposer.add(
            OrbitalCameraInputManager(
                canvas = canvas,
                camera = camera,
                position = Vector3(0.0, 0.0, 10.0),
                screenSpacePanning = false,
            )
        )
        // A right-button drag is in progress (OrbitControls' internal ROTATE state).
        manager.controls.asDynamic().state = 0

        canvas.dispatchEvent(WheelEvent("wheel", WheelEventInit(deltaY = -100.0, cancelable = true)))

        assertEquals(10.0 * 0.95.pow(3.0), camera.position.length(), absoluteTolerance = 1e-9)
        assertEquals(0, manager.controls.asDynamic().state.unsafeCast<Int>())
    }

    @Test
    fun mouse_camera_drags_do_not_capture_the_pointer() = test {
        // three.js r128 OrbitControls never captured mouse drags, so other canvas listeners stopped
        // receiving moves during a camera drag (the quest editor shows a pointer trap instead).
        val (canvas, captured) = canvasWithCaptureLog()

        canvas.dispatchEvent(pointerDown(pointerId = 7, pointerType = "mouse"))

        assertEquals(emptySet(), captured)
    }

    @Test
    fun touch_keeps_its_pointer_capture() = test {
        // Touch pointers are implicitly captured by the browser; r128 left that capture alone.
        val (canvas, captured) = canvasWithCaptureLog()
        captured.add(8)

        canvas.dispatchEvent(pointerDown(pointerId = 8, pointerType = "touch"))

        assertEquals(setOf(8), captured)
    }

    private fun WebTestContext.canvasWithCaptureLog(): Pair<HTMLCanvasElement, MutableSet<Int>> {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        val captured = mutableSetOf<Int>()
        canvas.asDynamic().setPointerCapture = { id: Int -> captured.add(id) }
        canvas.asDynamic().releasePointerCapture = { id: Int -> captured.remove(id) }
        canvas.asDynamic().hasPointerCapture = { id: Int -> id in captured }
        disposer.add(
            OrbitalCameraInputManager(
                canvas = canvas,
                camera = PerspectiveCamera(60.0, 1.0, 0.1, 1000.0),
                position = Vector3(0.0, 0.0, 10.0),
                screenSpacePanning = false,
            )
        )
        return Pair(canvas, captured)
    }

    private fun pointerDown(pointerId: Int, pointerType: String): PointerEvent =
        PointerEvent(
            "pointerdown",
            PointerEventInit(pointerId = pointerId, pointerType = pointerType, button = 2, buttons = 2),
        )
}
