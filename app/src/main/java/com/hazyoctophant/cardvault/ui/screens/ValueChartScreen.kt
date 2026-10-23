package com.hazyoctophant.cardvault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.hazyoctophant.cardvault.AppContainer
import com.hazyoctophant.cardvault.data.db.CardEntity
import com.hazyoctophant.cardvault.data.db.OwnedCard
import com.hazyoctophant.cardvault.model.Finish
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.ui.Routes
import com.hazyoctophant.cardvault.ui.appViewModel
import com.hazyoctophant.cardvault.ui.components.CardImage
import com.hazyoctophant.cardvault.ui.components.ChangeText
import com.hazyoctophant.cardvault.ui.components.ChoiceRow
import com.hazyoctophant.cardvault.ui.components.EmptyState
import com.hazyoctophant.cardvault.ui.components.GameFilterChips
import com.hazyoctophant.cardvault.ui.components.ScreenScaffold
import com.hazyoctophant.cardvault.ui.components.holoShimmer
import com.hazyoctophant.cardvault.ui.formatUsd
import com.hazyoctophant.cardvault.ui.theme.accent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/** One owned card (all copies of the same printing and finish combined). */
data class ValuedCard(
    val card: CardEntity,
    val finish: Finish,
    val quantity: Int,
    val unitUsd: Double,
    val previousDayUsd: Double?,
) {
    val totalUsd: Double get() = unitUsd * quantity
}

enum class RankBy(val label: String) { UNIT("Price per card"), TOTAL("Total (price × copies)") }

object ValueRanking {
    /** Combines stacks of the same card + finish (e.g. different conditions or binders). */
    fun combine(owned: List<OwnedCard>): List<ValuedCard> =
        owned.groupBy { it.card.id to it.entry.finish }.map { (_, stacks) ->
            val first = stacks.first()
            ValuedCard(
                card = first.card,
                finish = first.entry.finish,
                quantity = stacks.sumOf { it.entry.quantity },
                unitUsd = first.unitUsd,
                previousDayUsd = first.unitValue?.previousDayUsd,
            )
        }

    fun rank(cards: List<ValuedCard>, by: RankBy, game: Game?): List<ValuedCard> =
        cards.filter { (game == null || it.card.game == game) && it.unitUsd > 0 }
            .sortedByDescending { if (by == RankBy.UNIT) it.unitUsd else it.totalUsd }
}

class ValueChartViewModel(c: AppContainer) : ViewModel() {
    val owned = c.cards.observeCollection().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

/** A bar chart of your most valuable cards, with how many copies you own of each. */
@Composable
fun ValueChartScreen(nav: NavController) {
    val vm = appViewModel { ValueChartViewModel(it) }
    val owned by vm.owned.collectAsStateWithLifecycle()
    var game by rememberSaveable { mutableStateOf<Game?>(null) }
    var rankBy by rememberSaveable { mutableStateOf(RankBy.UNIT) }
    var limit by rememberSaveable { mutableStateOf(25) }

    val all = ValueRanking.combine(owned.orEmpty())
    val ranked = ValueRanking.rank(all, rankBy, game)
    val shown = if (limit == Int.MAX_VALUE) ranked else ranked.take(limit)
    val metric: (ValuedCard) -> Double = { if (rankBy == RankBy.UNIT) it.unitUsd else it.totalUsd }
    val max = shown.maxOfOrNull(metric) ?: 0.0
    val collectionTotal = all.filter { game == null || it.card.game == game }.sumOf { it.totalUsd }
    val shownTotal = shown.sumOf { it.totalUsd }

    ScreenScaffold(title = "Most valuable cards", nav = nav) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { GameFilterChips(game, { game = it }) }
            item { ChoiceRow(RankBy.entries, rankBy, { "Rank by: ${it.label}" }) { rankBy = it } }
            item {
                ChoiceRow(listOf(10, 25, 50, Int.MAX_VALUE), limit, { if (it == Int.MAX_VALUE) "All" else "Top $it" }) { limit = it }
            }
            if (shown.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        val top = shown.first()
                        Text("Most expensive card", style = MaterialTheme.typography.labelLarge)
                        Text(top.card.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "${formatUsd(top.unitUsd)} each · you have ${top.quantity} · ${formatUsd(top.totalUsd)} total",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (collectionTotal > 0) {
                            Text(
                                "These ${shown.size} cards are worth ${formatUsd(shownTotal)}, " +
                                    "${(shownTotal / collectionTotal * 100).toInt()}% of the ${formatUsd(collectionTotal)} total.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            if (owned != null && shown.isEmpty()) item {
                EmptyState(Icons.Default.BarChart, "No priced cards yet", "Scan or add cards, and their prices will appear here after the next update.")
            }
            itemsIndexed(shown, key = { _, v -> "${v.card.id}-${v.finish}" }) { index, v ->
                ValueBar(index + 1, v, metric(v), max) { nav.navigate(Routes.card(v.card.id)) }
            }
        }
    }
}

@Composable
private fun ValueBar(rank: Int, v: ValuedCard, value: Double, max: Double, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(28.dp))
        CardImage(v.card.imageUrl, Modifier.width(40.dp).holoShimmer(v.finish != Finish.NORMAL), v.card.name)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    v.card.name + if (v.finish != Finish.NORMAL) " (${v.finish.label})" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(formatUsd(value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            // The bar: length is proportional to the card's value relative to the top card.
            Box(
                Modifier.fillMaxWidth().height(14.dp).padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(if (max > 0) (value / max).toFloat().coerceIn(0.02f, 1f) else 0f)
                        .clip(RoundedCornerShape(6.dp)).background(v.card.game.accent()),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "×${v.quantity} owned · ${formatUsd(v.unitUsd)} each · ${v.card.setCode}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                ChangeText(v.unitUsd, v.previousDayUsd)
            }
        }
    }
}
