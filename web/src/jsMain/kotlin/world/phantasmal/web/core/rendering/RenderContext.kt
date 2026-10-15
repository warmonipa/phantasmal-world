package world.phantasmal.web.core.rendering

import org.w3c.dom.HTMLCanvasElement
import world.phantasmal.core.disposable.TrackedDisposable
import world.phantasmal.web.externals.three.*

open class RenderContext(
    val canvas: HTMLCanvasElement,
    val camera: Camera,
) : TrackedDisposable() {
    // A standard model viewer rig: ambient + key + fill. Models without baked lighting (items,
    // characters) need a directional cue, otherwise concave and convex surfaces look the same.
    // Their authored vertex normals are kept as is, even where a vertex is shared by surfaces
    // facing different ways (e.g. the rim of Akiko's Frying Pan), so the ambient floor keeps such
    // triangles readable instead of black. Renderers that rotate [lightHolder] with the camera
    // keep the key light at the viewer's upper left and the fill light at the lower right.
    private val ambientLight = AmbientLight(color = 0xffffff, intensity = 0.45)

    private val keyLight = DirectionalLight(color = 0xffffff, intensity = 0.6).apply {
        position.set(-1.0, 1.2, 1.5)
    }

    private val fillLight = DirectionalLight(color = 0xffffff, intensity = 0.2).apply {
        position.set(1.0, -0.5, 1.0)
    }

    val lightHolder = Group().add(ambientLight, keyLight, fillLight)

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
