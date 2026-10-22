package com.hazyoctophant.cardvault

import android.app.Application
import android.content.Context
import com.hazyoctophant.cardvault.data.SettingsStore
import com.hazyoctophant.cardvault.data.db.AppDatabase
import com.hazyoctophant.cardvault.data.mtgdb.MtgDatabase
import com.hazyoctophant.cardvault.data.mtgdb.MtgDatabaseWorker
import com.hazyoctophant.cardvault.data.remote.ExchangeRates
import com.hazyoctophant.cardvault.data.remote.Http
import com.hazyoctophant.cardvault.data.remote.OnePieceCatalog
import com.hazyoctophant.cardvault.data.remote.PokemonTcgCatalog
import com.hazyoctophant.cardvault.data.remote.ScryfallCatalog
import com.hazyoctophant.cardvault.data.repo.CardRepository
import com.hazyoctophant.cardvault.data.repo.LibraryRepository
import com.hazyoctophant.cardvault.data.repo.PriceRepository
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.pricing.PriceRefreshWorker

/** Hand-wired dependencies; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    val db = AppDatabase.build(context)
    private val http = Http()
    private val scryfall = ScryfallCatalog(http)
    private val catalogs = mapOf(
        Game.MTG to scryfall,
        Game.POKEMON to PokemonTcgCatalog(http) { settings.current.pokemonApiKey },
        Game.ONE_PIECE to OnePieceCatalog(http, context.cacheDir),
    )
    val prices = PriceRepository(db, settings, ExchangeRates(http), catalogs)
    val mtgDb = MtgDatabase(db, http, scryfall, settings)
    val cards = CardRepository(db, catalogs, prices, mtgDb)
    val library = LibraryRepository(db, cards)
}

class CardVaultApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        PriceRefreshWorker.createChannel(this)
        PriceRefreshWorker.schedule(this, container.settings.current.dailyRefresh)
        container.settings.current.let { s ->
            // Keep the offline Magic database fresh once it has been downloaded.
            MtgDatabaseWorker.scheduleWeekly(this, s.mtgDbAutoUpdate && s.mtgDbVersion.isNotEmpty())
        }
    }
}
