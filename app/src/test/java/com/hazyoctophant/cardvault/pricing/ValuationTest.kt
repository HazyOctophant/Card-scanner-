package com.hazyoctophant.cardvault.pricing

import com.hazyoctophant.cardvault.data.db.PriceEntity
import com.hazyoctophant.cardvault.model.Finish
import com.hazyoctophant.cardvault.model.PriceSource
import com.hazyoctophant.cardvault.model.ValuationMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ValuationTest {
    private val prices = listOf(
        PriceEntity("c", PriceSource.TCGPLAYER, Finish.NORMAL, "USD", 10.0),
        PriceEntity("c", PriceSource.CARDMARKET, Finish.NORMAL, "EUR", 5.0),
        PriceEntity("c", PriceSource.MTGO, Finish.NORMAL, "TIX", 99.0),
        PriceEntity("c", PriceSource.TCGPLAYER, Finish.FOIL, "USD", 30.0),
    )

    @Test
    fun averagesConvertedSourcesAndIgnoresTix() {
        val v = Valuation.values(prices, ValuationMode.AVERAGE, eurToUsd = 2.0)
        assertEquals(10.0, v.getValue(Finish.NORMAL), 1e-9)
        assertEquals(30.0, v.getValue(Finish.FOIL), 1e-9)
    }

    @Test
    fun preferredSourceFallsBackWhenMissing() {
        val v = Valuation.values(prices, ValuationMode.CARDMARKET, eurToUsd = 1.1)
        assertEquals(5.5, v.getValue(Finish.NORMAL), 1e-9)
        assertEquals(30.0, v.getValue(Finish.FOIL), 1e-9)
    }

    @Test
    fun highestAndLowest() {
        assertEquals(10.0, Valuation.values(prices, ValuationMode.HIGHEST, 1.0).getValue(Finish.NORMAL), 1e-9)
        assertEquals(5.0, Valuation.values(prices, ValuationMode.LOWEST, 1.0).getValue(Finish.NORMAL), 1e-9)
    }
}
