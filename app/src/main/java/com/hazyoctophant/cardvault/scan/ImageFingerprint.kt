package com.hazyoctophant.cardvault.scan

import com.hazyoctophant.cardvault.model.Game
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** Rectangle in fractions of the card (0..1). */
data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        val FULL = Region(0f, 0f, 1f, 1f)

        /** Where the artwork sits on each game's card frame. */
        fun art(game: Game?): Region = when (game) {
            Game.MTG -> Region(0.08f, 0.11f, 0.92f, 0.55f)
            Game.POKEMON -> Region(0.08f, 0.10f, 0.92f, 0.48f)
            Game.ONE_PIECE -> Region(0.05f, 0.05f, 0.95f, 0.70f)
            null -> Region(0.08f, 0.10f, 0.92f, 0.55f)
        }
    }
}

/**
 * A compact visual signature of a card image, used to find the exact printing that was photographed
 * (same artwork, frame and colors) among many candidates with the same name.
 *
 * Built from plain ARGB pixels so it works identically for camera photos and downloaded card images:
 *  - a perceptual hash (DCT of a 32×32 grayscale), robust to blur and scaling;
 *  - a normalised 16×22 grayscale thumbnail compared by correlation, robust to brightness/contrast;
 *  - a chromaticity color histogram, robust to lighting intensity.
 */
class ImageFingerprint private constructor(
    val hash: Long,
    private val thumb: FloatArray,
    private val histogram: FloatArray,
) {
    /** 0 (unrelated) … 1 (identical). */
    fun similarity(other: ImageFingerprint): Float {
        val hamming = java.lang.Long.bitCount(hash xor other.hash)
        // Two unrelated images differ in ~32 of 64 bits, so scale against that rather than 64.
        val hashSim = (1f - hamming / 32f).coerceIn(0f, 1f)
        var ncc = 0f
        for (i in thumb.indices) ncc += thumb[i] * other.thumb[i]
        ncc = (ncc / thumb.size).coerceIn(0f, 1f)
        var inter = 0f
        for (i in histogram.indices) inter += minOf(histogram[i], other.histogram[i])
        // Histograms of any two cards overlap a lot; stretch the useful range.
        val histSim = ((inter - 0.4f) / 0.6f).coerceIn(0f, 1f)
        return 0.35f * hashSim + 0.4f * ncc + 0.25f * histSim
    }

    companion object {
        private const val HASH_SIZE = 32
        private const val THUMB_W = 16
        private const val THUMB_H = 22
        private const val CHROMA_BINS = 8

        fun of(pixels: IntArray, width: Int, height: Int, region: Region = Region.FULL): ImageFingerprint {
            val x0 = (region.left * width).toInt().coerceIn(0, width - 1)
            val y0 = (region.top * height).toInt().coerceIn(0, height - 1)
            val x1 = (region.right * width).toInt().coerceIn(x0 + 1, width)
            val y1 = (region.bottom * height).toInt().coerceIn(y0 + 1, height)

            val gray = resampleGray(pixels, width, x0, y0, x1, y1, HASH_SIZE, HASH_SIZE)
            val thumb = normalise(resampleGray(pixels, width, x0, y0, x1, y1, THUMB_W, THUMB_H))
            return ImageFingerprint(perceptualHash(gray), thumb, chromaHistogram(pixels, width, x0, y0, x1, y1))
        }

        private fun luminance(p: Int): Float {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            return 0.299f * r + 0.587f * g + 0.114f * b
        }

        /** Box-filter downsample of a sub-rectangle to [outW]×[outH] grayscale. */
        private fun resampleGray(px: IntArray, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int, outW: Int, outH: Int): FloatArray {
            val out = FloatArray(outW * outH)
            val w = x1 - x0
            val h = y1 - y0
            for (oy in 0 until outH) {
                val sy0 = y0 + oy * h / outH
                val sy1 = maxOf(sy0 + 1, y0 + (oy + 1) * h / outH)
                for (ox in 0 until outW) {
                    val sx0 = x0 + ox * w / outW
                    val sx1 = maxOf(sx0 + 1, x0 + (ox + 1) * w / outW)
                    var sum = 0f
                    var n = 0
                    for (y in sy0 until sy1) for (x in sx0 until sx1) {
                        sum += luminance(px[y * stride + x]); n++
                    }
                    out[oy * outW + ox] = sum / n
                }
            }
            return out
        }

        /** Zero mean, unit variance, so correlation ignores exposure and contrast. */
        private fun normalise(v: FloatArray): FloatArray {
            val mean = v.average().toFloat()
            var variance = 0f
            for (x in v) variance += (x - mean) * (x - mean)
            val sd = sqrt(variance / v.size).coerceAtLeast(1e-3f)
            return FloatArray(v.size) { (v[it] - mean) / sd }
        }

        private val cosTable = Array(8) { u -> FloatArray(HASH_SIZE) { x -> cos((2 * x + 1) * u * PI / (2 * HASH_SIZE)).toFloat() } }

        /** pHash: low-frequency DCT coefficients compared with their median. */
        private fun perceptualHash(gray: FloatArray): Long {
            val coeffs = FloatArray(64)
            for (v in 0 until 8) for (u in 0 until 8) {
                var sum = 0f
                for (y in 0 until HASH_SIZE) {
                    val cy = cosTable[v][y]
                    for (x in 0 until HASH_SIZE) sum += gray[y * HASH_SIZE + x] * cosTable[u][x] * cy
                }
                coeffs[v * 8 + u] = sum
            }
            val median = coeffs.copyOfRange(1, 64).sorted()[31] // skip the DC term
            var hash = 0L
            for (i in 0 until 64) if (coeffs[i] > median) hash = hash or (1L shl i)
            return hash
        }

        /** Histogram over (r, g) chromaticity — color, independent of brightness. */
        private fun chromaHistogram(px: IntArray, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int): FloatArray {
            val hist = FloatArray(CHROMA_BINS * CHROMA_BINS)
            var total = 0f
            val step = maxOf(1, (x1 - x0) / 64)
            for (y in y0 until y1 step step) for (x in x0 until x1 step step) {
                val p = px[y * stride + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val sum = r + g + b
                if (sum < 30) continue // too dark to have a reliable color
                val rb = (r * CHROMA_BINS / (sum + 1)).coerceAtMost(CHROMA_BINS - 1)
                val gb = (g * CHROMA_BINS / (sum + 1)).coerceAtMost(CHROMA_BINS - 1)
                hist[rb * CHROMA_BINS + gb] += 1f
                total += 1f
            }
            if (total > 0) for (i in hist.indices) hist[i] /= total
            return hist
        }
    }
}

/** Fingerprints of a whole card and of its artwork. */
data class CardSignature(val full: ImageFingerprint, val art: ImageFingerprint) {
    /** Artwork decides most of the match (it differs between printings); the frame/text area helps confirm. */
    fun similarity(other: CardSignature): Float = 0.6f * art.similarity(other.art) + 0.4f * full.similarity(other.full)

    companion object {
        fun of(pixels: IntArray, width: Int, height: Int, game: Game?) = CardSignature(
            full = ImageFingerprint.of(pixels, width, height),
            art = ImageFingerprint.of(pixels, width, height, Region.art(game)),
        )
    }
}
