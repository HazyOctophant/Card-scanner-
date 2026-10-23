package com.hazyoctophant.cardvault.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.scan.CardIndexRepository
import com.hazyoctophant.cardvault.scan.CardIndexWorker
import com.hazyoctophant.cardvault.ui.appContainer
import kotlinx.coroutines.launch

/** Download / update / remove the visual card library for each game. */
@Composable
fun CardLibraryPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = appContainer().cardIndex
    val installed by repo.installed.collectAsStateWithLifecycle()
    val work by CardIndexWorker.observe(context).collectAsStateWithLifecycle(emptyList())
    val running = work.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    val failed = work.firstOrNull { it.state == WorkInfo.State.FAILED }?.outputData?.getString(CardIndexWorker.KEY_ERROR)
    val scope = rememberCoroutineScope()

    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "The card library lets the scanner recognise a card from its picture by comparing your photo with " +
                "every printed card. Download it for each game you collect (Wi-Fi recommended).",
            style = MaterialTheme.typography.bodySmall,
        )
        Game.entries.forEach { game ->
            val info = installed[game]
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(game.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        info?.let { "%,d cards · built %s".format(it.cards, it.version) } ?: "Not downloaded (${CardIndexRepository.approxSize(game)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(enabled = running == null, onClick = { CardIndexWorker.download(context, listOf(game)) }) {
                    Text(if (info == null) "Download" else "Update")
                }
                if (info != null) TextButton(onClick = { scope.launch { repo.delete(game) } }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (running != null) {
            val p = running.progress.getFloat(CardIndexWorker.KEY_PROGRESS, 0f)
            Text(
                if (running.state == WorkInfo.State.ENQUEUED) "Waiting for a connection…" else "Downloading card library… ${(p * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
            )
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        } else if (installed.size < Game.entries.size) {
            Button(onClick = { CardIndexWorker.download(context, Game.entries.filter { it !in installed }) }) {
                Text("Download all")
            }
        }
        failed?.let {
            Text(
                "Last download failed: $it. The library is published by the project's \"Card image index\" GitHub workflow; " +
                    "if it hasn't finished building yet, try again later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
