package com.hazyoctophant.cardvault.scan

import com.hazyoctophant.cardvault.model.Game

/** What the scanner could read off a card; used to look the card up in the right database. */
data class ScanHint(
    val game: Game?,
    val onePieceId: String? = null,
    val pokemonNumber: String? = null,
    val pokemonTotal: String? = null,
    val mtgSet: String? = null,
    val mtgNumber: String? = null,
    val nameCandidates: List<String> = emptyList(),
) {
    /** A stable key for "the same card is still in front of the camera". */
    val key: String?
        get() = when (game) {
            Game.ONE_PIECE -> onePieceId
            Game.POKEMON -> pokemonNumber?.let { "$it/${pokemonTotal.orEmpty()}" }
            Game.MTG -> if (mtgSet != null && mtgNumber != null) "$mtgSet#$mtgNumber" else null
            null -> null
        } ?: nameCandidates.firstOrNull()?.lowercase()

    /** True when a printed identifier (not just a name) was read, so the lookup is precise. */
    val hasIdentifier: Boolean
        get() = onePieceId != null || pokemonNumber != null || (mtgSet != null && mtgNumber != null)
}

/**
 * Turns OCR text lines (top to bottom) into a [ScanHint].
 *
 * Printed identifiers are much more reliable to OCR than stylised card names, so the parser looks for:
 *  - One Piece: the card number, e.g. "OP05-119", "ST10-004", "EB01-012", "P-041".
 *  - Pokémon: the collector number, e.g. "125/198", "TG05/TG30", "SV049/SV122".
 *  - Magic: the set code + language ("DMU • EN") and collector number ("0123" or "123/281").
 */
object CardTextParser {
    private val onePiece = Regex("""\b(OP|ST|EB|PRB)\s?-?\s?(\d{2})\s?-\s?(\d{3})\b""", RegexOption.IGNORE_CASE)
    private val onePiecePromo = Regex("""\bP\s?-\s?(\d{3})\b""")

    private val slashNumber = Regex("""\b([A-Z]{0,3}\d{1,3})\s?/\s?([A-Z]{0,3}\d{2,3})\b""")
    private val mtgSetLang = Regex(
        """\b([A-Z0-9]{3,5})\s?[•·*.+]\s?(EN|JP|JA|DE|FR|IT|ES|SP|PT|KO|RU|CS|CT|ZHS|ZHT|PH)\b"""
    )
    /** Modern Magic frames print a bare 4-digit number followed by a rarity letter, e.g. "0123 R". */
    private val mtgBareNumber = Regex("""(?:^|\s)(\d{3,4})\s+([CURMSLTP])\b""")

    private val pokemonWords = listOf(
        "weakness", "resistance", "retreat", "evolves from", "pokémon", "pokemon", "trainer", "supporter",
        "stage 1", "stage 2", "basic", "energy", "illus.", "nintendo", "creatures", "game freak", "prize card",
    )
    private val mtgWords = listOf(
        "wizards of the coast", "creature —", "creature -", "instant", "sorcery", "enchantment", "artifact",
        "planeswalker", "legendary", "flying", "trample", "haste", "{t}", "mana", "battlefield", "graveyard",
        "tap:", "untap", "ward",
    )
    private val onePieceWords = listOf(
        "don!!", "[on play]", "[when attacking]", "[activate: main]", "[blocker]", "[rush]", "[counter]",
        "[trigger]", "counter", "life", "leader", "character", "eiichiro oda", "bandai", "[your turn]",
        "[on k.o.]", "[opponent's turn]",
    )

    /** Common OCR confusions in One Piece card numbers. */
    private fun ocrFix(text: String) = text.replace("0P", "OP").replace("5T", "ST")

    fun onePieceId(text: String): String? {
        val t = ocrFix(text)
        onePiece.find(t)?.let { m ->
            return "${m.groupValues[1].uppercase()}${m.groupValues[2]}-${m.groupValues[3]}"
        }
        onePiecePromo.find(t)?.let { m -> return "P-${m.groupValues[1]}" }
        return null
    }

