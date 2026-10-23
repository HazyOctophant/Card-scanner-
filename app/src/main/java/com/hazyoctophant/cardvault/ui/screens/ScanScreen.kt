package com.hazyoctophant.cardvault.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
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
import com.hazyoctophant.cardvault.scan.FrameCrop
import com.hazyoctophant.cardvault.scan.ScanPhase
import com.hazyoctophant.cardvault.scan.ScanState
import com.hazyoctophant.cardvault.scan.ScanViewModel
import com.hazyoctophant.cardvault.scan.ScoredMatch
import com.hazyoctophant.cardvault.scan.TextAnalyzer
import com.hazyoctophant.cardvault.ui.Routes
import com.hazyoctophant.cardvault.ui.appContainer
import com.hazyoctophant.cardvault.ui.appViewModel
import com.hazyoctophant.cardvault.ui.components.CardImage
import com.hazyoctophant.cardvault.ui.components.CardLibraryPanel
import com.hazyoctophant.cardvault.ui.components.ChoiceRow
import com.hazyoctophant.cardvault.ui.components.EmptyState
import com.hazyoctophant.cardvault.ui.components.QuantityStepper
import com.hazyoctophant.cardvault.ui.formatUsd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Width of the on-screen card guide as a fraction of the screen width. */
private const val GUIDE_FRACTION = 0.72f

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
            EmptyState(Icons.Default.CameraAlt, "Camera access needed", "Card Vault photographs cards to identify them. Photos never leave your phone.") {
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }
        return
    }

    val vm = appViewModel { ScanViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val binders by vm.binders.collectAsStateWithLifecycle(emptyList())
    val profile by vm.activeProfile.collectAsStateWithLifecycle(null)
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var torch by remember { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val libraries by appContainer().cardIndex.installed.collectAsStateWithLifecycle()
    var showLibrary by remember { mutableStateOf(false) }
    if (showLibrary) {
        AlertDialog(
            onDismissRequest = { showLibrary = false },
            title = { Text("Card library") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { CardLibraryPanel() } },
            confirmButton = { TextButton(onClick = { showLibrary = false }) { Text("Done") } },
        )
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember { TextAnalyzer { lines -> vm.onLines(lines) } }
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS or CameraController.IMAGE_CAPTURE)
            // Same 4:3 framing for preview and photo, so the on-screen guide maps onto the photo.
            val fourThree = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            previewResolutionSelector = ResolutionSelector.Builder().setAspectRatioStrategy(fourThree).build()
            imageCaptureResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(fourThree)
                .setResolutionStrategy(ResolutionStrategy(Size(2880, 2160), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build()
            imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            setImageAnalysisAnalyzer(executor, analyzer)
        }
    }

    // Wire the shutter: photo -> upright bitmap -> crop to the guide -> match.
    LaunchedEffect(controller, viewSize) {
        vm.requestCapture = {
            controller.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val photo = try { uprightBitmap(image) } finally { image.close() }
                    val r = FrameCrop.guideRect(photo.width, photo.height, viewSize.width, viewSize.height, GUIDE_FRACTION)
                    val card = Bitmap.createBitmap(photo, r.left, r.top, r.width, r.height)
                    if (card !== photo) photo.recycle()
                    vm.analyzePhoto(shrink(card, 1400))
                }

                override fun onError(exception: ImageCaptureException) = vm.captureFailed("Couldn't take the photo: ${exception.message}")
            })
        }
    }
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            vm.requestCapture = null
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            analyzer.close()
            executor.shutdown()
        }
    }
    LaunchedEffect(torch) { controller.enableTorch(torch) }
    LaunchedEffect(state.phase) { analyzer.paused = vm.isBusy }

    // Identify a card from a photo already on the phone.
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { loadBitmap(context, uri) }.getOrNull() }
            if (bmp != null) vm.analyzePhoto(bmp) else vm.captureFailed("Couldn't open that picture")
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { viewSize = it }) {
        AndroidView(
            factory = { ctx -> PreviewView(ctx).apply { this.controller = controller; scaleType = PreviewView.ScaleType.FILL_CENTER } },
            modifier = Modifier.fillMaxSize(),
        )

        // Card-shaped guide: line the card's edges up with it.
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth(GUIDE_FRACTION).aspectRatio(FrameCrop.CARD_ASPECT)
                .border(
                    BorderStroke(3.dp, if (state.phase == ScanPhase.READING) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.75f)),
                    RoundedCornerShape(14.dp),
                ),
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
                    Text("Auto-capture", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = state.autoCapture, onCheckedChange = vm::setAutoCapture)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${state.session.size} added · ${formatUsd(state.session.sumOf { it.valueUsd })}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (libraries.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(top = 6.dp).clickable { showLibrary = true },
                ) {
                    Text(
                        "Tap to download the card library — recognises cards from their picture (much more accurate)",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
                    owner = profile?.name,
                    onAdd = vm::add,
                    onDismiss = vm::dismiss,
                    onSearch = {
                        val hint = state.hint
                        vm.dismiss()
                        nav.navigate(Routes.search(hint?.game ?: state.forcedGame, hint?.nameCandidates?.firstOrNull().orEmpty()))
                    },
                )
                ScanPhase.NOT_FOUND -> Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Couldn't identify this card", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Read: " + (state.hint?.let { h ->
                                listOfNotNull(h.onePieceId, h.pokemonNumber?.let { n -> "$n/${h.pokemonTotal.orEmpty()}" }, h.mtgSet, h.mtgNumber)
                                    .joinToString(" ") + " " + h.nameCandidates.joinToString(" / ")
                            } ?: "nothing legible"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Tips: fill the frame with the card, avoid glare (tilt it slightly away from lights), and choose the game at the top.",
                            style = MaterialTheme.typography.bodySmall,
                        )
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
                ScanPhase.MATCHING, ScanPhase.CAPTURING -> Unit
                else -> ShutterBar(onShutter = vm::capture, onGallery = {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
            }
        }
    }
}

