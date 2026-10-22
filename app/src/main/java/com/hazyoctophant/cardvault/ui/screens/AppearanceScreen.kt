package com.hazyoctophant.cardvault.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.hazyoctophant.cardvault.data.AppSettings
import com.hazyoctophant.cardvault.model.BackgroundLayout
import com.hazyoctophant.cardvault.model.BackgroundSource
import com.hazyoctophant.cardvault.model.DarkMode
import com.hazyoctophant.cardvault.model.ThemePreset
import com.hazyoctophant.cardvault.model.accentSwatches
import com.hazyoctophant.cardvault.ui.Routes
import com.hazyoctophant.cardvault.ui.appContainer
import com.hazyoctophant.cardvault.ui.components.ChoiceRow
import com.hazyoctophant.cardvault.ui.components.ScreenScaffold
import com.hazyoctophant.cardvault.ui.components.SectionTitle
import com.hazyoctophant.cardvault.ui.theme.schemeFor
import kotlinx.coroutines.launch

/** Themes, accent colors and background artwork. Changes apply instantly. */
@Composable
fun AppearanceScreen(nav: NavController) {
    val container = appContainer()
    val s by container.settings.settings.collectAsStateWithLifecycle()
    val photos by container.wallpapers.files.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun update(t: (AppSettings) -> AppSettings) = container.settings.update(t)

    // The system photo picker needs no storage permission.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            container.wallpapers.add(uris)
            update { it.copy(backgroundSource = BackgroundSource.PHOTOS) }
        }
    }

    ScreenScaffold(title = "Appearance", nav = nav) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).verticalScroll(rememberScrollState())) {
            SectionTitle("Theme")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemePreset.entries.forEach { preset ->
                    ThemeSwatch(preset, selected = s.theme == preset) { update { it.copy(theme = preset) } }
                }
            }
            Text(
                s.theme.description,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )

            SectionTitle("Light / dark")
            ChoiceRow(DarkMode.entries, s.darkMode, { it.label }) { m -> update { it.copy(darkMode = m) } }

            SectionTitle("Accent color")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ColorDot(null, s.accentOverride == 0L) { update { it.copy(accentOverride = 0L) } }
                accentSwatches.forEach { c -> ColorDot(Color(c), s.accentOverride == c) { update { it.copy(accentOverride = c) } } }
            }

            SectionTitle("Background artwork")
            ChoiceRow(BackgroundSource.entries, s.backgroundSource, { it.label }) { src -> update { it.copy(backgroundSource = src) } }
            Text(
                when (s.backgroundSource) {
                    BackgroundSource.NONE -> "A plain background in your theme's color."
                    BackgroundSource.MY_CARDS -> "A collage of the most valuable cards in your collection. It updates as you add cards."
                    BackgroundSource.PHOTOS -> "Your own pictures, like card art, playmats or binder pages. Add as many as you like."
                    BackgroundSource.ONE_CARD ->
                        if (s.wallpaperCardImage.isEmpty()) "Open any card and tap the wallpaper button to use its art."
                        else "Using the card you picked. Open another card and tap the wallpaper button to change it."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            if (s.backgroundSource == BackgroundSource.PHOTOS) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Icon(Icons.Default.AddPhotoAlternate, null); Text(" Add photos") }
                    photos.forEach { file ->
                        Box {
                            AsyncImage(file, null, Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                            Icon(
                                Icons.Default.Close, "Remove photo",
                                tint = Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                    .clickable { scope.launch { container.wallpapers.remove(file) } },
                            )
                        }
                    }
                }
            }
            if (s.backgroundSource == BackgroundSource.ONE_CARD) {
                FilledTonalButton(onClick = { nav.navigate(Routes.COLLECTION) }, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("Choose a card from my collection")
                }
            }
            if (s.backgroundSource != BackgroundSource.NONE) {
                ChoiceRow(BackgroundLayout.entries, s.backgroundLayout, { it.label }) { l -> update { it.copy(backgroundLayout = l) } }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Artwork visibility ${(s.backgroundVisibility * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = s.backgroundVisibility,
                        onValueChange = { v -> update { it.copy(backgroundVisibility = v) } },
                        valueRange = 0.05f..0.7f,
                    )
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    ToggleLine("Soft blur", s.backgroundBlur) { on -> update { it.copy(backgroundBlur = on) } }
                }
            }

            SectionTitle("Effects")
            ToggleLine("Holo shimmer on foil and holo cards", s.holoEffects) { on -> update { it.copy(holoEffects = on) } }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ThemeSwatch(preset: ThemePreset, selected: Boolean, onClick: () -> Unit) {
    val dark = schemeFor(preset, dark = true, accentOverride = 0L)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(84.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(12.dp))
                .background(
                    if (preset == ThemePreset.MATERIAL_YOU) Brush.sweepGradient(
                        listOf(Color(0xFFFF8A80), Color(0xFFFFD54F), Color(0xFFA5D6A7), Color(0xFF80D8FF), Color(0xFFB39DDB), Color(0xFFFF8A80)),
                    ) else SolidColor(dark.background),
                )
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (preset != ThemePreset.MATERIAL_YOU) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(dark.primary, dark.secondary, dark.tertiary).forEach { Box(Modifier.size(16.dp).clip(CircleShape).background(it)) }
                }
            }
            if (selected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Text(preset.label, style = MaterialTheme.typography.labelSmall, fontWeight = if (selected) FontWeight.Bold else null, maxLines = 1)
    }
}

@Composable
private fun ColorDot(color: Color?, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape)
            .background(color ?: MaterialTheme.colorScheme.surfaceVariant)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (color == null) Text("A", style = MaterialTheme.typography.labelMedium)
        else if (selected) Icon(Icons.Default.Check, null, tint = Color.Black, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ToggleLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
