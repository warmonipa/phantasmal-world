package world.phantasmal.web.assetsGeneration

import java.io.File
import kotlin.math.abs

/**
 * Synthesizes Sinow Red (Ultimate rare Sinow Gold). PSO computes this recolor at runtime — there
 * is no Red texture file in the game data. Visually, Sinow Red is Sinow Gold with the same
 * geometry and shading but a gold→red hue, so we produce it by rotating the hue of every DXT1
 * endpoint colour of SinowGold.xvm toward red (default −45°, gold ≈ 45° → red ≈ 0°).
 *
 * Only the two RGB565 endpoints of each 8-byte block change; the 2-bit indices are kept, so the
 * XVM stays byte-compatible and no DXT re-encoding is needed. The model is Sinow Gold's own
 * geometry (its UV matches the texture).
 *
 * Usage: SynthesizeSinowRed <npcs-assets-dir> [hueDeltaDegrees]
 */

private const val XVRT = 0x54525658

private fun i32(b: ByteArray, p: Int): Int =
    (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
        ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)

private fun u16(b: ByteArray, p: Int): Int =
    (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)

private fun putU16(b: ByteArray, p: Int, v: Int) {
    b[p] = (v and 0xFF).toByte(); b[p + 1] = ((v ushr 8) and 0xFF).toByte()
}

private fun putI32(b: ByteArray, p: Int, v: Int) {
    b[p] = (v and 0xFF).toByte(); b[p + 1] = ((v ushr 8) and 0xFF).toByte()
    b[p + 2] = ((v ushr 16) and 0xFF).toByte(); b[p + 3] = ((v ushr 24) and 0xFF).toByte()
}

/** Absolute (dxtDataOffset, dxtByteSize) of every XVRT texture, in file order. */
private fun xvrtRegions(bytes: ByteArray): List<Pair<Int, Int>> {
    val out = mutableListOf<Pair<Int, Int>>()
    var pos = 0
    while (pos + 8 <= bytes.size) {
        val type = i32(bytes, pos)
        val size = i32(bytes, pos + 4)
        val dataStart = pos + 8
        if (size < 0 || dataStart + size > bytes.size) break
        if (type == XVRT) out.add(Pair(dataStart + 56, i32(bytes, dataStart + 16)))
        pos = dataStart + size
    }
    return out
}

private fun rotateHue565(c: Int, deltaDeg: Double, satScale: Double): Int {
    val r = ((c ushr 11) and 0x1F) / 31.0
    val g = ((c ushr 5) and 0x3F) / 63.0
    val b = (c and 0x1F) / 31.0

    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val v = max
    val d = max - min
    val s = (if (max <= 0.0) 0.0 else d / max) * satScale
    var h = when {
        d == 0.0 -> 0.0
        max == r -> 60 * (((g - b) / d) % 6)
        max == g -> 60 * ((b - r) / d + 2)
        else -> 60 * ((r - g) / d + 4)
    }
    if (h < 0) h += 360.0
    h = (h + deltaDeg) % 360.0
    if (h < 0) h += 360.0

    // HSV -> RGB
    val c2 = v * s
    val x = c2 * (1 - abs((h / 60.0) % 2 - 1))
    val m = v - c2
    val (rr, gg, bb) = when ((h / 60.0).toInt()) {
        0 -> Triple(c2, x, 0.0)
        1 -> Triple(x, c2, 0.0)
        2 -> Triple(0.0, c2, x)
        3 -> Triple(0.0, x, c2)
        4 -> Triple(x, 0.0, c2)
        else -> Triple(c2, 0.0, x)
    }
    val ri = (((rr + m) * 31).toInt()).coerceIn(0, 31)
    val gi = (((gg + m) * 63).toInt()).coerceIn(0, 63)
    val bi = (((bb + m) * 31).toInt()).coerceIn(0, 31)
    return (ri shl 11) or (gi shl 5) or bi
}

private fun swap01(idx: Int): Int {
    var out = 0
    for (j in 0 until 16) {
        val code = (idx ushr (2 * j)) and 0b11
        val nc = when (code) { 0 -> 1; 1 -> 0; else -> code }
        out = out or (nc shl (2 * j))
    }
    return out
}

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "Usage: SynthesizeSinowRed <npcs-assets-dir> [hueDeltaDegrees]" }
    val dir = File(args[0])
    val hueDelta = args.getOrNull(1)?.toDoubleOrNull() ?: -45.0
    val satScale = args.getOrNull(2)?.toDoubleOrNull() ?: 1.0
    println("Hue delta: $hueDelta°, saturation scale: $satScale")

    val gold = File(dir, "SinowGold.xvm").readBytes()
    val red = gold.copyOf()

    var flips = 0
    for ((ob, sz) in xvrtRegions(gold)) {
        for (k in 0 until sz / 8) {
            val base = ob + k * 8
            val c0 = u16(gold, base); val c1 = u16(gold, base + 2)
            var r0 = rotateHue565(c0, hueDelta, satScale)
            var r1 = rotateHue565(c1, hueDelta, satScale)
            val mode = c0 > c1
            if (r0 != r1 && (r0 > r1) != mode) {
                flips++
                val t = r0; r0 = r1; r1 = t
                val idx = i32(red, base + 4)
                putI32(red, base + 4, if (mode) idx xor 0x55555555.toInt() else swap01(idx))
            }
            putU16(red, base, r0); putU16(red, base + 2, r1)
        }
    }
    println("Endpoint-order flips fixed: $flips")

    File(dir, "SinowGold.ult.xvm").writeBytes(red)
    println("Wrote SinowGold.ult.xvm (${red.size} bytes)")

    // Sinow Red reuses Sinow Gold's geometry (its UV matches the recoloured texture).
    File(dir, "SinowGold.ult.nj").writeBytes(File(dir, "SinowGold.nj").readBytes())
    println("Wrote SinowGold.ult.nj (copied from SinowGold.nj)")
}
