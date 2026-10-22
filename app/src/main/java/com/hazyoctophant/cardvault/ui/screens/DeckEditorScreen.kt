package com.hazyoctophant.cardvault.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.hazyoctophant.cardvault.AppContainer
import com.hazyoctophant.cardvault.data.db.CardEntity
import com.hazyoctophant.cardvault.data.db.DeckCardEntity
import com.hazyoctophant.cardvault.data.db.DeckCardWithCard
import com.hazyoctophant.cardvault.data.db.DeckEntity
import com.hazyoctophant.cardvault.model.DeckZone
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.strategy.DeckAnalysis
import com.hazyoctophant.cardvault.strategy.DeckAnalyzer
import com.hazyoctophant.cardvault.strategy.DeckEntry
import com.hazyoctophant.cardvault.strategy.DeckFormat
import com.hazyoctophant.cardvault.strategy.DeckRules
import com.hazyoctophant.cardvault.strategy.DeckSuggester
import com.hazyoctophant.cardvault.strategy.Formats
import com.hazyoctophant.cardvault.strategy.Issue
import com.hazyoctophant.cardvault.strategy.OwnedStock
import com.hazyoctophant.cardvault.strategy.Severity
import com.hazyoctophant.cardvault.strategy.Suggestion
import com.hazyoctophant.cardvault.ui.Routes
import com.hazyoctophant.cardvault.ui.appViewModel
import com.hazyoctophant.cardvault.ui.components.BarChart
import com.hazyoctophant.cardvault.ui.components.CardImage
import com.hazyoctophant.cardvault.ui.components.ChoiceRow
import com.hazyoctophant.cardvault.ui.components.LabeledValue
import com.hazyoctophant.cardvault.ui.components.ScreenScaffold
import com.hazyoctophant.cardvault.ui.components.SectionTitle
import com.hazyoctophant.cardvault.ui.formatUsd
import com.hazyoctophant.cardvault.ui.theme.Gain
import com.hazyoctophant.cardvault.ui.theme.Loss
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DeckState(
    val deck: DeckEntity,
    val format: DeckFormat,
    val cards: List<DeckCardWithCard>,
    val owned: Map<String, Int>,
    val analysis: DeckAnalysis,
    val issues: List<Issue>,
    val suggestions: List<Suggestion>,
) {
    val value: Double get() = cards.sumOf { it.unitUsd * it.deckCard.quantity }
    /** Copies in the deck beyond what the user owns, and what they'd cost to buy. */
    val missing: List<Pair<DeckCardWithCard, Int>>
        get() {
            val used = mutableMapOf<String, Int>()
            return cards.mapNotNull { c ->
                val have = (owned[c.card.id] ?: 0) - (used[c.card.id] ?: 0)
                used[c.card.id] = (used[c.card.id] ?: 0) + c.deckCard.quantity
                val short = c.deckCard.quantity - have.coerceAtLeast(0)
                if (short > 0) c to short else null
            }
        }
    val costToComplete: Double get() = missing.sumOf { (c, n) -> c.unitUsd * n }
}

