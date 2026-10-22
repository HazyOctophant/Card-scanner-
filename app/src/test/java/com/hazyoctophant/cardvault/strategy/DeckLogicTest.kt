package com.hazyoctophant.cardvault.strategy

import com.hazyoctophant.cardvault.data.db.CardEntity
import com.hazyoctophant.cardvault.model.DeckZone
import com.hazyoctophant.cardvault.model.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckLogicTest {
    private fun mtg(name: String, type: String, cost: Double = 0.0, colors: String = "R", text: String = "", legal: String = "standard,modern") =
        CardEntity("mtg:$name", Game.MTG, name, "SET", "Set", "1", typeLine = type, colors = colors, cost = cost, text = text, legalities = legal)

    private fun op(id: String, name: String, type: String, cost: Double? = 2.0, colors: String = "Red", counter: Int? = 1000, sub: String = "Straw Hat Crew", text: String = "") =
        CardEntity("op:$id", Game.ONE_PIECE, name, "OP-01", "Romance Dawn", id, typeLine = type, subtypes = sub, colors = colors, cost = cost, counter = counter, text = text)

    private fun pkm(name: String, super_: String, subtypes: String, types: String = "Lightning", evolvesFrom: String? = null, text: String = "") =
        CardEntity("pkm:$name", Game.POKEMON, name, "SV", "Set", "1", typeLine = super_, subtypes = subtypes, colors = types, evolvesFrom = evolvesFrom, text = text, legalities = "standard")

    @Test
    fun magicCopyLimitAndBasicLands() {
        val format = Formats.get(Game.MTG, "standard")
        val deck = listOf(
            DeckEntry(mtg("Shock", "Instant", 1.0, text = "Shock deals 2 damage to any target."), 5),
            DeckEntry(mtg("Mountain", "Basic Land — Mountain", colors = "R", legal = ""), 55),
        )
        val issues = DeckRules.validate(format, deck)
        assertTrue(issues.any { it.severity == Severity.ERROR && it.message.startsWith("Shock") })
        assertFalse(issues.any { it.message.startsWith("Mountain") })
    }

    @Test
    fun magicLegalityChecked() {
        val format = Formats.get(Game.MTG, "standard")
        val deck = listOf(DeckEntry(mtg("Old Card", "Creature", 2.0, legal = "modern"), 4))
        assertTrue(DeckRules.validate(format, deck).any { it.message.contains("not legal") })
    }

    @Test
    fun onePieceLeaderColorAndSize() {
        val format = Formats.get(Game.ONE_PIECE, "standard")
        val leader = op("OP01-001", "Zoro", "LEADER", cost = null, colors = "Red", counter = null)
        val deck = listOf(
            DeckEntry(leader, 1, DeckZone.LEADER),
            DeckEntry(op("OP01-013", "Sanji", "CHARACTER"), 4),
            DeckEntry(op("OP01-060", "Blue Guy", "CHARACTER", colors = "Blue"), 4),
        )
        val issues = DeckRules.validate(format, deck)
        assertTrue(issues.any { it.message.contains("Blue Guy") && it.message.contains("colors") })
        assertTrue(issues.any { it.message.contains("needs exactly 50") })
    }

    @Test
    fun pokemonBrokenEvolutionLineIsReported() {
        val format = Formats.get(Game.POKEMON, "standard")
        val deck = listOf(DeckEntry(pkm("Raichu", "Pokémon", "Stage 1", evolvesFrom = "Pikachu"), 2))
        val analysis = DeckAnalyzer.analyze(format, deck)
        assertTrue(analysis.tips.any { it.message.contains("evolves from Pikachu") })
    }

    @Test
    fun suggesterPrefersEvolutionPartners() {
        val format = Formats.get(Game.POKEMON, "standard")
        val deck = listOf(DeckEntry(pkm("Raichu", "Pokémon", "Stage 1", evolvesFrom = "Pikachu"), 2))
        val owned = listOf(
            OwnedStock(pkm("Pikachu", "Pokémon", "Basic"), 4),
            OwnedStock(pkm("Charmander", "Pokémon", "Basic", types = "Fire"), 4),
        )
        val analysis = DeckAnalyzer.analyze(format, deck)
        val s = DeckSuggester.suggest(format, deck, owned, analysis)
        assertEquals("Pikachu", s.first().card.name)
        assertFalse(s.any { it.card.name == "Charmander" })
    }

    @Test
    fun autoFillRespectsCopyLimitsAndOwnership() {
        val format = Formats.get(Game.ONE_PIECE, "standard")
        val leader = op("OP01-001", "Zoro", "LEADER", cost = null, counter = null, text = "Your {Straw Hat Crew} Characters gain +1000 power.")
        val owned = (1..20).map { OwnedStock(op("OP01-%03d".format(it + 10), "Char $it", "CHARACTER", cost = (it % 6).toDouble()), 6) }
        val added = DeckSuggester.autoFill(format, listOf(DeckEntry(leader, 1, DeckZone.LEADER)), owned)
        assertEquals(50, added.sumOf { it.second })
        assertTrue(added.all { it.second <= 4 })
    }

    @Test
    fun hypergeometric() {
        // 24 lands in 60 cards: P(2..4 lands in opening 7) = 0.7746 (exact hypergeometric).
        assertEquals(0.7746, DeckAnalyzer.probabilityBetween(60, 24, 7, 2, 4), 1e-4)
        assertEquals(0.7945, DeckAnalyzer.probabilityBetween(40, 17, 7, 2, 4), 1e-4)
    }
}
