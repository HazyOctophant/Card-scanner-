package com.hazyoctophant.cardvault.strategy

import com.hazyoctophant.cardvault.data.db.CardEntity
import com.hazyoctophant.cardvault.model.DeckZone
import com.hazyoctophant.cardvault.model.Game

/** A card the user owns, with how many copies are available. */
data class OwnedStock(val card: CardEntity, val quantity: Int)

data class Suggestion(val card: CardEntity, val score: Double, val reasons: List<String>, val available: Int)

/** Ranks cards from the user's collection by how well they fit a deck. */
object DeckSuggester {

    fun suggest(
        format: DeckFormat,
        entries: List<DeckEntry>,
        owned: List<OwnedStock>,
        analysis: DeckAnalysis,
        limit: Int = 30,
    ): List<Suggestion> {
        val deckCards = entries.map { it.card }
        val head = format.headZone?.let { z -> entries.filter { it.zone == z }.map { it.card } }.orEmpty()
        val inDeckByKey = entries.filter { it.zone == DeckZone.MAIN }
            .groupBy { DeckRules.copyKey(it.card) }.mapValues { e -> e.value.sumOf { it.quantity } }

        // How often each subtype/type appears in the deck: shared types mean tribal/archetype synergy.
        val typeFrequency = deckCards.flatMap { it.subtypeList }.groupingBy { it }.eachCount()
        val leaderTypes = if (format.game == Game.ONE_PIECE) DeckAnalyzer.leaderTypes(head.firstOrNull()) else emptySet()
        val deckNames = deckCards.map { it.name }.toSet()
        val deckText = deckCards.joinToString("\n") { it.text }
        val deckColors = (head.ifEmpty { deckCards }).flatMap { it.colorList }.toSet()

        return owned.asSequence()
            .filter { it.card.game == format.game }
            .filter { DeckRules.isUnlimitedCopies(it.card) || (inDeckByKey[DeckRules.copyKey(it.card)] ?: 0) < format.maxCopies }
            .filter { !format.checkLegality || it.card.legalities.isEmpty() || format.id in it.card.legalities.split(',') }
            .filter { fitsColors(format, it.card, head, deckColors) }
            .filter { !(format.game == Game.ONE_PIECE && it.card.typeLine == "LEADER") }
            .mapNotNull { stock ->
                val c = stock.card
                val reasons = mutableListOf<String>()
                var score = 0.0
                val roles = CardRoles.of(c)

                val needed = roles.intersect(analysis.needs)
                if (needed.isNotEmpty()) {
                    score += 3.0 * needed.size
                    reasons += "Adds ${needed.joinToString { it.label.lowercase() }}, which the deck is short on"
                }
                val shared = c.subtypeList.filter { (typeFrequency[it] ?: 0) > 0 }
                if (shared.isNotEmpty()) {
                    score += shared.sumOf { minOf(typeFrequency[it] ?: 0, 8) } * 0.4
                    reasons += "Shares ${shared.joinToString()} with your deck"
                }
                if (leaderTypes.isNotEmpty() && c.subtypeList.any { it in leaderTypes }) {
                    score += 4.0
                    reasons += "Matches your Leader's type"
                }
                if (c.game == Game.POKEMON) {
                    if (c.evolvesFrom != null && c.evolvesFrom in deckNames) {
                        score += 5.0; reasons += "Evolves from ${c.evolvesFrom} in your deck"
                    }
                    if (deckCards.any { it.evolvesFrom == c.name }) {
                        score += 6.0; reasons += "Needed to evolve into cards in your deck"
                    }
                }
                if (c.name.length > 3 && deckText.contains(c.name, ignoreCase = true) && c.name !in deckNames) {
                    score += 3.0; reasons += "Named by a card in your deck"
                }
                val cost = c.cost?.toInt()
                if (cost != null && cost in analysis.curveGaps && !CardRoles.isLand(c)) {
                    score += 1.5; reasons += "Fills the ${cost}-cost slot in your curve"
                }
                if (c.game == Game.ONE_PIECE && (c.counter ?: 0) >= 2000) {
                    score += 1.0; reasons += "+2000 counter"
                }
                if (c.name in deckNames) {
                    score += 1.0; reasons += "Another copy of a card you already play"
                }
                if (score <= 0.0) null else Suggestion(c, score, reasons, stock.quantity)
            }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    private fun fitsColors(format: DeckFormat, card: CardEntity, head: List<CardEntity>, deckColors: Set<String>): Boolean {
        val colors = card.colorList
        return when (format.game) {
            Game.ONE_PIECE -> head.isEmpty() || colors.any { c -> head.any { c in it.colorList } }
            Game.MTG -> when {
                head.isNotEmpty() -> colors.all { c -> head.any { c in it.colorList } }
                deckColors.isEmpty() -> true
                else -> colors.all { it in deckColors }
            }
            Game.POKEMON -> when {
                deckColors.isEmpty() || CardRoles.isTrainer(card) -> true
                CardRoles.isEnergy(card) -> !CardRoles.hasSubtype(card, "Basic") || deckColors.any { card.name.contains(it, true) }
                else -> colors.any { it in deckColors || it == "Colorless" }
            }
        }
    }

    /**
     * Greedily completes the main deck from owned cards, best suggestions first, respecting copy limits
     * and how many copies are owned. Returns cards to add (card to quantity).
     */
    fun autoFill(
        format: DeckFormat,
        entries: List<DeckEntry>,
        owned: List<OwnedStock>,
    ): List<Pair<CardEntity, Int>> {
        val working = entries.toMutableList()
        val added = linkedMapOf<String, Pair<CardEntity, Int>>()
        var guard = 0
        while (working.filter { it.zone == DeckZone.MAIN }.sumOf { it.quantity } < format.targetSize && guard++ < 200) {
            val analysis = DeckAnalyzer.analyze(format, working)
            val already = working.filter { it.zone == DeckZone.MAIN }.groupBy { it.card.id }.mapValues { e -> e.value.sumOf { it.quantity } }
            val available = owned.map { it.copy(quantity = it.quantity - (already[it.card.id] ?: 0)) }.filter { it.quantity > 0 }
            val pick = suggest(format, working, available, analysis, limit = 1).firstOrNull() ?: break
            val room = format.targetSize - working.filter { it.zone == DeckZone.MAIN }.sumOf { it.quantity }
            val inDeck = working.filter { it.zone == DeckZone.MAIN && DeckRules.copyKey(it.card) == DeckRules.copyKey(pick.card) }.sumOf { it.quantity }
            val limit = if (DeckRules.isUnlimitedCopies(pick.card)) room else format.maxCopies - inDeck
            val n = minOf(pick.available, limit, room)
            if (n <= 0) break
            working += DeckEntry(pick.card, n, DeckZone.MAIN)
            val prev = added[pick.card.id]?.second ?: 0
            added[pick.card.id] = pick.card to prev + n
        }
        return added.values.toList()
    }
}
