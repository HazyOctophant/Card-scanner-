package com.hazyoctophant.cardvault.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hazyoctophant.cardvault.data.db.ProfileEntity
import com.hazyoctophant.cardvault.ui.appContainer
import kotlinx.coroutines.launch

private val profileColors = listOf(0xFF3D5AFE, 0xFFE5484D, 0xFFF2C14E, 0xFF2E9E5B, 0xFF8E44AD, 0xFFE07A2E, 0xFF00897B, 0xFFEC407A)

/** Round initial-letter avatar for a user. */
@Composable
fun ProfileAvatar(profile: ProfileEntity?, size: Int = 32, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color(profile?.color ?: 0xFF78909C))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            profile?.name?.firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = if (size >= 40) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
        )
    }
}

/** Avatar of the active user that opens the user switcher. Put it in a top app bar. */
@Composable
fun ProfileSwitcherButton() {
    val profiles = appContainer().profiles
    val active by profiles.activeProfile.collectAsStateWithLifecycle(null)
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { ProfileAvatar(active) }
    if (open) ProfilesDialog(onDismiss = { open = false })
}

/** Switch between users, add new ones, rename or delete them. */
@Composable
fun ProfilesDialog(onDismiss: () -> Unit) {
    val repo = appContainer().profiles
    val profilesFlow = remember { repo.observeProfiles() }
    val list by profilesFlow.collectAsStateWithLifecycle(emptyList())
    val activeId by repo.activeId.collectAsStateWithLifecycle(repo.currentId)
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ProfileEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProfileEntity?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Users") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Each user has their own collection, binders and decks. Card prices are shared.",
                    style = MaterialTheme.typography.bodySmall,
                )
                list.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { repo.switchTo(p.id); onDismiss() }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProfileAvatar(p, 36)
                        Spacer(Modifier.width(12.dp))
                        Text(p.name, Modifier.weight(1f), fontWeight = if (p.id == activeId) FontWeight.Bold else null)
                        if (p.id == activeId) Icon(Icons.Default.Check, "Active", tint = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = { editing = p }) { Icon(Icons.Default.Edit, "Rename") }
                        if (list.size > 1) IconButton(onClick = { deleting = p }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
                TextButton(onClick = { adding = true }) { Icon(Icons.Default.PersonAdd, null); Text("  Add user") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    if (adding || editing != null) {
        val existing = editing
        ProfileEditor(
            initialName = existing?.name ?: "",
            initialColor = existing?.color ?: profileColors[list.size % profileColors.size],
            title = if (existing == null) "New user" else "Edit user",
            onDismiss = { adding = false; editing = null },
        ) { name, color ->
            scope.launch {
                if (existing == null) repo.switchTo(repo.create(name, color)) else repo.rename(existing, name, color)
            }
            adding = false; editing = null
        }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${p.name}?") },
            text = { Text("This permanently deletes ${p.name}'s collection, binders and decks.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { repo.delete(p) }; deleting = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProfileEditor(
    initialName: String,
    initialColor: Long,
    title: String,
    onDismiss: () -> Unit,
    onSave: (String, Long) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    profileColors.forEach { c ->
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                                .border(if (c == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable { color = c },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, color) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
