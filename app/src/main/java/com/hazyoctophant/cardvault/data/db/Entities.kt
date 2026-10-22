package com.hazyoctophant.cardvault.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.hazyoctophant.cardvault.model.Condition
import com.hazyoctophant.cardvault.model.DeckZone
import com.hazyoctophant.cardvault.model.Finish
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.model.PriceSource

/**
 * A card printing from one of the card databases (Scryfall, Pokémon TCG API, OPTCG API).
 * [id] is namespaced by game, e.g. "mtg:<scryfall id>", "pkm:sv3-125", "op:OP01-001_p1".
 */
@Entity(tableName = "cards", indices = [Index("game"), Index("name")])
data class CardEntity(
    @PrimaryKey val id: String,
    val game: Game,
    val name: String,
    val setCode: String,
    val setName: String,
    val number: String,
    val rarity: String = "",
    /** MTG type line, Pokémon supertype ("Pokémon", "Trainer", "Energy"), One Piece card type ("CHARACTER"). */
    val typeLine: String = "",
    /** Comma separated: Pokémon subtypes ("Basic,ex"), One Piece types ("Straw Hat Crew"), MTG subtypes. */
    val subtypes: String = "",
    /** Comma separated colors (MTG W/U/B/R/G, Pokémon types, One Piece colors). */
    val colors: String = "",
    /** MTG mana value, One Piece cost. */
    val cost: Double? = null,
    /** MTG power/toughness, Pokémon HP, One Piece power. */
    val power: String? = null,
    /** One Piece counter value. */
    val counter: Int? = null,
    /** Pokémon: the card this evolves from. */
    val evolvesFrom: String? = null,
    val text: String = "",
    /** Comma separated list of formats this card is legal in (lowercase). */
    val legalities: String = "",
    val imageUrl: String? = null,
    val imageUrlLarge: String? = null,
    /** A link to buy/view the card on a pricing site. */
    val marketUrl: String? = null,
    val releasedAt: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val colorList: List<String> get() = colors.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val subtypeList: List<String> get() = subtypes.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/** One stack of identical cards that the user owns. */
@Entity(tableName = "collection", indices = [Index("cardId"), Index("binderId")])
data class CollectionEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: String,
    val quantity: Int = 1,
    val finish: Finish = Finish.NORMAL,
    val condition: Condition = Condition.NEAR_MINT,
    val binderId: Long? = null,
    val purchasePrice: Double? = null,
    val notes: String = "",
    val addedAt: Long = System.currentTimeMillis(),
)

/** Latest price from one source for one finish of a card, in the source's native currency. */
@Entity(tableName = "prices", primaryKeys = ["cardId", "source", "finish"])
data class PriceEntity(
    val cardId: String,
    val source: PriceSource,
    val finish: Finish,
    val currency: String,
    val market: Double?,
    val low: Double? = null,
    val high: Double? = null,
    val fetchedAt: Long = System.currentTimeMillis(),
)

/** The blended unit value (USD) for a card+finish, recomputed after every price refresh. */
@Entity(tableName = "card_values", primaryKeys = ["cardId", "finish"])
data class CardValueEntity(
    val cardId: String,
    val finish: Finish,
    val valueUsd: Double,
    /** Value one day / seven days ago, for showing trends. */
    val previousDayUsd: Double? = null,
    val previousWeekUsd: Double? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Daily history of a card's blended value. */
@Entity(tableName = "price_history", primaryKeys = ["cardId", "finish", "epochDay"])
data class PriceHistoryEntity(
    val cardId: String,
    val finish: Finish,
    val epochDay: Long,
    val valueUsd: Double,
)

/** Daily total collection value per game, for the dashboard chart. */
@Entity(tableName = "collection_value_history", primaryKeys = ["epochDay", "game"])
data class CollectionValueHistoryEntity(
    val epochDay: Long,
    val game: Game,
    val totalUsd: Double,
    val cardCount: Int,
)

@Entity(tableName = "binders")
data class BinderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val game: Game? = null,
    /** ARGB color for the binder cover. */
    val color: Long = 0xFF3D5AFE,
    /** Pockets per page: 9 (3x3), 12 (4x3) or 16 (4x4). */
    val pocketsPerPage: Int = 9,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "decks")
data class DeckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val game: Game,
    /** e.g. "standard", "commander", "standard" (Pokémon), "standard" (One Piece). */
    val format: String,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "deck_cards",
    primaryKeys = ["deckId", "cardId", "zone"],
    indices = [Index("cardId")],
)
data class DeckCardEntity(
    val deckId: Long,
    val cardId: String,
    val zone: DeckZone = DeckZone.MAIN,
    val quantity: Int = 1,
)

// ---- Query result types ----

data class OwnedCard(
    @Embedded val entry: CollectionEntryEntity,
    @Relation(parentColumn = "cardId", entityColumn = "id")
    val card: CardEntity,
    @Relation(parentColumn = "cardId", entityColumn = "cardId")
    val values: List<CardValueEntity>,
) {
    /** The value matching this entry's finish, falling back to the normal printing, then anything. */
    val unitValue: CardValueEntity?
        get() = values.firstOrNull { it.finish == entry.finish }
            ?: values.firstOrNull { it.finish == Finish.NORMAL }
            ?: values.firstOrNull()

    /** True when the shown value is for a different finish than the card the user owns. */
    val valueIsEstimate: Boolean get() = unitValue != null && unitValue?.finish != entry.finish

    val unitUsd: Double get() = unitValue?.valueUsd ?: 0.0
    val totalUsd: Double get() = unitUsd * entry.quantity
}

data class DeckCardWithCard(
    @Embedded val deckCard: DeckCardEntity,
    @Relation(parentColumn = "cardId", entityColumn = "id")
    val card: CardEntity,
    @Relation(parentColumn = "cardId", entityColumn = "cardId")
    val values: List<CardValueEntity>,
) {
    val unitUsd: Double
        get() = (values.firstOrNull { it.finish == Finish.NORMAL } ?: values.minByOrNull { it.valueUsd })?.valueUsd ?: 0.0
}

data class DeckSummary(
    @Embedded val deck: DeckEntity,
    val cardCount: Int,
)

data class BinderSummary(
    @Embedded val binder: BinderEntity,
    val cardCount: Int,
)
