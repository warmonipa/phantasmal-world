package world.phantasmal.web.questEditor.rendering

import kotlinx.browser.document
import org.khronos.webgl.Float32Array
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.CanvasRenderingContext2D
import world.phantasmal.web.externals.three.*
import world.phantasmal.webui.obj
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * Renders room ID labels as transparent circles with text for quest sections.
 */
class RoomIdRenderer {
    companion object {
        private const val CIRCLE_SEGMENTS = 32  // Segments for circle outline
        private const val CIRCLE_RADIUS = 60.0f  // Circle radius in world units (increased for better visibility)
        private const val CIRCLE_COLOR = 0x4CAF50  // Green color for visibility
        private const val CIRCLE_OPACITY = 0.4  // Slightly more opaque
        private const val TEXT_COLOR = 0xFFFFFF  // White text
        private const val TEXT_SIZE = 32.0  // Text size (significantly increased for better visibility)
    }

    /**
     * Creates a room ID display with transparent circle and text label showing custom text.
     */
    fun createRoomIdLabelWithText(
        centerX: Float,
        centerY: Float,
        centerZ: Float,
        roomIdText: String
    ): Group {
        val group = Group()
        
        // Create transparent circle outline (elevated above ground)
        val circle = createTransparentCircle(CIRCLE_RADIUS, CIRCLE_COLOR, CIRCLE_OPACITY)
        group.add(circle)
        
        // Create text label using a plane with canvas texture
        val textPlane = createTextPlane(roomIdText, TEXT_COLOR, TEXT_SIZE)
        // Position text slightly above the circle for better visibility
        textPlane.position.y = 5.0
        group.add(textPlane)
        
        // Position the entire group at the section center, moderately elevated
        group.position.set(centerX.toDouble(), (centerY + 40.0f).toDouble(), centerZ.toDouble())  // Reasonable height
        group.name = "RoomIdLabel_${roomIdText.hashCode()}"
        
        // Make sure it renders after other geometry and is always visible
        group.renderOrder = 9999  // Higher render order
        
        // Force frustum culling off to always render
        circle.frustumCulled = false
        textPlane.frustumCulled = false
        group.frustumCulled = false
        
        return group
    }

    /**
     * Creates a room ID display with transparent circle and text label showing the room number.
     */
    fun createRoomIdLabel(
        centerX: Float,
        centerY: Float,
        centerZ: Float,
        roomId: Int
    ): Group {
        return createRoomIdLabelWithText(centerX, centerY, centerZ, roomId.toString())
    }
    
    /**
     * Creates a transparent circle using line segments.
     */
    private fun createTransparentCircle(radius: Float, color: Int, opacity: Double): Object3D {
        // Create circle vertices
        val vertices = mutableListOf<Float>()
        for (i in 0..CIRCLE_SEGMENTS) {
            val angle = (i * 2 * PI / CIRCLE_SEGMENTS)
            val x = (cos(angle) * radius).toFloat()
            val z = (sin(angle) * radius).toFloat()
            vertices.add(x)
            vertices.add(0.0f) // Y relative to group position
            vertices.add(z)
        }
        
        val geometry = BufferGeometry().apply {
            setAttribute("position", Float32BufferAttribute(Float32Array(vertices.toTypedArray()), 3))
        }
        
        val material = LineBasicMaterial(obj {
            this.color = Color(color)
            transparent = true
            this.opacity = opacity
            linewidth = 2.0
        })
        
        // Force material settings after creation
        material.asDynamic().depthTest = false
        material.asDynamic().depthWrite = false
        
        return Line(geometry, material).apply {
            name = "RoomCircle"
            // Set high render order for this line specifically
            renderOrder = 10000
            frustumCulled = false
        }
    }
    
    /**
     * Creates a text plane using canvas texture for displaying room ID numbers.
     */
    private fun createTextPlane(text: String, color: Int, size: Double): Mesh {
        // Create canvas for text rendering (larger to avoid text clipping)
        val canvas = document.createElement("CANVAS") as HTMLCanvasElement
        canvas.width = 256  // Increased width
        canvas.height = 128  // Increased height
        val context = canvas.getContext("2d") as CanvasRenderingContext2D
        
        // Configure text rendering with larger font
        context.fillStyle = "#${color.toString(16).padStart(6, '0')}"
        context.font = "bold ${size * 2.5}px Arial"  // Reduced multiplier to fit better in canvas
        context.asDynamic().textAlign = "center"
        context.asDynamic().textBaseline = "middle"
        
        // Add some padding and stroke for better visibility
        context.strokeStyle = "#000000"
        context.lineWidth = 3.0
        
        // Clear canvas and draw text (adjusted for larger canvas)
        context.clearRect(0.0, 0.0, 256.0, 128.0)
        
        // Draw text with stroke (outline) first, then fill
        context.strokeText(text, 128.0, 64.0)  // Black outline
        context.fillText(text, 128.0, 64.0)    // White fill
        
        // Create texture from canvas
        val texture = Texture()
        texture.asDynamic().image = canvas
        texture.needsUpdate = true
        
        // Create plane geometry (larger to ensure text is not clipped)
        val geometry = PlaneGeometry(size * 3.0, size * 1.8)  // Increased size to accommodate larger text
        
        // Create material with aggressive visibility settings
        val material = MeshBasicMaterial(obj {
            map = texture
            transparent = true
            alphaTest = 0.05  // Lower alpha test threshold
        })
        
        // Force material settings for absolute visibility
        material.asDynamic().depthTest = false
        material.asDynamic().depthWrite = false
        
        return Mesh(geometry, material).apply {
            name = "RoomText"
            // Make text always face the camera (billboard effect)
            // Don't set lookAt here, it will be handled dynamically
            
            // Set high render order for this mesh specifically
            renderOrder = 10000
            
            // Additional settings to ensure visibility
            frustumCulled = false
        }
    }
    
