package com.hazyoctophant.cardvault.data

import android.content.Context
import com.hazyoctophant.cardvault.model.BackgroundLayout
import com.hazyoctophant.cardvault.model.BackgroundSource
import com.hazyoctophant.cardvault.model.DarkMode
import com.hazyoctophant.cardvault.model.ThemePreset
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
    // ---- Appearance ----
    val theme: ThemePreset = ThemePreset.VAULT,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    /** ARGB accent that overrides the theme's primary color; 0 = use the theme's own. */
    val accentOverride: Long = 0L,
    val backgroundSource: BackgroundSource = BackgroundSource.NONE,
    val backgroundLayout: BackgroundLayout = BackgroundLayout.COLLAGE,
    /** How strongly background artwork shows through (0 = hidden, 1 = full). */
    val backgroundVisibility: Float = 0.22f,
    val backgroundBlur: Boolean = false,
    /** Image used when [backgroundSource] is ONE_CARD. */
    val wallpaperCardImage: String = "",
    /** Animated rainbow sheen on foil / holo cards. */
    val holoEffects: Boolean = true,
    /** The user whose collection is shown (see ProfileEntity). */
    val activeProfileId: Long = 1L,
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
            .putString("theme", next.theme.name)
            .putString("darkMode", next.darkMode.name)
            .putLong("accentOverride", next.accentOverride)
            .putString("backgroundSource", next.backgroundSource.name)
            .putString("backgroundLayout", next.backgroundLayout.name)
            .putFloat("backgroundVisibility", next.backgroundVisibility)
            .putBoolean("backgroundBlur", next.backgroundBlur)
            .putString("wallpaperCardImage", next.wallpaperCardImage)
            .putBoolean("holoEffects", next.holoEffects)
            .putLong("activeProfileId", next.activeProfileId)
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
        theme = enumPref("theme", ThemePreset.VAULT),
        darkMode = enumPref("darkMode", DarkMode.SYSTEM),
        accentOverride = prefs.getLong("accentOverride", 0L),
        backgroundSource = enumPref("backgroundSource", BackgroundSource.NONE),
        backgroundLayout = enumPref("backgroundLayout", BackgroundLayout.COLLAGE),
        backgroundVisibility = prefs.getFloat("backgroundVisibility", 0.22f),
        backgroundBlur = prefs.getBoolean("backgroundBlur", false),
        wallpaperCardImage = prefs.getString("wallpaperCardImage", "") ?: "",
        holoEffects = prefs.getBoolean("holoEffects", true),
        activeProfileId = prefs.getLong("activeProfileId", 1L),
    )

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E): E =
        prefs.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
}
