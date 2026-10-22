package com.hazyoctophant.cardvault.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.hazyoctophant.cardvault.AppContainer
import com.hazyoctophant.cardvault.data.db.OwnedCard
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.ui.Routes
import com.hazyoctophant.cardvault.ui.appViewModel
import com.hazyoctophant.cardvault.ui.components.EmptyState
import com.hazyoctophant.cardvault.ui.components.GameFilterChips
import com.hazyoctophant.cardvault.ui.components.OwnedCardRow
import com.hazyoctophant.cardvault.ui.components.OwnedCardTile
import com.hazyoctophant.cardvault.ui.components.ScreenScaffold
import com.hazyoctophant.cardvault.ui.formatUsd
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

enum class SortBy(val label: String) {
    VALUE("Value (high → low)"),
    TOTAL("Total value (qty × price)"),
    CHANGE("Price change today"),
    NAME("Name"),
    SET("Set & number"),
    RARITY("Rarity"),
    COST("Cost / mana value"),
    RECENT("Recently added"),
    QUANTITY("Quantity"),
}

enum class GroupBy(val label: String) {
    NONE("No grouping"),
    GAME("Game"),
    SET("Set"),
    TYPE("Card type"),
    RARITY("Rarity"),
    COLOR("Color / type"),
}

object CollectionQuery {
    fun filter(list: List<OwnedCard>, game: Game?, query: String): List<OwnedCard> {
        val q = query.trim().lowercase()
        return list.filter { o ->
            (game == null || o.card.game == game) &&
                (q.isEmpty() || o.card.name.lowercase().contains(q) || o.card.setName.lowercase().contains(q) ||
                    o.card.number.lowercase().contains(q) || o.card.typeLine.lowercase().contains(q) ||
                    o.card.subtypes.lowercase().contains(q))
        }
    }

    fun sort(list: List<OwnedCard>, by: SortBy): List<OwnedCard> = when (by) {
        SortBy.VALUE -> list.sortedByDescending { it.unitUsd }
        SortBy.TOTAL -> list.sortedByDescending { it.totalUsd }
        SortBy.CHANGE -> list.sortedByDescending { o ->
            val prev = o.unitValue?.previousDayUsd ?: return@sortedByDescending 0.0
            if (prev <= 0) 0.0 else kotlin.math.abs(o.unitUsd - prev) / prev
        }
        SortBy.NAME -> list.sortedBy { it.card.name.lowercase() }
        SortBy.SET -> list.sortedWith(compareBy<OwnedCard>({ it.card.releasedAt ?: "" }, { it.card.setName }, { numberKey(it.card.number) }))
        SortBy.RARITY -> list.sortedWith(compareBy<OwnedCard>({ it.card.rarity }, { it.card.name }))
        SortBy.COST -> list.sortedWith(compareBy<OwnedCard>({ it.card.cost ?: -1.0 }, { it.card.name }))
        SortBy.RECENT -> list.sortedByDescending { it.entry.addedAt }
        SortBy.QUANTITY -> list.sortedByDescending { it.entry.quantity }
    }

    fun group(list: List<OwnedCard>, by: GroupBy): List<Pair<String, List<OwnedCard>>> = when (by) {
        GroupBy.NONE -> listOf("" to list)
        GroupBy.GAME -> list.groupBy { it.card.game.label }.toList()
        GroupBy.SET -> list.groupBy { it.card.setName.ifEmpty { "Unknown set" } }.toList()
        GroupBy.TYPE -> list.groupBy { typeGroup(it) }.toList()
        GroupBy.RARITY -> list.groupBy { it.card.rarity.ifEmpty { "Unknown" } }.toList()
        GroupBy.COLOR -> list.groupBy { it.card.colorList.joinToString("/").ifEmpty { "Colorless" } }.toList()
    }.sortedByDescending { (_, cards) -> cards.sumOf { it.totalUsd } }

