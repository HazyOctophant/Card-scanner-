package com.hazyoctophant.cardvault.data.repo

import com.hazyoctophant.cardvault.data.db.AppDatabase
import com.hazyoctophant.cardvault.data.db.CardEntity
import com.hazyoctophant.cardvault.data.db.CollectionEntryEntity
import com.hazyoctophant.cardvault.data.db.OwnedCard
import com.hazyoctophant.cardvault.data.mtgdb.MtgDatabase
import com.hazyoctophant.cardvault.data.remote.CardCatalog
import com.hazyoctophant.cardvault.data.remote.CatalogCard
import com.hazyoctophant.cardvault.model.Condition
import com.hazyoctophant.cardvault.model.Finish
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.scan.MatchScorer
import com.hazyoctophant.cardvault.scan.ScanHint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/** Card lookups (remote) and the active user's collection (local). */
@OptIn(ExperimentalCoroutinesApi::class)
class CardRepository(
    private val db: AppDatabase,
    private val catalogs: Map<Game, CardCatalog>,
    private val prices: PriceRepository,
    private val mtgDb: MtgDatabase,
    private val profiles: ProfileRepository,
) {
    fun observeCollection(): Flow<List<OwnedCard>> = profiles.activeId.flatMapLatest { db.collection().observeAll(it) }
    fun observeBinderCards(binderId: Long): Flow<List<OwnedCard>> = db.collection().observeBinder(binderId)
    fun observeCard(cardId: String): Flow<CardEntity?> = db.cards().observe(cardId)
    fun observeOwned(cardId: String): Flow<List<OwnedCard>> =
        profiles.activeId.flatMapLatest { db.collection().observeForCard(cardId, it) }

    suspend fun search(game: Game, query: String): List<CatalogCard> {
        val online = runCatching { catalogs.getValue(game).search(query) }
        if (game == Game.MTG && online.getOrNull().isNullOrEmpty()) {
            // Offline (or no online match): fall back to the downloaded Magic database.
            val local = mtgDb.findByName(query, limit = 40).map { it.toCatalogCard() }
            if (local.isNotEmpty() || online.isSuccess) return local
        }
        return online.getOrThrow()
    }

    /**
     * Candidates for a scanned card. When the game couldn't be detected, every database is tried with
     * the names that were read.
     */
    suspend fun identify(hint: ScanHint): List<CatalogCard> {
        hint.game?.let { game ->
            val online = runCatching { catalogs.getValue(game).identify(hint) }
            if (game == Game.MTG && online.getOrNull().isNullOrEmpty()) {
                val local = identifyOffline(hint)
                if (local.isNotEmpty() || online.isSuccess) return local
            }
            return online.getOrThrow()
        }
        if (hint.nameCandidates.isEmpty()) return emptyList()
        for (game in listOf(Game.MTG, Game.POKEMON, Game.ONE_PIECE)) {
            val found = runCatching { catalogs.getValue(game).identify(hint.copy(game = game)) }.getOrDefault(emptyList())
            if (found.isNotEmpty()) return found
        }
        return identifyOffline(hint)
    }

    /**
     * A wide candidate list for picture matching: the precise lookup (card number) plus every printing
     * of each name that was read, so alternate arts and reprints can be told apart by their images.
     */
    suspend fun candidatesForPhoto(hint: ScanHint): List<CatalogCard> {
        val games = hint.game?.let { listOf(it) } ?: listOf(Game.MTG, Game.POKEMON, Game.ONE_PIECE)
        val out = LinkedHashMap<String, CatalogCard>()
        for (game in games) {
            val g = hint.copy(game = game)
            runCatching { catalogs.getValue(game).identify(g) }.getOrNull()?.forEach { out.putIfAbsent(it.card.id, it) }
            for (name in hint.nameCandidates.take(2)) {
                runCatching { search(game, name) }.getOrNull()?.take(40)?.forEach { out.putIfAbsent(it.card.id, it) }
            }
            if (game == Game.MTG && out.isEmpty()) identifyOffline(g).forEach { out.putIfAbsent(it.card.id, it) }
        }
        if (hint.game != null) return out.values.toList()
        // Game unknown: fuzzy name searches always return *something*, so only keep cards whose name the
        // OCR actually read; otherwise a misread would turn into a random card from another game.
        val read = hint.nameCandidates.joinToString(" ")
        return out.values.filter { MatchScorer.nameScore(read, it.card.name) >= 0.5f }
    }

    /** Magic cards by OCR'd name from the offline database (works with no signal). */
    private suspend fun identifyOffline(hint: ScanHint): List<CatalogCard> {
        for (name in hint.nameCandidates) {
            val found = mtgDb.findByName(name)
            if (found.isNotEmpty()) return found.map { it.toCatalogCard() }
        }
        return emptyList()
    }

    /** Full data and prices for cards by id, across games. */
    suspend fun byIds(ids: List<String>): List<CatalogCard> {
        val byGame = ids.groupBy { id ->
            when {
                id.startsWith("mtg:") -> Game.MTG
                id.startsWith("pkm:") -> Game.POKEMON
                else -> Game.ONE_PIECE
            }
        }
        val found = byGame.flatMap { (game, gameIds) ->
            runCatching { catalogs.getValue(game).byIds(gameIds) }.getOrDefault(emptyList())
        }.associateBy { it.card.id }
        return ids.mapNotNull { found[it] } // keep the caller's order
    }

    /** Stores a card (and its prices) that came from a remote database. */
    suspend fun save(card: CatalogCard) {
        db.cards().upsert(listOf(card.card))
        prices.store(card)
    }

    /** Adds copies to the collection, merging into an identical existing stack. Returns the entry id. */
    suspend fun addToCollection(
        card: CatalogCard,
        quantity: Int = 1,
        finish: Finish = Finish.NORMAL,
        condition: Condition = Condition.NEAR_MINT,
        binderId: Long? = null,
        purchasePrice: Double? = null,
    ): Long {
        save(card)
        val profileId = profiles.currentId
        val existing = db.collection().findStack(profileId, card.card.id, finish, condition, binderId)
        return if (existing != null) {
            db.collection().update(existing.copy(quantity = existing.quantity + quantity))
            existing.id
        } else {
            db.collection().insert(
                CollectionEntryEntity(
                    profileId = profileId,
                    cardId = card.card.id,
                    quantity = quantity,
                    finish = finish,
                    condition = condition,
                    binderId = binderId,
                    purchasePrice = purchasePrice,
                )
            )
        }.also { prices.recordCollectionTotals() }
    }

    suspend fun updateEntry(entry: CollectionEntryEntity) {
        if (entry.quantity <= 0) db.collection().delete(entry) else db.collection().update(entry)
        prices.recordCollectionTotals()
    }

    suspend fun deleteEntry(entry: CollectionEntryEntity) {
        db.collection().delete(entry)
        prices.recordCollectionTotals()
    }

    suspend fun moveToBinder(entries: List<CollectionEntryEntity>, binderId: Long?) {
        entries.forEach { db.collection().update(it.copy(binderId = binderId)) }
    }

    suspend fun ownedQuantity(cardId: String): Int = db.collection().ownedQuantity(cardId, profiles.currentId)
}