@Composable
private fun ShutterBar(onShutter: () -> Unit, onGallery: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(bottom = 20.dp, top = 4.dp)) {
        IconButton(onClick = onGallery, modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)) {
            Icon(Icons.Default.PhotoLibrary, "Identify a card from a photo", tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Box(
            Modifier.align(Alignment.Center).size(76.dp).border(4.dp, Color.White, CircleShape).padding(7.dp)
                .background(Color.White, CircleShape).clickable(onClick = onShutter),
        )
    }
}

/** The capture as an upright bitmap (camera sensors are usually rotated relative to the screen). */
private fun uprightBitmap(image: ImageProxy): Bitmap {
    val raw = image.toBitmap()
    val degrees = image.imageInfo.rotationDegrees
    if (degrees == 0) return raw
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
}

/** Downscale so the longest side is at most [max] px (enough for OCR, light on memory). */
private fun shrink(bmp: Bitmap, max: Int): Bitmap {
    val longest = maxOf(bmp.width, bmp.height)
    if (longest <= max) return bmp
    val f = max.toFloat() / longest
    return Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true).also { if (it !== bmp) bmp.recycle() }
}

private fun loadBitmap(context: Context, uri: Uri): Bitmap {
    val bmp = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // pixels must be readable for matching
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > 2000) decoder.setTargetSampleSize(longest / 1400)
        }
    } else {
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            ?: error("Unreadable image")
    }
    return shrink(bmp, 1400)
}

@Composable
private fun GameSelector(selected: Game?, onSelect: (Game?) -> Unit, modifier: Modifier = Modifier) {
    val colors = FilterChipDefaults.filterChipColors(containerColor = Color.Black.copy(alpha = 0.5f), labelColor = Color.White)
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item { FilterChip(selected == null, { onSelect(null) }, { Text("Auto") }, colors = colors) }
        Game.entries.forEach { g -> item { FilterChip(selected == g, { onSelect(g) }, { Text(g.short) }, colors = colors) } }
    }
}

