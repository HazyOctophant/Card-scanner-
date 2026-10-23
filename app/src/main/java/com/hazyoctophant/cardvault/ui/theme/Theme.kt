package com.hazyoctophant.cardvault.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.hazyoctophant.cardvault.data.AppSettings
import com.hazyoctophant.cardvault.model.DarkMode
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.model.ThemePreset

val Gain = Color(0xFF2E9E5B)
val Loss = Color(0xFFD9473F)

fun Game.accent(): Color = when (this) {
    Game.MTG -> Color(0xFFE07A2E)
    Game.POKEMON -> Color(0xFFF2C14E)
    Game.ONE_PIECE -> Color(0xFFE5484D)
}

/** The user's appearance settings, available to any composable (e.g. for holo effects). */
val LocalAppearance = staticCompositionLocalOf { AppSettings() }

private fun onColor(c: Color) = if (c.luminance() > 0.4f) Color(0xFF111111) else Color.White

/** Builds a full Material 3 scheme from a handful of theme colors. */
fun schemeFor(preset: ThemePreset, dark: Boolean, accentOverride: Long): ColorScheme {
    val base = if (preset == ThemePreset.MATERIAL_YOU) ThemePreset.VAULT else preset
    val primary = when {
        accentOverride != 0L -> if (dark) Color(accentOverride) else lerp(Color(accentOverride), Color.Black, 0.45f)
        dark -> Color(base.accent)
        else -> Color(base.accentDeep)
    }
    val secondary = Color(if (dark) base.secondary else base.secondaryDeep)
    val tertiary = Color(base.tertiary).let { if (dark) it else lerp(it, Color.Black, 0.4f) }
    val bg = Color(if (dark) base.darkBackground else base.lightBackground)
    val tint = if (dark) Color.White else primary
    fun layer(f: Float) = lerp(bg, tint, f)
    return if (dark) {
        darkColorScheme(
            primary = primary, onPrimary = onColor(primary),
            primaryContainer = lerp(primary, bg, 0.65f), onPrimaryContainer = lerp(primary, Color.White, 0.5f),
            secondary = secondary, onSecondary = onColor(secondary),
            secondaryContainer = lerp(secondary, bg, 0.7f), onSecondaryContainer = lerp(secondary, Color.White, 0.5f),
            tertiary = tertiary, onTertiary = onColor(tertiary),
            background = bg, onBackground = Color(0xFFECEAF2),
            surface = bg, onSurface = Color(0xFFECEAF2),
            surfaceVariant = layer(0.12f), onSurfaceVariant = Color(0xFFC8C4D0),
            surfaceContainerLowest = layer(0.02f), surfaceContainerLow = layer(0.05f), surfaceContainer = layer(0.07f),
            surfaceContainerHigh = layer(0.10f), surfaceContainerHighest = layer(0.13f),
            outline = layer(0.35f), outlineVariant = layer(0.2f),
        )
    } else {
        lightColorScheme(
            primary = primary, onPrimary = onColor(primary),
            primaryContainer = lerp(primary, Color.White, 0.75f), onPrimaryContainer = lerp(primary, Color.Black, 0.5f),
            secondary = secondary, onSecondary = onColor(secondary),
            secondaryContainer = lerp(secondary, Color.White, 0.75f), onSecondaryContainer = lerp(secondary, Color.Black, 0.5f),
            tertiary = tertiary, onTertiary = onColor(tertiary),
            background = bg, onBackground = Color(0xFF1B1A20),
            surface = bg, onSurface = Color(0xFF1B1A20),
            surfaceVariant = layer(0.10f), onSurfaceVariant = Color(0xFF4A4752),
            surfaceContainerLowest = Color.White, surfaceContainerLow = layer(0.03f), surfaceContainer = layer(0.05f),
            surfaceContainerHigh = layer(0.08f), surfaceContainerHighest = layer(0.11f),
            outline = layer(0.45f), outlineVariant = layer(0.2f),
        )
    }
}

@Composable
fun CardVaultTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.darkMode) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = if (settings.theme == ThemePreset.MATERIAL_YOU && settings.accentOverride == 0L && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        schemeFor(settings.theme, dark, settings.accentOverride)
    }
    CompositionLocalProvider(LocalAppearance provides settings) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
