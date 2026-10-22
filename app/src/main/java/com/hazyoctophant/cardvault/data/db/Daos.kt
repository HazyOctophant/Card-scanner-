package com.hazyoctophant.cardvault.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.hazyoctophant.cardvault.model.Condition
import com.hazyoctophant.cardvault.model.DeckZone
import com.hazyoctophant.cardvault.model.Finish
import kotlinx.coroutines.flow.Flow

@Dao
interface CardDao {
    @Upsert
    suspend fun upsert(cards: List<CardEntity>)

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun get(id: String): CardEntity?

    @Query("SELECT * FROM cards WHERE id = :id")
    fun observe(id: String): Flow<CardEntity?>

    /** Every card that is owned or used in a deck: these are the ones whose prices we track. */
    @Query(
        """SELECT * FROM cards WHERE id IN (SELECT cardId FROM collection)
           OR id IN (SELECT cardId FROM deck_cards)"""
    )
    suspend fun trackedCards(): List<CardEntity>
}

@Dao
interface CollectionDao {
    @Transaction
    @Query("SELECT * FROM collection ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<OwnedCard>>

    @Transaction
    @Query("SELECT * FROM collection ORDER BY addedAt DESC")
    suspend fun getAll(): List<OwnedCard>

    @Transaction
    @Query("SELECT * FROM collection WHERE binderId = :binderId ORDER BY addedAt ASC")
    fun observeBinder(binderId: Long): Flow<List<OwnedCard>>

    @Transaction
    @Query("SELECT * FROM collection WHERE cardId = :cardId")
    fun observeForCard(cardId: String): Flow<List<OwnedCard>>

    @Query(
        """SELECT * FROM collection WHERE cardId = :cardId AND finish = :finish
           AND condition = :condition AND ((binderId IS NULL AND :binderId IS NULL) OR binderId = :binderId)
           LIMIT 1"""
    )
    suspend fun findStack(cardId: String, finish: Finish, condition: Condition, binderId: Long?): CollectionEntryEntity?

    @Query("SELECT * FROM collection WHERE id = :id")
    suspend fun get(id: Long): CollectionEntryEntity?

    @Insert
    suspend fun insert(entry: CollectionEntryEntity): Long

    @Update
    suspend fun update(entry: CollectionEntryEntity)

    @Delete
    suspend fun delete(entry: CollectionEntryEntity)

    @Query("UPDATE collection SET binderId = NULL WHERE binderId = :binderId")
    suspend fun clearBinder(binderId: Long)

    @Query("SELECT COALESCE(SUM(quantity), 0) FROM collection WHERE cardId = :cardId")
    suspend fun ownedQuantity(cardId: String): Int
}

@Dao
interface PriceDao {
    @Upsert
    suspend fun upsertPrices(prices: List<PriceEntity>)

    @Query("SELECT * FROM prices")
    suspend fun allPrices(): List<PriceEntity>

    @Query("SELECT * FROM prices WHERE cardId = :cardId")
    fun observePrices(cardId: String): Flow<List<PriceEntity>>

    @Query("SELECT * FROM prices")
    fun observeAllPrices(): Flow<List<PriceEntity>>

    @Upsert
    suspend fun upsertValues(values: List<CardValueEntity>)

    @Query("SELECT * FROM card_values WHERE cardId = :cardId")
    fun observeValues(cardId: String): Flow<List<CardValueEntity>>

    @Upsert
    suspend fun upsertHistory(history: List<PriceHistoryEntity>)

    /** The most recent history point on or before [epochDay] for every card/finish. */
    @Query(
        """SELECT h.* FROM price_history h
           JOIN (SELECT cardId, finish, MAX(epochDay) AS d FROM price_history WHERE epochDay <= :epochDay GROUP BY cardId, finish) m
           ON h.cardId = m.cardId AND h.finish = m.finish AND h.epochDay = m.d"""
    )
    suspend fun latestHistoryOnOrBefore(epochDay: Long): List<PriceHistoryEntity>

    @Query("SELECT * FROM price_history WHERE cardId = :cardId AND finish = :finish ORDER BY epochDay ASC")
    fun observeHistory(cardId: String, finish: Finish): Flow<List<PriceHistoryEntity>>

    @Upsert
    suspend fun upsertCollectionHistory(rows: List<CollectionValueHistoryEntity>)

    @Query("SELECT * FROM collection_value_history ORDER BY epochDay ASC")
    fun observeCollectionHistory(): Flow<List<CollectionValueHistoryEntity>>
}

@Dao
interface BinderDao {
    @Query(
        """SELECT b.*, COALESCE((SELECT SUM(quantity) FROM collection c WHERE c.binderId = b.id), 0) AS cardCount
           FROM binders b ORDER BY b.createdAt ASC"""
    )
    fun observeAll(): Flow<List<BinderSummary>>

    @Query("SELECT * FROM binders WHERE id = :id")
    fun observe(id: Long): Flow<BinderEntity?>

    @Query("SELECT * FROM binders ORDER BY createdAt ASC")
    suspend fun getAll(): List<BinderEntity>

    @Insert
    suspend fun insert(binder: BinderEntity): Long

    @Update
    suspend fun update(binder: BinderEntity)

    @Delete
    suspend fun delete(binder: BinderEntity)
}

@Dao
interface DeckDao {
    @Query(
        """SELECT d.*, COALESCE((SELECT SUM(quantity) FROM deck_cards dc WHERE dc.deckId = d.id), 0) AS cardCount
           FROM decks d ORDER BY d.updatedAt DESC"""
    )
    fun observeAll(): Flow<List<DeckSummary>>

    @Query("SELECT * FROM decks WHERE id = :id")
    fun observe(id: Long): Flow<DeckEntity?>

    @Query("SELECT * FROM decks WHERE id = :id")
    suspend fun get(id: Long): DeckEntity?

    @Transaction
    @Query("SELECT * FROM deck_cards WHERE deckId = :deckId")
    fun observeCards(deckId: Long): Flow<List<DeckCardWithCard>>

    @Transaction
    @Query("SELECT * FROM deck_cards WHERE deckId = :deckId")
    suspend fun getCards(deckId: Long): List<DeckCardWithCard>

    @Insert
    suspend fun insert(deck: DeckEntity): Long

    @Update
    suspend fun update(deck: DeckEntity)

    @Delete
    suspend fun delete(deck: DeckEntity)

    @Query("DELETE FROM deck_cards WHERE deckId = :deckId")
    suspend fun clearCards(deckId: Long)

    @Query("SELECT * FROM deck_cards WHERE deckId = :deckId AND cardId = :cardId AND zone = :zone")
    suspend fun getCard(deckId: Long, cardId: String, zone: DeckZone): DeckCardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCard(card: DeckCardEntity)

    @Query("DELETE FROM deck_cards WHERE deckId = :deckId AND cardId = :cardId AND zone = :zone")
    suspend fun removeCard(deckId: Long, cardId: String, zone: DeckZone)

    @Query("UPDATE decks SET updatedAt = :now WHERE id = :deckId")
    suspend fun touch(deckId: Long, now: Long = System.currentTimeMillis())
}
