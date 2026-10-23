package com.hazyoctophant.cardvault.scan

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.hazyoctophant.cardvault.AppContainer
import com.hazyoctophant.cardvault.data.remote.CatalogCard
import com.hazyoctophant.cardvault.model.Condition
import com.hazyoctophant.cardvault.model.Finish
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.pricing.Valuation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ScanPhase { LOOKING, READING, CAPTURING, MATCHING, RESULTS, NOT_FOUND, ERROR }

data class ScannedItem(val name: String, val valueUsd: Double)

data class ScanState(
    val forcedGame: Game? = null,
    /** Take the photo automatically once a card number is steady in view. */
    val autoCapture: Boolean = true,
    val phase: ScanPhase = ScanPhase.LOOKING,
    val hint: ScanHint? = null,
    val matches: List<ScoredMatch<CatalogCard>> = emptyList(),
    /** The cropped photo of the card that was matched, shown next to the result. */
    val photo: Bitmap? = null,
    val message: String? = null,
    val session: List<ScannedItem> = emptyList(),
    val binderId: Long? = null,
)

/**
 * Scanning works in two steps:
 *  1. Live preview: on-device OCR watches for a card and its printed number (for auto-capture and feedback).
 *  2. Photo match: a full-resolution photo of the whole card is read again (all text) and compared,
 *     picture against picture, with every candidate printing. The best match is offered to add.
 */
class ScanViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ScanState())
    val state = _state.asStateFlow()
    val binders = c.library.observeBinders()
    val activeProfile = c.profiles.activeProfile

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var lastKey: String? = null
    private var stableFrames = 0
    private var cooldownUntil = 0L
    private var job: Job? = null

    /** Set by the screen: takes a photo and hands it to [analyzePhoto]. */
    var requestCapture: (() -> Unit)? = null

    val isBusy: Boolean
        get() = _state.value.phase in setOf(ScanPhase.CAPTURING, ScanPhase.MATCHING, ScanPhase.RESULTS, ScanPhase.NOT_FOUND)

    fun setGame(game: Game?) = _state.update { it.copy(forcedGame = game) }
    fun setAutoCapture(on: Boolean) = _state.update { it.copy(autoCapture = on) }
    fun setBinder(id: Long?) = _state.update { it.copy(binderId = id) }

    /** Live OCR frames from the preview. */
    fun onLines(lines: List<String>) {
        if (isBusy || SystemClock.elapsedRealtime() < cooldownUntil) return
        val hint = CardTextParser.parse(lines, _state.value.forcedGame)
        val key = hint.key ?: return
        if (key == lastKey) stableFrames++ else { lastKey = key; stableFrames = 1 }
        _state.update { it.copy(phase = ScanPhase.READING, hint = hint, message = null) }
        if (_state.value.autoCapture && hint.hasIdentifier && stableFrames >= 3) capture()
    }

    /** Shutter button (or auto-capture). */
    fun capture() {
        if (isBusy) return
        stableFrames = 0
        _state.update { it.copy(phase = ScanPhase.CAPTURING, message = null) }
        requestCapture?.invoke() ?: _state.update { it.copy(phase = ScanPhase.LOOKING) }
    }

    fun captureFailed(message: String) {
        _state.update { it.copy(phase = ScanPhase.ERROR, message = message) }
        resumeSoon()
    }

    /** Reads and matches a photo of a card (from the camera, cropped to the frame, or from the gallery). */
    fun analyzePhoto(photo: Bitmap) {
        job?.cancel()
        _state.update { it.copy(phase = ScanPhase.MATCHING, photo = photo, matches = emptyList(), message = "Reading the card…") }
        job = viewModelScope.launch {
            try {
                val text = recognizer.process(InputImage.fromBitmap(photo, 0)).await()
                val lines = text.textBlocks.flatMap { it.lines }
                    .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                    .map { it.text }
                // Merge with what the live preview read, in case the photo missed something.
                val live = _state.value.hint
                val parsed = CardTextParser.parse(lines, _state.value.forcedGame ?: live?.game)
                val hint = parsed.copy(
                    onePieceId = parsed.onePieceId ?: live?.onePieceId,
                    pokemonNumber = parsed.pokemonNumber ?: live?.pokemonNumber,
                    pokemonTotal = parsed.pokemonTotal ?: live?.pokemonTotal,
                    mtgSet = parsed.mtgSet ?: live?.mtgSet,
                    mtgNumber = parsed.mtgNumber ?: live?.mtgNumber,
                    nameCandidates = (parsed.nameCandidates + live?.nameCandidates.orEmpty()).distinct(),
                )
                _state.update { it.copy(hint = hint, message = "Finding every printing…") }
                val candidates = c.cards.candidatesForPhoto(hint)
                if (candidates.isEmpty()) {
                    _state.update { it.copy(phase = ScanPhase.NOT_FOUND, message = null) }
                    return@launch
                }
                _state.update { it.copy(message = "Comparing ${candidates.size} card images…") }
                val ranked = c.imageMatcher.rank(photo, candidates, hint, lines.joinToString("\n"))
                _state.update { it.copy(phase = ScanPhase.RESULTS, matches = ranked, message = null) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(phase = ScanPhase.ERROR, message = "Couldn't look the card up: ${e.message}") }
                resumeSoon()
            }
        }
    }

    fun add(card: CatalogCard, quantity: Int, finish: Finish, condition: Condition) {
        viewModelScope.launch {
            c.cards.addToCollection(card, quantity, finish, condition, _state.value.binderId)
            val settings = c.settings.current
            val values = Valuation.values(card.prices, settings.valuationMode, settings.eurToUsd)
            val unit = values[finish] ?: values[Finish.NORMAL] ?: values.values.firstOrNull() ?: 0.0
            _state.update {
                it.copy(
                    session = it.session + List(quantity) { ScannedItem(card.card.name, unit) },
                    message = "Added ${if (quantity > 1) "$quantity × " else ""}${card.card.name}",
                )
            }
        }
        reset(cooldownMs = 3000)
    }

    /** Close the results and keep scanning. The same card is ignored briefly so it isn't re-captured at once. */
    fun dismiss() = reset(cooldownMs = 2500)

    private fun reset(cooldownMs: Long) {
        job?.cancel()
        lastKey = null
        stableFrames = 0
        cooldownUntil = SystemClock.elapsedRealtime() + cooldownMs
        _state.update { it.copy(phase = ScanPhase.LOOKING, matches = emptyList(), hint = null, photo = null) }
    }

    private fun resumeSoon() = viewModelScope.launch {
        kotlinx.coroutines.delay(2500)
        if (_state.value.phase == ScanPhase.ERROR) reset(0)
    }

    override fun onCleared() = recognizer.close()

    companion object {
        /** Most rares only exist as holo/foil; pick the finish that actually has a price. */
        fun defaultFinish(card: CatalogCard): Finish {
            val finishes = card.prices.map { it.finish }.toSet()
            return if (Finish.NORMAL in finishes || finishes.isEmpty()) Finish.NORMAL else card.prices.first().finish
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
