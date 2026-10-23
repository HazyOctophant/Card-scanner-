package com.hazyoctophant.cardvault.scan

import com.hazyoctophant.cardvault.model.Game
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Rectangle in fractions of an image (0..1). */
data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    /** This region expressed inside [outer] (both in fractions of the same image). */
    fun within(outer: Region) = Region(
        outer.left + left * (outer.right - outer.left),
        outer.top + top * (outer.bottom - outer.top),
        outer.left + right * (outer.right - outer.left),
        outer.top + bottom * (outer.bottom - outer.top),
    )

    companion object {
        val FULL = Region(0f, 0f, 1f, 1f)

        /** Where the artwork sits on each game's card frame. */
        fun art(game: Game?): Region = when (game) {
            Game.MTG -> Region(0.08f, 0.11f, 0.92f, 0.55f)
            Game.POKEMON -> Region(0.08f, 0.10f, 0.92f, 0.48f)
            Game.ONE_PIECE -> Region(0.05f, 0.05f, 0.95f, 0.70f)
            null -> Region(0.08f, 0.10f, 0.92f, 0.55f)
        }

        /**
         * Slightly different crops of a photo, to tolerate a card that isn't perfectly lined up with the
         * frame: the matcher keeps whichever lines up best.
         */
        val photoVariants: List<Region> = listOf(
            FULL,
            Region(0.03f, 0.03f, 0.97f, 0.97f),
            Region(0.06f, 0.05f, 0.94f, 0.95f),
            Region(0.0f, 0.0f, 0.96f, 0.97f),
            Region(0.04f, 0.03f, 1.0f, 1.0f),
            Region(0.02f, 0.0f, 0.98f, 0.96f),
            Region(0.02f, 0.04f, 0.98f, 1.0f),
        )
    }
}

/**
 * A compact visual signature of a card (≈360 bytes), used to recognise the exact printing in a photo by
 * comparing it with signatures of every known card image.
 *
 * Every part is normalised so it survives the differences between a phone photo and a scan:
 *  - [art] / [full]: grayscale thumbnails of the artwork and the whole card, zero-mean/unit-variance
 *    (ignores exposure and contrast), compared by correlation;
 *  - [color]: a 6×4 grid of opponent colors over the artwork with the average removed per channel
 *    (ignores a color cast from indoor lighting);
 *  - [hash]: a 64-bit perceptual (DCT) hash of the artwork.
 */
