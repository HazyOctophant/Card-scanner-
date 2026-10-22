package com.hazyoctophant.cardvault.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.work.WorkInfo
import com.hazyoctophant.cardvault.AppContainer
import com.hazyoctophant.cardvault.data.db.OwnedCard
import com.hazyoctophant.cardvault.data.db.PriceEntity
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.model.PriceSource
import com.hazyoctophant.cardvault.pricing.PriceRefreshWorker
import com.hazyoctophant.cardvault.ui.Routes
import com.hazyoctophant.cardvault.ui.appViewModel
import com.hazyoctophant.cardvault.ui.components.ChangeText
import com.hazyoctophant.cardvault.ui.components.ChoiceRow
import com.hazyoctophant.cardvault.ui.components.GameDot
import com.hazyoctophant.cardvault.ui.components.GameFilterChips
import com.hazyoctophant.cardvault.ui.components.ScreenScaffold
import com.hazyoctophant.cardvault.ui.formatMoney
import com.hazyoctophant.cardvault.ui.formatTimestamp
import com.hazyoctophant.cardvault.ui.formatUsd
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.File

data class SheetRow(val owned: OwnedCard, val prices: List<PriceEntity>) {
    fun source(s: PriceSource): PriceEntity? =
        prices.firstOrNull { it.source == s && it.finish == owned.entry.finish }
            ?: prices.firstOrNull { it.source == s && it.finish == owned.unitValue?.finish }
}

class PriceSheetViewModel(c: AppContainer) : ViewModel() {
    val rows = combine(c.cards.observeCollection(), c.db.prices().observeAllPrices()) { owned, prices ->
        val byCard = prices.groupBy { it.cardId }
        owned.map { SheetRow(it, byCard[it.card.id].orEmpty()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = c.settings.settings
}

private enum class SheetSort(val label: String) { TOTAL("Total"), UNIT("Unit price"), DAY("24h change"), WEEK("7d change"), NAME("Name") }

/** Every owned card with each site's price, the blended value, totals and trends. Exportable as CSV. */
@Composable
fun PriceSheetScreen(nav: NavController) {
    val vm = appViewModel { PriceSheetViewModel(it) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val work by PriceRefreshWorker.observeNow(context).collectAsStateWithLifecycle(emptyList())
    val refreshing = work.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    var game by rememberSaveable { mutableStateOf<Game?>(null) }
    var sort by rememberSaveable { mutableStateOf(SheetSort.TOTAL) }

    fun change(o: OwnedCard, prev: Double?): Double = if (prev == null || prev <= 0) 0.0 else (o.unitUsd - prev) / prev
    val visible = rows.filter { game == null || it.owned.card.game == game }.let { list ->
        when (sort) {
            SheetSort.TOTAL -> list.sortedByDescending { it.owned.totalUsd }
            SheetSort.UNIT -> list.sortedByDescending { it.owned.unitUsd }
            SheetSort.DAY -> list.sortedByDescending { kotlin.math.abs(change(it.owned, it.owned.unitValue?.previousDayUsd)) }
            SheetSort.WEEK -> list.sortedByDescending { kotlin.math.abs(change(it.owned, it.owned.unitValue?.previousWeekUsd)) }
            SheetSort.NAME -> list.sortedBy { it.owned.card.name.lowercase() }
        }
    }

    ScreenScaffold(
        title = "Price sheet",
        nav = nav,
        actions = {
            if (refreshing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { PriceRefreshWorker.runNow(context) }) { Icon(Icons.Default.Refresh, "Refresh prices now") }
            IconButton(onClick = { shareCsv(context, visible) }) { Icon(Icons.Default.FileDownload, "Export CSV") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(formatUsd(visible.sumOf { it.owned.totalUsd }), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${visible.sumOf { it.owned.entry.quantity }} cards · updated ${formatTimestamp(settings.lastRefreshAt)} · refreshes daily",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Value = ${settings.valuationMode.label}. Cardmarket € converted at ${"%.3f".format(settings.eurToUsd)}.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GameFilterChips(game, { game = it })
            ChoiceRow(SheetSort.entries, sort, { "Sort: ${it.label}" }) { sort = it }
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text("Card", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("TCGplayer", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
                Text("Cardmkt", Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium)
                Text("Total", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(visible, key = { it.owned.entry.id }) { row ->
                    val o = row.owned
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.navigate(Routes.card(o.card.id)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                GameDot(o.card.game); Spacer(Modifier.width(4.dp))
                                Text(o.card.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                "${o.card.setCode} #${o.card.number} · ${o.entry.finish.label} · ×${o.entry.quantity}",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row {
                                Text("24h ", style = MaterialTheme.typography.labelSmall)
                                ChangeText(o.unitUsd, o.unitValue?.previousDayUsd)
                                Text("  7d ", style = MaterialTheme.typography.labelSmall)
                                ChangeText(o.unitUsd, o.unitValue?.previousWeekUsd)
                            }
                        }
                        Text(row.source(PriceSource.TCGPLAYER)?.market?.let { formatMoney(it, "USD") } ?: "—", Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall)
                        Text(row.source(PriceSource.CARDMARKET)?.market?.let { formatMoney(it, "EUR") } ?: "—", Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
                        Column(Modifier.width(72.dp)) {
                            Text(formatUsd(o.totalUsd), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("${formatUsd(o.unitUsd)} ea", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

private fun shareCsv(context: Context, rows: List<SheetRow>) {
    val text = buildString {
        appendLine("Game,Name,Set,Set code,Number,Rarity,Finish,Condition,Quantity,TCGplayer USD,Cardmarket EUR,Unit value USD,Total USD,Prev day USD,Prev week USD")
        rows.forEach { r ->
            val o = r.owned
            appendLine(
                listOf(
                    o.card.game.label, o.card.name, o.card.setName, o.card.setCode, o.card.number, o.card.rarity,
                    o.entry.finish.label, o.entry.condition.short, "${o.entry.quantity}",
                    r.source(PriceSource.TCGPLAYER)?.market?.toString().orEmpty(),
                    r.source(PriceSource.CARDMARKET)?.market?.toString().orEmpty(),
                    "%.2f".format(o.unitUsd), "%.2f".format(o.totalUsd),
                    o.unitValue?.previousDayUsd?.let { "%.2f".format(it) }.orEmpty(),
                    o.unitValue?.previousWeekUsd?.let { "%.2f".format(it) }.orEmpty(),
                ).joinToString(",") { csv(it) }
            )
        }
    }
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "card-vault-prices.csv").apply { writeText(text) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Export price sheet"))
}
