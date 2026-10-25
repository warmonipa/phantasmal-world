package world.phantasmal.psolib.fileFormats

import mu.KotlinLogging
import world.phantasmal.core.PwResult
import world.phantasmal.core.Severity
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.Cursor

private val logger = KotlinLogging.logger {}

private const val HEADER_SIZE = 0x40
private const val ENTRY_HEADER_SIZE = 0x40
private const val NAME_SIZE = 0x20
private const val DATA_START = 0x800

class BmlEntry(
    val name: String,
    /** PRS-compressed model data (NJ/XJ). */
    val data: Buffer,
    /** PRS-compressed texture data (XVM). May be an empty buffer if the entry has no textures. */
    val textureData: Buffer,
    val decompressedSize: Int,
    val decompressedTextureSize: Int,
)

/**
 * Parses a BML model container. BML is a format used by PSO to bundle compressed model and texture
 * data.
 *
 * Format:
 * - Header: 0x40 bytes (4 unknown, 4 num_entries, 0x38 unknown).
 * - Entry headers: each 0x40 bytes (0x20 filename, 4 compressed_size, 4 unknown,
 *   4 decompressed_size, 4 compressed_gvm_size, 4 decompressed_gvm_size, 0x0C unknown).
 * - The complete directory is padded to a 0x800 boundary.
 * - P/1 streams are aligned to 0x20; P/0 streams are aligned to 0x800.
 * - Only PRS-compressed BB layouts verified against original resources are accepted.
 * - Each entry contains model data followed by texture data, both PRS-compressed.
 *
 * The returned [BmlEntry.data] and [BmlEntry.textureData] are the raw PRS-compressed buffers.
 * Use [world.phantasmal.psolib.compression.prs.prsDecompress] to decompress them.
 */
fun parseBml(cursor: Cursor): PwResult<List<BmlEntry>> {
    val result = PwResult.build<List<BmlEntry>>(logger)

    if (cursor.bytesLeft < HEADER_SIZE) {
        return result
            .addProblem(
                Severity.Error,
                "BML file is corrupted.",
                "Expected at least $HEADER_SIZE bytes for the header, got ${cursor.bytesLeft} bytes.",
            )
            .failure()
    }

    // Parse header.
    cursor.seek(4) // Skip unknown.
    val numEntries = cursor.int()
    val compression = cursor.uByte().toInt()
    val paired = cursor.uByte().toInt()
    cursor.seek(0x36)
    if (compression != 0x50 || paired !in 0..1) {
        return result.addProblem(Severity.Error, "Unsupported BML layout.").failure()
    }
    val alignment = if (paired == 1) 0x20 else 0x800

    if (numEntries < 0 || numEntries > 10000) {
        return result
            .addProblem(Severity.Error, "BML file is corrupted.", "Invalid entry count: $numEntries.")
            .failure()
    }

    // Parse entry headers.
    data class EntryHeader(
        val name: String,
        val compressedSize: Int,
        val compressedGvmSize: Int,
        val decompressedSize: Int,
        val decompressedTextureSize: Int,
    )

    val entryHeaders = mutableListOf<EntryHeader>()

    for (i in 0 until numEntries) {
        if (cursor.bytesLeft < ENTRY_HEADER_SIZE) {
            result.addProblem(
                Severity.Error,
                "BML entry header $i is invalid.",
                "Couldn't read entry header $i, only ${cursor.bytesLeft} bytes left.",
            )
            return result.failure()
        }

        val name = cursor.stringAscii(NAME_SIZE, nullTerminated = true, dropRemaining = true)
        val compressedSize = cursor.int()
        cursor.seek(4) // Skip unknown.
        val decompressedSize = cursor.int()
        val compressedGvmSize = cursor.int()
        val decompressedTextureSize = cursor.int()
        cursor.seek(0x0C) // Skip unknown.

        if (compressedSize < 0 || compressedGvmSize < 0 || decompressedSize < 0 || decompressedTextureSize < 0) {
            return result.addProblem(Severity.Error, "Negative size in BML entry $i.").failure()
        }
        entryHeaders.add(EntryHeader(name, compressedSize, compressedGvmSize, decompressedSize, decompressedTextureSize))
    }

    val dataStart = alignUp(HEADER_SIZE + numEntries * ENTRY_HEADER_SIZE, DATA_START)
    var dataOffset = dataStart
    val entries = mutableListOf<BmlEntry>()

    for (header in entryHeaders) {
        // Read model data.
        if (dataOffset > cursor.size || header.compressedSize > cursor.size - dataOffset) {
            result.addProblem(
                Severity.Error,
                "BML entry \"${header.name}\" is invalid.",
                "Model data of size ${header.compressedSize} at offset $dataOffset extends beyond end of file (${cursor.size} bytes).",
            )
            return result.failure()
        }

        cursor.seekStart(dataOffset)
        val modelData = cursor.buffer(header.compressedSize)

        // Advance past model data, aligned to the archive stream boundary.
        dataOffset += alignUp(header.compressedSize, alignment)

        // Read texture data.
        val textureData: Buffer

        if (header.compressedGvmSize > 0) {
            if (dataOffset > cursor.size || header.compressedGvmSize > cursor.size - dataOffset) {
                result.addProblem(
                    Severity.Error,
                    "BML entry \"${header.name}\" is invalid.",
                    "Texture data of size ${header.compressedGvmSize} at offset $dataOffset extends beyond end of file (${cursor.size} bytes).",
                )
                return result.failure()
            }

            cursor.seekStart(dataOffset)
            textureData = cursor.buffer(header.compressedGvmSize)

            // Advance past texture data, aligned to the archive stream boundary.
            dataOffset += alignUp(header.compressedGvmSize, alignment)
        } else {
            textureData = Buffer.withSize(0)
        }

        entries.add(BmlEntry(header.name, modelData, textureData, header.decompressedSize, header.decompressedTextureSize))
    }

    return result.success(entries)
}

private fun alignUp(value: Int, alignment: Int): Int {
    val mask = alignment - 1
    return (value + mask) and mask.inv()
}
