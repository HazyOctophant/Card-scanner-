package com.hazyoctophant.cardvault.model

enum class Game(val label: String, val short: String) {
    MTG("Magic: The Gathering", "Magic"),
    POKEMON("Pokémon", "Pokémon"),
    ONE_PIECE("One Piece", "One Piece");
}

/** Printing finish. Prices are tracked per finish because foils/holos are priced very differently. */
enum class Finish(val label: String) {
    NORMAL("Normal"),
    FOIL("Foil"),
    ETCHED("Etched foil"),
    HOLO("Holofoil"),
    REVERSE_HOLO("Reverse holo"),
    FIRST_EDITION("1st edition");

    companion object {
        fun forGame(game: Game): List<Finish> = when (game) {
            Game.MTG -> listOf(NORMAL, FOIL, ETCHED)
            Game.POKEMON -> listOf(NORMAL, HOLO, REVERSE_HOLO, FIRST_EDITION)
            Game.ONE_PIECE -> listOf(NORMAL, FOIL)
        }
    }
}

enum class Condition(val label: String, val short: String) {
    NEAR_MINT("Near Mint", "NM"),
    LIGHTLY_PLAYED("Lightly Played", "LP"),
    MODERATELY_PLAYED("Moderately Played", "MP"),
    HEAVILY_PLAYED("Heavily Played", "HP"),
    DAMAGED("Damaged", "DMG");
}

/** Where a price came from. */
enum class PriceSource(val label: String, val currency: String) {
    TCGPLAYER("TCGplayer", "USD"),
    CARDMARKET("Cardmarket", "EUR"),
    MTGO("MTGO (tix)", "TIX");
}

/** How several source prices are combined into the single value shown for a card. */
enum class ValuationMode(val label: String) {
    AVERAGE("Average of sources"),
    TCGPLAYER("TCGplayer only"),
    CARDMARKET("Cardmarket only"),
    HIGHEST("Highest source"),
    LOWEST("Lowest source");
}

enum class DeckZone(val label: String) {
    MAIN("Main deck"),
    SIDEBOARD("Sideboard"),
    COMMANDER("Commander"),
    LEADER("Leader"),
    DON("DON!! deck");
}