    private fun typeGroup(o: OwnedCard): String {
        val t = o.card.typeLine
        return when (o.card.game) {
            Game.MTG -> listOf("Creature", "Planeswalker", "Instant", "Sorcery", "Artifact", "Enchantment", "Land", "Battle")
                .firstOrNull { t.contains(it) } ?: t.ifEmpty { "Other" }
            Game.POKEMON -> if (t.startsWith("Pok")) "Pokémon" else t.ifEmpty { "Other" }
            Game.ONE_PIECE -> t.lowercase().replaceFirstChar { it.uppercase() }.ifEmpty { "Other" }
        }
    }

    /** Sort "9" before "10" and "OP01-002" before "OP01-010". */
    fun numberKey(n: String): String = n.replace(Regex("\\d+")) { it.value.padStart(5, '0') }
}

class CollectionViewModel(c: AppContainer) : ViewModel() {
    val owned = c.cards.observeCollection().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@Composable
fun CollectionScreen(nav: NavController) {
    val vm = appViewModel { CollectionViewModel(it) }
    val owned by vm.owned.collectAsStateWithLifecycle()
    var game by rememberSaveable { mutableStateOf<Game?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SortBy.VALUE) }
    var group by rememberSaveable { mutableStateOf(GroupBy.NONE) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var sortMenu by rememberSaveable { mutableStateOf(false) }
    var groupMenu by rememberSaveable { mutableStateOf(false) }

    val visible = CollectionQuery.sort(CollectionQuery.filter(owned.orEmpty(), game, query), sort)
    val groups = CollectionQuery.group(visible, group)

    ScreenScaffold(
        title = "Collection",
        actions = {
            IconButton(onClick = { nav.navigate(Routes.PRICES) }) { Icon(Icons.Default.TableChart, "Price sheet") }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortBy.entries.forEach { s ->
                        DropdownMenuItem(text = { Text((if (s == sort) "✓ " else "") + s.label) }, onClick = { sort = s; sortMenu = false })
                    }
                }
            }
            Box {
                IconButton(onClick = { groupMenu = true }) { Icon(Icons.Default.Workspaces, "Group") }
                DropdownMenu(groupMenu, onDismissRequest = { groupMenu = false }) {
                    GroupBy.entries.forEach { g ->
                        DropdownMenuItem(text = { Text((if (g == group) "✓ " else "") + g.label) }, onClick = { group = g; groupMenu = false })
                    }
                }
            }
            IconButton(onClick = { grid = !grid }) {
                Icon(if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, "Toggle grid")
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate(Routes.search(game)) }) { Icon(Icons.Default.Add, "Add card by search") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search name, set, type…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            GameFilterChips(game, { game = it })
            Text(
                "${visible.sumOf { it.entry.quantity }} cards · ${formatUsd(visible.sumOf { it.totalUsd })}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            when {
                owned == null -> Unit
                owned!!.isEmpty() -> EmptyState(Icons.Default.Collections, "No cards yet", "Scan cards with the camera or add them with the + button.")
                visible.isEmpty() -> EmptyState(Icons.Default.Search, "No matches", "Try a different search or filter.")
                grid -> LazyVerticalGrid(
                    GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    groups.forEach { (title, cards) ->
                        if (title.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { GroupHeader(title, cards) }
                        items(cards, key = { it.entry.id }) { o -> OwnedCardTile(o, { nav.navigate(Routes.card(o.card.id)) }) }
                    }
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    groups.forEach { (title, cards) ->
                        if (title.isNotEmpty()) item(key = "h-$title") { GroupHeader(title, cards) }
                        items(cards, key = { it.entry.id }) { o ->
                            OwnedCardRow(o, { nav.navigate(Routes.card(o.card.id)) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String, cards: List<OwnedCard>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("${cards.sumOf { it.entry.quantity }} · ${formatUsd(cards.sumOf { it.totalUsd })}", style = MaterialTheme.typography.labelMedium)
    }
}
