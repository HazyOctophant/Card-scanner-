package com.hazyoctophant.cardvault.scan

import com.hazyoctophant.cardvault.data.db.CardEntity
import com.hazyoctophant.cardvault.model.Game

/** A candidate printing with how well it matches what was photographed. */
data class ScoredMatch<T>(val item: T, val score: Float, val imageScore: Float?, val textScore: Float, val numberMatch: Boolean) {
    /** Shown to the user, e.g. "92% match". */
    val percent: Int get() = (score * 100).toInt().coerceIn(0, 99)
}

/**
 * Ranks candidate printings by combining the picture comparison with everything the OCR read
 * (name, type line, rules text, collector number).
 */
object MatchScorer {
    private val wordRe = Regex("[a-z0-9]{3,}")

    private fun words(s: String) = wordRe.findAll(s.lowercase()).map { it.value }.toSet()

    /** Fraction of the card's own words (name, type, rules text) that the OCR also found. */
    fun textScore(ocrText: String, card: CardEntity): Float {
        val seen = words(ocrText)
        if (seen.isEmpty()) return 0f
        val nameWords = words(card.name)
        val bodyWords = words(card.typeLine + " " + card.subtypes + " " + card.text)
        val nameHit = if (nameWords.isEmpty()) 0f else nameWords.count { it in seen }.toFloat() / nameWords.size
        val bodyHit = if (bodyWords.isEmpty()) nameHit else bodyWords.count { it in seen }.toFloat() / bodyWords.size
        return 0.55f * nameHit + 0.45f * bodyHit
    }

    /** Whether the printed collector number / card id the OCR read belongs to this printing. */
    fun numberMatches(hint: ScanHint, card: CardEntity): Boolean {
        fun digits(s: String) = s.trimStart('0').ifEmpty { "0" }
        return when (card.game) {
            Game.ONE_PIECE -> hint.onePieceId != null && card.number.equals(hint.onePieceId, true)
            Game.POKEMON -> {
                val n = hint.pokemonNumber ?: return false
                val own = card.number.substringBefore('/')
                val total = card.number.substringAfter('/', "")
                own.equals(n, true) || (own.all(Char::isDigit) && n.all(Char::isDigit) && digits(own) == digits(n) &&
                    (hint.pokemonTotal == null || total.isEmpty() || digits(total) == digits(hint.pokemonTotal)))
            }
            Game.MTG -> hint.mtgNumber != null && digits(card.number) == digits(hint.mtgNumber) &&
                (hint.mtgSet == null || card.setCode.equals(hint.mtgSet, true))
        }
    }

    /** Fraction of a card name's words that the OCR found (0..1). */
    fun nameScore(ocrText: String, name: String): Float {
        val nameWords = words(name)
        if (nameWords.isEmpty()) return 0f
        val seen = words(ocrText)
        return nameWords.count { it in seen }.toFloat() / nameWords.size
    }

    /**
     * Re-ranks visual library hits with what the OCR read: picture similarity first, then the name and
     * the printed number as tie-breakers between look-alike printings.
     */
    fun rerankHits(hits: List<IndexHit>, hint: ScanHint, ocrText: String): List<Pair<IndexHit, Float>> = hits.map { h ->
        val e = h.entry
        val asCard = CardEntity(e.id, h.game, e.name, e.setCode, "", e.number)
        val score = h.similarity + 0.25f * nameScore(ocrText, e.name) + if (numberMatches(hint, asCard)) 0.15f else 0f
        h to score
    }.sortedByDescending { it.second }

    fun <T> rank(
        candidates: List<T>,
        cardOf: (T) -> CardEntity,
        imageScoreOf: (T) -> Float?,
        hint: ScanHint,
        ocrText: String,
    ): List<ScoredMatch<T>> = candidates.map { c ->
        val card = cardOf(c)
        val image = imageScoreOf(c)
        val text = textScore(ocrText, card)
        val number = numberMatches(hint, card)
        val base = if (image != null) 0.65f * image + 0.35f * text else text
        ScoredMatch(c, (base + if (number) 0.15f else 0f).coerceAtMost(1f), image, text, number)
    }.sortedByDescending { it.score }
}
