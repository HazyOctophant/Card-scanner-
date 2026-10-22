package com.hazyoctophant.cardvault.data

import android.content.Context
import com.hazyoctophant.cardvault.model.ValuationMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val valuationMode: ValuationMode = ValuationMode.AVERAGE,
    val eurToUsd: Double = 1.08,
    val pokemonApiKey: String = "",
    val dailyRefresh: Boolean = true,
    val notifyOnRefresh: Boolean = true,
    val lastRefreshAt: Long = 0L,
    val lastRefreshError: String? = null,
    /** Scryfall's `updated_at` for the offline Magic database that's installed (empty = none). */
    val mtgDbVersion: String = "",
    val mtgDbImportedAt: Long = 0L,
    val mtgDbAutoUpdate: Boolean = true,
)

/** Small key/value settings store backed by SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = state.asStateFlow()
    val current: AppSettings get() = state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value)
        prefs.edit()
            .putString("valuationMode", next.valuationMode.name)
            .putFloat("eurToUsd", next.eurToUsd.toFloat())
            .putString("pokemonApiKey", next.pokemonApiKey)
            .putBoolean("dailyRefresh", next.dailyRefresh)
            .putBoolean("notifyOnRefresh", next.notifyOnRefresh)
            .putLong("lastRefreshAt", next.lastRefreshAt)
            .putString("lastRefreshError", next.lastRefreshError)
            .putString("mtgDbVersion", next.mtgDbVersion)
            .putLong("mtgDbImportedAt", next.mtgDbImportedAt)
            .putBoolean("mtgDbAutoUpdate", next.mtgDbAutoUpdate)
            .apply()
        state.value = next
    }

    private fun read() = AppSettings(
        valuationMode = prefs.getString("valuationMode", null)
            ?.let { runCatching { ValuationMode.valueOf(it) }.getOrNull() } ?: ValuationMode.AVERAGE,
        eurToUsd = prefs.getFloat("eurToUsd", 1.08f).toDouble(),
        pokemonApiKey = prefs.getString("pokemonApiKey", "") ?: "",
        dailyRefresh = prefs.getBoolean("dailyRefresh", true),
        notifyOnRefresh = prefs.getBoolean("notifyOnRefresh", true),
        lastRefreshAt = prefs.getLong("lastRefreshAt", 0L),
        lastRefreshError = prefs.getString("lastRefreshError", null),
        mtgDbVersion = prefs.getString("mtgDbVersion", "") ?: "",
        mtgDbImportedAt = prefs.getLong("mtgDbImportedAt", 0L),
        mtgDbAutoUpdate = prefs.getBoolean("mtgDbAutoUpdate", true),
    )
}
