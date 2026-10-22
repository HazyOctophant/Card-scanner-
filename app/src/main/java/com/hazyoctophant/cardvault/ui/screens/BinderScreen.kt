package com.hazyoctophant.cardvault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.hazyoctophant.cardvault.AppContainer
import com.hazyoctophant.cardvault.data.db.BinderEntity
import com.hazyoctophant.cardvault.data.db.OwnedCard
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.ui.ALL_CARDS_BINDER
import com.hazyoctophant.cardvault.ui.Routes
import com.hazyoctophant.cardvault.ui.UNFILED_BINDER
import com.hazyoctophant.cardvault.ui.appViewModel
import com.hazyoctophant.cardvault.ui.components.CardImage
import com.hazyoctophant.cardvault.ui.components.EmptyState
import com.hazyoctophant.cardvault.ui.components.GameFilterChips
import com.hazyoctophant.cardvault.ui.components.OwnedCardTile
import com.hazyoctophant.cardvault.ui.components.ScreenScaffold
import com.hazyoctophant.cardvault.ui.formatUsd
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BinderViewModel(private val c: AppContainer, val binderId: Long) : ViewModel() {
    val binder = (if (binderId >= 0) c.library.observeBinder(binderId) else flowOf(null))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val all = c.cards.observeCollection().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val cards = c.cards.observeCollection().map { list ->
        when (binderId) {
            ALL_CARDS_BINDER -> list
            UNFILED_BINDER -> list.filter { it.entry.binderId == null }
            else -> list.filter { it.entry.binderId == binderId }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun moveIn(entries: List<OwnedCard>) = viewModelScope.launch { c.cards.moveToBinder(entries.map { it.entry }, binderId) }
    fun removeFromBinder(entry: OwnedCard) = viewModelScope.launch { c.cards.moveToBinder(listOf(entry.entry), null) }
    suspend fun save(binder: BinderEntity) = c.library.updateBinder(binder)
    suspend fun delete(binder: BinderEntity) = c.library.deleteBinder(binder)
}

private enum class BinderSort(val label: String) { SET("Set & number"), VALUE("Value"), NAME("Name"), RECENT("Recently added"), COLOR("Color / type") }

@Composable
fun BinderScreen(nav: NavController, binderId: Long) {
    val vm = appViewModel(key = "binder-$binderId") { BinderViewModel(it, binderId) }
    val binder by vm.binder.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val all by vm.all.collectAsStateWithLifecycle()
    var pages by rememberSaveable { mutableStateOf(true) }
    var sort by rememberSaveable { mutableStateOf(BinderSort.SET) }
    var sortMenu by remember { mutableStateOf(false) }
    var game by rememberSaveable { mutableStateOf<Game?>(null) }
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val title = when (binderId) {
        ALL_CARDS_BINDER -> "All cards"
        UNFILED_BINDER -> "Not in a binder"
        else -> binder?.name ?: "Binder"
    }
    val pockets = binder?.pocketsPerPage ?: 9
    val sorted = (cards.orEmpty()).filter { game == null || it.card.game == game }.let { list ->
        when (sort) {
            BinderSort.SET -> list.sortedWith(compareBy({ it.card.game.ordinal }, { it.card.releasedAt ?: "" }, { it.card.setName }, { CollectionQuery.numberKey(it.card.number) }))
            BinderSort.VALUE -> list.sortedByDescending { it.unitUsd }
            BinderSort.NAME -> list.sortedBy { it.card.name.lowercase() }
            BinderSort.RECENT -> list.sortedByDescending { it.entry.addedAt }
            BinderSort.COLOR -> list.sortedWith(compareBy({ it.card.colors }, { it.card.name }))
        }
    }

    ScreenScaffold(
        title = title,
        nav = nav,
        actions = {
            if (binderId >= 0) {
                IconButton(onClick = { adding = true }) { Icon(Icons.Default.LibraryAdd, "Add cards to binder") }
                IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Edit binder") }
            }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    BinderSort.entries.forEach { s ->
                        DropdownMenuItem({ Text((if (s == sort) "✓ " else "") + s.label) }, onClick = { sort = s; sortMenu = false })
                    }
                }
            }
            IconButton(onClick = { pages = !pages }) {
                Icon(if (pages) Icons.Default.GridView else Icons.Default.AutoStories, if (pages) "Scroll view" else "Page view")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            if (binder?.game == null) GameFilterChips(game, { game = it })
            Text(
                "${sorted.sumOf { it.entry.quantity }} cards · ${formatUsd(sorted.sumOf { it.totalUsd })}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            when {
                cards == null -> Unit
                sorted.isEmpty() -> EmptyState(
                    Icons.Default.AutoStories,
                    "This binder is empty",
                    if (binderId >= 0) "Tap the add button above to file cards here, or choose this binder while scanning." else "Scan or add some cards first.",
                )
                pages -> BinderPages(sorted, pockets) { nav.navigate(Routes.card(it.card.id)) }
                else -> LazyVerticalGrid(
                    GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(sorted, key = { it.entry.id }) { o -> OwnedCardTile(o, { nav.navigate(Routes.card(o.card.id)) }) }
                }
            }
        }
    }

    if (editing && binder != null) {
        val b = binder!!
        BinderDialog(
            b,
            onDismiss = { editing = false },
            onDelete = { scope.launch { vm.delete(b); editing = false; nav.popBackStack() } },
        ) { name, g, color, p ->
            scope.launch { vm.save(b.copy(name = name, game = g, color = color, pocketsPerPage = p)) }
            editing = false
        }
    }
    if (adding) {
        val candidates = all.orEmpty().filter { it.entry.binderId != binderId && (binder?.game == null || it.card.game == binder?.game) }
            .sortedWith(compareBy({ it.entry.binderId != null }, { it.card.name }))
        val selected = remember { mutableStateListOf<Long>() }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add cards to $title") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(candidates, key = { it.entry.id }) { o ->
                        Row(
                            Modifier.fillMaxWidth().clickable { if (o.entry.id in selected) selected.remove(o.entry.id) else selected.add(o.entry.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(o.entry.id in selected, null)
                            CardImage(o.card.imageUrl, Modifier.width(32.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(o.card.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${o.card.setCode} · ×${o.entry.quantity}${if (o.entry.binderId != null) " · in another binder" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.moveIn(candidates.filter { it.entry.id in selected })
                    adding = false
                }) { Text("Add ${selected.size}") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

/** Binder pages you swipe through, like a real 9-pocket binder. */
@Composable
private fun BinderPages(cards: List<OwnedCard>, pockets: Int, onClick: (OwnedCard) -> Unit) {
    val columns = when (pockets) { 4 -> 2; 9 -> 3; 12 -> 3; else -> 4 }
    val rows = pockets / columns
    val pageCount = (cards.size + pockets - 1) / pockets
    val pager = rememberPagerState { pageCount }
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp), pageSpacing = 12.dp) { page ->
            val slice = cards.drop(page * pockets).take(pockets)
            Column(
                Modifier.fillMaxSize().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (r in 0 until rows) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (col in 0 until columns) {
                            val card = slice.getOrNull(r * columns + col)
                            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (card != null) {
                                    Box(Modifier.clickable { onClick(card) }) {
                                        CardImage(card.card.imageUrl, Modifier, card.card.name)
                                        if (card.entry.quantity > 1) {
                                            Text(
                                                "×${card.entry.quantity}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp)
                                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                } else {
                                    Box(Modifier.aspectRatio(63f / 88f).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)))
                                }
                            }
                        }
                    }
                }
            }
        }
        val pageCards = cards.drop(pager.currentPage * pockets).take(pockets)
        Text(
            "Page ${pager.currentPage + 1} of $pageCount · ${formatUsd(pageCards.sumOf { it.totalUsd })} on this page",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
        )
        Spacer(Modifier.height(4.dp))
    }
}
