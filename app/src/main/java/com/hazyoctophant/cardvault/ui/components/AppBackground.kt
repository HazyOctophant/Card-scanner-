package com.hazyoctophant.cardvault.ui.components

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.hazyoctophant.cardvault.model.BackgroundLayout
import com.hazyoctophant.cardvault.model.BackgroundSource
import com.hazyoctophant.cardvault.ui.appContainer
import com.hazyoctophant.cardvault.ui.theme.LocalAppearance
import kotlinx.coroutines.flow.map

/**
 * Draws the theme background behind every screen: a plain color, or artwork (a tilted collage of your most
 * valuable cards or your photos, or a single image) faded under a scrim so text stays readable.
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val s = LocalAppearance.current
    val container = appContainer()
    val cardImages by remember(container) {
        container.cards.observeCollection().map { owned ->
            owned.sortedByDescending { it.unitUsd }.mapNotNull { it.card.imageUrlLarge ?: it.card.imageUrl }.distinct().take(24)
        }
    }.collectAsStateWithLifecycle(emptyList())
    val photos by container.wallpapers.files.collectAsStateWithLifecycle()

    val images: List<Any> = when (s.backgroundSource) {
        BackgroundSource.NONE -> emptyList()
        BackgroundSource.MY_CARDS -> cardImages
        BackgroundSource.PHOTOS -> photos
        BackgroundSource.ONE_CARD -> listOfNotNull(s.wallpaperCardImage.takeIf { it.isNotEmpty() })
    }
    val bg = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxSize().background(bg)) {
        if (images.isNotEmpty()) {
            val art = Modifier.fillMaxSize().then(if (s.backgroundBlur && Build.VERSION.SDK_INT >= 31) Modifier.blur(12.dp) else Modifier)
            when (s.backgroundLayout) {
                BackgroundLayout.COLLAGE -> Collage(images, art)
                BackgroundLayout.SINGLE -> AsyncImage(images.first(), null, art, contentScale = ContentScale.Crop)
            }
            Box(Modifier.fillMaxSize().background(bg.copy(alpha = 1f - s.backgroundVisibility.coerceIn(0f, 1f))))
        }
        content()
    }
}

/** A rotated wall of card-shaped tiles that fills the screen, cycling through [images]. */
@Composable
fun Collage(images: List<Any>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val width = maxWidth * 1.7f
        val height = maxHeight * 1.5f
        val columns = 5
        val tileWidth = width / columns
        val rows = (height / (tileWidth * 88f / 63f)).toInt() + 1
        Column(
            Modifier.requiredSize(width, height).rotate(-14f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (r in 0 until rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in 0 until columns) {
                        AsyncImage(
                            model = images[(r * columns + c + r) % images.size],
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(tileWidth - 6.dp).aspectRatio(63f / 88f).clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }
        }
    }
}

/** An animated rainbow sheen, like light moving across a foil card. No-op when holo effects are off. */
fun Modifier.holoShimmer(enabled: Boolean = true): Modifier = composed {
    if (!enabled || !LocalAppearance.current.holoEffects) return@composed this
    val transition = rememberInfiniteTransition(label = "holo")
    val shift by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart),
        label = "holoShift",
    )
    drawWithContent {
        drawContent()
        val x = size.width * shift
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent, Color(0x55FF6EC7), Color(0x557DF9FF), Color(0x55FFF59D), Color(0x55B388FF), Color.Transparent,
                ),
                start = Offset(x - size.width, 0f),
                end = Offset(x, size.height),
            ),
            blendMode = BlendMode.Screen,
        )
    }
}
