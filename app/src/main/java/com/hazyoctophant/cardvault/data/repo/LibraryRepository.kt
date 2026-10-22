package com.hazyoctophant.cardvault.data.repo

import com.hazyoctophant.cardvault.data.db.AppDatabase
import com.hazyoctophant.cardvault.data.db.BinderEntity
import com.hazyoctophant.cardvault.data.db.DeckCardEntity
import com.hazyoctophant.cardvault.data.db.DeckEntity
import com.hazyoctophant.cardvault.data.remote.CatalogCard
import com.hazyoctophant.cardvault.model.DeckZone
import com.hazyoctophant.cardvault.model.Game
import androidx.room.withTransaction

/** Binders and decks. */
class LibraryRepository(
    private val db: AppDatabase,
    private val cards: CardRepository,
) {
    // ---- Binders ----
    fun observeBinders() = db.binders().observeAll()
    fun observeBinder(id: Long) = db.binders().observe(id)
    suspend fun binders() = db.binders().getAll()
    suspend fun createBinder(name: String, game: Game?, color: Long, pockets: Int) =
        db.binders().insert(BinderEntity(name = name, game = game, color = color, pocketsPerPage = pockets))
    suspend fun updateBinder(binder: BinderEntity) = db.binders().update(binder)

    /** Deleting a binder keeps its cards; they just become unfiled. */
    suspend fun deleteBinder(binder: BinderEntity) = db.withTransaction {
        db.collection().clearBinder(binder.id)
        db.binders().delete(binder)
    }

    // ---- Decks ----
    fun observeDecks() = db.decks().observeAll()
    fun observeDeck(id: Long) = db.decks().observe(id)
    fun observeDeckCards(id: Long) = db.decks().observeCards(id)
    suspend fun deckCards(id: Long) = db.decks().getCards(id)

    suspend fun createDeck(name: String, game: Game, format: String) =
        db.decks().insert(DeckEntity(name = name, game = game, format = format))

    suspend fun updateDeck(deck: DeckEntity) = db.decks().update(deck.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deleteDeck(deck: DeckEntity) = db.withTransaction {
        db.decks().clearCards(deck.id)
        db.decks().delete(deck)
    }

    /** Adds [delta] copies (negative removes). Cards from search results are saved first. */
    suspend fun changeDeckCard(deckId: Long, cardId: String, zone: DeckZone, delta: Int) {
        val current = db.decks().getCard(deckId, cardId, zone)?.quantity ?: 0
        setDeckCard(deckId, cardId, zone, current + delta)
    }

    suspend fun setDeckCard(deckId: Long, cardId: String, zone: DeckZone, quantity: Int) {
        if (quantity <= 0) db.decks().removeCard(deckId, cardId, zone)
        else db.decks().putCard(DeckCardEntity(deckId, cardId, zone, quantity))
        db.decks().touch(deckId)
    }

    suspend fun addCatalogCardToDeck(deckId: Long, card: CatalogCard, zone: DeckZone, quantity: Int = 1) {
        cards.save(card)
        changeDeckCard(deckId, card.card.id, zone, quantity)
    }

    suspend fun replaceDeckCards(deckId: Long, newCards: List<DeckCardEntity>) = db.withTransaction {
        db.decks().clearCards(deckId)
        newCards.forEach { db.decks().putCard(it) }
        db.decks().touch(deckId)
    }
}
