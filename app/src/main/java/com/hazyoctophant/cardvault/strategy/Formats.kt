package com.hazyoctophant.cardvault.strategy

import com.hazyoctophant.cardvault.data.db.CardEntity
import com.hazyoctophant.cardvault.model.DeckZone
import com.hazyoctophant.cardvault.model.Game

data class DeckEntry(val card: CardEntity, val quantity: Int, val zone: DeckZone = DeckZone.MAIN)

data class DeckFormat(
    val id: String,
    val label: String,
    val game: Game,
    val minMain: Int,
    val maxMain: Int?,
    val maxCopies: Int,
    val sideboardMax: Int = 0,
    /** Zone that must hold exactly one card (Commander / Leader), if any. */
    val headZone: DeckZone? = null,
    /** Whether card legality should be checked against the database's legal-format list. */
    val checkLegality: Boolean = true,
) {
    val targetSize: Int get() = maxMain ?: minMain
}

object Formats {
    val all: List<DeckFormat> = listOf(
        DeckFormat("standard", "Standard", Game.MTG, 60, null, 4, sideboardMax = 15),
        DeckFormat("pioneer", "Pioneer", Game.MTG, 60, null, 4, sideboardMax = 15),
        DeckFormat("modern", "Modern", Game.MTG, 60, null, 4, sideboardMax = 15),
        DeckFormat("legacy", "Legacy", Game.MTG, 60, null, 4, sideboardMax = 15),
        DeckFormat("pauper", "Pauper", Game.MTG, 60, null, 4, sideboardMax = 15),
        DeckFormat("commander", "Commander", Game.MTG, 99, 99, 1, headZone = DeckZone.COMMANDER),
        DeckFormat("casual", "Casual (60+)", Game.MTG, 60, null, 4, sideboardMax = 15, checkLegality = false),
        DeckFormat("limited", "Draft / Sealed (40)", Game.MTG, 40, null, Int.MAX_VALUE, checkLegality = false),

        DeckFormat("standard", "Standard", Game.POKEMON, 60, 60, 4),
        DeckFormat("expanded", "Expanded", Game.POKEMON, 60, 60, 4),
        DeckFormat("unlimited", "Unlimited", Game.POKEMON, 60, 60, 4),

        DeckFormat("standard", "Standard", Game.ONE_PIECE, 50, 50, 4, headZone = DeckZone.LEADER, checkLegality = false),
    )

    fun forGame(game: Game) = all.filter { it.game == game }
    fun get(game: Game, id: String) = all.firstOrNull { it.game == game && it.id == id } ?: forGame(game).first()
}

enum class Severity { INFO, GOOD, WARNING, ERROR }

data class Issue(val severity: Severity, val message: String)

/** Hard deck-construction rules for each game. */
object DeckRules {
    private val basicLandNames = setOf(
        "Plains", "Island", "Swamp", "Mountain", "Forest", "Wastes",
        "Snow-Covered Plains", "Snow-Covered Island", "Snow-Covered Swamp", "Snow-Covered Mountain", "Snow-Covered Forest",
    )

    fun isUnlimitedCopies(card: CardEntity): Boolean = when (card.game) {
        Game.MTG -> card.name in basicLandNames || card.typeLine.contains("Basic Land") ||
            card.text.contains("A deck can have any number of cards named", ignoreCase = true)
        Game.POKEMON -> card.typeLine == "Energy" && card.subtypeList.any { it.equals("Basic", true) }
        Game.ONE_PIECE -> false
    }

    /** Pokémon and Magic limit copies by name; One Piece by card number. */
    fun copyKey(card: CardEntity): String = when (card.game) {
        Game.ONE_PIECE -> card.number
        else -> card.name.lowercase()
    }

