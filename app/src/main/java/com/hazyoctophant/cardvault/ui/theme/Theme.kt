package com.hazyoctophant.cardvault.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.hazyoctophant.cardvault.model.Game

val Gain = Color(0xFF2E9E5B)
val Loss = Color(0xFFD9473F)

fun Game.accent(): Color = when (this) {
    Game.MTG -> Color(0xFFE07A2E)
    Game.POKEMON -> Color(0xFFF2C14E)
    Game.ONE_PIECE -> Color(0xFFE5484D)
}

private val Dark = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    secondary = Color(0xFFF2C14E),
    background = Color(0xFF14121C),
    surface = Color(0xFF1B1924),
)
private val Light = lightColorScheme(
    primary = Color(0xFF2F5BD3),
    secondary = Color(0xFFB8860B),
)

@Composable
fun CardVaultTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
