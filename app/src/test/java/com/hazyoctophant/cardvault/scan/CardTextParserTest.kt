package com.hazyoctophant.cardvault.scan

import com.hazyoctophant.cardvault.model.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTextParserTest {

    @Test
    fun onePieceCardNumber() {
        val lines = listOf("5000", "Monkey.D.Luffy", "[On Play] Draw 1 card.", "LEADER", "Straw Hat Crew", "OP01-003 L")
        val hint = CardTextParser.parse(lines)
        assertEquals(Game.ONE_PIECE, hint.game)
        assertEquals("OP01-003", hint.onePieceId)
    }

    @Test
    fun onePieceOcrConfusions() {
        assertEquals("OP05-119", CardTextParser.onePieceId("0P05 -119 SEC"))
        assertEquals("ST10-004", CardTextParser.onePieceId("ST10-004"))
        assertEquals("EB01-012", CardTextParser.onePieceId("eb01-012"))
        assertEquals("P-041", CardTextParser.onePieceId("P-041 PROMO"))
    }

    @Test
    fun pokemonCollectorNumber() {
        val lines = listOf(
            "BASIC", "Pikachu ex HP200", "Resolute Heart", "Weakness", "Resistance", "Retreat",
            "Illus. 5ban Graphics", "057/191", "©2024 Pokémon / Nintendo / Creatures / GAME FREAK",
        )
        val hint = CardTextParser.parse(lines)
        assertEquals(Game.POKEMON, hint.game)
        assertEquals("057", hint.pokemonNumber)
        assertEquals("191", hint.pokemonTotal)
        assertEquals("Pikachu ex", hint.nameCandidates.first())
    }

    @Test
    fun pokemonTrainerGalleryNumber() {
        val hint = CardTextParser.parse(listOf("Trainer", "Supporter", "Iono", "TG13/TG30"), Game.POKEMON)
        assertEquals("TG13", hint.pokemonNumber)
        assertEquals("TG30", hint.pokemonTotal)
    }

    @Test
    fun magicSetCodeAndNumber() {
        val lines = listOf(
            "Lightning Bolt", "Instant", "Lightning Bolt deals 3 damage to any target.",
            "0123 C", "M25 • EN Christopher Moeller", "™ & © 2024 Wizards of the Coast",
        )
        val hint = CardTextParser.parse(lines)
        assertEquals(Game.MTG, hint.game)
        assertEquals("M25", hint.mtgSet)
        assertEquals("123", hint.mtgNumber)
        assertEquals("Lightning Bolt", hint.nameCandidates.first())
    }

    @Test
    fun magicOldStyleSlashNumber() {
        val hint = CardTextParser.parse(listOf("Sol Ring", "Artifact", "{T}: Add {C}{C}.", "254/302 U", "C21 • EN"), Game.MTG)
        assertEquals("C21", hint.mtgSet)
        assertEquals("254", hint.mtgNumber)
        assertTrue(hint.hasIdentifier)
    }

    @Test
    fun unknownTextHasNoGame() {
        val hint = CardTextParser.parse(listOf("hello world"))
        assertNull(hint.game)
    }
}
