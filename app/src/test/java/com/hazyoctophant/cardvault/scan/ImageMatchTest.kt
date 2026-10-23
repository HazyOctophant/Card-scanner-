package com.hazyoctophant.cardvault.scan

import com.hazyoctophant.cardvault.data.db.CardEntity
import com.hazyoctophant.cardvault.model.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ImageMatchTest {
    private val w = 126
    private val h = 176

    /** A synthetic "card": shared frame/text area, plus artwork made of colored shapes unique to [seed]. */
    private fun card(seed: Int, sharedFrame: Boolean = true): IntArray {
        val rnd = Random(seed)
        val px = IntArray(w * h) { 0xFF2A2A2A.toInt() }
        val frameRnd = Random(if (sharedFrame) 1 else seed)
        repeat(30) { // text lines in the lower half
            val y = h / 2 + frameRnd.nextInt(h / 2 - 4)
            val x = frameRnd.nextInt(w - 40)
            for (dx in 0 until 30) px[y * w + x + dx] = 0xFFE0E0E0.toInt()
        }
        repeat(12) { // artwork blobs in the upper half
            val cx = rnd.nextInt(w); val cy = 20 + rnd.nextInt(h / 2 - 20); val r = 6 + rnd.nextInt(18)
            val color = (0xFF shl 24) or rnd.nextInt(0xFFFFFF)
            for (y in maxOf(0, cy - r) until minOf(h, cy + r)) for (x in maxOf(0, cx - r) until minOf(w, cx + r)) {
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) px[y * w + x] = color
            }
        }
        return px
    }

    /** Simulates a phone photo: brighter, noisy, slightly shifted. */
    private fun photo(src: IntArray): IntArray {
        val rnd = Random(99)
        return IntArray(w * h) { i ->
            val x = (i % w + 2).coerceAtMost(w - 1)
            val y = (i / w + 3).coerceAtMost(h - 1)
            val p = src[y * w + x]
            fun ch(shift: Int) = (((p shr shift) and 0xFF) * 1.15 + 12 + rnd.nextInt(-10, 10)).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    /** A photo where the card is a bit smaller than the frame and off-centre (background around it). */
    private fun misaligned(src: IntArray): IntArray {
        val out = IntArray(w * h) { 0xFF6B4F3A.toInt() } // wooden table
        val scale = 0.92f
        val ox = 6; val oy = 4
        for (y in 0 until h) for (x in 0 until w) {
            val sx = ((x - ox) / scale).toInt(); val sy = ((y - oy) / scale).toInt()
            if (sx in 0 until w && sy in 0 until h) out[y * w + x] = src[sy * w + sx]
        }
        return photo(out)
    }

    /** Warm indoor light: everything shifted toward orange. */
    private fun warm(src: IntArray) = IntArray(src.size) { i ->
        val p = src[i]
        val r = (((p shr 16) and 0xFF) * 1.1 + 20).toInt().coerceAtMost(255)
        val g = ((p shr 8) and 0xFF)
        val b = (((p) and 0xFF) * 0.75).toInt()
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun index(seeds: IntRange): CardIndex {
        val entries = seeds.map { IndexEntry("mtg:$it", "Card $it", "SET", "$it", VisualSignature.of(card(it), w, h, Game.MTG)) }
        val bytes = java.io.ByteArrayOutputStream().also { CardIndex.write(it, Game.MTG, "test", entries) }.toByteArray()
        return CardIndex.read(bytes.inputStream())
    }

    @Test
    fun photoMatchesItsOwnPrintingAboveLookAlikes() {
        val idx = index(10..400)
        val hits = idx.search(PhotoSignature.of(photo(card(17)), w, h, Game.MTG))
        assertEquals("mtg:17", hits.first().entry.id)
        assertTrue("best=${hits[0].similarity} second=${hits[1].similarity}", hits[0].similarity - hits[1].similarity > 0.15f)
    }

    @Test
    fun misalignedAndWarmLitPhotosStillMatch() {
        val idx = index(10..400)
        for (seed in listOf(23, 150, 333)) {
            val shot = warm(misaligned(card(seed)))
            assertEquals("mtg:$seed", idx.search(PhotoSignature.of(shot, w, h, Game.MTG)).first().entry.id)
        }
    }

    @Test
    fun indexRoundTripKeepsEntries() {
        val idx = index(1..5)
        assertEquals(5, idx.size)
        assertEquals("Card 3", idx.entry(2).name)
        assertTrue(idx.entry(2).signature.similarity(VisualSignature.of(card(3), w, h, Game.MTG)) > 0.99f)
    }

    @Test
    fun scorerPrefersNumberAndImageAgreement() {
        fun c(id: String, number: String) = CardEntity(id, Game.POKEMON, "Pikachu", "SV", "Set", number, text = "Thunder Shock")
        val hint = ScanHint(Game.POKEMON, pokemonNumber = "025", pokemonTotal = "198", nameCandidates = listOf("Pikachu"))
        val ranked = MatchScorer.rank(
            listOf(c("a", "25/198") to 0.6f, c("b", "26/198") to 0.62f, c("c", "25/102") to 0.9f),
            cardOf = { it.first }, imageScoreOf = { it.second }, hint = hint, ocrText = "Pikachu HP 60 Thunder Shock 025/198",
        )
        // "c" has the best picture match; "a" also matches the printed number. Picture wins clearly here.
        assertEquals("c", ranked.first().item.first.id)
        assertTrue(ranked.first { it.item.first.id == "a" }.numberMatch)
        assertTrue(ranked.first { it.item.first.id == "a" }.score > ranked.first { it.item.first.id == "b" }.score)
    }
}
