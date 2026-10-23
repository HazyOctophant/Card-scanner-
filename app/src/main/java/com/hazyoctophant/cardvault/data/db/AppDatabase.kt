package com.hazyoctophant.cardvault.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hazyoctophant.cardvault.data.mtgdb.MtgDbCard
import com.hazyoctophant.cardvault.data.mtgdb.MtgDbDao

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
        MtgDbCard::class,
        ProfileEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cards(): CardDao
    abstract fun collection(): CollectionDao
    abstract fun prices(): PriceDao
    abstract fun binders(): BinderDao
    abstract fun decks(): DeckDao
    abstract fun mtgDb(): MtgDbDao
    abstract fun profiles(): ProfileDao

    companion object {
        const val DEFAULT_PROFILE_ID = 1L
        private const val DEFAULT_PROFILE_COLOR = 0xFF3D5AFE

        private fun insertDefaultProfile(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT OR IGNORE INTO profiles (id, name, color, createdAt) VALUES (?, ?, ?, ?)",
                arrayOf<Any>(DEFAULT_PROFILE_ID, "Me", DEFAULT_PROFILE_COLOR, System.currentTimeMillis()),
            )
        }

        /** v1 → v2: multiple users. Existing cards, binders and decks move to a default "Me" profile. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `color` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                insertDefaultProfile(db)
                for (table in listOf("collection", "binders", "decks")) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `profileId` INTEGER NOT NULL DEFAULT 1")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_${table}_profileId` ON `$table` (`profileId`)")
                }
                // The value history's primary key gains profileId, so the table is rebuilt.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collection_value_history_new` (`profileId` INTEGER NOT NULL, " +
                        "`epochDay` INTEGER NOT NULL, `game` TEXT NOT NULL, `totalUsd` REAL NOT NULL, `cardCount` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`profileId`, `epochDay`, `game`))"
                )
                db.execSQL(
                    "INSERT INTO `collection_value_history_new` (profileId, epochDay, game, totalUsd, cardCount) " +
                        "SELECT 1, epochDay, game, totalUsd, cardCount FROM `collection_value_history`"
                )
                db.execSQL("DROP TABLE `collection_value_history`")
                db.execSQL("ALTER TABLE `collection_value_history_new` RENAME TO `collection_value_history`")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "cardvault.db")
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) = insertDefaultProfile(db)
                })
                .build()
    }
}
