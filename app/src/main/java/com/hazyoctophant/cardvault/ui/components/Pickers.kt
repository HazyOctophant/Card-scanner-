package com.hazyoctophant.cardvault.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hazyoctophant.cardvault.data.db.CardEntity
import com.hazyoctophant.cardvault.model.Condition
import com.hazyoctophant.cardvault.model.Finish

/** A horizontally scrolling single-choice chip row. */
@Composable
fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
fun QuantityStepper(value: Int, onChange: (Int) -> Unit, min: Int = 1) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (value > min) onChange(value - 1) }) { Icon(Icons.Default.Remove, "Fewer") }
        Text("$value", style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onChange(value + 1) }) { Icon(Icons.Default.Add, "More") }
    }
}

/** Choose quantity, finish, condition and binder before adding a card to the collection. */
@Composable
fun AddCardDialog(
    card: CardEntity,
    binders: List<Pair<Long, String>>,
    onDismiss: () -> Unit,
    onAdd: (quantity: Int, finish: Finish, condition: Condition, binderId: Long?) -> Unit,
    initialFinish: Finish = Finish.NORMAL,
) {
    var qty by remember { mutableIntStateOf(1) }
    var finish by remember { mutableStateOf(initialFinish) }
    var condition by remember { mutableStateOf(Condition.NEAR_MINT) }
    var binder by remember { mutableStateOf<Pair<Long?, String>>(null to "No binder") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(card.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${card.setName} · #${card.number}", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quantity"); Spacer(Modifier.width(8.dp)); QuantityStepper(qty, { qty = it })
                }
                Text("Finish", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(Finish.forGame(card.game), finish, { it.label }) { finish = it }
                Text("Condition", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(Condition.entries, condition, { it.short }) { condition = it }
                if (binders.isNotEmpty()) {
                    Text("Binder", style = MaterialTheme.typography.labelLarge)
                    ChoiceRow(listOf<Pair<Long?, String>>(null to "No binder") + binders, binder, { it.second }) { binder = it }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(qty, finish, condition, binder.first) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
