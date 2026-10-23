package com.hazyoctophant.cardvault.data.remote

import com.hazyoctophant.cardvault.data.db.CardEntity
import com.hazyoctophant.cardvault.data.db.PriceEntity
import com.hazyoctophant.cardvault.model.Finish
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.model.PriceSource
import com.hazyoctophant.cardvault.scan.ScanHint
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.URLEncoder

/**
 * Pokémon via TCGdex (https://tcgdex.dev): free, no key, card images, and TCGplayer (USD) +
 * Cardmarket (EUR) prices. Replaces the Pokémon TCG API, which has been deprecated.
 */
class TcgdexCatalog(private val http: Http) : CardCatalog {
    override val game = Game.POKEMON
    private val base = "https://api.tcgdex.net/v2/en"
    private val parallel = Semaphore(8)

    override suspend fun search(query: String): List<CatalogCard> {
        val name = query.trim()
        if (name.isEmpty()) return emptyList()
        val briefs = http.get("$base/cards?name=${enc(name)}&pagination:itemsPerPage=40").arr().orEmpty()
            .mapNotNull { it.obj()?.str("id") }
        return byIds(briefs.map { "pkm:$it" })
    }

    override suspend fun identify(hint: ScanHint): List<CatalogCard> {
        for (name in hint.nameCandidates) {
            val found = search(name)
            if (found.isEmpty()) continue
            val number = hint.pokemonNumber?.let(::digits)
            // Put printings whose collector number matches what was read first.
            return found.sortedByDescending { c -> number != null && digits(c.card.number.substringBefore('/')) == number }
        }
        return emptyList()
    }

    override suspend fun refresh(cards: List<CardEntity>): List<CatalogCard> = byIds(cards.map { it.id })

    override suspend fun byIds(ids: List<String>): List<CatalogCard> = coroutineScope {
        ids.distinct().map { id ->
            async {
                parallel.withPermit {
                    runCatching { http.get("$base/cards/${enc(id.removePrefix("pkm:"))}").obj()?.let(::parse) }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun parse(o: JsonObject): CatalogCard {
        val id = "pkm:" + (o.str("id") ?: "")
        val set = o.obj("set")
        val official = set?.obj("cardCount")?.str("official")
        val localId = o.str("localId") ?: ""
        val category = o.str("category") ?: ""
        val subtypes = listOfNotNull(
            o.str("stage")?.replace(Regex("Stage(\\d)"), "Stage $1"),
            o.str("suffix"),
            o.str("trainerType")?.let { if (it == "Tool") "Pokémon Tool" else it },
            o.str("energyType")?.let { if (it == "Normal") "Basic" else it },
        )
        val text = buildList {
            o.str("effect")?.let(::add)
            o.arr("abilities")?.mapNotNull { it.obj() }?.forEach { add("${it.str("type") ?: "Ability"} — ${it.str("name")}: ${it.str("effect").orEmpty()}") }
            o.arr("attacks")?.mapNotNull { it.obj() }?.forEach { a ->
                val cost = a.arr("cost").strings().joinToString("") { e -> "[${e.take(1)}]" }
                add("$cost ${a.str("name")} ${a.str("damage").orEmpty()}".trim() + (a.str("effect")?.let { "\n$it" } ?: ""))
            }
        }.joinToString("\n\n")
        val legal = o.obj("legal")?.entries
            ?.filter { (it.value as? JsonPrimitive)?.booleanOrNull == true }
            ?.joinToString(",") { it.key.lowercase() }.orEmpty()
        val image = o.str("image")

        val card = CardEntity(
            id = id,
            game = Game.POKEMON,
            name = o.str("name") ?: "Unknown",
            setCode = (set?.str("id") ?: "").uppercase(),
            setName = set?.str("name") ?: "",
            number = if (official != null) "$localId/$official" else localId,
            rarity = o.str("rarity") ?: "",
            typeLine = if (category.startsWith("Pok")) "Pokémon" else category,
            subtypes = subtypes.joinToString(","),
            colors = o.arr("types").strings().joinToString(","),
            power = o.str("hp")?.let { "$it HP" },
            evolvesFrom = o.str("evolveFrom"),
            text = text,
            legalities = legal + (o.str("regulationMark")?.let { if (legal.isEmpty()) "mark-$it" else ",mark-$it" } ?: ""),
            imageUrl = image?.let { "$it/low.png" },
            imageUrlLarge = image?.let { "$it/high.png" },
            marketUrl = "https://www.tcgplayer.com/search/pokemon/product?q=" + enc(o.str("name") ?: ""),
        )

        val pricing = o.obj("pricing")
        val prices = buildList {
            pricing?.obj("tcgplayer")?.forEach { (variant, value) ->
                val v = value.obj() ?: return@forEach
                val market = v.num("marketPrice") ?: v.num("midPrice") ?: return@forEach
                val finish = when {
                    variant.contains("reverse") -> Finish.REVERSE_HOLO
                    variant.contains("1st") -> Finish.FIRST_EDITION
                    variant.contains("holo") -> Finish.HOLO
                    else -> Finish.NORMAL
                }
                add(PriceEntity(id, PriceSource.TCGPLAYER, finish, "USD", market, low = v.num("lowPrice"), high = v.num("highPrice")))
            }
            pricing?.obj("cardmarket")?.let { cm ->
                (cm.num("trend") ?: cm.num("avg"))?.takeIf { it > 0 }?.let {
                    add(PriceEntity(id, PriceSource.CARDMARKET, Finish.NORMAL, "EUR", it, low = cm.num("low")))
                }
                // Cardmarket's "-holo" columns are its reverse-holo prices.
                (cm.num("trend-holo") ?: cm.num("avg-holo"))?.takeIf { it > 0 }?.let {
                    add(PriceEntity(id, PriceSource.CARDMARKET, Finish.REVERSE_HOLO, "EUR", it, low = cm.num("low-holo")))
                }
            }
        }.distinctBy { it.source to it.finish }
        // Holo-only cards have no "normal" TCGplayer price; file Cardmarket's base price under holo to match.
        val tcgFinishes = prices.filter { it.source == PriceSource.TCGPLAYER }.map { it.finish }.toSet()
        val aligned = if (Finish.NORMAL !in tcgFinishes && Finish.HOLO in tcgFinishes) {
            prices.map { if (it.source == PriceSource.CARDMARKET && it.finish == Finish.NORMAL) it.copy(finish = Finish.HOLO) else it }
        } else prices
        return CatalogCard(card, aligned)
    }

    private fun digits(s: String) = s.filter(Char::isDigit).trimStart('0').ifEmpty { "0" }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
