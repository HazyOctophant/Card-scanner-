package com.hazyoctophant.cardvault.data.mtgdb

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface MtgDbDao {
    @Upsert
    suspend fun upsert(cards: List<MtgDbCard>)

    /** Removes cards that weren't in the latest import (e.g. renamed or withdrawn). */
    @Query("DELETE FROM mtg_db WHERE importedAt < :importStartedAt")
    suspend fun deleteOlderThan(importStartedAt: Long)

    @Query("DELETE FROM mtg_db")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM mtg_db")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM mtg_db")
    suspend fun count(): Int

    @RawQuery(observedEntities = [MtgDbCard::class])
    fun observe(query: SupportSQLiteQuery): Flow<List<MtgDbCard>>

    @RawQuery
    suspend fun query(query: SupportSQLiteQuery): List<MtgDbCard>

    @Query("SELECT * FROM mtg_db WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): MtgDbCard?

    /** Name lookup for scanner fallback: prefix matches first, then anywhere in the name. */
    @Query(
        """SELECT * FROM mtg_db WHERE name LIKE '%' || :text || '%'
           ORDER BY CASE WHEN name LIKE :text || '%' THEN 0 ELSE 1 END, edhrecRank IS NULL, edhrecRank
           LIMIT :limit"""
    )
    suspend fun nameContains(text: String, limit: Int): List<MtgDbCard>
}
