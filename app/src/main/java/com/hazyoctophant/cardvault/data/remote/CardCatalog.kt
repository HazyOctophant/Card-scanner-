package com.hazyoctophant.cardvault.data.remote

import com.hazyoctophant.cardvault.data.db.CardEntity
import com.hazyoctophant.cardvault.data.db.PriceEntity
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.scan.ScanHint

/** A card from a remote database together with the prices it was published with. */
data class CatalogCard(val card: CardEntity, val prices: List<PriceEntity>)

/** One card game's database: search, identify scanned cards, and refresh prices. */
interface CardCatalog {
    val game: Game

    /** Free-text search by card name. */
    suspend fun search(query: String): List<CatalogCard>

    /** Best-effort candidates for what the camera saw, most likely first. */
    suspend fun identify(hint: ScanHint): List<CatalogCard>

    /** Re-fetch the given cards (with current prices). Cards that can't be found are omitted. */
    suspend fun refresh(cards: List<CardEntity>): List<CatalogCard>
}
