package world.phantasmal.web.core.rendering

import org.w3c.dom.HTMLCanvasElement
import world.phantasmal.core.disposable.TrackedDisposable
import world.phantasmal.web.externals.three.*

open class RenderContext(
    val canvas: HTMLCanvasElement,
    val camera: Camera,
) : TrackedDisposable() {
    private val light = HemisphereLight(
        skyColor = 0xffffff,
        groundColor = 0x505050,
        intensity = 0.65
    )

    // A key light from the upper left: models without baked lighting (items, characters) need a
    // directional cue, otherwise concave and convex surfaces look the same. Renderers that rotate
    // [lightHolder] with the camera keep it at the viewer's upper left.
    private val keyLight = DirectionalLight(color = 0xffffff, intensity = 0.6).apply {
        position.set(-1.0, 1.2, 1.5)
    }

    val lightHolder = Group().add(light, keyLight)

    var width: Int = 0
    var height: Int = 0

    val scene: Scene =
        Scene().apply {
            background = Color(0x181818)
            add(lightHolder)
        }

    fun pointerPosToDeviceCoords(pos: Vector2) {
        pos.set((pos.x / width) * 2 - 1, (pos.y / height) * -2 + 1)
    }
}
