package com.hazyoctophant.cardvault.data.mtgdb

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.hazyoctophant.cardvault.data.SettingsStore
import com.hazyoctophant.cardvault.data.db.AppDatabase
import com.hazyoctophant.cardvault.data.db.CardEntity
import com.hazyoctophant.cardvault.data.remote.CatalogCard
import com.hazyoctophant.cardvault.data.remote.Http
import com.hazyoctophant.cardvault.data.remote.ScryfallCatalog
import com.hazyoctophant.cardvault.data.remote.arr
import com.hazyoctophant.cardvault.data.remote.num
import com.hazyoctophant.cardvault.data.remote.obj
import com.hazyoctophant.cardvault.data.remote.str
import com.hazyoctophant.cardvault.data.remote.strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeToSequence
import java.io.FilterInputStream
import java.io.InputStream

data class ImportResult(val cards: Int, val version: String, val skipped: Boolean)

/**
 * The complete Magic card pool, downloaded from Scryfall's daily "Oracle Cards" bulk file
 * (https://scryfall.com/docs/api/bulk-data): one entry per unique card, ~35,000 cards with prices.
 * Once downloaded it powers offline search, scanner fallback and deck suggestions beyond your collection.
 */
class MtgDatabase(
    private val db: AppDatabase,
    private val http: Http,
    private val scryfall: ScryfallCatalog,
    private val settings: SettingsStore,
) {
    private val dao = db.mtgDb()

    fun observeCount(): Flow<Int> = dao.observeCount()
    suspend fun count(): Int = dao.count()

    fun observe(filter: MtgFilter): Flow<List<MtgDbCard>> = dao.observe(filter.toQuery())

    /** Online equivalent of [observe], for when the database hasn't been downloaded. */
    suspend fun searchOnline(filter: MtgFilter): List<CatalogCard> =
        scryfall.searchQuery(filter.toScryfallQuery(), filter.sort.scryfallOrder, filter.sort.scryfallDir)

    /** Cards whose name matches text read by the scanner, best match first. */
    suspend fun findByName(name: String, limit: Int = 10): List<MtgDbCard> {
        val clean = name.trim()
        if (clean.length < 3) return emptyList()
        dao.byName(clean)?.let { return listOf(it) }
        return dao.nameContains(clean, limit)
    }

    /**
     * Deck-building candidates: cards legal in [format] whose color identity fits [allowedColors]
     * (null = any), most-played first.
     */
    suspend fun candidates(allowedColors: Set<String>?, format: String?, limit: Int = 2500): List<CardEntity> {
        val filter = MtgFilter(
            colors = allowedColors?.ifEmpty { setOf("C") } ?: emptySet(),
            colorMode = ColorMode.WITHIN,
            format = format?.takeIf { it in MtgFilter.FORMATS },
            sort = MtgSort.POPULAR,
        )
        return dao.query(filter.toQuery(limit)).map { it.toCardEntity() }
    }

    suspend fun get(id: String): MtgDbCard? = dao.query(SimpleSQLiteQuery("SELECT * FROM mtg_db WHERE id = ?", arrayOf(id))).firstOrNull()

    suspend fun clear() {
        dao.clear()
        settings.update { it.copy(mtgDbVersion = "", mtgDbImportedAt = 0L) }
    }

    /**
     * Downloads and imports the bulk file. Streams the ~150 MB JSON so memory stays small, and keeps the
     * previous data usable until the new import finishes. Skips the download if nothing changed.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun import(force: Boolean, onProgress: suspend (fraction: Float, cards: Int) -> Unit): ImportResult =
        withContext(Dispatchers.IO) {
            val info = scryfall.bulkInfo("oracle_cards") ?: error("Scryfall bulk data is unavailable")
            val version = info.str("updated_at") ?: ""
            val uri = info.str("download_uri") ?: error("Scryfall bulk data has no download link")
            val size = info.num("size")?.toLong() ?: 0L
            if (!force && version == settings.current.mtgDbVersion && dao.count() > 0) {
                return@withContext ImportResult(dao.count(), version, skipped = true)
            }

            val startedAt = System.currentTimeMillis()
            var imported = 0
            http.download(uri).use { response ->
                val body = response.body ?: error("Empty download")
                val counting = CountingInputStream(body.byteStream())
                val batch = ArrayList<MtgDbCard>(BATCH)
                for (element in http.json.decodeToSequence(counting, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)) {
                    val o = element.obj() ?: continue
                    if (o.str("layout") in SKIPPED_LAYOUTS) continue
                    if (o.arr("games")?.strings()?.contains("paper") == false) continue // digital-only
                    val card = scryfall.parseCard(o)
                    val manaCost = o.str("mana_cost")
                        ?: o.arr("card_faces")?.mapNotNull { it.obj()?.str("mana_cost") }?.joinToString(" // ").orEmpty()
                    batch += MtgDbCard.from(card, manaCost, o.num("edhrec_rank")?.toInt(), startedAt)
                    if (batch.size == BATCH) {
                        dao.upsert(batch)
                        imported += batch.size
                        batch.clear()
                        onProgress(if (size > 0) (counting.count.toFloat() / size).coerceIn(0f, 0.99f) else 0f, imported)
                    }
                }
                if (batch.isNotEmpty()) {
                    dao.upsert(batch)
                    imported += batch.size
                }
            }
            check(imported > 1000) { "Download looked incomplete ($imported cards)" }
            db.withTransaction { dao.deleteOlderThan(startedAt) }
            settings.update { it.copy(mtgDbVersion = version, mtgDbImportedAt = System.currentTimeMillis()) }
            onProgress(1f, imported)
            ImportResult(imported, version, skipped = false)
        }

    private fun MtgFilter.toQuery(limit: Int = 300): SimpleSQLiteQuery {
        val (sql, args) = toSql(limit)
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    companion object {
        private const val BATCH = 500
        /** Non-playable objects in the bulk file. */
        private val SKIPPED_LAYOUTS = setOf(
            "token", "double_faced_token", "emblem", "art_series", "vanguard", "scheme", "planar", "augment", "host",
        )
    }
}
