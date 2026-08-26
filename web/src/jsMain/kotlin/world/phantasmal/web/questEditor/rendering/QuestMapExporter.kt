package world.phantasmal.web.questEditor.rendering

import kotlinx.browser.document
import kotlinx.coroutines.await
import mu.KotlinLogging
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLImageElement
import world.phantasmal.web.core.rendering.DisposableThreeRenderer
import world.phantasmal.web.externals.jszip.JSZip
import world.phantasmal.web.externals.three.*
import world.phantasmal.web.questEditor.loading.AreaAssetLoader
import world.phantasmal.web.questEditor.models.QuestEntityModel
import world.phantasmal.web.questEditor.models.QuestModel
import world.phantasmal.web.questEditor.models.SectionModel
import world.phantasmal.web.questEditor.controllers.AreaAndLabel
import world.phantasmal.web.questEditor.stores.AreaStore
import world.phantasmal.webui.DisposableContainer
import world.phantasmal.webui.files.downloadFile
import world.phantasmal.webui.obj
import kotlin.js.Promise
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val logger = KotlinLogging.logger {}

external interface JSZipFileOptions {
    var base64: Boolean
}

external interface JSZipGenerateOptions {
    var type: String
}

/**
 * Exports a 2D top-down PNG for each area of a quest and packages them into a single zip.
 *
 * The background is the real in-game textured render geometry viewed through an orthographic
 * top-down camera (a "slice" of the 3D scene). Objects and bosses are overlaid as flat legend
 * icons (three.js [Sprite]s); a legend panel is composited on top. Everything is drawn as
 * three.js objects and captured in a single [HTMLCanvasElement.toDataURL] per area.
 */
