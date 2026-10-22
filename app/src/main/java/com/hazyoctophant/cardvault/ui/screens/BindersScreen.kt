package com.hazyoctophant.cardvault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.hazyoctophant.cardvault.AppContainer
import com.hazyoctophant.cardvault.data.db.BinderEntity
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.ui.ALL_CARDS_BINDER
import com.hazyoctophant.cardvault.ui.Routes
import com.hazyoctophant.cardvault.ui.UNFILED_BINDER
import com.hazyoctophant.cardvault.ui.appViewModel
import com.hazyoctophant.cardvault.ui.components.ChoiceRow
import com.hazyoctophant.cardvault.ui.components.ScreenScaffold
import com.hazyoctophant.cardvault.ui.formatUsd
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

val binderColors = listOf(0xFF3D5AFE, 0xFFE5484D, 0xFFF2C14E, 0xFF2E9E5B, 0xFF8E44AD, 0xFFE07A2E, 0xFF37474F, 0xFF00897B)

data class BinderCover(val id: Long, val name: String, val color: Long, val cards: Int, val value: Double, val binder: BinderEntity?)

class BindersViewModel(private val c: AppContainer) : ViewModel() {
    val covers = combine(c.library.observeBinders(), c.cards.observeCollection()) { binders, owned ->
        fun value(filter: (Long?) -> Boolean) = owned.filter { filter(it.entry.binderId) }.let { l -> l.sumOf { it.entry.quantity } to l.sumOf { it.totalUsd } }
        val (allCount, allValue) = value { true }
        val (unfiledCount, unfiledValue) = value { it == null }
        listOf(
            BinderCover(ALL_CARDS_BINDER, "All cards", 0xFF455A64, allCount, allValue, null),
            BinderCover(UNFILED_BINDER, "Not in a binder", 0xFF78909C, unfiledCount, unfiledValue, null),
        ) + binders.map { b ->
            val (n, v) = value { it == b.binder.id }
            BinderCover(b.binder.id, b.binder.name, b.binder.color, n, v, b.binder)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun create(name: String, game: Game?, color: Long, pockets: Int) = viewModelScope.launch {
        c.library.createBinder(name, game, color, pockets)
    }
}

@Composable
fun BindersScreen(nav: NavController) {
    val vm = appViewModel { BindersViewModel(it) }
    val covers by vm.covers.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = "Binders",
        floatingActionButton = { FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "New binder") } },
    ) { padding ->
        LazyVerticalGrid(
            GridCells.Adaptive(150.dp),
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(covers, key = { it.id }) { cover -> BinderCoverView(cover) { nav.navigate(Routes.binder(cover.id)) } }
        }
    }
    if (creating) BinderDialog(null, onDismiss = { creating = false }) { name, game, color, pockets ->
        vm.create(name, game, color, pockets)
        creating = false
    }
}

@Composable
private fun BinderCoverView(cover: BinderCover, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().aspectRatio(0.78f).clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp, topStart = 4.dp, bottomStart = 4.dp))
            .background(Color(cover.color)).clickable(onClick = onClick).padding(14.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = Color.White.copy(alpha = 0.8f))
        Spacer(Modifier.weight(1f))
        Text(cover.name, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
        Text("${cover.cards} cards", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
        Text(formatUsd(cover.value), color = Color.White, style = MaterialTheme.typography.titleSmall)
    }
}

/** Create or edit a binder. */
@Composable
fun BinderDialog(
    existing: BinderEntity?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSave: (name: String, game: Game?, color: Long, pockets: Int) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var game by remember { mutableStateOf(existing?.game) }
    var color by remember { mutableStateOf(existing?.color ?: binderColors.first()) }
    var pockets by remember { mutableStateOf(existing?.pocketsPerPage ?: 9) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New binder" else "Edit binder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Text("Game", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(listOf<Game?>(null) + Game.entries, game, { it?.short ?: "Any" }) { game = it }
                Text("Pockets per page", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(listOf(4, 9, 12, 16), pockets, { "$it" }) { pockets = it }
                Text("Color", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    binderColors.forEach { c ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(Color(c)).clickable { color = c },
                            contentAlignment = Alignment.Center,
                        ) { if (c == color) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                onDelete?.let { TextButton(onClick = it) { Text("Delete binder (cards are kept)", color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), game, color, pockets) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
