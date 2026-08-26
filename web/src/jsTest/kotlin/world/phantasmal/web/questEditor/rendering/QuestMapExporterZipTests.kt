package world.phantasmal.web.questEditor.rendering

import kotlinx.browser.document
import kotlinx.coroutines.await
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import world.phantasmal.web.externals.jszip.JSZip
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.webui.obj
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runtime (headless-browser) coverage for the JSZip + canvas interop the exporter relies on.
 *
 * These exercise the two layers that previously failed only at runtime and were invisible to
 * compilation and pure unit tests:
 *  - `JSZip()` construction (the wrong module-import shape produced "JSZip is not a constructor"), and
 *  - the option objects passed to `file(..)`/`generateAsync(..)` (a bad `js("{...}")` literal
 *    evaluated to `undefined`, breaking `generateAsync`).
 */
class QuestMapExporterZipTests : WebTestSuite {
    @Test
    fun jszip_can_be_constructed_and_generate_an_archive() = testAsync {
        val zip = JSZip()
        zip.file("hello.txt", "aGVsbG8=", obj<JSZipFileOptions> { base64 = true })

        val buf = zip.generateAsync(obj<JSZipGenerateOptions> { type = "arraybuffer" }).await()

        // A non-empty archive is produced (an empty zip is 22 bytes; ours has an entry).
        assertTrue(buf.byteLength > 22, "Expected a non-empty zip archive, got ${buf.byteLength} bytes.")
    }

    @Test
    fun a_canvas_png_is_stored_into_the_zip_as_base64() = testAsync {
        val canvas = document.createElement("CANVAS") as HTMLCanvasElement
        canvas.width = 16
        canvas.height = 16
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.fillRect(0.0, 0.0, 16.0, 16.0)

        // Mirrors QuestMapExporter: strip the data-URL prefix, add as base64.
        val pngBase64 = canvas.toDataURL("image/png").substringAfter(",")
        assertTrue(pngBase64.isNotEmpty(), "Canvas produced an empty PNG data URL.")

        val zip = JSZip()
        zip.file("01_area.png", pngBase64, obj<JSZipFileOptions> { base64 = true })
        val buf = zip.generateAsync(obj<JSZipGenerateOptions> { type = "arraybuffer" }).await()

        assertTrue(buf.byteLength > 22, "Expected the PNG to be stored in the archive.")
    }
}
