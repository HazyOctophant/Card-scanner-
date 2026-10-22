package com.hazyoctophant.cardvault.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.work.WorkInfo
import com.hazyoctophant.cardvault.AppContainer
import com.hazyoctophant.cardvault.data.db.OwnedCard
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.pricing.PriceRefreshWorker
import com.hazyoctophant.cardvault.ui.Routes
import com.hazyoctophant.cardvault.ui.appContainer
import com.hazyoctophant.cardvault.ui.appViewModel
import com.hazyoctophant.cardvault.ui.components.CardImage
import com.hazyoctophant.cardvault.ui.components.ChangeText
import com.hazyoctophant.cardvault.ui.components.EmptyState
import com.hazyoctophant.cardvault.ui.components.GameDot
import com.hazyoctophant.cardvault.ui.components.LineChart
import com.hazyoctophant.cardvault.ui.components.ScreenScaffold
import com.hazyoctophant.cardvault.ui.components.SectionTitle
import com.hazyoctophant.cardvault.ui.formatSignedUsd
import com.hazyoctophant.cardvault.ui.formatTimestamp
import com.hazyoctophant.cardvault.ui.formatUsd
import com.hazyoctophant.cardvault.ui.navigateTab
import com.hazyoctophant.cardvault.ui.theme.Gain
import com.hazyoctophant.cardvault.ui.theme.Loss
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class GameTotal(val game: Game, val cards: Int, val value: Double)

data class HomeState(
    val total: Double = 0.0,
    val previousTotal: Double? = null,
    val cardCount: Int = 0,
    val perGame: List<GameTotal> = emptyList(),
    val history: List<Double> = emptyList(),
    val mostValuable: List<OwnedCard> = emptyList(),
    val movers: List<OwnedCard> = emptyList(),
    val loaded: Boolean = false,
)

class HomeViewModel(c: AppContainer) : ViewModel() {
    val state = combine(c.cards.observeCollection(), c.db.prices().observeCollectionHistory()) { owned, history ->
        val byDay = history.groupBy { it.epochDay }.toSortedMap().mapValues { e -> e.value.sumOf { it.totalUsd } }
        val total = owned.sumOf { it.totalUsd }
        // Yesterday's total as the price sheet saw it, for the headline change.
        val previous = owned.sumOf { o -> (o.unitValue?.previousDayUsd ?: o.unitUsd) * o.entry.quantity }
        HomeState(
            total = total,
            previousTotal = previous.takeIf { owned.any { it.unitValue?.previousDayUsd != null } },
            cardCount = owned.sumOf { it.entry.quantity },
            perGame = Game.entries.map { g ->
                val list = owned.filter { it.card.game == g }
                GameTotal(g, list.sumOf { it.entry.quantity }, list.sumOf { it.totalUsd })
            },
            history = byDay.values.toList().takeLast(90),
            mostValuable = owned.sortedByDescending { it.unitUsd }.distinctBy { it.card.id }.take(12),
            movers = owned.filter { it.unitValue?.previousDayUsd != null && it.unitUsd > 0.5 }
                .sortedByDescending { o -> kotlin.math.abs(o.unitUsd - (o.unitValue?.previousDayUsd ?: o.unitUsd)) }
                .distinctBy { it.card.id }.take(12),
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeState())
}

@Composable
fun HomeScreen(nav: NavController) {
    val vm = appViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings by appContainer().settings.settings.collectAsState()
    val work by PriceRefreshWorker.observeNow(context).collectAsStateWithLifecycle(emptyList())
    val refreshing = work.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }

    ScreenScaffold(
        title = "Card Vault",
        actions = {
            if (refreshing) CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { PriceRefreshWorker.runNow(context) }) { Icon(Icons.Default.Refresh, "Refresh prices") }
            IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Icon(Icons.Default.Settings, "Settings") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
            item {
                ElevatedCard(Modifier.fillMaxWidth().padding(16.dp).clickable { nav.navigate(Routes.PRICES) }) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Collection value", style = MaterialTheme.typography.labelLarge)
                        Text(formatUsd(state.total), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        state.previousTotal?.let { prev ->
                            val delta = state.total - prev
                            Text(
                                "${formatSignedUsd(delta)} since yesterday",
                                color = if (delta >= 0) Gain else Loss,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${state.cardCount} cards · prices updated ${formatTimestamp(settings.lastRefreshAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        LineChart(state.history, Modifier.fillMaxWidth().height(80.dp))
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.perGame.forEach { g ->
                        Card(Modifier.weight(1f).clickable { nav.navigateTab(Routes.COLLECTION) }) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    GameDot(g.game); Spacer(Modifier.width(6.dp))
                                    Text(g.game.short, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                }
                                Text(formatUsd(g.value), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text("${g.cards} cards", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { nav.navigateTab(Routes.SCAN) }, label = { Text("Scan") }, leadingIcon = { Icon(Icons.Default.CameraAlt, null) })
                    AssistChip(onClick = { nav.navigate(Routes.MTG_DATABASE) }, label = { Text("Magic database") }, leadingIcon = { Icon(Icons.Default.Storage, null) })
                    AssistChip(onClick = { nav.navigate(Routes.PRICES) }, label = { Text("Price sheet") }, leadingIcon = { Icon(Icons.Default.TableChart, null) })
                    AssistChip(onClick = { nav.navigateTab(Routes.BINDERS) }, label = { Text("Binders") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) })
                    AssistChip(onClick = { nav.navigateTab(Routes.DECKS) }, label = { Text("Decks") }, leadingIcon = { Icon(Icons.Default.Layers, null) })
                }
            }
            if (state.loaded && state.cardCount == 0) {
                item {
                    EmptyState(
                        Icons.Default.CameraAlt,
                        "Scan your first card",
                        "Point the camera at a Magic, Pokémon or One Piece card. Card Vault reads the card number, finds it, and tracks its price every day.",
                    )
                }
            }
            if (settings.lastRefreshError != null) {
                item {
                    Text(
                        "Last update had problems: ${settings.lastRefreshError}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            if (state.mostValuable.isNotEmpty()) {
                item { SectionTitle("Most valuable") }
                item { CardRow(state.mostValuable) { nav.navigate(Routes.card(it)) } }
            }
            if (state.movers.isNotEmpty()) {
                item { SectionTitle("Biggest moves today") }
                item { CardRow(state.movers) { nav.navigate(Routes.card(it)) } }
            }
        }
    }
}

@Composable
private fun CardRow(cards: List<OwnedCard>, onClick: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(cards, key = { it.entry.id }) { o ->
            Column(Modifier.width(110.dp).clickable { onClick(o.card.id) }) {
                CardImage(o.card.imageUrl, Modifier.fillMaxWidth(), o.card.name)
                Text(o.card.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatUsd(o.unitUsd), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(4.dp))
                    ChangeText(o.unitUsd, o.unitValue?.previousDayUsd)
                }
            }
        }
    }
}
