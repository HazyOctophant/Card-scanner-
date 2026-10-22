package com.hazyoctophant.cardvault.data.remote

import com.hazyoctophant.cardvault.data.db.CardEntity
import com.hazyoctophant.cardvault.data.db.PriceEntity
import com.hazyoctophant.cardvault.model.Finish
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.model.PriceSource
import com.hazyoctophant.cardvault.scan.ScanHint
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * Magic: The Gathering via Scryfall (https://scryfall.com/docs/api).
 * Prices: TCGplayer (USD), Cardmarket (EUR) and MTGO (tix).
 */
class ScryfallCatalog(private val http: Http) : CardCatalog {
    override val game = Game.MTG
    private val base = "https://api.scryfall.com"

    override suspend fun search(query: String): List<CatalogCard> {
        val q = enc(query.trim())
        val page = http.get("$base/cards/search?q=$q&unique=prints&order=released&dir=desc").obj()
            ?: return emptyList()
        return page.arr("data")?.mapNotNull { it.obj()?.let(::parse) }?.take(60) ?: emptyList()
    }

    override suspend fun identify(hint: ScanHint): List<CatalogCard> {
        val set = hint.mtgSet
        val number = hint.mtgNumber
        if (set != null && number != null) {
            http.get("$base/cards/${enc(set.lowercase())}/${enc(number)}").obj()?.let { return listOf(parse(it)) }
        }
        for (name in hint.nameCandidates) {
            val named = http.get("$base/cards/named?fuzzy=${enc(name)}").obj() ?: continue
            if (named.str("object") != "card") continue
            // Offer every printing of the recognised name so the user can pick the right set.
            val oracleName = named.str("name") ?: name
            val prints = http.get(
                "$base/cards/search?q=${enc("!\"$oracleName\"")}&unique=prints&order=released&dir=desc"
            ).obj()?.arr("data")?.mapNotNull { it.obj()?.let(::parse) }.orEmpty()
            val exact = parse(named)
            val ordered = buildList {
                // If the set code was read but the number wasn't, put that set's printings first.
                if (set != null) addAll(prints.filter { it.card.setCode.equals(set, true) })
                add(exact)
                addAll(prints)
            }.distinctBy { it.card.id }
            return ordered.take(40)
        }
        return emptyList()
    }

    override suspend fun refresh(cards: List<CardEntity>): List<CatalogCard> {
        val out = mutableListOf<CatalogCard>()
        cards.chunked(75).forEachIndexed { i, chunk ->
            if (i > 0) delay(120) // Scryfall asks for 50–100 ms between requests.
            val body = buildJsonObject {
                put("identifiers", JsonArray(chunk.map { buildJsonObject { put("id", it.id.removePrefix("mtg:")) } }))
            }
            http.post("$base/cards/collection", body.toString()).obj()
                ?.arr("data")?.mapNotNullTo(out) { it.obj()?.let(::parse) }
        }
        return out
    }

    private fun parse(o: JsonObject): CatalogCard {
        val id = "mtg:" + (o.str("id") ?: "")
        val faces = o.arr("card_faces")?.mapNotNull { it.obj() }.orEmpty()
        val firstFace = faces.firstOrNull()
        val images = o.obj("image_uris") ?: firstFace?.obj("image_uris")
        val typeLine = o.str("type_line") ?: firstFace?.str("type_line") ?: ""
        val text = o.str("oracle_text")
            ?: faces.mapNotNull { f -> f.str("oracle_text")?.let { "${f.str("name")}: $it" } }.joinToString("\n\n")
        val power = (o.str("power") ?: firstFace?.str("power"))?.let { p ->
            "$p/${o.str("toughness") ?: firstFace?.str("toughness") ?: "?"}"
        } ?: (o.str("loyalty") ?: firstFace?.str("loyalty"))?.let { "Loyalty $it" }
        val legal = o.obj("legalities")?.entries
            ?.filter { (it.value as? JsonPrimitive)?.content == "legal" }
            ?.joinToString(",") { it.key }.orEmpty()
        val subtypes = typeLine.substringAfter("—", "").split(' ', '/').map { it.trim() }.filter { it.isNotEmpty() }

        val card = CardEntity(
            id = id,
            game = Game.MTG,
            name = o.str("name") ?: "Unknown",
            setCode = (o.str("set") ?: "").uppercase(),
            setName = o.str("set_name") ?: "",
            number = o.str("collector_number") ?: "",
            rarity = o.str("rarity")?.replaceFirstChar { it.uppercase() } ?: "",
            typeLine = typeLine,
            subtypes = subtypes.joinToString(","),
            colors = o.arr("color_identity").strings().joinToString(","),
            cost = o.num("cmc"),
            power = power,
            text = text,
            legalities = legal,
            imageUrl = images?.str("normal"),
            imageUrlLarge = images?.str("large") ?: images?.str("normal"),
            marketUrl = o.obj("purchase_uris")?.str("tcgplayer") ?: o.str("scryfall_uri"),
            releasedAt = o.str("released_at"),
        )

        val p = o.obj("prices")
        val prices = buildList {
            fun addPrice(key: String, source: PriceSource, finish: Finish) {
                p?.num(key)?.let { add(PriceEntity(id, source, finish, source.currency, it)) }
            }
            addPrice("usd", PriceSource.TCGPLAYER, Finish.NORMAL)
            addPrice("usd_foil", PriceSource.TCGPLAYER, Finish.FOIL)
            addPrice("usd_etched", PriceSource.TCGPLAYER, Finish.ETCHED)
            addPrice("eur", PriceSource.CARDMARKET, Finish.NORMAL)
            addPrice("eur_foil", PriceSource.CARDMARKET, Finish.FOIL)
            addPrice("tix", PriceSource.MTGO, Finish.NORMAL)
        }
        return CatalogCard(card, prices)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
