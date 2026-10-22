package com.hazyoctophant.cardvault.scan

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hazyoctophant.cardvault.AppContainer
import com.hazyoctophant.cardvault.data.remote.CatalogCard
import com.hazyoctophant.cardvault.model.Condition
import com.hazyoctophant.cardvault.model.Finish
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.pricing.Valuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ScanPhase { LOOKING, READING, SEARCHING, RESULTS, NOT_FOUND, ERROR }

data class ScannedItem(val name: String, val valueUsd: Double)

data class ScanState(
    val forcedGame: Game? = null,
    val quickAdd: Boolean = false,
    val phase: ScanPhase = ScanPhase.LOOKING,
    val hint: ScanHint? = null,
    val candidates: List<CatalogCard> = emptyList(),
    val message: String? = null,
    val session: List<ScannedItem> = emptyList(),
    val binderId: Long? = null,
)

/**
 * Turns OCR frames into card lookups. A reading has to be stable across consecutive frames before we
 * hit the network, which filters out half-focused frames and keeps API use polite.
 */
class ScanViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ScanState())
    val state = _state.asStateFlow()
    val binders = c.library.observeBinders()

    private var lastKey: String? = null
    private var stableFrames = 0
    private var cooldownKey: String? = null
    private var cooldownUntil = 0L

    val isBusy: Boolean
        get() = _state.value.phase in setOf(ScanPhase.SEARCHING, ScanPhase.RESULTS, ScanPhase.NOT_FOUND)

    fun setGame(game: Game?) = _state.update { it.copy(forcedGame = game) }
    fun setQuickAdd(on: Boolean) = _state.update { it.copy(quickAdd = on) }
    fun setBinder(id: Long?) = _state.update { it.copy(binderId = id) }

    fun onLines(lines: List<String>) {
        if (isBusy) return
        val hint = CardTextParser.parse(lines, _state.value.forcedGame)
        val key = hint.key ?: return
        val now = SystemClock.elapsedRealtime()
        if (key == cooldownKey && now < cooldownUntil) return

        if (key == lastKey) stableFrames++ else { lastKey = key; stableFrames = 1 }
        _state.update { it.copy(phase = ScanPhase.READING, hint = hint, message = null) }
        val needed = if (hint.hasIdentifier) 2 else 3
        if (stableFrames >= needed) lookup(hint, key)
    }

    private fun lookup(hint: ScanHint, key: String) {
        stableFrames = 0
        _state.update { it.copy(phase = ScanPhase.SEARCHING) }
        viewModelScope.launch {
            val result = runCatching { c.cards.identify(hint) }
            val found = result.getOrDefault(emptyList())
            when {
                result.isFailure -> {
                    _state.update { it.copy(phase = ScanPhase.ERROR, message = "Lookup failed: ${result.exceptionOrNull()?.message}") }
                    resumeAfter(key, 3000)
                }
                found.isEmpty() -> {
                    _state.update { it.copy(phase = ScanPhase.NOT_FOUND, candidates = emptyList()) }
                }
                _state.value.quickAdd && hint.hasIdentifier -> {
                    val card = found.first()
                    add(card, 1, defaultFinish(card), Condition.NEAR_MINT)
                    resumeAfter(key, 4000)
                }
                else -> _state.update { it.copy(phase = ScanPhase.RESULTS, candidates = found) }
            }
        }
    }

    fun add(card: CatalogCard, quantity: Int, finish: Finish, condition: Condition) {
        viewModelScope.launch {
            c.cards.addToCollection(card, quantity, finish, condition, _state.value.binderId)
            val settings = c.settings.current
            val unit = Valuation.values(card.prices, settings.valuationMode, settings.eurToUsd).let { it[finish] ?: it[Finish.NORMAL] ?: it.values.firstOrNull() } ?: 0.0
            _state.update {
                it.copy(
                    session = it.session + List(quantity) { ScannedItem(card.card.name, unit) },
                    message = "Added ${if (quantity > 1) "$quantity × " else ""}${card.card.name}",
                )
            }
        }
        lastKey?.let { cooldownKey = it; cooldownUntil = SystemClock.elapsedRealtime() + 4000 }
        reset()
    }

    /** Close the results and keep scanning. The same card is ignored briefly so it isn't re-detected at once. */
    fun dismiss() {
        lastKey?.let { cooldownKey = it; cooldownUntil = SystemClock.elapsedRealtime() + 2500 }
        reset()
    }

    private fun reset() {
        lastKey = null
        stableFrames = 0
        _state.update { it.copy(phase = ScanPhase.LOOKING, candidates = emptyList(), hint = null) }
    }

    private fun resumeAfter(key: String, ms: Long) {
        cooldownKey = key
        cooldownUntil = SystemClock.elapsedRealtime() + ms
        viewModelScope.launch {
            delay(1500)
            if (_state.value.phase != ScanPhase.RESULTS) reset()
        }
    }

    companion object {
        /** Most rares only exist as holo/foil; pick the finish that actually has a price. */
        fun defaultFinish(card: CatalogCard): Finish {
            val finishes = card.prices.map { it.finish }.toSet()
            return if (Finish.NORMAL in finishes || finishes.isEmpty()) Finish.NORMAL else card.prices.first().finish
        }
    }
}
