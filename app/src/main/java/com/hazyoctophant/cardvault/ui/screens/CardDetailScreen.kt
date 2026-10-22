package com.hazyoctophant.cardvault.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.hazyoctophant.cardvault.AppContainer
import com.hazyoctophant.cardvault.data.db.CardEntity
import com.hazyoctophant.cardvault.data.db.CollectionEntryEntity
import com.hazyoctophant.cardvault.data.db.OwnedCard
import com.hazyoctophant.cardvault.data.db.PriceEntity
import com.hazyoctophant.cardvault.data.remote.CatalogCard
import com.hazyoctophant.cardvault.model.Condition
import com.hazyoctophant.cardvault.model.DeckZone
import com.hazyoctophant.cardvault.model.Finish
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.pricing.Valuation
import com.hazyoctophant.cardvault.ui.Routes
import com.hazyoctophant.cardvault.ui.appViewModel
import com.hazyoctophant.cardvault.ui.components.AddCardDialog
import com.hazyoctophant.cardvault.ui.components.CardImage
import com.hazyoctophant.cardvault.ui.components.ChoiceRow
import com.hazyoctophant.cardvault.ui.components.LineChart
import com.hazyoctophant.cardvault.ui.components.ScreenScaffold
import com.hazyoctophant.cardvault.ui.components.SectionTitle
import com.hazyoctophant.cardvault.ui.formatMoney
import com.hazyoctophant.cardvault.ui.formatTimestamp
import com.hazyoctophant.cardvault.ui.formatUsd
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class CardDetailViewModel(private val c: AppContainer, val cardId: String) : ViewModel() {
    val card = c.cards.observeCard(cardId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val owned = c.cards.observeOwned(cardId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val prices = c.db.prices().observePrices(cardId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val historyFinish = MutableStateFlow(Finish.NORMAL)
    val history = historyFinish.flatMapLatest { c.db.prices().observeHistory(cardId, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val binders = c.library.observeBinders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val decks = c.library.observeDecks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = c.settings.settings

    fun update(entry: CollectionEntryEntity) = viewModelScope.launch { c.cards.updateEntry(entry) }
    fun delete(entry: CollectionEntryEntity) = viewModelScope.launch { c.cards.deleteEntry(entry) }
    fun addCopies(card: CardEntity, quantity: Int, finish: Finish, condition: Condition, binderId: Long?) = viewModelScope.launch {
        c.cards.addToCollection(CatalogCard(card, prices.value), quantity, finish, condition, binderId)
    }
    fun addToDeck(deckId: Long, card: CardEntity, zone: DeckZone) = viewModelScope.launch {
        c.library.changeDeckCard(deckId, card.id, zone, 1)
    }
}

@Composable
fun CardDetailScreen(nav: NavController, cardId: String) {
    val vm = appViewModel(key = cardId) { CardDetailViewModel(it, cardId) }
    val card by vm.card.collectAsStateWithLifecycle()
    val owned by vm.owned.collectAsStateWithLifecycle()
    val prices by vm.prices.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val binders by vm.binders.collectAsStateWithLifecycle()
    val decks by vm.decks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var deckDialog by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf(false) }

    val c = card
    ScreenScaffold(
        title = c?.name ?: "Card",
        nav = nav,
        actions = {
            c?.marketUrl?.let { url ->
                IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open on market site")
                }
            }
        },
    ) { padding ->
        if (c == null) return@ScreenScaffold
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(16.dp)) {
                CardImage(c.imageUrlLarge ?: c.imageUrl, Modifier.width(170.dp), c.name)
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(c.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${c.setName} (${c.setCode}) · #${c.number}", style = MaterialTheme.typography.bodyMedium)
                    if (c.rarity.isNotEmpty()) Text(c.rarity, style = MaterialTheme.typography.bodyMedium)
                    Text(listOf(c.typeLine, c.subtypeList.joinToString(" · ")).filter { it.isNotEmpty() }.joinToString(" — "), style = MaterialTheme.typography.bodySmall)
                    c.cost?.let { Text(if (c.game == Game.MTG) "Mana value ${it.toInt()}" else "Cost ${it.toInt()}", style = MaterialTheme.typography.bodySmall) }
                    c.power?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    c.counter?.let { Text("Counter +$it", style = MaterialTheme.typography.bodySmall) }
                    c.evolvesFrom?.let { Text("Evolves from $it", style = MaterialTheme.typography.bodySmall) }
                    if (c.colors.isNotEmpty()) Text(c.colorList.joinToString(" / "), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { addDialog = true }) { Icon(Icons.Default.Add, null); Text(" Add copy") }
                    OutlinedButton(onClick = { deckDialog = true }) { Icon(Icons.Default.Layers, null); Text(" Add to deck") }
                }
            }
            if (c.text.isNotBlank()) {
                Text(c.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
            }

            SectionTitle("Prices")
            PriceTable(prices, settings.eurToUsd)
            Text(
                "Value uses: ${settings.valuationMode.label} · €1 = ${"%.3f".format(settings.eurToUsd)} USD · updated ${formatTimestamp(prices.maxOfOrNull { it.fetchedAt } ?: 0)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            val finishes = prices.map { it.finish }.distinct()
            if (finishes.isNotEmpty()) {
                SectionTitle("Price history")
                val selected by vm.historyFinish.collectAsStateWithLifecycle()
                if (finishes.size > 1) ChoiceRow(finishes, selected, { it.label }) { vm.historyFinish.value = it }
                LineChart(history.map { it.valueUsd }, Modifier.fillMaxWidth().height(120.dp).padding(16.dp))
                if (history.isNotEmpty()) {
                    Text(
                        "Low ${formatUsd(history.minOf { it.valueUsd })} · High ${formatUsd(history.maxOf { it.valueUsd })} over ${history.size} days",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            SectionTitle("In your collection")
            if (owned.isEmpty()) {
                Text("You don't own this card yet.", modifier = Modifier.padding(horizontal = 16.dp))
            }
            owned.forEach { o ->
                OwnedStackEditor(o, binders.map { it.binder.id to it.binder.name }, vm::update, vm::delete)
                HorizontalDivider()
            }
            Spacer(Modifier.height(32.dp))
        }

        if (addDialog) {
            AddCardDialog(
                card = c,
                binders = binders.map { it.binder.id to it.binder.name },
                onDismiss = { addDialog = false },
                onAdd = { qty, finish, condition, binderId ->
                    vm.addCopies(c, qty, finish, condition, binderId)
                    addDialog = false
                },
            )
        }
        if (deckDialog) {
            val forGame = decks.filter { it.deck.game == c.game }
            AlertDialog(
                onDismissRequest = { deckDialog = false },
                title = { Text("Add to deck") },
                text = {
                    Column {
                        if (forGame.isEmpty()) Text("No ${c.game.label} decks yet. Create one on the Decks tab.")
                        forGame.forEach { d ->
                            TextButton(onClick = {
                                val zone = when {
                                    c.game == Game.ONE_PIECE && c.typeLine == "LEADER" -> DeckZone.LEADER
                                    else -> DeckZone.MAIN
                                }
                                vm.addToDeck(d.deck.id, c, zone)
                                deckDialog = false
                            }) { Text("${d.deck.name} (${d.cardCount})") }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { deckDialog = false; nav.navigate(Routes.DECKS) }) { Text("Decks") } },
                dismissButton = { TextButton(onClick = { deckDialog = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun PriceTable(prices: List<PriceEntity>, eurToUsd: Double) {
    if (prices.isEmpty()) {
        Text("No prices published for this card yet.", modifier = Modifier.padding(horizontal = 16.dp))
        return
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            prices.sortedWith(compareBy({ it.finish.ordinal }, { it.source.ordinal })).forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.source.label, style = MaterialTheme.typography.bodyMedium)
                        Text(p.finish.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(p.market?.let { formatMoney(it, p.currency) } ?: "—", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        if (p.currency == "EUR") Valuation.toUsd(p, eurToUsd)?.let {
                            Text("≈ ${formatUsd(it)}", style = MaterialTheme.typography.labelSmall)
                        }
                        p.low?.let { Text("low ${formatMoney(it, p.currency)}", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OwnedStackEditor(
    o: OwnedCard,
    binders: List<Pair<Long, String>>,
    onUpdate: (CollectionEntryEntity) -> Unit,
    onDelete: (CollectionEntryEntity) -> Unit,
) {
    val e = o.entry
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${e.finish.label} · ${e.condition.label}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${formatUsd(o.unitUsd)} each${if (o.valueIsEstimate) " (est. from ${o.unitValue?.finish?.label})" else ""} · total ${formatUsd(o.totalUsd)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Added ${formatTimestamp(e.addedAt)}", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { onUpdate(e.copy(quantity = e.quantity - 1)) }) { Icon(Icons.Default.Remove, "One less") }
            Text("${e.quantity}", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onUpdate(e.copy(quantity = e.quantity + 1)) }) { Icon(Icons.Default.Add, "One more") }
            IconButton(onClick = { onDelete(e) }) { Icon(Icons.Default.Delete, "Remove from collection") }
        }
        ChoiceRow(Finish.forGame(o.card.game), e.finish, { it.label }) { onUpdate(e.copy(finish = it)) }
        ChoiceRow(Condition.entries, e.condition, { it.short }) { onUpdate(e.copy(condition = it)) }
        ChoiceRow(listOf<Pair<Long?, String>>(null to "No binder") + binders, binders.firstOrNull { it.first == e.binderId } ?: (null to "No binder"), { it.second }) {
            onUpdate(e.copy(binderId = it.first))
        }
    }
}
