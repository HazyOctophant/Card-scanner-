package com.hazyoctophant.cardvault.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.hazyoctophant.cardvault.data.remote.CatalogCard
import com.hazyoctophant.cardvault.data.remote.Http
import com.hazyoctophant.cardvault.model.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Compares a photo of a card with card images: against the downloaded visual library (every card), and
 * against the official images of specific candidate printings fetched online.
 */
class CardImageMatcher(private val http: Http) {
    private val cache = LruCache<String, VisualSignature>(400)
    private val downloads = Semaphore(6)

    /** Pixels of [bitmap] scaled to the signature size. */
    fun pixelsOf(bitmap: Bitmap): IntArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, SIG_W, SIG_H, true)
        val px = IntArray(SIG_W * SIG_H)
        scaled.getPixels(px, 0, SIG_W, 0, 0, SIG_W, SIG_H)
        if (scaled !== bitmap) scaled.recycle()
        return px
    }

    fun photoSignature(pixels: IntArray, game: Game?) = PhotoSignature.of(pixels, SIG_W, SIG_H, game)

    /** Best matches for the photo across the given libraries. */
    suspend fun searchIndexes(photo: IntArray, indexes: List<CardIndex>, limitPerGame: Int = 20): List<IndexHit> =
        withContext(Dispatchers.Default) {
            indexes.flatMap { idx -> idx.search(photoSignature(photo, idx.game), limit = limitPerGame) }
                .sortedByDescending { it.similarity }
        }

    private suspend fun signatureOf(url: String, game: Game?): VisualSignature? {
        cache.get(url)?.let { return it }
        val bytes = downloads.withPermit { http.bytes(url) } ?: return null
        return withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= SIG_W * 2) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@withContext null
            val sig = VisualSignature.of(pixelsOf(bmp), SIG_W, SIG_H, game)
            bmp.recycle()
            sig.also { cache.put(url, it) }
        }
    }

    /**
     * Ranks [candidates] against the photo. [known] supplies image scores already computed (e.g. from the
     * library), so only the remaining images are downloaded; text pre-filters long lists to [maxImages].
     */
    suspend fun rank(
        photo: IntArray,
        candidates: List<CatalogCard>,
        hint: ScanHint,
        ocrText: String,
        known: Map<String, Float> = emptyMap(),
        maxImages: Int = 40,
    ): List<ScoredMatch<CatalogCard>> = coroutineScope {
        val shortlist = MatchScorer.rank(candidates, { it.card }, { known[it.card.id] }, hint, ocrText)
            .take(maxImages).map { it.item }
        // Computed up front: the comparisons below run in parallel.
        val sigs = withContext(Dispatchers.Default) {
            shortlist.map { it.card.game }.distinct().associateWith { photoSignature(photo, it) }
        }
        fun photoSig(game: Game) = sigs.getValue(game)
        val imageScores = shortlist.map { c ->
            async(Dispatchers.Default) {
                known[c.card.id]?.let { return@async c.card.id to it }
                val url = c.card.imageUrl ?: c.card.imageUrlLarge ?: return@async c.card.id to null
                c.card.id to signatureOf(url, c.card.game)?.let { photoSig(c.card.game).similarity(it) }
            }
        }.awaitAll().toMap()
        MatchScorer.rank(shortlist, { it.card }, { imageScores[it.card.id] }, hint, ocrText)
    }

    companion object {
        const val SIG_W = 126
        const val SIG_H = 176
    }
}
