package com.hazyoctophant.cardvault.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, message: String) : IOException(message)

/** Small JSON-over-HTTP helper shared by all card database clients. */
class Http(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** GET a JSON document. Returns null on 404 (the APIs use it for "no such card"). */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): JsonElement? =
        execute(Request.Builder().url(url).get(), headers)

    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): JsonElement? =
        execute(Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())), headers)

    /** Downloads a small binary file such as a card image; null if it isn't available. */
    suspend fun bytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.bytes() else null
            }
        }.getOrNull()
    }

    /** Opens a (large) download for streaming. The caller must close the response. */
    suspend fun download(url: String): okhttp3.Response = withContext(Dispatchers.IO) {
        val response = client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw HttpException(response.code, "HTTP ${response.code} downloading ${response.request.url.host}")
        }
        response
    }

    private suspend fun execute(builder: Request.Builder, headers: Map<String, String>): JsonElement? {
        builder.header("User-Agent", USER_AGENT).header("Accept", "application/json")
        headers.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()
        var attempt = 0
        while (true) {
            val result = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> Result.success(null)
                        response.code == 429 || response.code >= 500 ->
                            Result.failure(HttpException(response.code, "HTTP ${response.code} from ${request.url.host}"))
                        !response.isSuccessful ->
                            throw HttpException(response.code, "HTTP ${response.code} from ${request.url.host}")
                        else -> Result.success(json.parseToJsonElement(response.body?.string().orEmpty()))
                    }
                }
            }
            if (result.isSuccess || attempt >= 2) return result.getOrThrow()
            attempt++
            delay(1000L * attempt * attempt)
        }
    }

    companion object {
        const val USER_AGENT = "CardVault/0.1 (Android collection manager)"
    }
}

// ---- Lenient JSON accessors: the card APIs mix numbers, strings and nulls freely. ----

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.str(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    val s = p.contentOrNull?.trim() ?: return null
    return if (s.isEmpty() || s.equals("null", true) || s == "-") null else s
}

fun JsonObject.num(key: String): Double? =
    str(key)?.replace("$", "")?.replace(",", "")?.toDoubleOrNull()

fun JsonArray?.strings(): List<String> =
    this?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
