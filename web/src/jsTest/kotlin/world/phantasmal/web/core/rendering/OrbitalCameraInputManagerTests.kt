package world.phantasmal.web.core.rendering

import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.events.WheelEvent
import org.w3c.dom.events.WheelEventInit
import world.phantasmal.web.externals.three.PerspectiveCamera
import world.phantasmal.web.externals.three.Vector3
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
}
