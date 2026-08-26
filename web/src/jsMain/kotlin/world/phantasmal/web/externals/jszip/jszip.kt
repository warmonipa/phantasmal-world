@file:Suppress("unused")

package world.phantasmal.web.externals.jszip

import org.khronos.webgl.ArrayBuffer
import kotlin.js.Promise

/**
 * Minimal external for the subset of JSZip used by the quest map exporter.
 *
 * JSZip is published as a CommonJS module whose whole export is the constructor
 * (`module.exports = JSZip`), so [JsModule] is applied at the declaration level to bind this
 * class to the module's default export. A file-level `@file:JsModule` would instead look for a
 * named `JSZip` export, which does not exist and fails at runtime with
 * "JSZip is not a constructor".
 */
@JsModule("jszip")
@JsNonModule
external class JSZip {
    /**
     * Adds a file to the archive. For image data pass the base64 body (without the
     * `data:...;base64,` prefix) together with `obj<JSZipFileOptions> { base64 = true }`.
     */
    fun file(name: String, data: String, options: dynamic = definedExternally): JSZip

    /**
     * Serializes the archive. Call with `obj<JSZipGenerateOptions> { type = "arraybuffer" }` to
     * obtain an [ArrayBuffer] suitable for `downloadFile`.
     */
    fun generateAsync(options: dynamic): Promise<ArrayBuffer>
}
