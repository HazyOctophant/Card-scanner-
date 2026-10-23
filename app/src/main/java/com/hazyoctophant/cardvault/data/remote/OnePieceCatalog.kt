package com.hazyoctophant.cardvault.data.remote

import com.hazyoctophant.cardvault.data.db.CardEntity
import com.hazyoctophant.cardvault.data.db.PriceEntity
import com.hazyoctophant.cardvault.model.Finish
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.model.PriceSource
import com.hazyoctophant.cardvault.scan.CardTextParser
import com.hazyoctophant.cardvault.scan.ScanHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * One Piece Card Game via OPTCG API (https://optcgapi.com), which publishes TCGplayer market prices.
 * Every alternate art / parallel of a card id is a separate entry (card_image_id "OP01-001_p1").
 */
class OnePieceCatalog(
    private val http: Http,
    private val cacheDir: File,
) : CardCatalog {
    override val game = Game.ONE_PIECE
    private val base = "https://optcgapi.com/api"

    private val allCardsLock = Mutex()
    private var allCards: List<CatalogCard>? = null

    override suspend fun search(query: String): List<CatalogCard> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        CardTextParser.onePieceId(q)?.let { id -> return byId(id) }
        val words = q.lowercase().split(Regex("\\s+"))
        return loadAllCards()
            .filter { c -> words.all { w -> c.card.name.lowercase().contains(w) || c.card.subtypes.lowercase().contains(w) } }
            .sortedWith(compareBy({ !it.card.name.equals(q, true) }, { it.card.number }))
            .take(80)
    }

    override suspend fun identify(hint: ScanHint): List<CatalogCard> {
        hint.onePieceId?.let { id ->
            val found = byId(id)
            if (found.isNotEmpty()) return found
        }
        for (name in hint.nameCandidates) {
            val found = search(name)
            if (found.isNotEmpty()) return found.take(40)
        }
        return emptyList()
    }

    override suspend fun refresh(cards: List<CardEntity>): List<CatalogCard> = byIds(cards.map { it.id })

    override suspend fun byIds(ids: List<String>): List<CatalogCard> {
        // "op:OP01-001_p1" -> card number "OP01-001"; one request returns every art of that number.
        val numbers = ids.map { it.removePrefix("op:").substringBefore('_') }.distinct()
        val out = mutableListOf<CatalogCard>()
        numbers.forEachIndexed { i, number ->
            if (i > 0) delay(150)
            out += byId(number)
        }
        val wanted = ids.toSet()
        return out.filter { it.card.id in wanted }
    }

    /** All printings of one card number, e.g. "OP01-001". */
    private suspend fun byId(cardSetId: String): List<CatalogCard> {
        val endpoints = when {
            cardSetId.startsWith("ST") -> listOf("decks", "sets", "promos")
            cardSetId.startsWith("P-") -> listOf("promos", "sets", "decks")
            else -> listOf("sets", "decks", "promos")
        }
        for (endpoint in endpoints) {
            val result = runCatching { http.get("$base/$endpoint/card/$cardSetId/") }.getOrNull()
            val list = (result as? JsonArray)?.mapNotNull { it.obj()?.let(::parse) }
                ?: result.obj()?.let { listOf(parse(it)) }
            if (!list.isNullOrEmpty()) return list.distinctBy { it.card.id }
        }
        return emptyList()
    }

    /** The full card list, cached on disk for a day so name searches work offline-ish and fast. */
    private suspend fun loadAllCards(): List<CatalogCard> = allCardsLock.withLock {
        allCards ?: fetchAllCards().also { if (it.isNotEmpty()) allCards = it }
    }

    private suspend fun fetchAllCards(): List<CatalogCard> = withContext(Dispatchers.IO) {
        val cache = File(cacheDir, "optcg_all.json")
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < 24 * 3600_000L
        val combined: JsonArray = if (fresh) {
            http.json.parseToJsonElement(cache.readText()) as JsonArray
        } else {
            val parts = listOf("allSetCards", "allSTCards", "allPromoCards").mapNotNull { endpoint ->
                runCatching { http.get("$base/$endpoint/") as? JsonArray }.getOrNull()
            }
            JsonArray(parts.flatten()).also { if (it.isNotEmpty()) cache.writeText(it.toString()) }
        }
        combined.mapNotNull { it.obj()?.let(::parse) }.distinctBy { it.card.id }
    }

    private fun parse(o: JsonObject): CatalogCard {
        val number = o.str("card_set_id") ?: ""
        val imageId = o.str("card_image_id") ?: number
        val id = "op:$imageId"
        val isParallel = imageId.contains("_")
        val colors = (o.str("card_color") ?: "").split(' ', '/').map { it.trim() }.filter { it.isNotEmpty() }
        val type = o.str("card_type") ?: ""
        val power = o.str("card_power")
        val life = o.str("life")
        val card = CardEntity(
            id = id,
            game = Game.ONE_PIECE,
            name = o.str("card_name") ?: "Unknown",
            setCode = o.str("set_id") ?: number.substringBefore('-'),
            setName = o.str("set_name") ?: "",
            number = number,
            rarity = (o.str("rarity") ?: "") + if (isParallel) " (Parallel)" else "",
            typeLine = type.uppercase(),
            subtypes = (o.str("sub_types") ?: "").split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","),
            colors = colors.joinToString(","),
            cost = if (type.equals("Leader", true)) null else o.num("card_cost"),
            power = listOfNotNull(power?.let { "$it power" }, life?.let { "Life $it" }).joinToString(" · ").ifEmpty { null },
            counter = o.num("counter_amount")?.toInt(),
            text = o.str("card_text") ?: "",
            legalities = "standard",
            imageUrl = o.str("card_image"),
            imageUrlLarge = o.str("card_image"),
            marketUrl = "https://www.tcgplayer.com/search/one-piece-card-game/product?q=${number}",
            releasedAt = o.str("date_scraped"),
        )
        val prices = listOfNotNull(
            (o.num("market_price") ?: o.num("inventory_price"))?.let {
                PriceEntity(id, PriceSource.TCGPLAYER, Finish.NORMAL, "USD", it, low = o.num("inventory_price"))
            },
        )
        return CatalogCard(card, prices)
    }
}
