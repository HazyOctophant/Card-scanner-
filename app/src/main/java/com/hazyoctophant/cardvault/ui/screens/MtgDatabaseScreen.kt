package com.hazyoctophant.cardvault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.hazyoctophant.cardvault.data.mtgdb.ColorMode
import com.hazyoctophant.cardvault.data.mtgdb.MtgDatabaseWorker
import com.hazyoctophant.cardvault.data.mtgdb.MtgFilter
import com.hazyoctophant.cardvault.data.mtgdb.MtgSort
import com.hazyoctophant.cardvault.data.remote.CatalogCard
import com.hazyoctophant.cardvault.ui.Routes
import com.hazyoctophant.cardvault.ui.appViewModel
import com.hazyoctophant.cardvault.ui.components.CardImage
import com.hazyoctophant.cardvault.ui.components.ChoiceRow
import com.hazyoctophant.cardvault.ui.components.ScreenScaffold
import com.hazyoctophant.cardvault.ui.formatTimestamp
import com.hazyoctophant.cardvault.ui.formatUsd
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MtgResults(
    val cards: List<CatalogCard> = emptyList(),
    val offline: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MtgDatabaseViewModel(private val c: AppContainer) : ViewModel() {
    val filter = MutableStateFlow(MtgFilter(sort = MtgSort.POPULAR))
    val count = c.mtgDb.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val settings = c.settings.settings
    val ownedByName = c.cards.observeCollection().map { list -> list.groupBy { it.card.name }.mapValues { e -> e.value.sumOf { it.entry.quantity } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val results = combine(filter.debounce(250), count.map { it > 0 }.distinctUntilChanged()) { f, hasDb -> f to hasDb }
        .flatMapLatest { (f, hasDb) ->
            if (hasDb) {
                c.mtgDb.observe(f).map { list -> MtgResults(list.map { it.toCatalogCard() }, offline = true) }
            } else flow {
                emit(MtgResults(offline = false, loading = true))
                emit(
                    runCatching { c.mtgDb.searchOnline(f) }.fold(
                        { MtgResults(it, offline = false) },
                        { MtgResults(offline = false, error = "Online search failed (${it.message}). Download the database to search offline.") },
                    )
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MtgResults(loading = true))

    fun update(transform: (MtgFilter) -> MtgFilter) { filter.value = transform(filter.value) }

    /** Save the card locally so the detail screen (prices, add to collection / deck) can show it. */
    suspend fun open(card: CatalogCard) = c.cards.save(card)

    fun clearDatabase() = viewModelScope.launch { c.mtgDb.clear() }
}

/** Browse and filter every Magic card ever printed (one entry per card), offline once downloaded. */
@Composable
fun MtgDatabaseScreen(nav: NavController) {
    val vm = appViewModel { MtgDatabaseViewModel(it) }
    val filter by vm.filter.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val owned by vm.ownedByName.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val work by MtgDatabaseWorker.observeNow(context).collectAsStateWithLifecycle(emptyList())
    val running = work.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    val failed = work.firstOrNull { it.state == WorkInfo.State.FAILED }?.outputData?.getString(MtgDatabaseWorker.KEY_ERROR)
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ScreenScaffold(
        title = "Magic database",
        nav = nav,
        actions = {
            IconButton(onClick = { showFilters = !showFilters }) { Icon(Icons.Default.FilterList, "Filters") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            DatabaseStatus(
                count = count,
                updatedAt = settings.mtgDbImportedAt,
                running = running,
                error = failed,
                onDownload = { MtgDatabaseWorker.downloadNow(context, force = count == 0) },
            )
            OutlinedTextField(
                value = filter.text,
                onValueChange = { t -> vm.update { it.copy(text = t) } },
                placeholder = { Text("Name, type or rules text (e.g. \"draw a card\")") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ColorPicker(filter.colors) { color ->
                vm.update { f -> f.copy(colors = if (color in f.colors) f.colors - color else f.colors + color) }
            }
            if (showFilters) FilterPanel(filter, vm::update)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        results.loading -> "Searching…"
                        results.offline -> "${results.cards.size}${if (results.cards.size >= 300) "+" else ""} cards · offline"
                        else -> "${results.cards.size} cards · online (Scryfall)"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (!filter.isEmpty) TextButton(onClick = { vm.update { MtgFilter(sort = it.sort) } }) { Text("Clear filters") }
            }
            if (results.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            results.error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            LazyVerticalGrid(
                GridCells.Adaptive(104.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(results.cards, key = { it.card.id }) { c ->
                    Column(Modifier.clickable { scope.launch { vm.open(c); nav.navigate(Routes.card(c.card.id)) } }) {
                        Box {
                            CardImage(c.card.imageUrl, Modifier.fillMaxWidth(), c.card.name)
                            owned[c.card.name]?.let { n ->
                                Text(
                                    "Own $n",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Text(c.card.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            c.prices.firstOrNull { it.currency == "USD" }?.market?.let { formatUsd(it) } ?: "—",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DatabaseStatus(count: Int, updatedAt: Long, running: WorkInfo?, error: String?, onDownload: () -> Unit) {
    if (running != null) {
        val progress = running.progress.getFloat(MtgDatabaseWorker.KEY_PROGRESS, 0f)
        val cards = running.progress.getInt(MtgDatabaseWorker.KEY_CARDS, 0)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                if (running.state == WorkInfo.State.ENQUEUED) "Waiting for a network connection…" else "Downloading Magic database… $cards cards",
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    if (count == 0) {
        Card(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Download the full Magic database", style = MaterialTheme.typography.titleMedium)
                Text(
                    "About 35,000 cards with rules text and prices from Scryfall, for fast offline search, offline scanning and " +
                        "deck suggestions beyond your collection. It's roughly a 150 MB download, so Wi-Fi is recommended. " +
                        "Until then, searches here run online.",
                    style = MaterialTheme.typography.bodySmall,
                )
                error?.let { Text("Last attempt failed: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = onDownload) { Icon(Icons.Default.CloudDownload, null); Text(" Download") }
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "%,d cards on this phone · updated %s".format(count, formatTimestamp(updatedAt)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDownload) { Text("Update") }
        }
    }
}

private val manaColors = mapOf(
    "W" to Color(0xFFF8F2D8), "U" to Color(0xFF4A8FD9), "B" to Color(0xFF3B3434),
    "R" to Color(0xFFE0533D), "G" to Color(0xFF3E9A5B), "C" to Color(0xFFB8B2AA),
)

@Composable
private fun ColorPicker(selected: Set<String>, onToggle: (String) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        manaColors.forEach { (symbol, color) ->
            val on = symbol in selected
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(color)
                    .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onToggle(symbol) },
                contentAlignment = Alignment.Center,
            ) {
                Text(symbol, color = if (symbol == "W" || symbol == "C") Color.Black else Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FilterPanel(filter: MtgFilter, update: ((MtgFilter) -> MtgFilter) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChoiceRow(ColorMode.entries, filter.colorMode, { it.label }) { m -> update { it.copy(colorMode = m) } }
        MultiChips(MtgFilter.TYPES, filter.types) { t -> update { f -> f.copy(types = f.types.toggle(t)) } }
        MultiChips(MtgFilter.RARITIES, filter.rarities) { r -> update { f -> f.copy(rarities = f.rarities.toggle(r)) } }
        ChoiceRow(listOf<String?>(null) + MtgFilter.FORMATS, filter.format, { it?.replaceFirstChar(Char::uppercase) ?: "Any format" }) { fmt ->
            update { it.copy(format = fmt) }
        }
        ChoiceRow(listOf<Double?>(null, 1.0, 5.0, 20.0), filter.maxPriceUsd, { it?.let { p -> "≤ ${formatUsd(p)}" } ?: "Any price" }) { p ->
            update { it.copy(maxPriceUsd = p) }
        }
        ChoiceRow(MtgSort.entries, filter.sort, { "Sort: ${it.label}" }) { s -> update { it.copy(sort = s) } }
        Column(Modifier.padding(horizontal = 16.dp)) {
            val max = if (filter.maxManaValue >= MtgFilter.MAX_MV) "7+" else "${filter.maxManaValue}"
            Text("Mana value ${filter.minManaValue}–$max", style = MaterialTheme.typography.labelLarge)
            RangeSlider(
                value = filter.minManaValue.toFloat()..filter.maxManaValue.toFloat(),
                onValueChange = { r -> update { it.copy(minManaValue = r.start.toInt(), maxManaValue = r.endInclusive.toInt()) } },
                valueRange = 0f..MtgFilter.MAX_MV.toFloat(),
                steps = MtgFilter.MAX_MV - 1,
            )
        }
    }
}

@Composable
private fun MultiChips(options: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(o in selected, { onToggle(o) }, { Text(o) }) }
    }
}

private fun Set<String>.toggle(v: String) = if (v in this) this - v else this + v
