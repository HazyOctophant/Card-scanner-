package com.hazyoctophant.cardvault.indexer

import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.scan.CardIndex
import com.hazyoctophant.cardvault.scan.IndexEntry
import com.hazyoctophant.cardvault.scan.VisualSignature
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeToSequence
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.imageio.ImageIO

/** A card image to fingerprint. */
data class Source(val id: String, val name: String, val setCode: String, val number: String, val imageUrl: String)

const val SIG_W = 126
const val SIG_H = 176

private val json = Json { ignoreUnknownKeys = true; isLenient = true }
private val http: HttpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .connectTimeout(Duration.ofSeconds(20))
    .build()

private fun request(url: String) = HttpRequest.newBuilder(URI(url))
    .header("User-Agent", "CardVaultIndexer/1.0 (github.com/HazyOctophant/Card-scanner-)")
    .header("Accept", "*/*")
    .timeout(Duration.ofSeconds(120))
    .build()

private fun fetchStream(url: String): InputStream {
    var last: Exception? = null
    repeat(4) { attempt ->
        try {
            val r = http.send(request(url), HttpResponse.BodyHandlers.ofInputStream())
            if (r.statusCode() == 200) return r.body()
            r.body().close()
            last = IllegalStateException("HTTP ${r.statusCode()} for $url")
            if (r.statusCode() == 404) throw last!!
        } catch (e: Exception) {
            last = e
            if (e.message?.contains("HTTP 404") == true) throw e
        }
        Thread.sleep(1000L * (attempt + 1) * (attempt + 1))
    }
    throw last!!
}

/** Scryfall serves its bulk files gzip-compressed; detect that from the gzip magic bytes. */
fun maybeGunzip(input: InputStream): InputStream {
    val buffered = BufferedInputStream(input)
    buffered.mark(2)
    val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
    buffered.reset()
    return if (gzip) GZIPInputStream(buffered) else buffered
}

private fun fetchJson(url: String): JsonElement = maybeGunzip(fetchStream(url)).use { json.parseToJsonElement(it.readBytes().decodeToString()) }

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

// ---------------------------------------------------------------- sources

/** Scryfall "unique artwork": one entry per distinct illustration (reprints with the same art share one). */
@OptIn(ExperimentalSerializationApi::class)
fun magicSources(): List<Source> {
    // The bulk-data list names each file by "type"; look ours up there rather than guessing its URL.
    val list = (fetchJson("https://api.scryfall.com/bulk-data") as JsonObject).arr("data").orEmpty()
    val bulk = list.mapNotNull { it as? JsonObject }.firstOrNull { it.str("type") == "unique_artwork" }
        ?: error("Scryfall has no unique_artwork bulk file")
    val uri = bulkFileUrl(bulk) ?: error("No bulk download link in: $bulk")
    val skip = setOf("token", "double_faced_token", "emblem", "art_series", "vanguard", "scheme", "planar", "augment", "host")
    return maybeGunzip(fetchStream(uri)).use { input ->
        json.decodeToSequence(input, JsonElement.serializer(), DecodeSequenceMode.AUTO_DETECT)
            .mapNotNull { it as? JsonObject }
            .filter { o -> o.str("layout") !in skip && (o.arr("games")?.any { (it as? JsonPrimitive)?.content == "paper" } ?: true) }
            .mapNotNull { o ->
                val image = o.obj("image_uris")?.str("small")
                    ?: (o.arr("card_faces")?.firstOrNull() as? JsonObject)?.obj("image_uris")?.str("small")
                    ?: return@mapNotNull null
                Source("mtg:" + o.str("id"), o.str("name") ?: "", (o.str("set") ?: "").uppercase(), o.str("collector_number") ?: "", image)
            }
            .toList()
    }
}

