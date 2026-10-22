package com.hazyoctophant.cardvault.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hazyoctophant.cardvault.data.remote.CatalogCard
import com.hazyoctophant.cardvault.model.Condition
import com.hazyoctophant.cardvault.model.Finish
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.pricing.Valuation
import com.hazyoctophant.cardvault.scan.ScanPhase
import com.hazyoctophant.cardvault.scan.ScanState
import com.hazyoctophant.cardvault.scan.ScanViewModel
import com.hazyoctophant.cardvault.scan.TextAnalyzer
import com.hazyoctophant.cardvault.ui.Routes
import com.hazyoctophant.cardvault.ui.appContainer
import com.hazyoctophant.cardvault.ui.appViewModel
import com.hazyoctophant.cardvault.ui.components.CardImage
import com.hazyoctophant.cardvault.ui.components.ChoiceRow
import com.hazyoctophant.cardvault.ui.components.EmptyState
import com.hazyoctophant.cardvault.ui.components.QuantityStepper
import com.hazyoctophant.cardvault.ui.formatUsd
import java.util.concurrent.Executors

@Composable
fun ScanScreen(nav: NavController) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
        // Ask once for notifications so the daily price summary can be shown.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (!granted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Default.CameraAlt, "Camera access needed", "Card Vault reads card numbers with the camera. Images never leave your phone.") {
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }
        return
    }

    val vm = appViewModel { ScanViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val binders by vm.binders.collectAsStateWithLifecycle(emptyList())
    val lifecycleOwner = LocalLifecycleOwner.current
    var torch by remember { mutableStateOf(false) }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember { TextAnalyzer { lines -> vm.onLines(lines) } }
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            // Card numbers are tiny; give OCR a high-resolution frame.
            imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            setImageAnalysisAnalyzer(executor, analyzer)
        }
    }
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            analyzer.close()
            executor.shutdown()
        }
    }
    LaunchedEffect(torch) { controller.enableTorch(torch) }
    LaunchedEffect(state.phase) { analyzer.paused = state.phase == ScanPhase.RESULTS || state.phase == ScanPhase.NOT_FOUND }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> PreviewView(ctx).apply { this.controller = controller; scaleType = PreviewView.ScaleType.FILL_CENTER } },
            modifier = Modifier.fillMaxSize(),
        )

        // Card-shaped guide in the middle of the frame.
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth(0.72f).aspectRatio(63f / 88f)
                .border(BorderStroke(3.dp, if (state.phase == ScanPhase.READING) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f)), RoundedCornerShape(14.dp))
        )

        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameSelector(state.forcedGame, vm::setGame, Modifier.weight(1f))
                IconButton(onClick = { torch = !torch }) {
                    Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, "Flashlight", tint = Color.White)
                }
            }
            Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Quick add", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = state.quickAdd, onCheckedChange = vm::setQuickAdd)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${state.session.size} scanned · ${formatUsd(state.session.sumOf { it.valueUsd })}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (binders.isNotEmpty()) {
                val options = listOf<Pair<Long?, String>>(null to "No binder") + binders.map { it.binder.id to it.binder.name }
                ChoiceRow(options, options.firstOrNull { it.first == state.binderId } ?: options.first(), { "→ ${it.second}" }) { vm.setBinder(it.first) }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            StatusPill(state, Modifier.align(Alignment.CenterHorizontally))
            when (state.phase) {
                ScanPhase.RESULTS -> ResultsSheet(
                    state = state,
                    onAdd = vm::add,
                    onDismiss = vm::dismiss,
                    onSearch = {
                        val g = state.hint?.game ?: state.forcedGame
                        vm.dismiss()
                        nav.navigate(Routes.search(g, state.hint?.nameCandidates?.firstOrNull().orEmpty()))
                    },
                )
                ScanPhase.NOT_FOUND -> Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Couldn't identify this card", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Read: ${state.hint?.let { listOfNotNull(it.onePieceId, it.pokemonNumber?.let { n -> "$n/${it.pokemonTotal}" }, it.mtgSet, it.mtgNumber).joinToString(" ") + " " + it.nameCandidates.joinToString(" / ") }.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("Tip: hold the card flat, fill the frame, and make sure the card number at the bottom is in focus. Choosing the game above helps too.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = vm::dismiss) { Text("Try again") }
                            Button(onClick = {
                                val hint = state.hint
                                vm.dismiss()
                                nav.navigate(Routes.search(hint?.game ?: state.forcedGame, hint?.nameCandidates?.firstOrNull().orEmpty()))
                            }) { Icon(Icons.Default.Search, null); Text(" Search by name") }
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun GameSelector(selected: Game?, onSelect: (Game?) -> Unit, modifier: Modifier = Modifier) {
    val colors = FilterChipDefaults.filterChipColors(
        containerColor = Color.Black.copy(alpha = 0.5f),
        labelColor = Color.White,
    )
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item { FilterChip(selected == null, { onSelect(null) }, { Text("Auto") }, colors = colors) }
        Game.entries.forEach { g ->
            item { FilterChip(selected == g, { onSelect(g) }, { Text(g.short) }, colors = colors) }
        }
    }
}

