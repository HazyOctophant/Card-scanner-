package com.hazyoctophant.cardvault.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hazyoctophant.cardvault.data.db.OwnedCard
import com.hazyoctophant.cardvault.model.Finish
import com.hazyoctophant.cardvault.ui.formatUsd

/** One collection stack as a list row: thumbnail, name, set, finish/condition, quantity and value. */
@Composable
fun OwnedCardRow(o: OwnedCard, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardImage(o.card.imageUrl, Modifier.width(48.dp), o.card.name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameDot(o.card.game); Spacer(Modifier.width(6.dp))
                Text(o.card.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${o.card.setName} · #${o.card.number}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                listOfNotNull(
                    o.card.rarity.takeIf { it.isNotEmpty() },
                    o.entry.finish.label.takeIf { o.entry.finish != Finish.NORMAL },
                    o.entry.condition.short,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatUsd(o.totalUsd), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (o.entry.quantity > 1) Text("${o.entry.quantity} × ${formatUsd(o.unitUsd)}", style = MaterialTheme.typography.labelSmall)
            ChangeText(o.unitUsd, o.unitValue?.previousDayUsd)
        }
    }
}

/** A card in a grid / binder pocket, with a quantity badge and its value. */
@Composable
fun OwnedCardTile(o: OwnedCard, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(onClick = onClick)) {
        Box {
            CardImage(o.card.imageUrl, Modifier.fillMaxWidth().holoShimmer(o.entry.finish != Finish.NORMAL), o.card.name)
            if (o.entry.quantity > 1) {
                Text(
                    "×${o.entry.quantity}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                        .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (o.entry.finish != Finish.NORMAL) {
                Text(
                    o.entry.finish.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiary,
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                        .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.tertiary)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(o.card.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(formatUsd(o.unitUsd), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}
