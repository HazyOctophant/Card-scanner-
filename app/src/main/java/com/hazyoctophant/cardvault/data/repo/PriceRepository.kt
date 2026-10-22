package com.hazyoctophant.cardvault.data.repo

import com.hazyoctophant.cardvault.data.SettingsStore
import com.hazyoctophant.cardvault.data.db.AppDatabase
import com.hazyoctophant.cardvault.data.db.CardValueEntity
import com.hazyoctophant.cardvault.data.db.CollectionValueHistoryEntity
import com.hazyoctophant.cardvault.data.db.OwnedCard
import com.hazyoctophant.cardvault.data.db.PriceHistoryEntity
import com.hazyoctophant.cardvault.data.remote.CardCatalog
import com.hazyoctophant.cardvault.data.remote.CatalogCard
import com.hazyoctophant.cardvault.data.remote.ExchangeRates
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.pricing.Valuation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

data class RefreshResult(
    val cardsUpdated: Int,
    val cardsTracked: Int,
    val totalBefore: Double,
    val totalAfter: Double,
    val errors: List<String>,
)

/** Fetches prices from every source, blends them into per-card values, and keeps daily history. */
class PriceRepository(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val fx: ExchangeRates,
    private val catalogs: Map<Game, CardCatalog>,
) {    private val refreshLock = Mutex()

    /** Saves the prices that came with a freshly looked-up card and updates its value. */
    suspend fun store(card: CatalogCard) {
        db.prices().upsertPrices(card.prices)
        recomputeValues(setOf(card.card.id))
    }

    /** Re-download every owned (or deck) card from its database, then recompute all values. */
    suspend fun refreshAll(): RefreshResult = refreshLock.withLock {
        val before = db.collection().getAll().sumOf { it.totalUsd }
        val errors = mutableListOf<String>()
        fx.eurToUsd()?.let { rate -> settings.update { it.copy(eurToUsd = rate) } }

        val tracked = db.cards().trackedCards()
        var updated = 0
        tracked.groupBy { it.game }.forEach { (game, cards) ->
            try {
                val fresh = catalogs.getValue(game).refresh(cards)
                db.cards().upsert(fresh.map { it.card })
                db.prices().upsertPrices(fresh.flatMap { it.prices })
                updated += fresh.size
                if (fresh.size < cards.size) errors += "${game.label}: ${cards.size - fresh.size} card(s) not found"
            } catch (e: Exception) {
                errors += "${game.label}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        recomputeValues(null)
        val after = db.collection().getAll().sumOf { it.totalUsd }
        settings.update {
            it.copy(lastRefreshAt = System.currentTimeMillis(), lastRefreshError = errors.joinToString("\n").ifEmpty { null })
        }
        RefreshResult(updated, tracked.size, before, after, errors)
    }

    /** Recomputes blended values (all cards when [cardIds] is null), e.g. after the valuation mode changes. */
    suspend fun recomputeValues(cardIds: Set<String>?) {
        val s = settings.current
        val today = LocalDate.now().toEpochDay()
        val all = db.prices().allPrices().let { list -> if (cardIds == null) list else list.filter { it.cardId in cardIds } }
        val prevDay = db.prices().latestHistoryOnOrBefore(today - 1).associateBy { it.cardId to it.finish }
        val prevWeek = db.prices().latestHistoryOnOrBefore(today - 7).associateBy { it.cardId to it.finish }

        val values = all.groupBy { it.cardId }.flatMap { (cardId, prices) ->
            Valuation.values(prices, s.valuationMode, s.eurToUsd).map { (finish, usd) ->
                CardValueEntity(
                    cardId = cardId,
                    finish = finish,
                    valueUsd = usd,
                    previousDayUsd = prevDay[cardId to finish]?.valueUsd,
                    previousWeekUsd = prevWeek[cardId to finish]?.valueUsd,
                )
            }
        }
        db.prices().upsertValues(values)
        db.prices().upsertHistory(values.map { PriceHistoryEntity(it.cardId, it.finish, today, it.valueUsd) })
        recordCollectionTotals()
    }

    /** Snapshot today's total value per game for the dashboard chart. */
    suspend fun recordCollectionTotals() {
        val today = LocalDate.now().toEpochDay()
        val owned: List<OwnedCard> = db.collection().getAll()
        val rows = Game.entries.map { game ->
            val cards = owned.filter { it.card.game == game }
            CollectionValueHistoryEntity(today, game, cards.sumOf { it.totalUsd }, cards.sumOf { it.entry.quantity })
        }
        db.prices().upsertCollectionHistory(rows)
    }
}
