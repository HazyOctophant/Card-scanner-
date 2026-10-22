package com.hazyoctophant.cardvault.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.hazyoctophant.cardvault.AppContainer
import com.hazyoctophant.cardvault.data.db.CardEntity
import com.hazyoctophant.cardvault.data.remote.CatalogCard
import com.hazyoctophant.cardvault.model.Condition
import com.hazyoctophant.cardvault.model.DeckZone
import com.hazyoctophant.cardvault.model.Finish
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.pricing.Valuation
import com.hazyoctophant.cardvault.scan.ScanViewModel
import com.hazyoctophant.cardvault.ui.appViewModel
import com.hazyoctophant.cardvault.ui.components.AddCardDialog
import com.hazyoctophant.cardvault.ui.components.CardImage
import com.hazyoctophant.cardvault.ui.components.GameFilterChips
import com.hazyoctophant.cardvault.ui.components.ScreenScaffold
import com.hazyoctophant.cardvault.ui.formatUsd
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SearchViewModel(private val c: AppContainer) : ViewModel() {
    private val _results = MutableStateFlow<List<CatalogCard>>(emptyList())
    val results = _results.asStateFlow()
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val owned = c.cards.observeCollection().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val binders = c.library.observeBinders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = c.settings.settings

    fun search(game: Game, query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            loading.value = true
            error.value = null
            runCatching { c.cards.search(game, query) }
                .onSuccess { _results.value = it; if (it.isEmpty()) error.value = "No cards found for \"$query\"." }
                .onFailure { error.value = "Search failed: ${it.message}" }
            loading.value = false
        }
    }

    suspend fun addToCollection(card: CatalogCard, qty: Int, finish: Finish, condition: Condition, binderId: Long?) =
        c.cards.addToCollection(card, qty, finish, condition, binderId)

    suspend fun addToDeck(deckId: Long, card: CatalogCard, zone: DeckZone) = c.library.addCatalogCardToDeck(deckId, card, zone)
}

/**
 * Find a card by name in the online databases. Adds to the collection, or to a deck when opened from
 * the deck editor (where "My collection" lets you pick from cards you own).
 */
@Composable
fun SearchScreen(nav: NavController, initialGame: Game?, initialQuery: String, deckId: Long?, zone: DeckZone) {
    val vm = appViewModel { SearchViewModel(it) }
    val results by vm.results.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val owned by vm.owned.collectAsStateWithLifecycle()
    val binders by vm.binders.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var game by rememberSaveable { mutableStateOf(initialGame ?: Game.MTG) }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var tab by rememberSaveable { mutableStateOf(if (deckId != null) 0 else 1) }
    var adding by remember { mutableStateOf<CatalogCard?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { if (initialQuery.isNotBlank() && deckId == null) vm.search(game, initialQuery) }

    fun pick(card: CatalogCard) {
        if (deckId != null) {
            val z = if (card.card.game == Game.ONE_PIECE && card.card.typeLine == "LEADER") DeckZone.LEADER else zone
            scope.launch {
                vm.addToDeck(deckId, card, z)
                snackbar.showSnackbar("Added ${card.card.name} to ${z.label.lowercase()}")
            }
        } else {
            adding = card
        }
    }

    ScreenScaffold(title = if (deckId != null) "Add to deck" else "Add card", nav = nav, snackbar = snackbar) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            if (deckId == null) GameFilterChips(game, { g -> game = g ?: Game.MTG; vm.search(game, query) }, allowAll = false)
            if (deckId != null) {
                TabRow(tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("My collection") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("All cards") })
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(if (game == Game.ONE_PIECE) "Name or card number (OP01-001)" else "Card name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (tab == 1) vm.search(game, query) }),
                trailingIcon = { IconButton(onClick = { if (tab == 1) vm.search(game, query) }) { Icon(Icons.Default.Search, "Search") } },
                modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (tab == 0) {
                // Cards already owned, filtered locally.
                val q = query.trim().lowercase()
                val mine = owned.filter { it.card.game == game && (q.isEmpty() || it.card.name.lowercase().contains(q)) }
                    .groupBy { it.card.id }.map { (_, stacks) -> stacks.first().card to stacks.sumOf { it.entry.quantity } }
                    .sortedBy { it.first.name }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(mine, key = { it.first.id }) { (card, qty) ->
                        ResultRow(card, "Own $qty", null) { pick(CatalogCard(card, emptyList())) }
                        HorizontalDivider()
                    }
                }
            } else {
                error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(results, key = { it.card.id }) { r ->
                        val values = Valuation.values(r.prices, settings.valuationMode, settings.eurToUsd)
                        val price = values[ScanViewModel.defaultFinish(r)] ?: values.values.firstOrNull()
                        ResultRow(r.card, r.card.rarity, price) { pick(r) }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    adding?.let { card ->
        AddCardDialog(
            card = card.card,
            binders = binders.map { it.binder.id to it.binder.name },
            initialFinish = ScanViewModel.defaultFinish(card),
            onDismiss = { adding = null },
            onAdd = { qty, finish, condition, binderId ->
                adding = null
                scope.launch {
                    vm.addToCollection(card, qty, finish, condition, binderId)
                    snackbar.showSnackbar("Added ${card.card.name}")
                }
            },
        )
    }
}

@Composable
private fun ResultRow(card: CardEntity, detail: String, price: Double?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CardImage(card.imageUrl, Modifier.width(52.dp), card.name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(card.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${card.setName} · #${card.number}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.labelSmall)
        }
        price?.let { Text(formatUsd(it), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
    }
}
