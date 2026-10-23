package com.hazyoctophant.cardvault.data.mtgdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MtgFilterTest {

    @Test
    fun emptyFilterSelectsEverything() {
        val (sql, args) = MtgFilter().toSql()
        assertEquals("SELECT * FROM mtg_db ORDER BY name COLLATE NOCASE ASC LIMIT 300", sql)
        assertTrue(args.isEmpty())
        assertEquals("game:paper", MtgFilter().toScryfallQuery())
    }

    @Test
    fun colorIdentityWithinExcludesOtherColors() {
        val (sql, _) = MtgFilter(colors = setOf("U", "B")).toSql()
        listOf("W", "R", "G").forEach { assertTrue(sql.contains("colors NOT LIKE '%$it%'")) }
        assertFalse(sql.contains("NOT LIKE '%U%'"))
        assertEquals("id<=ub", MtgFilter(colors = setOf("B", "U")).toScryfallQuery())
    }

    @Test
    fun colorlessOnly() {
        assertTrue(MtgFilter(colors = setOf("C")).toSql().first.contains("colors = ''"))
        assertEquals("id=c", MtgFilter(colors = setOf("C")).toScryfallQuery())
    }

    @Test
    fun fullFilterBuildsMatchingSqlAndScryfallQuery() {
        val f = MtgFilter(
            text = "draw",
            colors = setOf("G"),
            colorMode = ColorMode.INCLUDES,
            types = setOf("Creature"),
            rarities = setOf("Rare"),
            minManaValue = 2,
            maxManaValue = 4,
            format = "modern",
            maxPriceUsd = 5.0,
            sort = MtgSort.PRICE,
        )
        val (sql, args) = f.toSql(limit = 50)
        assertTrue(sql.contains("colors LIKE '%G%'"))
        assertTrue(sql.contains("rarity IN (?)"))
        assertTrue(sql.endsWith("ORDER BY usd IS NULL, usd DESC LIMIT 50"))
        assertEquals(listOf("%draw%", "%draw%", "%draw%", "%Creature%", "Rare", 2.0, 4.0, "%,modern,%", 5.0), args)
        assertEquals(sql.count { it == '?' }, args.size)
        assertEquals(
            "(\"draw\" or o:\"draw\" or t:\"draw\") id>=g (t:creature) (r:rare) mv>=2 mv<=4 f:modern usd<=5.00",
            f.toScryfallQuery(),
        )
    }
}