    /**
     * Creates SCL_TAMA visualization with circle, one radius line and value display.
     * Only shown when ObjRoomID object is selected.
     * Positioned close to ground level like EventCollision range circles.
     */
    fun createSclTamaVisualization(
        centerX: Float,
        centerY: Float,
        centerZ: Float,
        sclTamaValue: Float
    ): Group {
        val group = Group()
        val calculatedRadius = sclTamaValue * 10.0f
        
        // Create transparent circle outline at ground level
        val circle = createGroundLevelCircle(calculatedRadius, CIRCLE_COLOR, CIRCLE_OPACITY)
        group.add(circle)
        
        // Create single radius line at ground level
        val radiusLine = createGroundLevelRadiusLine(calculatedRadius)
        group.add(radiusLine)
        
        // Create value text at the end of the line, slightly elevated above ground
        val valueText = createSimpleValueText(calculatedRadius)
        valueText.position.set(calculatedRadius.toDouble(), 2.0, 0.0) // Slightly above ground
        group.add(valueText)
        
        // Position the entire group at entity position (no height offset)
        group.position.set(centerX.toDouble(), centerY.toDouble(), centerZ.toDouble())
        group.name = "SclTamaVisualization_${sclTamaValue.hashCode()}"
        
        // Rendering settings
        group.renderOrder = 9999
        group.frustumCulled = false
        
        return group
    }
    
    /**
     * Creates simple value text display (just the number).
     */
    private fun createSimpleValueText(radiusValue: Float): Mesh {
        val valueText = "${(radiusValue * 10).toInt() / 10.0f}" // Just the number, no "R:" prefix
        return createTextPlane(valueText, 0xFFFF00, (TEXT_SIZE * 0.8f).toDouble()).apply {
            name = "SclTamaSimpleText"
            frustumCulled = false
        }
    }
    
    /**
     * Creates a transparent circle at ground level like EventCollision range circles.
     */
    private fun createGroundLevelCircle(radius: Float, color: Int, opacity: Double): Object3D {
        // Create circle vertices
        val vertices = mutableListOf<Float>()
        for (i in 0..CIRCLE_SEGMENTS) {
            val angle = (i * 2 * PI / CIRCLE_SEGMENTS)
            val x = (cos(angle) * radius).toFloat()
            val z = (sin(angle) * radius).toFloat()
            vertices.add(x)
            vertices.add(0.0f) // Y is always 0 (ground level)
            vertices.add(z)
        }
        
        val geometry = BufferGeometry().apply {
            setAttribute("position", Float32BufferAttribute(Float32Array(vertices.toTypedArray()), 3))
        }
        
        val material = LineBasicMaterial(obj {
            this.color = Color(color)
            transparent = true
            this.opacity = opacity
            linewidth = 2.0
        })
        
        return Line(geometry, material).apply {
            name = "SclTamaGroundCircle"
            renderOrder = 1000 // Same as EventCollision range circles
            frustumCulled = false
        }
    }
    
    /**
     * Creates a single radius line at ground level.
     */
    private fun createGroundLevelRadiusLine(radius: Float): LineSegments {
        val points = FloatArray(6) // 1 line * 2 points * 3 coordinates
        var index = 0
        
        // Single line from center to east at ground level
        points[index++] = 0f; points[index++] = 0f; points[index++] = 0f
        points[index++] = radius; points[index++] = 0f; points[index++] = 0f
        
        val geometry = BufferGeometry().apply {
            setAttribute("position", Float32BufferAttribute(Float32Array(points.toTypedArray()), 3))
        }
        
        val material = LineBasicMaterial(obj {
            color = Color(1.0, 0.8, 0.0) // Golden color for radius line
            transparent = true
            opacity = 0.8
            linewidth = 3.0 // Thicker line for better visibility
        })
        
        return LineSegments(geometry, material).apply {
            name = "SclTamaGroundRadiusLine"
            renderOrder = 1000 // Same as EventCollision range circles
            frustumCulled = false
        }
    }
}