/** The file link of a Scryfall bulk-data entry ("download_uri", or any field that points at a .json file). */
fun bulkFileUrl(bulk: JsonObject): String? =
    bulk.str("download_uri") ?: bulk.str("download_url")
        ?: bulk.values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .firstOrNull { it.startsWith("http") && (it.contains(".json") || it.contains("/file")) && !it.contains("api.scryfall.com/bulk-data") }

/** TCGdex: every English Pokémon card with a picture. */
fun pokemonSources(): List<Source> {
    val list = fetchJson("https://api.tcgdex.net/v2/en/cards") as JsonArray
    return list.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val id = o.str("id") ?: return@mapNotNull null
        val image = o.str("image") ?: return@mapNotNull null
        Source("pkm:$id", o.str("name") ?: "", id.substringBeforeLast('-').uppercase(), o.str("localId") ?: "", "$image/low.png")
    }
}

/** OPTCG API: booster, starter deck and promo cards, including every parallel / alternate art. */
fun onePieceSources(): List<Source> {
    val out = LinkedHashMap<String, Source>()
    for (endpoint in listOf("allSetCards", "allSTCards", "allPromoCards")) {
        val arr = runCatching { fetchJson("https://optcgapi.com/api/$endpoint/") as? JsonArray }.getOrNull() ?: continue
        for (e in arr) {
            val o = e as? JsonObject ?: continue
            val number = o.str("card_set_id") ?: continue
            val imageId = o.str("card_image_id") ?: number
            val image = o.str("card_image") ?: continue
            out.putIfAbsent(imageId, Source("op:$imageId", o.str("card_name") ?: "", o.str("set_id") ?: number.substringBefore('-'), number, image))
        }
    }
    return out.values.toList()
}

// ---------------------------------------------------------------- fingerprinting

/** Downloads and scales an image to the signature size, on white (for transparent PNG corners). */
fun pixelsOf(url: String): IntArray? {
    val bytes = fetchStream(url).use { it.readBytes() }
    val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
    val scaled = BufferedImage(SIG_W, SIG_H, BufferedImage.TYPE_INT_RGB)
    val g = scaled.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.color = Color.WHITE
    g.fillRect(0, 0, SIG_W, SIG_H)
    g.drawImage(img, 0, 0, SIG_W, SIG_H, null)
    g.dispose()
    return scaled.getRGB(0, 0, SIG_W, SIG_H, null, 0, SIG_W)
}

fun main(args: Array<String>) {
    val game = Game.valueOf(args[0])
    val out = File(args[1])
    val limit = args.getOrNull(2)?.toIntOrNull()
    val threads = if (game == Game.MTG) 8 else 12

    val sources = when (game) {
        Game.MTG -> magicSources()
        Game.POKEMON -> pokemonSources()
        Game.ONE_PIECE -> onePieceSources()
    }.distinctBy { it.id }.let { if (limit != null) it.take(limit) else it }
    println("${game.name}: ${sources.size} card images to fingerprint")

    val done = AtomicInteger()
    val failed = AtomicInteger()
    val pool = Executors.newFixedThreadPool(threads)
    val futures = sources.map { s ->
        pool.submit<IndexEntry?> {
            try {
                pixelsOf(s.imageUrl)?.let { px -> IndexEntry(s.id, s.name, s.setCode, s.number, VisualSignature.of(px, SIG_W, SIG_H, game)) }
            } catch (e: Exception) {
                failed.incrementAndGet()
                null
            } finally {
                val n = done.incrementAndGet()
                if (n % 1000 == 0) println("  $n / ${sources.size} (${failed.get()} failed)")
            }
        }
    }
    val entries = futures.mapNotNull { it.get() }
    pool.shutdown()
    println("${game.name}: indexed ${entries.size}, failed ${failed.get()}")
    check(entries.size >= sources.size * 0.8) { "Too many failures; not publishing a partial index" }

    out.parentFile?.mkdirs()
    GZIPOutputStream(out.outputStream()).use { CardIndex.write(it, game, LocalDate.now().toString(), entries) }
    println("Wrote ${out.length() / 1024} KB to $out")
}
