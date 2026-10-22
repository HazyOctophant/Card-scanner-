package com.hazyoctophant.cardvault.data.remote

import com.hazyoctophant.cardvault.data.db.CardEntity
import com.hazyoctophant.cardvault.data.db.PriceEntity
import com.hazyoctophant.cardvault.model.Finish
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.model.PriceSource
import com.hazyoctophant.cardvault.scan.ScanHint
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/**
 * Pokémon via the Pokémon TCG API (https://docs.pokemontcg.io).
 * Prices: TCGplayer (USD, per printing) and Cardmarket (EUR).
 * Works without a key; a free key from https://dev.pokemontcg.io raises the rate limit.
 */
class PokemonTcgCatalog(
    private val http: Http,
    private val apiKey: () -> String?,
) : CardCatalog {
    override val game = Game.POKEMON
    private val base = "https://api.pokemontcg.io/v2"

    private val headers: Map<String, String>
        get() = apiKey()?.takeIf { it.isNotBlank() }?.let { mapOf("X-Api-Key" to it.trim()) } ?: emptyMap()

    override suspend fun search(query: String): List<CatalogCard> {
        val name = query.trim().replace("\"", "")
        if (name.isEmpty()) return emptyList()
        val q = if (name.contains(' ')) "name:\"$name\"" else "name:$name*"
        return query(q, pageSize = 60)
    }

    override suspend fun identify(hint: ScanHint): List<CatalogCard> {
        val number = hint.pokemonNumber
        val total = hint.pokemonTotal
        if (number != null) {
            val numberClause = "number:${normalizeNumber(number)}"
            val totalClause = total?.toIntOrNull()?.let { " set.printedTotal:$it" }.orEmpty()
            val byNumber = query(numberClause + totalClause, pageSize = 50)
            if (byNumber.isNotEmpty()) {
                // Several sets share a size; prefer ones whose name matches what we read.
                val ranked = byNumber.sortedByDescending { c ->
                    hint.nameCandidates.maxOfOrNull { nameScore(it, c.card.name) } ?: 0
                }
                val best = ranked.first()
                val bestScore = hint.nameCandidates.maxOfOrNull { nameScore(it, best.card.name) } ?: 0
                if (bestScore > 0 || hint.nameCandidates.isEmpty()) return ranked.take(20)
            }
        }
        for (name in hint.nameCandidates) {
            val results = search(name)
            if (results.isNotEmpty()) return results.take(40)
        }
        return emptyList()
    }

    override suspend fun refresh(cards: List<CardEntity>): List<CatalogCard> {
        val out = mutableListOf<CatalogCard>()
        cards.chunked(25).forEachIndexed { i, chunk ->
            if (i > 0) delay(250)
            val q = chunk.joinToString(" OR ", "(", ")") { "id:\"${it.id.removePrefix("pkm:")}\"" }
            out += query(q, pageSize = 50)
        }
        return out
    }

    private suspend fun query(q: String, pageSize: Int): List<CatalogCard> {
        val url = "$base/cards?q=${enc(q)}&pageSize=$pageSize&orderBy=-set.releaseDate"
        return http.get(url, headers).obj()?.arr("data")?.mapNotNull { it.obj()?.let(::parse) }.orEmpty()
    }

    private fun parse(o: JsonObject): CatalogCard {
        val rawId = o.str("id") ?: ""
        val id = "pkm:$rawId"
        val set = o.obj("set")
        val subtypes = o.arr("subtypes").strings()
        val text = buildList {
            o.arr("rules").strings().forEach { add(it) }
            o.arr("abilities")?.mapNotNull { it.obj() }?.forEach { add("Ability — ${it.str("name")}: ${it.str("text").orEmpty()}") }
            o.arr("attacks")?.mapNotNull { it.obj() }?.forEach { a ->
                val cost = a.arr("cost").strings().joinToString("") { e -> "[${e.take(1)}]" }
                add("$cost ${a.str("name")} ${a.str("damage").orEmpty()}".trim() + (a.str("text")?.let { "\n$it" } ?: ""))
            }
        }.joinToString("\n\n")
        val legal = o.obj("legalities")?.entries
            ?.filter { (it.value as? JsonPrimitive)?.content.equals("Legal", true) }
            ?.joinToString(",") { it.key.lowercase() }.orEmpty()
        val tcg = o.obj("tcgplayer")
        val images = o.obj("images")

        val card = CardEntity(
            id = id,
            game = Game.POKEMON,
            name = o.str("name") ?: "Unknown",
            setCode = set?.str("ptcgoCode") ?: set?.str("id")?.uppercase() ?: "",
            setName = set?.str("name") ?: "",
            number = o.str("number")?.let { n -> set?.str("printedTotal")?.let { "$n/$it" } ?: n } ?: "",
            rarity = o.str("rarity") ?: "",
            typeLine = o.str("supertype") ?: "",
            subtypes = subtypes.joinToString(","),
            colors = o.arr("types").strings().joinToString(","),
            power = o.str("hp")?.let { "$it HP" },
            evolvesFrom = o.str("evolvesFrom"),
            text = text,
            legalities = legal + (o.str("regulationMark")?.let { if (legal.isEmpty()) "mark-$it" else ",mark-$it" } ?: ""),
            imageUrl = images?.str("small"),
            imageUrlLarge = images?.str("large") ?: images?.str("small"),
            marketUrl = tcg?.str("url") ?: o.obj("cardmarket")?.str("url"),
            releasedAt = set?.str("releaseDate")?.replace('/', '-'),
        )

        val prices = buildList {
            tcg?.obj("prices")?.forEach { (variant, value) ->
                val v = value.obj() ?: return@forEach
                val finish = when (variant) {
                    "normal" -> Finish.NORMAL
                    "holofoil", "unlimitedHolofoil" -> Finish.HOLO
                    "reverseHolofoil" -> Finish.REVERSE_HOLO
                    "1stEditionHolofoil", "1stEditionNormal", "1stEdition" -> Finish.FIRST_EDITION
                    else -> Finish.NORMAL
                }
                val market = v.num("market") ?: v.num("mid") ?: return@forEach
                add(PriceEntity(id, PriceSource.TCGPLAYER, finish, "USD", market, low = v.num("low"), high = v.num("high")))
            }
            o.obj("cardmarket")?.obj("prices")?.let { cm ->
                (cm.num("trendPrice") ?: cm.num("averageSellPrice"))?.let {
                    add(PriceEntity(id, PriceSource.CARDMARKET, Finish.NORMAL, "EUR", it, low = cm.num("lowPrice")))
                }
                cm.num("reverseHoloTrend")?.takeIf { it > 0 }?.let {
                    add(PriceEntity(id, PriceSource.CARDMARKET, Finish.REVERSE_HOLO, "EUR", it, low = cm.num("reverseHoloLow")))
                }
            }
        }.distinctBy { it.source to it.finish }
        // Holo-only cards (most rares) have no "normal" TCGplayer price; Cardmarket's single trend
        // price is for the standard printing, so file it under the same finish TCGplayer uses.
        val tcgFinishes = prices.filter { it.source == PriceSource.TCGPLAYER }.map { it.finish }.toSet()
        val aligned = if (Finish.NORMAL !in tcgFinishes && Finish.HOLO in tcgFinishes) {
            prices.map { if (it.source == PriceSource.CARDMARKET && it.finish == Finish.NORMAL) it.copy(finish = Finish.HOLO) else it }
        } else prices
        return CatalogCard(card, aligned)
    }

    companion object {
        /** "025" -> "25", but keep alphanumeric numbers such as "TG05" or "SV049" intact. */
        fun normalizeNumber(n: String): String = if (n.all { it.isDigit() }) n.trimStart('0').ifEmpty { "0" } else n

        /** Crude similarity between OCR text and a card name: number of shared words. */
        fun nameScore(ocr: String, name: String): Int {
            val a = ocr.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.toSet()
            val b = name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.toSet()
            return a.intersect(b).size
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
