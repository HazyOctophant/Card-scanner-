package com.hazyoctophant.cardvault.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.hazyoctophant.cardvault.AppContainer
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.strategy.Formats
import com.hazyoctophant.cardvault.ui.Routes
import com.hazyoctophant.cardvault.ui.appViewModel
import com.hazyoctophant.cardvault.ui.components.ChoiceRow
import com.hazyoctophant.cardvault.ui.components.EmptyState
import com.hazyoctophant.cardvault.ui.components.GameDot
import com.hazyoctophant.cardvault.ui.components.ScreenScaffold
import com.hazyoctophant.cardvault.ui.formatTimestamp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DecksViewModel(private val c: AppContainer) : ViewModel() {
    val decks = c.library.observeDecks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    suspend fun create(name: String, game: Game, format: String) = c.library.createDeck(name, game, format)
}

@Composable
fun DecksScreen(nav: NavController) {
    val vm = appViewModel { DecksViewModel(it) }
    val decks by vm.decks.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ScreenScaffold(
        title = "Decks",
        floatingActionButton = { FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "New deck") } },
    ) { padding ->
        if (decks?.isEmpty() == true) {
            EmptyState(
                Icons.Default.Layers,
                "Build your first deck",
                "Create a deck, add cards from your collection, and get archetype analysis plus suggestions from the cards you own.",
                Modifier.padding(top = padding.calculateTopPadding()),
            )
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(decks.orEmpty(), key = { it.deck.id }) { d ->
                Card(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.deck(d.deck.id)) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        GameDot(d.deck.game)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.deck.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${d.deck.game.short} · ${Formats.get(d.deck.game, d.deck.format).label} · edited ${formatTimestamp(d.deck.updatedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text("${d.cardCount} cards", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        var game by remember { mutableStateOf(Game.MTG) }
        var format by remember { mutableStateOf(Formats.forGame(Game.MTG).first()) }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New deck") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Deck name") }, singleLine = true)
                    Text("Game", style = MaterialTheme.typography.labelLarge)
                    ChoiceRow(Game.entries, game, { it.short }) { game = it; format = Formats.forGame(it).first() }
                    Text("Format", style = MaterialTheme.typography.labelLarge)
                    ChoiceRow(Formats.forGame(game), format, { it.label }) { format = it }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch {
                        val id = vm.create(name.trim(), game, format.id)
                        creating = false
                        nav.navigate(Routes.deck(id))
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}
