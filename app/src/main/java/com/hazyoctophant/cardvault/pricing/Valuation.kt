package com.hazyoctophant.cardvault.pricing

import com.hazyoctophant.cardvault.data.db.PriceEntity
import com.hazyoctophant.cardvault.model.Finish
import com.hazyoctophant.cardvault.model.PriceSource
import com.hazyoctophant.cardvault.model.ValuationMode

/** Combines prices from several sites into one USD value per finish. */
object Valuation {
    fun toUsd(price: PriceEntity, eurToUsd: Double): Double? {
        val market = price.market ?: return null
        return when (price.currency) {
            "USD" -> market
            "EUR" -> market * eurToUsd
            else -> null // MTGO tix are informational only.
        }
    }

    fun values(prices: List<PriceEntity>, mode: ValuationMode, eurToUsd: Double): Map<Finish, Double> =
        prices.groupBy { it.finish }.mapNotNull { (finish, forFinish) ->
            val bySource = forFinish.mapNotNull { p -> toUsd(p, eurToUsd)?.let { p.source to it } }.toMap()
            value(bySource, mode)?.let { finish to it }
        }.toMap()

    private fun value(bySource: Map<PriceSource, Double>, mode: ValuationMode): Double? {
        if (bySource.isEmpty()) return null
        val all = bySource.values
        return when (mode) {
            ValuationMode.AVERAGE -> all.average()
            ValuationMode.HIGHEST -> all.max()
            ValuationMode.LOWEST -> all.min()
            // Fall back to whatever exists when the preferred site doesn't carry the card.
            ValuationMode.TCGPLAYER -> bySource[PriceSource.TCGPLAYER] ?: all.average()
            ValuationMode.CARDMARKET -> bySource[PriceSource.CARDMARKET] ?: all.average()
        }
    }
}
