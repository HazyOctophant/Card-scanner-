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
 * Compares a photo of a card with the official images of candidate printings and ranks them,
 * so the exact printing (artwork, frame, set) comes first.
 */
class CardImageMatcher(private val http: Http) {
    private val cache = LruCache<String, CardSignature>(400)
    private val downloads = Semaphore(6)

    fun signatureOf(bitmap: Bitmap, game: Game?): CardSignature {
        val scaled = Bitmap.createScaledBitmap(bitmap, SIG_W, SIG_H, true)
        val px = IntArray(SIG_W * SIG_H)
        scaled.getPixels(px, 0, SIG_W, 0, 0, SIG_W, SIG_H)
        if (scaled !== bitmap) scaled.recycle()
        return CardSignature.of(px, SIG_W, SIG_H, game)
    }

    private suspend fun signatureOf(url: String, game: Game?): CardSignature? {
        cache.get(url)?.let { return it }
        val bytes = downloads.withPermit { http.bytes(url) } ?: return null
        return withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= SIG_W * 2) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@withContext null
            signatureOf(bmp, game).also { bmp.recycle(); cache.put(url, it) }
        }
    }

    /**
     * Ranks [candidates] against [photo]. The text score pre-filters long candidate lists so only the
     * most plausible [maxImages] images are downloaded.
     */
    suspend fun rank(
        photo: Bitmap,
        candidates: List<CatalogCard>,
        hint: ScanHint,
        ocrText: String,
        maxImages: Int = 48,
    ): List<ScoredMatch<CatalogCard>> = coroutineScope {
        val game = hint.game ?: candidates.firstOrNull()?.card?.game
        val photoSig = withContext(Dispatchers.Default) { signatureOf(photo, game) }
        val shortlist = MatchScorer.rank(candidates, { it.card }, { null }, hint, ocrText).take(maxImages).map { it.item }
        val imageScores = shortlist.map { c ->
            async {
                val url = c.card.imageUrl ?: c.card.imageUrlLarge ?: return@async c.card.id to null
                c.card.id to signatureOf(url, c.card.game)?.let { photoSig.similarity(it) }
            }
        }.awaitAll().toMap()
        MatchScorer.rank(shortlist, { it.card }, { imageScores[it.card.id] }, hint, ocrText)
    }

    companion object {
        const val SIG_W = 126
        const val SIG_H = 176
    }
}