    fun validate(format: DeckFormat, entries: List<DeckEntry>): List<Issue> {
        val issues = mutableListOf<Issue>()
        val main = entries.filter { it.zone == DeckZone.MAIN }
        val mainCount = main.sumOf { it.quantity }

        when {
            mainCount < format.minMain ->
                issues += Issue(Severity.ERROR, "Main deck has $mainCount cards; ${format.label} needs ${sizeText(format)}.")
            format.maxMain != null && mainCount > format.maxMain ->
                issues += Issue(Severity.ERROR, "Main deck has $mainCount cards; ${format.label} allows ${sizeText(format)}.")
            else -> issues += Issue(Severity.GOOD, "Deck size OK ($mainCount).")
        }

        val side = entries.filter { it.zone == DeckZone.SIDEBOARD }.sumOf { it.quantity }
        if (side > format.sideboardMax) {
            issues += Issue(Severity.ERROR, "Sideboard has $side cards (max ${format.sideboardMax}).")
        }

        val head = format.headZone?.let { zone -> entries.filter { it.zone == zone } }
        if (format.headZone != null) {
            val n = head!!.sumOf { it.quantity }
            val label = format.headZone.label
            if (n == 0) issues += Issue(Severity.ERROR, "Choose a $label.")
            else if (n > 1 && format.game == Game.ONE_PIECE) issues += Issue(Severity.ERROR, "Only one $label is allowed.")
            else if (n > 2) issues += Issue(Severity.ERROR, "At most two ${label}s (partners) are allowed.")
            if (format.game == Game.ONE_PIECE && head.any { !it.card.typeLine.equals("LEADER", true) }) {
                issues += Issue(Severity.ERROR, "The Leader slot must hold a Leader card.")
            }
        }

        // Copy limits (across main + sideboard).
        val playable = entries.filter { it.zone == DeckZone.MAIN || it.zone == DeckZone.SIDEBOARD }
        playable.groupBy { copyKey(it.card) }.forEach { (_, group) ->
            val card = group.first().card
            val count = group.sumOf { it.quantity }
            if (!isUnlimitedCopies(card) && count > format.maxCopies) {
                issues += Issue(Severity.ERROR, "${card.name}: $count copies (max ${format.maxCopies}).")
            }
        }
        if (format.game == Game.POKEMON) {
            val aceSpecs = main.filter { e -> e.card.subtypeList.any { it.equals("ACE SPEC", true) } }.sumOf { it.quantity }
            if (aceSpecs > 1) issues += Issue(Severity.ERROR, "Only one ACE SPEC card is allowed ($aceSpecs).")
            val radiant = main.filter { e -> e.card.subtypeList.any { it.equals("Radiant", true) } }.sumOf { it.quantity }
            if (radiant > 1) issues += Issue(Severity.ERROR, "Only one Radiant Pokémon is allowed ($radiant).")
            if (main.none { e -> e.card.typeLine.startsWith("Pok") && e.card.subtypeList.any { it.equals("Basic", true) } }) {
                issues += Issue(Severity.ERROR, "A Pokémon deck needs at least one Basic Pokémon.")
            }
        }

        // Format legality, as reported by the card database.
        if (format.checkLegality) {
            playable.map { it.card }.distinctBy { it.id }
                .filter { it.legalities.isNotEmpty() && format.id !in it.legalities.split(',') }
                .forEach { issues += Issue(Severity.ERROR, "${it.name} is not legal in ${format.label}.") }
        }

        // Colour identity.
        val leaders = head.orEmpty().map { it.card }
        if (leaders.isNotEmpty()) {
            val allowed = leaders.flatMap { it.colorList }.toSet()
            main.map { it.card }.distinctBy { it.id }.forEach { card ->
                val ok = when (format.game) {
                    Game.ONE_PIECE -> card.colorList.any { it in allowed }
                    Game.MTG -> card.colorList.all { it in allowed }
                    Game.POKEMON -> true
                }
                if (!ok) issues += Issue(Severity.ERROR, "${card.name} is outside the ${format.headZone?.label?.lowercase()}'s colors.")
            }
        }
        return issues
    }

    private fun sizeText(f: DeckFormat) = when {
        f.maxMain == null -> "at least ${f.minMain}"
        f.maxMain == f.minMain -> "exactly ${f.minMain}"
        else -> "${f.minMain}–${f.maxMain}"
    }
}