    /** Which game the text most likely comes from, or null if there's not enough evidence. */
    fun detectGame(lines: List<String>): Game? {
        val all = lines.joinToString("\n")
        val lower = all.lowercase()
        // A full One Piece card number ("OP05-119") is distinctive enough on its own.
        if (onePiece.containsMatchIn(ocrFix(all))) return Game.ONE_PIECE
        val scores = mapOf(
            Game.POKEMON to pokemonWords.count { lower.contains(it) } +
                (if (Regex("""\bHP\s?\d{2,3}\b|\b\d{2,3}\s?HP\b""").containsMatchIn(all)) 3 else 0),
            Game.MTG to mtgWords.count { lower.contains(it) } +
                (if (mtgSetLang.containsMatchIn(all)) 3 else 0),
            Game.ONE_PIECE to onePieceWords.count { lower.contains(it) } +
                (if (onePieceId(all) != null) 2 else 0),
        )
        val best = scores.maxByOrNull { it.value } ?: return null
        return if (best.value >= 2 && scores.values.count { it == best.value } == 1) best.key else null
    }

    fun parse(lines: List<String>, forcedGame: Game? = null): ScanHint {
        val clean = lines.map { it.trim() }.filter { it.isNotEmpty() }
        val all = clean.joinToString("\n")
        val game = forcedGame ?: detectGame(clean)
        val names = nameCandidates(clean, game)
        return when (game) {
            Game.ONE_PIECE -> ScanHint(game, onePieceId = onePieceId(all), nameCandidates = names)
            Game.POKEMON -> {
                // The collector number sits at the bottom of the card, so prefer the last match.
                val m = slashNumber.findAll(all).lastOrNull()
                ScanHint(game, pokemonNumber = m?.groupValues?.get(1), pokemonTotal = m?.groupValues?.get(2), nameCandidates = names)
            }
            Game.MTG -> {
                val set = mtgSetLang.findAll(all).lastOrNull()?.groupValues?.get(1)
                val number = slashNumber.findAll(all).lastOrNull()?.groupValues?.get(1)?.takeIf { it.all(Char::isDigit) }
                    ?: clean.asReversed().firstNotNullOfOrNull { mtgBareNumber.find(it)?.groupValues?.get(1) }
                ScanHint(game, mtgSet = set, mtgNumber = number?.trimStart('0')?.ifEmpty { "0" }, nameCandidates = names)
            }
            null -> ScanHint(null, nameCandidates = names)
        }
    }

    private val junkLine = Regex(
        """^(basic|stage\s?\d|trainer|supporter|item|stadium|tool|energy|pokémon tool|evolves from.*|""" +
            """leader|character|event|stage|counter|life|don!!.*|weakness.*|resistance.*|retreat.*|illus.*|""" +
            """[\d\s/+×x-]+)$""",
        RegexOption.IGNORE_CASE,
    )

    /** Plausible card names: short, mostly-letter lines from the top of the card. */
    fun nameCandidates(lines: List<String>, game: Game?): List<String> {
        val ordered = if (game == Game.ONE_PIECE) lines.asReversed() else lines // One Piece names are printed near the bottom.
        return ordered.asSequence()
            .map { line ->
                line.replace(Regex("""\bHP\s?\d+|\d+\s?HP\b"""), "")
                    .replace(Regex("""[{}\[\]()|_@#$%^&*=<>~]"""), " ")
                    .replace(Regex("""\s+\d+$"""), "")
                    .replace(Regex("""\s{2,}"""), " ")
                    .trim()
            }
            .filter { it.length in 3..40 }
            .filter { line -> line.count { it.isLetter() } >= line.length * 0.6 }
            .filter { !junkLine.matches(it) }
            .filter { line -> line.split(' ').size <= 6 }
            .distinct()
            .take(3)
            .toList()
    }
}
