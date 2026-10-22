package com.hazyoctophant.cardvault.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.strategy.Bar
import com.hazyoctophant.cardvault.ui.formatPercent
import com.hazyoctophant.cardvault.ui.percentChange
import com.hazyoctophant.cardvault.ui.theme.Gain
import com.hazyoctophant.cardvault.ui.theme.Loss
import com.hazyoctophant.cardvault.ui.theme.accent

/** A card picture at the real 63×88 mm aspect ratio, with a placeholder while it loads. */
@Composable
fun CardImage(url: String?, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val shape = RoundedCornerShape(6.dp)
    SubcomposeAsyncImage(
        model = url,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier.aspectRatio(63f / 88f).clip(shape),
        loading = { CardPlaceholder() },
        error = { CardPlaceholder() },
    )
}

@Composable
private fun CardPlaceholder() {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Style, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
    }
}

@Composable
fun GameFilterChips(
    selected: Game?,
    onSelect: (Game?) -> Unit,
    modifier: Modifier = Modifier,
    allowAll: Boolean = true,
    allLabel: String = "All games",
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (allowAll) FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(allLabel) })
        Game.entries.forEach { g ->
            FilterChip(selected = selected == g, onClick = { onSelect(g) }, label = { Text(g.short) }, leadingIcon = {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(g.accent()))
            })
        }
    }
}

@Composable
fun GameDot(game: Game, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(game.accent()))
}

/** Coloured percentage change, e.g. "+4.2%". Shows nothing when there's no previous value. */
@Composable
fun ChangeText(now: Double, before: Double?, modifier: Modifier = Modifier) {
    val p = percentChange(now, before) ?: return
    if (kotlin.math.abs(p) < 0.05) return
    Text(
        formatPercent(p),
        color = if (p >= 0) Gain else Loss,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier,
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Minimal line chart for value history. */
@Composable
fun LineChart(values: List<Double>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    if (values.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("History builds up as prices update each day.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val min = values.min()
        val max = values.max()
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        fun y(v: Double) = (size.height - ((v - min) / range * size.height * 0.9 + size.height * 0.05)).toFloat()
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1f)
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(i * stepX, y(v)) }
        drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
    }
}

/** Horizontal bars with labels, e.g. a mana curve or type breakdown. */
@Composable
fun BarChart(bars: List<Bar>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val max = (bars.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
    Row(modifier.height(120.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        bars.forEach { bar ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${bar.count}", style = MaterialTheme.typography.labelSmall)
                Box(
                    Modifier.fillMaxWidth().height((80f * bar.count / max).dp.coerceAtLeast(2.dp))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(color)
                )
                Spacer(Modifier.height(2.dp))
                Text(bar.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}