class VisualSignature(
    val art: ByteArray,
    val full: ByteArray,
    val color: ByteArray,
    val hash: Long,
) {
    /** 0 (unrelated) … 1 (identical). */
    fun similarity(o: VisualSignature): Float {
        val hamming = java.lang.Long.bitCount(hash xor o.hash)
        val hashSim = (1f - hamming / 32f).coerceIn(0f, 1f) // unrelated images differ in ~32 bits
        return 0.40f * ncc(art, o.art) + 0.25f * ncc(full, o.full) + 0.20f * ncc(color, o.color) + 0.15f * hashSim
    }

    /** Cheaper first-pass score (no hash, no color) for scanning large indexes. */
    fun quickSimilarity(o: VisualSignature): Float = 0.6f * ncc(art, o.art) + 0.4f * ncc(full, o.full)

    companion object {
        const val ART_W = 16
        const val ART_H = 12
        const val FULL_W = 9
        const val FULL_H = 12
        const val COLOR_W = 6
        const val COLOR_H = 4
        const val ART_SIZE = ART_W * ART_H
        const val FULL_SIZE = FULL_W * FULL_H
        const val COLOR_SIZE = COLOR_W * COLOR_H * 2
        private const val HASH_SIZE = 32
        private const val SCALE = 31f // z-scores stored as bytes: z * 31, clamped to ±127

        /** Correlation of two z-score byte vectors, clamped to 0..1. */
        fun ncc(a: ByteArray, b: ByteArray, aOff: Int = 0, bOff: Int = 0, n: Int = a.size): Float {
            var sum = 0
            for (i in 0 until n) sum += a[aOff + i] * b[bOff + i]
            return (sum / (n * SCALE * SCALE)).coerceIn(0f, 1f)
        }

        /**
         * Signature of the [crop] of an ARGB image (fractions of the image), assuming the crop is the card.
         */
        fun of(pixels: IntArray, width: Int, height: Int, game: Game?, crop: Region = Region.FULL): VisualSignature {
            val artRegion = Region.art(game).within(crop)
            val artGray = resample(pixels, width, height, artRegion, HASH_SIZE, HASH_SIZE, ::luma)
            return VisualSignature(
                art = quantise(resample(pixels, width, height, artRegion, ART_W, ART_H, ::luma)),
                full = quantise(resample(pixels, width, height, crop, FULL_W, FULL_H, ::luma)),
                color = quantise(resample(pixels, width, height, artRegion, COLOR_W, COLOR_H, ::redGreen)) +
                    quantise(resample(pixels, width, height, artRegion, COLOR_W, COLOR_H, ::yellowBlue)),
                hash = perceptualHash(artGray),
            )
        }

        private fun luma(p: Int): Float =
            0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)

        private fun redGreen(p: Int): Float = (((p shr 16) and 0xFF) - ((p shr 8) and 0xFF)).toFloat()

        private fun yellowBlue(p: Int): Float =
            (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF)) / 2f - (p and 0xFF)

        /** Box-filter average of [r] into an [outW]×[outH] grid of channel values. */
        private fun resample(px: IntArray, w: Int, h: Int, r: Region, outW: Int, outH: Int, channel: (Int) -> Float): FloatArray {
            val x0 = (r.left * w).toInt().coerceIn(0, w - 1)
            val y0 = (r.top * h).toInt().coerceIn(0, h - 1)
            val x1 = (r.right * w).toInt().coerceIn(x0 + 1, w)
            val y1 = (r.bottom * h).toInt().coerceIn(y0 + 1, h)
            val rw = x1 - x0
            val rh = y1 - y0
            val out = FloatArray(outW * outH)
            for (oy in 0 until outH) {
                val sy0 = y0 + oy * rh / outH
                val sy1 = maxOf(sy0 + 1, y0 + (oy + 1) * rh / outH)
                for (ox in 0 until outW) {
                    val sx0 = x0 + ox * rw / outW
                    val sx1 = maxOf(sx0 + 1, x0 + (ox + 1) * rw / outW)
                    var sum = 0f
                    var n = 0
                    for (y in sy0 until sy1) for (x in sx0 until sx1) { sum += channel(px[y * w + x]); n++ }
                    out[oy * outW + ox] = sum / n
                }
            }
            return out
        }

        private fun quantise(v: FloatArray): ByteArray {
            val mean = v.average().toFloat()
            var variance = 0f
            for (x in v) variance += (x - mean) * (x - mean)
            val sd = sqrt(variance / v.size).coerceAtLeast(1f)
            return ByteArray(v.size) { (((v[it] - mean) / sd) * SCALE).roundToInt().coerceIn(-127, 127).toByte() }
        }

        private val cosTable = Array(8) { u -> FloatArray(HASH_SIZE) { x -> cos((2 * x + 1) * u * PI / (2 * HASH_SIZE)).toFloat() } }

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
            val median = coeffs.copyOfRange(1, 64).sorted()[31]
            var hash = 0L
            for (i in 0 until 64) if (coeffs[i] > median) hash = hash or (1L shl i)
            return hash
        }
    }
}

/** Signatures of one photo under several slightly different crops; matching keeps the best one. */
class PhotoSignature(val variants: List<VisualSignature>) {
    fun similarity(reference: VisualSignature): Float = variants.maxOf { it.similarity(reference) }
    fun quickSimilarity(reference: VisualSignature): Float = variants.first().quickSimilarity(reference)

    companion object {
        fun of(pixels: IntArray, width: Int, height: Int, game: Game?) =
            PhotoSignature(Region.photoVariants.map { VisualSignature.of(pixels, width, height, game, it) })
    }
}
