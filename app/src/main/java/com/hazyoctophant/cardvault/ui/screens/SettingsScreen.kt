package com.hazyoctophant.cardvault.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hazyoctophant.cardvault.data.mtgdb.MtgDatabaseWorker
import com.hazyoctophant.cardvault.model.ValuationMode
import com.hazyoctophant.cardvault.ui.Routes
import com.hazyoctophant.cardvault.pricing.PriceRefreshWorker
import com.hazyoctophant.cardvault.ui.appContainer
import com.hazyoctophant.cardvault.ui.components.ScreenScaffold
import com.hazyoctophant.cardvault.ui.components.SectionTitle
import com.hazyoctophant.cardvault.ui.formatTimestamp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nav: NavController) {
    val container = appContainer()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    ScreenScaffold(title = "Settings", nav = nav) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).verticalScroll(rememberScrollState())) {
            SectionTitle("Card value")
            Text(
                "Prices come from several marketplaces. Choose how they're combined into the value shown for each card.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            ValuationMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        container.settings.update { it.copy(valuationMode = mode) }
                        // Recompute stored values with the new rule; no network needed.
                        scope.launch { container.prices.recomputeValues(null) }
                    }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.valuationMode == mode, onClick = null, modifier = Modifier.padding(8.dp))
                    Text(mode.label)
                }
            }

            SectionTitle("Daily price updates")
            ToggleRow("Update prices once a day", settings.dailyRefresh) { on ->
                container.settings.update { it.copy(dailyRefresh = on) }
                PriceRefreshWorker.schedule(context, on)
            }
            ToggleRow("Notify me with the new collection value", settings.notifyOnRefresh) { on ->
                container.settings.update { it.copy(notifyOnRefresh = on) }
            }
            Text(
                "Last update: ${formatTimestamp(settings.lastRefreshAt)}" + (settings.lastRefreshError?.let { "\n$it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SectionTitle("Magic card database")
            val mtgCountFlow = remember { container.mtgDb.observeCount() }
            val mtgCount by mtgCountFlow.collectAsStateWithLifecycle(0)
            Text(
                if (mtgCount == 0) "Not downloaded. Open Home → Magic database to download every Magic card for offline search."
                else "%,d cards on this phone · updated %s".format(mtgCount, formatTimestamp(settings.mtgDbImportedAt)),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            ToggleRow("Update weekly (Wi-Fi + charging only)", settings.mtgDbAutoUpdate) { on ->
                container.settings.update { it.copy(mtgDbAutoUpdate = on) }
                MtgDatabaseWorker.scheduleWeekly(context, on && settings.mtgDbVersion.isNotEmpty())
            }
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { nav.navigate(Routes.MTG_DATABASE) }) { Text("Open") }
                TextButton(onClick = { MtgDatabaseWorker.downloadNow(context, force = true) }) { Text(if (mtgCount == 0) "Download" else "Re-download") }
                if (mtgCount > 0) TextButton(onClick = { scope.launch { container.mtgDb.clear() } }) {
                    Text("Delete (free space)", color = MaterialTheme.colorScheme.error)
                }
            }

            SectionTitle("Pokémon TCG API key (optional)")
            OutlinedTextField(
                value = settings.pokemonApiKey,
                onValueChange = { v -> container.settings.update { it.copy(pokemonApiKey = v) } },
                placeholder = { Text("Free key from dev.pokemontcg.io") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Text(
                "Works without a key, but a free key gives much higher rate limits for big collections.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp, 4.dp),
            )

            SectionTitle("Data sources")
            Text(
                """
                • Magic: Scryfall — card database (bulk data), TCGplayer (USD), Cardmarket (EUR) and MTGO prices.
                • Pokémon: Pokémon TCG API — TCGplayer (USD, per printing) and Cardmarket (EUR).
                • One Piece: OPTCG API — TCGplayer market prices.
                • Exchange rate: European Central Bank via frankfurter.app.
                Card text recognition runs on your phone; camera images are never uploaded.
                Card Vault isn't affiliated with Wizards of the Coast, The Pokémon Company, Bandai, or any marketplace.
                """.trimIndent(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