class QuestMapExporter(
    private val areaAssetLoader: AreaAssetLoader,
    private val areaStore: AreaStore,
    private val createThreeRenderer: (HTMLCanvasElement) -> DisposableThreeRenderer,
) : DisposableContainer() {

    /** Longest output edge in logical pixels; the other edge preserves the area's X/Z aspect. */
    private val maxOutputEdge = 2048.0

    /** Marker icon size on screen, in pixels. */
    private val markerPx = 22.0

    suspend fun exportQuest(
        quest: QuestModel,
        areas: List<AreaAndLabel>,
        ultimate: Boolean,
    ) {
        val disposableRenderer =
            createThreeRenderer(document.createElement("CANVAS") as HTMLCanvasElement)
        val renderer = disposableRenderer.renderer
        renderer.setClearColor(Color(0x181818), 1.0)
        // Render at 1:1 device pixels so the offscreen canvas size equals our logical size; this
        // keeps the 2D legend composition (below) predictable.
        renderer.setPixelRatio(1.0)
        // Manual color clearing per area (see clearColor() below); depth still auto-clears.
        renderer.autoClearColor = false

        // One shared material (and texture) per category; both map sprites and legend swatches
        // reuse the same category definition (single source of truth).
        val categoryMaterials = HashMap<MapMarkerCategory, SpriteMaterial>()

        try {
            val zip = JSZip()
            var successCount = 0
            var firstError: Throwable? = null

            areas.forEachIndexed { i, al ->
                try {
                    if (exportArea(quest, al, ultimate, renderer, categoryMaterials, zip, i + 1)) {
                        successCount++
                    }
                } catch (e: Throwable) {
                    if (firstError == null) firstError = e
                    logger.error(e) { "Failed to export map for area \"${al.area.name}\"." }
                }
            }

            if (successCount == 0) {
                throw firstError ?: IllegalStateException("No areas were available to export.")
            }

            val buf = zip.generateAsync(obj<JSZipGenerateOptions> { type = "arraybuffer" }).await()
            val name = quest.name.value.ifBlank { "quest" }
            downloadFile(buf, "${sanitize(name)}_maps.zip")
        } finally {
            categoryMaterials.values.forEach { mat ->
                mat.map?.dispose()
                mat.dispose()
            }
            disposableRenderer.dispose()
        }
    }

    /** Returns true when a PNG was produced and added to [zip]; false when the area was skipped. */
    private suspend fun exportArea(
        quest: QuestModel,
        al: AreaAndLabel,
        ultimate: Boolean,
        renderer: WebGLRenderer,
        categoryMaterials: HashMap<MapMarkerCategory, SpriteMaterial>,
        zip: JSZip,
        index: Int,
    ): Boolean {
        val variant = al.variant ?: areaStore.getVariant(quest.episode, al.area.id, 0) ?: return false
        val floorIds = al.floorIds ?: setOf(al.area.id)

        // Only export areas that carry data (entities), i.e. the floors the quest actually uses.
        // Pioneer 2 / Lab (the hub, area id 0) is always kept even when it has no entities.
        val hasEntities =
            quest.objects.value.any { it.floorId in floorIds } ||
                quest.npcs.value.any { it.floorId in floorIds }
        if (!hasEntities && al.area.id != 0) return false

        // Await all async data BEFORE touching the (shared, possibly live) geometry. The export
        // always uses the flat "Simple View" collision geometry (cleaner top-down, no roofs),
        // regardless of the editor's current Simple View toggle.
        val geom = areaAssetLoader.loadCollisionGeometry(quest.episode, variant, ultimate)
        val sections = areaStore.getSections(quest.episode, variant)

        // Collect markers as (category, worldX, worldZ). Only registry-known objects/bosses.
        val markers = ArrayList<Marker>()
        for (o in quest.objects.value) {
            if (o.floorId !in floorIds) continue
            val category = MapMarkerIcons.categoryForObject(o.type) ?: continue
            val wp = worldPosition(o, sections)
            markers.add(Marker(category, wp.x, wp.z))
        }
        for (n in quest.npcs.value) {
            if (n.floorId !in floorIds) continue
            val category = MapMarkerIcons.categoryForNpc(n.type) ?: continue
            val wp = worldPosition(n, sections)
            markers.add(Marker(category, wp.x, wp.z))
        }

        geom.updateMatrixWorld(true)
        val box = Box3().setFromObject(geom)
        if (box.isEmpty()) return false
        val min = box.min
        val max = box.max
        val worldW = max.x - min.x
        val worldH = max.z - min.z
        if (worldW <= 0.0 || worldH <= 0.0) return false

        val (w, h) =
            if (worldW >= worldH) Pair(maxOutputEdge, maxOutputEdge * worldH / worldW)
            else Pair(maxOutputEdge * worldW / worldH, maxOutputEdge)
        renderer.setSize(w, h)

        val presentCategories = MapMarkerCategory.values().filter { c -> markers.any { it.category == c } }

        // ---- Synchronous block: no suspension until the geometry is restored. ----
        val oldParent = geom.parent
        val oldVisible = geom.visible

        val scene = Scene()
        scene.add(HemisphereLight(0xffffff, 0x505050, 1.0))
        geom.visible = true
        scene.add(geom)

        val marginFactor = 1.06
        val frustumW = worldW * marginFactor
        val frustumH = worldH * marginFactor
        val worldPerPx = frustumW / w
        val markerWorld = markerPx * worldPerPx
        val cx = (min.x + max.x) / 2.0
        val cz = (min.z + max.z) / 2.0
        val markerY = max.y + 10.0

        val sprites = ArrayList<Sprite>(markers.size)
        for (m in markers) {
            val material = categoryMaterials.getOrPut(m.category) { createMarkerMaterial(m.category) }
            val sprite = Sprite(material)
            sprite.position.set(m.x, markerY, m.z)
            sprite.scale.set(markerWorld, markerWorld, 1.0)
            sprite.renderOrder = 10_000
            sprite.frustumCulled = false
            scene.add(sprite)
            sprites.add(sprite)
        }

        val camera = OrthographicCamera(
            left = -frustumW / 2,
            right = frustumW / 2,
            top = frustumH / 2,
            bottom = -frustumH / 2,
            near = 1.0,
            far = (max.y - min.y) + 4000.0,
        )
        camera.position.set(cx, max.y + 2000.0, cz)
        camera.up.set(0.0, 0.0, -1.0)
        camera.lookAt(cx, min.y, cz)
        camera.updateProjectionMatrix()

        renderer.clearColor()
        renderer.render(scene, camera)

        val mapDataUrl = renderer.domElement.toDataURL("image/png")

        for (s in sprites) scene.remove(s)
        scene.remove(geom)
        geom.visible = oldVisible
        if (oldParent != null) oldParent.add(geom)
        // ---- End synchronous block. ----

        // Compose the legend into a side gutter so it never covers the map.
        val finalDataUrl =
            if (presentCategories.isEmpty()) mapDataUrl
            else composeWithLegend(mapDataUrl, w, h, presentCategories)

        val pngBase64 = finalDataUrl.substringAfter(",")
        val fileName = "${index.toString().padStart(2, '0')}_${sanitize(al.area.name)}.png"
        zip.file(fileName, pngBase64, obj<JSZipFileOptions> { base64 = true })
        return true
    }

    private fun worldPosition(entity: QuestEntityModel<*, *>, sections: List<SectionModel>): Vector3 {
        val local = entity.position.value
        val section = sections.find { it.id == entity.sectionId.value } ?: return local
        return sectionToWorld(local, section.position, section.rotation)
    }

    private fun createMarkerMaterial(category: MapMarkerCategory): SpriteMaterial {
        val canvas = document.createElement("CANVAS") as HTMLCanvasElement
        canvas.width = 64
        canvas.height = 64
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.clearRect(0.0, 0.0, 64.0, 64.0)
        drawShape(ctx, 32.0, 32.0, 24.0, cssColor(category.colorHex), category.shape)

        val texture = Texture()
        texture.asDynamic().image = canvas
        texture.needsUpdate = true

        val material = SpriteMaterial(obj {
            map = texture
            transparent = true
        })
        material.asDynamic().depthTest = false
        material.asDynamic().depthWrite = false
        return material
    }

    /**
     * Composites the rendered map (a `mapW` x `mapH` PNG data URL) and a legend panel onto a wider
     * canvas, with the legend in a right-hand gutter so it never overlaps the map, and returns the
     * combined PNG data URL.
     */
    private suspend fun composeWithLegend(
        mapDataUrl: String,
        mapW: Double,
        mapH: Double,
        categories: List<MapMarkerCategory>,
    ): String {
        val mapImg = loadImage(mapDataUrl)

        val pad = 16.0
        val titleH = 30.0
        val rowH = 34.0
        val gutter = 16.0

        // Measure the widest label to size the panel.
        val measure = (document.createElement("CANVAS") as HTMLCanvasElement)
            .getContext("2d") as CanvasRenderingContext2D
        measure.font = "16px Arial"
        val maxLabelW = categories.maxOf { measure.measureText(it.label).width }
        val panelW = pad + 24.0 + 12.0 + maxLabelW + pad
        val panelH = pad * 2 + titleH + categories.size * rowH

        val finalW = mapW + gutter + panelW
        val finalH = maxOf(mapH, panelH)

        val canvas = document.createElement("CANVAS") as HTMLCanvasElement
        canvas.width = finalW.toInt()
        canvas.height = finalH.toInt()
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D

        ctx.asDynamic().fillStyle = "#181818"
        ctx.fillRect(0.0, 0.0, finalW, finalH)
        ctx.asDynamic().drawImage(mapImg, 0.0, 0.0)

        drawLegendPanel(ctx, mapW + gutter, 0.0, panelW, panelH, categories, pad, titleH, rowH)

        return canvas.toDataURL("image/png")
    }

    private fun drawLegendPanel(
        ctx: CanvasRenderingContext2D,
        x: Double,
        y: Double,
        panelW: Double,
        panelH: Double,
        categories: List<MapMarkerCategory>,
        pad: Double,
        titleH: Double,
        rowH: Double,
    ) {
        ctx.asDynamic().fillStyle = "rgba(0,0,0,0.72)"
        ctx.beginPath()
        ctx.asDynamic().roundRect(x, y, panelW, panelH, 12.0)
        ctx.fill()

        ctx.asDynamic().textAlign = "left"
        ctx.asDynamic().textBaseline = "middle"
        ctx.asDynamic().fillStyle = "#ffffff"
        ctx.font = "bold 20px Arial"
        ctx.fillText("Legend", x + pad, y + pad + titleH / 2)

        var rowY = y + pad + titleH
        for (category in categories) {
            drawShape(ctx, x + pad + 12.0, rowY + rowH / 2, 9.0, cssColor(category.colorHex), category.shape)
            ctx.asDynamic().fillStyle = "#ffffff"
            ctx.font = "16px Arial"
            ctx.fillText(category.label, x + pad + 24.0 + 12.0, rowY + rowH / 2)
            rowY += rowH
        }
    }

    private suspend fun loadImage(dataUrl: String): HTMLImageElement {
        val img = document.createElement("IMG") as HTMLImageElement
        img.src = dataUrl
        // decode() resolves once the image is fully ready to be drawn to a canvas.
        (img.asDynamic().decode() as Promise<*>).await()
        return img
    }

    private fun drawShape(
        ctx: CanvasRenderingContext2D,
        cx: Double,
        cy: Double,
        r: Double,
        color: String,
        shape: MapMarkerShape,
    ) {
        ctx.asDynamic().fillStyle = color
        ctx.asDynamic().strokeStyle = "#101010"
        ctx.lineWidth = 2.0

        when (shape) {
            MapMarkerShape.CIRCLE -> {
                ctx.beginPath()
                ctx.arc(cx, cy, r, 0.0, 2 * PI)
                ctx.fill()
                ctx.stroke()
            }
            MapMarkerShape.SQUARE -> {
                ctx.beginPath()
                ctx.rect(cx - r, cy - r, 2 * r, 2 * r)
                ctx.fill()
                ctx.stroke()
            }
            MapMarkerShape.DIAMOND -> {
                ctx.beginPath()
                ctx.moveTo(cx, cy - r)
                ctx.lineTo(cx + r, cy)
                ctx.lineTo(cx, cy + r)
                ctx.lineTo(cx - r, cy)
                ctx.closePath()
                ctx.fill()
                ctx.stroke()
            }
            MapMarkerShape.TRIANGLE -> {
                ctx.beginPath()
                ctx.moveTo(cx, cy - r)
                ctx.lineTo(cx + r, cy + r)
                ctx.lineTo(cx - r, cy + r)
                ctx.closePath()
                ctx.fill()
                ctx.stroke()
            }
            MapMarkerShape.RING -> {
                ctx.asDynamic().strokeStyle = color
                ctx.lineWidth = r * 0.5
                ctx.beginPath()
                ctx.arc(cx, cy, r * 0.7, 0.0, 2 * PI)
                ctx.stroke()
            }
            MapMarkerShape.STAR -> {
                ctx.beginPath()
                val points = 5
                for (k in 0 until points * 2) {
                    val radius = if (k % 2 == 0) r else r * 0.45
                    val angle = -PI / 2 + k * PI / points
                    val px = cx + radius * cos(angle)
                    val py = cy + radius * sin(angle)
                    if (k == 0) ctx.moveTo(px, py) else ctx.lineTo(px, py)
                }
                ctx.closePath()
                ctx.fill()
                ctx.stroke()
            }
        }
    }

    private fun cssColor(hex: Int): String = "#" + hex.toString(16).padStart(6, '0')

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").ifBlank { "area" }

    private class Marker(val category: MapMarkerCategory, val x: Double, val z: Double)
}

/**
 * Transforms a section-relative [local] position into a world position, mirroring
 * `QuestEntityModel` (`local.applyEuler(sectionRotation) + sectionPosition`). Applies the
 * section's FULL Euler rotation, not just yaw. Extracted for direct regression testing.
 */
internal fun sectionToWorld(local: Vector3, sectionPosition: Vector3, sectionRotation: Euler): Vector3 =
    local.clone().applyEuler(sectionRotation).add(sectionPosition)
