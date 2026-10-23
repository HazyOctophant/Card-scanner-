package com.hazyoctophant.cardvault.data.mtgdb

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.hazyoctophant.cardvault.data.db.CardEntity
import com.hazyoctophant.cardvault.data.db.PriceEntity
import com.hazyoctophant.cardvault.data.remote.CatalogCard
import com.hazyoctophant.cardvault.model.Finish
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.model.PriceSource

/**
 * One Magic card (its most recognisable printing) from Scryfall's "Oracle Cards" bulk file,
 * stored on the phone so the whole card pool can be searched offline.
 * Prices are inline because there are ~35,000 rows and they're only used for display and sorting.
 */
@Entity(tableName = "mtg_db", indices = [Index("name"), Index("cost"), Index("edhrecRank")])
data class MtgDbCard(
    @PrimaryKey val id: String,
    val name: String,
    val setCode: String,
    val setName: String,
    val number: String,
    val rarity: String,
    val typeLine: String,
    val subtypes: String,
    /** Color identity, comma separated (W,U,B,R,G). */
    val colors: String,
    val manaCost: String,
    val cost: Double?,
    val power: String?,
    val text: String,
    val legalities: String,
    val imageUrl: String?,
    val imageUrlLarge: String?,
    val marketUrl: String?,
    val releasedAt: String?,
    /** Popularity rank on EDHREC (1 = most played); a good proxy for how widely a card sees play. */
    val edhrecRank: Int?,
    val usd: Double?,
    val usdFoil: Double?,
    val usdEtched: Double?,
    val eur: Double?,
    val eurFoil: Double?,
    val importedAt: Long,
) {
    fun toCardEntity() = CardEntity(
        id = id, game = Game.MTG, name = name, setCode = setCode, setName = setName, number = number,
        rarity = rarity, typeLine = typeLine, subtypes = subtypes, colors = colors, cost = cost, power = power,
        text = text, legalities = legalities, imageUrl = imageUrl, imageUrlLarge = imageUrlLarge,
        marketUrl = marketUrl, releasedAt = releasedAt,
    )

    fun toCatalogCard(): CatalogCard {
        val prices = listOfNotNull(
            usd?.let { PriceEntity(id, PriceSource.TCGPLAYER, Finish.NORMAL, "USD", it) },
            usdFoil?.let { PriceEntity(id, PriceSource.TCGPLAYER, Finish.FOIL, "USD", it) },
            usdEtched?.let { PriceEntity(id, PriceSource.TCGPLAYER, Finish.ETCHED, "USD", it) },
            eur?.let { PriceEntity(id, PriceSource.CARDMARKET, Finish.NORMAL, "EUR", it) },
            eurFoil?.let { PriceEntity(id, PriceSource.CARDMARKET, Finish.FOIL, "EUR", it) },
        )
        return CatalogCard(toCardEntity(), prices)
    }

    companion object {
        fun from(card: CatalogCard, manaCost: String, edhrecRank: Int?, importedAt: Long): MtgDbCard {
            val c = card.card
            fun price(source: PriceSource, finish: Finish) =
                card.prices.firstOrNull { it.source == source && it.finish == finish }?.market
            return MtgDbCard(
                id = c.id, name = c.name, setCode = c.setCode, setName = c.setName, number = c.number,
                rarity = c.rarity, typeLine = c.typeLine, subtypes = c.subtypes, colors = c.colors,
                manaCost = manaCost, cost = c.cost, power = c.power, text = c.text, legalities = c.legalities,
                imageUrl = c.imageUrl, imageUrlLarge = c.imageUrlLarge, marketUrl = c.marketUrl, releasedAt = c.releasedAt,
                edhrecRank = edhrecRank,
                usd = price(PriceSource.TCGPLAYER, Finish.NORMAL),
                usdFoil = price(PriceSource.TCGPLAYER, Finish.FOIL),
                usdEtched = price(PriceSource.TCGPLAYER, Finish.ETCHED),
                eur = price(PriceSource.CARDMARKET, Finish.NORMAL),
                eurFoil = price(PriceSource.CARDMARKET, Finish.FOIL),
                importedAt = importedAt,
            )
        }
    }
}
