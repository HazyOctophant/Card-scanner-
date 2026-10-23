package com.hazyoctophant.cardvault.scan

import android.content.Context
import com.hazyoctophant.cardvault.data.remote.Http
import com.hazyoctophant.cardvault.model.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream

data class InstalledIndex(val cards: Int, val version: String, val sizeBytes: Long)

/**
 * The visual card library: per-game files of fingerprints of every card image, built by the
 * "Card image index" GitHub workflow and downloaded from the repository's "card-index" release.
 */
class CardIndexRepository(context: Context, private val http: Http) {
    private val dir = File(context.filesDir, "card-index").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("card-index", Context.MODE_PRIVATE)
    private val loaded = mutableMapOf<Game, CardIndex>()
    private val lock = Mutex()

    private val _installed = MutableStateFlow(readInstalled())
    val installed: StateFlow<Map<Game, InstalledIndex>> = _installed.asStateFlow()

    private fun file(game: Game) = File(dir, "${game.name}.cvix.gz")

    private fun readInstalled(): Map<Game, InstalledIndex> = Game.entries.mapNotNull { g ->
        val f = file(g)
        if (!f.exists()) null
        else g to InstalledIndex(prefs.getInt("${g.name}.cards", 0), prefs.getString("${g.name}.version", "") ?: "", f.length())
    }.toMap()

    /** The index for [game], loading it into memory on first use; null if it isn't downloaded. */
    suspend fun index(game: Game): CardIndex? = lock.withLock {
        loaded[game] ?: withContext(Dispatchers.IO) {
            val f = file(game)
            if (!f.exists()) null else runCatching { GZIPInputStream(f.inputStream()).use { CardIndex.read(it) } }.getOrNull()
        }?.also { loaded[game] = it }
    }

    suspend fun download(game: Game, onProgress: suspend (Float) -> Unit): InstalledIndex = withContext(Dispatchers.IO) {
        val tmp = File(dir, "${game.name}.download")
        http.download(urlFor(game)).use { response ->
            val body = response.body ?: error("Empty download")
            val total = body.contentLength()
            tmp.outputStream().use { out ->
                val input = body.byteStream()
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0 && done - lastReport > 256 * 1024) { lastReport = done; onProgress(done.toFloat() / total) }
                }
            }
        }
        // Validate before replacing the old copy.
        val index = GZIPInputStream(tmp.inputStream()).use { CardIndex.read(it) }
        check(index.game == game && index.size > 0) { "Downloaded index is invalid" }
        tmp.renameTo(file(game))
        prefs.edit().putInt("${game.name}.cards", index.size).putString("${game.name}.version", index.version).apply()
        lock.withLock { loaded[game] = index }
        _installed.value = readInstalled()
        onProgress(1f)
        _installed.value.getValue(game)
    }

    suspend fun delete(game: Game) {
        lock.withLock { loaded.remove(game) }
        withContext(Dispatchers.IO) { file(game).delete() }
        _installed.value = readInstalled()
    }

    companion object {
        private const val RELEASE = "https://github.com/HazyOctophant/Card-scanner-/releases/download/card-index"
        fun urlFor(game: Game) = "$RELEASE/${game.name}.cvix.gz"

        /** Approximate download sizes, shown before downloading. */
        fun approxSize(game: Game) = when (game) {
            Game.MTG -> "about 15 MB"
            Game.POKEMON -> "about 8 MB"
            Game.ONE_PIECE -> "about 2 MB"
        }
    }
}
