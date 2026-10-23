package com.hazyoctophant.cardvault.data.repo

import androidx.room.withTransaction
import com.hazyoctophant.cardvault.data.SettingsStore
import com.hazyoctophant.cardvault.data.db.AppDatabase
import com.hazyoctophant.cardvault.data.db.ProfileEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** The people using the app. Each has a separate collection, binders, decks and value history. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileRepository(private val db: AppDatabase, private val settings: SettingsStore) {
    val activeId: Flow<Long> = settings.settings.map { it.activeProfileId }.distinctUntilChanged()
    val currentId: Long get() = settings.current.activeProfileId

    fun observeProfiles(): Flow<List<ProfileEntity>> = db.profiles().observeAll()
    val activeProfile: Flow<ProfileEntity?> = activeId.flatMapLatest { db.profiles().observe(it) }

    suspend fun all(): List<ProfileEntity> = db.profiles().getAll()

    /** Makes sure there is always at least one user, and that the active one exists. */
    suspend fun ensureValid() {
        val profiles = db.profiles().getAll()
        if (profiles.isEmpty()) {
            val id = db.profiles().insert(ProfileEntity(id = AppDatabase.DEFAULT_PROFILE_ID, name = "Me"))
            switchTo(id)
        } else if (profiles.none { it.id == currentId }) {
            switchTo(profiles.first().id)
        }
    }

    suspend fun create(name: String, color: Long): Long =
        db.profiles().insert(ProfileEntity(name = name.trim(), color = color))

    suspend fun rename(profile: ProfileEntity, name: String, color: Long) =
        db.profiles().update(profile.copy(name = name.trim(), color = color))

    fun switchTo(id: Long) = settings.update { it.copy(activeProfileId = id) }

    /** Deletes a user and everything in their collection. The last remaining user can't be deleted. */
    suspend fun delete(profile: ProfileEntity) {
        val others = db.profiles().getAll().filter { it.id != profile.id }
        if (others.isEmpty()) return
        db.withTransaction {
            db.collection().deleteForProfile(profile.id)
            db.binders().deleteForProfile(profile.id)
            db.decks().deleteCardsForProfile(profile.id)
            db.decks().deleteForProfile(profile.id)
            db.prices().deleteHistoryForProfile(profile.id)
            db.profiles().delete(profile)
        }
        if (currentId == profile.id) switchTo(others.first().id)
    }
}