@Composable
private fun StatusPill(state: ScanState, modifier: Modifier = Modifier) {
    val text = when (state.phase) {
        ScanPhase.LOOKING -> state.message ?: "Line the card up with the frame, then tap the shutter"
        ScanPhase.READING -> if (state.autoCapture && state.hint?.hasIdentifier == true) "Hold still…" else "Seen: ${state.hint?.key?.uppercase().orEmpty()} — tap the shutter"
        ScanPhase.CAPTURING -> "Taking photo…"
        ScanPhase.MATCHING -> state.message ?: "Matching…"
        ScanPhase.ERROR -> state.message ?: "Something went wrong"
        else -> return
    }
    Surface(modifier.padding(12.dp), color = Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.phase == ScanPhase.MATCHING || state.phase == ScanPhase.CAPTURING) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** "Is this your card?" — the best picture match, alternatives, finish/condition/quantity, then confirm. */
@Composable
private fun ResultsSheet(
    state: ScanState,
    owner: String?,
    onAdd: (CatalogCard, Int, Finish, Condition) -> Unit,
    onDismiss: () -> Unit,
    onSearch: () -> Unit,
) {
    val settings by appContainer().settings.settings.collectAsStateWithLifecycle()
    var index by remember(state.matches) { mutableIntStateOf(0) }
    val match: ScoredMatch<CatalogCard> = state.matches.getOrNull(index) ?: return
    val selected = match.item
    var finish by remember(selected) { mutableStateOf(ScanViewModel.defaultFinish(selected)) }
    var condition by remember { mutableStateOf(Condition.NEAR_MINT) }
    var qty by remember(selected) { mutableIntStateOf(1) }
    val values = remember(selected, settings) { Valuation.values(selected.prices, settings.valuationMode, settings.eurToUsd) }

    Surface(Modifier.fillMaxWidth().heightIn(max = 620.dp), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), tonalElevation = 3.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (owner != null) "Add this card to $owner's collection?" else "Add this card to your collection?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            // Your photo next to the matched printing.
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                state.photo?.let { photo ->
                    Column(Modifier.width(110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            photo.asImageBitmap(), "Your photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(FrameCrop.CARD_ASPECT).background(Color.Black, RoundedCornerShape(6.dp)),
                        )
                        Text("Your card", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Column(Modifier.width(110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CardImage(selected.card.imageUrl, Modifier.fillMaxWidth(), selected.card.name)
                    Text(matchLabel(match), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(selected.card.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${selected.card.setName} (${selected.card.setCode})", style = MaterialTheme.typography.bodySmall)
                    Text("#${selected.card.number} · ${selected.card.rarity}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        values[finish]?.let { formatUsd(it) } ?: "No ${finish.label.lowercase()} price",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (match.percent < 60) {
                Text(
                    "Not a confident match — check the other printings below, or search instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (state.matches.size > 1) {
                Text("Other printings (best matches first)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(state.matches.take(24), key = { _, m -> m.item.card.id }) { i, m ->
                        Column(
                            Modifier.width(78.dp).clickable { index = i }
                                .then(if (i == index) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                                .padding(3.dp),
                        ) {
                            CardImage(m.item.card.imageUrl, Modifier.fillMaxWidth(), m.item.card.name)
                            Text("${m.percent}% · ${m.item.card.setCode}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Text("Finish", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp))
            ChoiceRow(Finish.forGame(selected.card.game), finish, { f -> values[f]?.let { "${f.label} ${formatUsd(it)}" } ?: f.label }) { finish = it }
            Text("Condition", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp))
            ChoiceRow(Condition.entries, condition, { it.short }) { condition = it }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Copies")
                QuantityStepper(qty, { qty = it })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSearch) { Text("Not it? Search") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss) { Text("No") }
                Button(onClick = { onAdd(selected, qty, finish, condition) }) { Text("Yes, add") }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

private fun matchLabel(m: ScoredMatch<*>): String = when {
    m.numberMatch && m.percent >= 60 -> "${m.percent}% match · number ✓"
    else -> "${m.percent}% match"
}