class DeckEditorViewModel(private val c: AppContainer, val deckId: Long) : ViewModel() {
    val state = combine(c.library.observeDeck(deckId), c.library.observeDeckCards(deckId), c.cards.observeCollection()) { deck, cards, owned ->
        if (deck == null) return@combine null
        val format = Formats.get(deck.game, deck.format)
        val entries = cards.map { DeckEntry(it.card, it.deckCard.quantity, it.deckCard.zone) }
        val stock = owned.filter { it.card.game == deck.game }.groupBy { it.card.id }
            .map { (_, stacks) -> OwnedStock(stacks.first().card, stacks.sumOf { it.entry.quantity }) }
        val inDeck = cards.groupBy { it.card.id }.mapValues { e -> e.value.sumOf { it.deckCard.quantity } }
        val available = stock.map { it.copy(quantity = it.quantity - (inDeck[it.card.id] ?: 0)) }.filter { it.quantity > 0 }
        val analysis = DeckAnalyzer.analyze(format, entries)
        DeckState(
            deck = deck,
            format = format,
            cards = cards.sortedWith(compareBy({ it.deckCard.zone.ordinal }, { it.card.cost ?: 0.0 }, { it.card.name })),
            owned = stock.associate { it.card.id to it.quantity },
            analysis = analysis,
            issues = DeckRules.validate(format, entries),
            suggestions = DeckSuggester.suggest(format, entries, available, analysis),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun change(cardId: String, zone: DeckZone, delta: Int) = viewModelScope.launch { c.library.changeDeckCard(deckId, cardId, zone, delta) }

    fun move(card: DeckCardWithCard, to: DeckZone) = viewModelScope.launch {
        c.library.changeDeckCard(deckId, card.card.id, card.deckCard.zone, -1)
        c.library.changeDeckCard(deckId, card.card.id, to, 1)
    }

    fun add(card: CardEntity, zone: DeckZone = DeckZone.MAIN) = change(card.id, zone, 1)

    /** Fill the main deck from owned cards using the suggester. Returns how many cards were added. */
    suspend fun autoFill(): Int {
        val s = state.value ?: return 0
        val entries = s.cards.map { DeckEntry(it.card, it.deckCard.quantity, it.deckCard.zone) }
        val stock = s.owned.mapNotNull { (id, qty) ->
            (s.cards.firstOrNull { it.card.id == id }?.card ?: c.db.cards().get(id))?.let { OwnedStock(it, qty) }
        }
        val additions = DeckSuggester.autoFill(s.format, entries, stock)
        val merged = (s.cards.map { it.deckCard } + additions.map { (card, n) -> DeckCardEntity(deckId, card.id, DeckZone.MAIN, n) })
            .groupBy { it.cardId to it.zone }
            .map { (_, list) -> list.first().copy(quantity = list.sumOf { it.quantity }) }
        c.library.replaceDeckCards(deckId, merged)
        return additions.sumOf { it.second }
    }

    fun update(deck: DeckEntity) = viewModelScope.launch { c.library.updateDeck(deck) }
    suspend fun delete(deck: DeckEntity) = c.library.deleteDeck(deck)
}

@Composable
fun DeckEditorScreen(nav: NavController, deckId: Long) {
    val vm = appViewModel(key = "deck-$deckId") { DeckEditorViewModel(it, deckId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val s = state

    ScreenScaffold(
        title = s?.deck?.name ?: "Deck",
        nav = nav,
        snackbar = snackbar,
        actions = {
            IconButton(onClick = {
                s ?: return@IconButton
                val text = deckListText(s)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share deck list"))
            }) { Icon(Icons.Default.Share, "Share deck list") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Rename / change format") }, onClick = { menu = false; editing = true })
                    DropdownMenuItem({ Text("Delete deck") }, onClick = {
                        menu = false
                        val d = s?.deck ?: return@DropdownMenuItem
                        scope.launch { vm.delete(d); nav.popBackStack() }
                    })
                }
            }
        },
        floatingActionButton = {
            if (s != null && tab == 0) {
                FloatingActionButton(onClick = { nav.navigate(Routes.search(s.deck.game, deckId = deckId)) }) { Icon(Icons.Default.Add, "Add cards") }
            }
        },
    ) { padding ->
        if (s == null) return@ScreenScaffold
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            DeckHeader(s)
            TabRow(tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Cards") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Analysis") })
                Tab(tab == 2, { tab = 2 }, text = { Text("Suggestions") })
            }
            when (tab) {
                0 -> CardsTab(s, vm, onOpen = { nav.navigate(Routes.card(it)) }, onAutoFill = {
                    scope.launch {
                        val n = vm.autoFill()
                        snackbar.showSnackbar(if (n == 0) "No more matching cards in your collection." else "Added $n cards from your collection.")
                    }
                })
                1 -> AnalysisTab(s)
                else -> SuggestionsTab(s, onAdd = { vm.add(it) }, onOpen = { nav.navigate(Routes.card(it)) })
            }
        }
    }

    if (editing && s != null) {
        var name by remember { mutableStateOf(s.deck.name) }
        var format by remember { mutableStateOf(s.format) }
        var notes by remember { mutableStateOf(s.deck.notes) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Deck settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    ChoiceRow(Formats.forGame(s.deck.game), format, { it.label }) { format = it }
                    OutlinedTextField(notes, { notes = it }, label = { Text("Notes / game plan") }, minLines = 3)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.update(s.deck.copy(name = name.ifBlank { s.deck.name }, format = format.id, notes = notes))
                    editing = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DeckHeader(s: DeckState) {
    val main = s.cards.filter { it.deckCard.zone == DeckZone.MAIN }.sumOf { it.deckCard.quantity }
    val errors = s.issues.count { it.severity == Severity.ERROR }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LabeledValue("Main", "$main / ${s.format.targetSize}${if (s.format.maxMain == null) "+" else ""}")
        LabeledValue("Value", formatUsd(s.value))
        LabeledValue("To complete", formatUsd(s.costToComplete))
        LabeledValue("Status", if (errors == 0) "Legal" else "$errors issue${if (errors > 1) "s" else ""}")
    }
    Text(
        "${s.format.label} · ${s.analysis.archetype}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun CardsTab(s: DeckState, vm: DeckEditorViewModel, onOpen: (String) -> Unit, onAutoFill: () -> Unit) {
    val missing = s.missing.associate { it.first.card.id to it.second }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Row(Modifier.padding(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onAutoFill) { Icon(Icons.Default.AutoAwesome, null); Text(" Auto-build from my cards") }
            }
        }
        s.cards.groupBy { it.deckCard.zone }.forEach { (zone, cards) ->
            item(key = "zone-$zone") { SectionTitle("${zone.label} (${cards.sumOf { it.deckCard.quantity }})") }
            cards.groupBy { cardGroup(it.card) }.forEach { (group, list) ->
                if (zone == DeckZone.MAIN) item(key = "g-$zone-$group") {
                    Text("$group (${list.sumOf { it.deckCard.quantity }})", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                items(list, key = { "${it.card.id}-${it.deckCard.zone}" }) { dc ->
                    DeckCardRow(dc, s, missing[dc.card.id], vm, onOpen)
                    HorizontalDivider()
                }
            }
        }
        if (s.cards.isEmpty()) item {
            Text(
                "Add cards with the + button, or let Auto-build pick the best cards you own for this deck.",
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun DeckCardRow(dc: DeckCardWithCard, s: DeckState, missing: Int?, vm: DeckEditorViewModel, onOpen: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { onOpen(dc.card.id) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        CardImage(dc.card.imageUrl, Modifier.width(40.dp), dc.card.name)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(dc.card.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(dc.card.cost?.let { "Cost ${it.toInt()}" }, formatUsd(dc.unitUsd), missing?.let { "need $it more" }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (missing != null) Loss else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { vm.change(dc.card.id, dc.deckCard.zone, -1) }) { Icon(Icons.Default.Remove, "One less") }
        Text("${dc.deckCard.quantity}", style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { vm.change(dc.card.id, dc.deckCard.zone, 1) }) { Icon(Icons.Default.Add, "One more") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Move") }
            DropdownMenu(menu, { menu = false }) {
                zonesFor(s.format).filter { it != dc.deckCard.zone }.forEach { z ->
                    DropdownMenuItem({ Text("Move to ${z.label}") }, onClick = { menu = false; vm.move(dc, z) })
                }
                DropdownMenuItem({ Text("Remove all") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = {
                    menu = false; vm.change(dc.card.id, dc.deckCard.zone, -dc.deckCard.quantity)
                })
            }
        }
    }
}

private fun zonesFor(format: DeckFormat): List<DeckZone> = buildList {
    add(DeckZone.MAIN)
    if (format.sideboardMax > 0) add(DeckZone.SIDEBOARD)
    format.headZone?.let { add(it) }
}

private fun cardGroup(c: CardEntity): String = when (c.game) {
    Game.MTG -> listOf("Creature", "Planeswalker", "Instant", "Sorcery", "Artifact", "Enchantment", "Battle", "Land")
        .firstOrNull { c.typeLine.contains(it) } ?: "Other"
    Game.POKEMON -> when {
        c.typeLine.startsWith("Pok") -> "Pokémon"
        else -> c.typeLine.ifEmpty { "Other" }
    }
    Game.ONE_PIECE -> c.typeLine.lowercase().replaceFirstChar { it.uppercase() }.ifEmpty { "Other" }
}

@Composable
private fun AnalysisTab(s: DeckState) {
    val a = s.analysis
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Archetype: ${a.archetype}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(a.archetypeSummary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                a.stats.chunked(2).forEach { pair ->
                    Row { pair.forEach { st -> LabeledValue(st.label, st.value, Modifier.weight(1f)) } ; if (pair.size == 1) Spacer(Modifier.weight(1f)) }
                }
            }
        }
        item { SectionTitle(if (s.deck.game == Game.POKEMON) "Card mix" else "Cost curve") }
        item { BarChart(a.curve, Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
        if (a.colors.isNotEmpty()) {
            item { SectionTitle("Colors / types") }
            item { BarChart(a.colors, Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.secondary) }
        }
        item { SectionTitle("Rules check") }
        items(s.issues) { IssueRow(it) }
        item { SectionTitle("Strategy tips") }
        items(a.tips) { IssueRow(it) }
        if (s.deck.notes.isNotBlank()) {
            item { SectionTitle("Your notes") }
            item { Text(s.deck.notes, Modifier.padding(horizontal = 16.dp)) }
        }
    }
}

@Composable
private fun IssueRow(issue: Issue) {
    val (icon, tint) = when (issue.severity) {
        Severity.ERROR -> Icons.Default.Error to MaterialTheme.colorScheme.error
        Severity.WARNING -> Icons.Default.Warning to Color(0xFFE0A030)
        Severity.GOOD -> Icons.Default.CheckCircle to Gain
        Severity.INFO -> Icons.Default.Info to MaterialTheme.colorScheme.primary
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(issue.message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SuggestionsTab(s: DeckState, onAdd: (CardEntity) -> Unit, onOpen: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Text(
                "Cards from your collection that fit this deck, ranked by synergy with what's already in it and by what the analysis says the deck is missing.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (s.suggestions.isEmpty()) item {
            Text("No suggestions yet — add a few core cards (or a Leader / Commander) first, or scan more cards.", Modifier.padding(16.dp))
        }
        items(s.suggestions, key = { it.card.id }) { sug ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(sug.card.id) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CardImage(sug.card.imageUrl, Modifier.width(48.dp), sug.card.name)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(sug.card.name, style = MaterialTheme.typography.titleSmall)
                    sug.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    Text("You have ${sug.available} available", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onAdd(sug.card) }) { Icon(Icons.Default.Add, "Add to deck") }
            }
            HorizontalDivider()
        }
    }
}

/** Plain-text deck list in the common "4 Card Name" format, grouped by zone. */
private fun deckListText(s: DeckState): String = buildString {
    appendLine("${s.deck.name} — ${s.deck.game.label} ${s.format.label}")
    s.cards.groupBy { it.deckCard.zone }.forEach { (zone, cards) ->
        appendLine()
        appendLine("${zone.label}:")
        cards.forEach { appendLine("${it.deckCard.quantity} ${it.card.name} (${it.card.setCode}) ${it.card.number}") }
    }
    appendLine()
    appendLine("Value: ${formatUsd(s.value)}")
}