@Composable
private fun StatusPill(state: ScanState, modifier: Modifier = Modifier) {
    val text = when (state.phase) {
        ScanPhase.LOOKING -> state.message ?: "Point at a card — keep the bottom edge (card number) in view"
        ScanPhase.READING -> "Reading ${state.hint?.key?.uppercase().orEmpty()}…"
        ScanPhase.SEARCHING -> "Looking it up…"
        ScanPhase.ERROR -> state.message ?: "Something went wrong"
        else -> return
    }
    Surface(modifier.padding(12.dp), color = Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.phase == ScanPhase.SEARCHING) {
                CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ResultsSheet(
    state: ScanState,
    onAdd: (CatalogCard, Int, Finish, Condition) -> Unit,
    onDismiss: () -> Unit,
    onSearch: () -> Unit,
) {
    val settings by appContainer().settings.settings.collectAsStateWithLifecycle()
    var index by remember(state.candidates) { mutableIntStateOf(0) }
    val selected = state.candidates.getOrNull(index) ?: return
    var finish by remember(selected) { mutableStateOf(ScanViewModel.defaultFinish(selected)) }
    var condition by remember { mutableStateOf(Condition.NEAR_MINT) }
    var qty by remember(selected) { mutableIntStateOf(1) }
    val values = remember(selected, settings) { Valuation.values(selected.prices, settings.valuationMode, settings.eurToUsd) }

    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), tonalElevation = 3.dp) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (state.candidates.size == 1) "Found it" else "${state.candidates.size} matches — tap the right printing",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(state.candidates, key = { _, c -> c.card.id }) { i, c ->
                    Column(
                        Modifier.width(92.dp).clickable { index = i }
                            .then(if (i == index) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                            .padding(3.dp),
                    ) {
                        CardImage(c.card.imageUrl, Modifier.fillMaxWidth(), c.card.name)
                        Text(c.card.setCode, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(selected.card.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${selected.card.setName} · #${selected.card.number} · ${selected.card.rarity}", style = MaterialTheme.typography.bodySmall)
                Text(
                    values[finish]?.let { "${formatUsd(it)} (${finish.label})" } ?: "No ${finish.label.lowercase()} price published",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            ChoiceRow(Finish.forGame(selected.card.game), finish, { f -> values[f]?.let { "${f.label} ${formatUsd(it)}" } ?: f.label }) { finish = it }
            ChoiceRow(Condition.entries, condition, { it.short }) { condition = it }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                QuantityStepper(qty, { qty = it })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSearch) { Text("Search") }
                TextButton(onClick = onDismiss) { Text("Skip") }
                Button(onClick = { onAdd(selected, qty, finish, condition) }) { Text("Add") }
            }
        }
    }
}
