package com.hazyoctophant.cardvault.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CardEntity::class,
        CollectionEntryEntity::class,
        PriceEntity::class,
        CardValueEntity::class,
        PriceHistoryEntity::class,
        CollectionValueHistoryEntity::class,
        BinderEntity::class,
        DeckEntity::class,
        DeckCardEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cards(): CardDao
    abstract fun collection(): CollectionDao
    abstract fun prices(): PriceDao
    abstract fun binders(): BinderDao
    abstract fun decks(): DeckDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "cardvault.db").build()
    }
}
