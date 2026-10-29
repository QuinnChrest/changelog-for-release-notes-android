package com.yourversionnumber.changelog.kit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.serializer
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** What went wrong with a request, sorted by what the app should do next. */
class ApiException(
    /** The HTTP status, or 0 when no answer came (offline, timed out). */
    val status: Int,
    /** The API's stable code, or `network` and `unreadable` with no API answer. */
    val code: String,
    override val message: String,
    val field: String? = null,
    /** The note's current revision, with `revision_mismatch`. */
    val revision: String? = null,
    val retryAfterSeconds: Long? = null,
) : Exception(message) {
    /** The token no longer works: stop sending, keep the queue, ask for a new one. */
    val signedOut: Boolean get() = status == 401

    /** Worth sending again later with the same idempotency key. */
    val retryable: Boolean get() = status == 0 || status == 429 || status >= 500 || code == "busy"
}

/**
 * A client of `/v1`. It holds the token only in memory; the app keeps it in
 * the Keystore. The token is never logged or put in a URL.
 */
class Api(
    val baseUrl: String,
    private val token: String,
    val userAgent: String,
) {
    suspend fun me(): Me = send("GET", "v1/me")

    suspend fun createNote(note: NoteRequest, idempotencyKey: String): NoteReceipt =
        send("POST", "v1/notes", body = encode(note), idempotencyKey = idempotencyKey)

    suspend fun note(date: String, id: String): NoteDetail = send("GET", "v1/notes/$date/$id")

    suspend fun editNote(date: String, id: String, text: String, revision: String, idempotencyKey: String): NoteRef =
        send<Wrapped>("PUT", "v1/notes/$date/$id", body = encode(EditBody(text, revision)), idempotencyKey = idempotencyKey).note

    suspend fun appendNote(date: String, id: String, text: String, idempotencyKey: String): NoteRef =
        send<Wrapped>("POST", "v1/notes/$date/$id/append", body = encode(AppendBody(text)), idempotencyKey = idempotencyKey).note

    suspend fun deleteNote(date: String, id: String, idempotencyKey: String) {
        send<Unit>("DELETE", "v1/notes/$date/$id", idempotencyKey = idempotencyKey)
    }

    suspend fun tags(): Tags = send("GET", "v1/tags")

    suspend fun setPinned(pinned: List<String>, idempotencyKey: String): Tags =
        send("PUT", "v1/tags/pinned", body = encode(PinsBody(pinned)), idempotencyKey = idempotencyKey)

    suspend fun activity(after: String? = null, before: String? = null, kinds: List<String> = emptyList(), limit: Int = 50): ActivityPage {
        val query = buildList {
            add("limit" to limit.toString())
            after?.let { add("after" to it) }
            before?.let { add("before" to it) }
            if (kinds.isNotEmpty()) add("kinds" to kinds.joinToString(","))
        }
        return send("GET", "v1/activity", query = query)
    }

    /** Sign out: the token revokes itself. */
    suspend fun revokeToken() {
        send<Unit>("DELETE", "v1/token")
    }

    // Transport

    @Serializable
    private data class Wrapped(val note: NoteRef)

    @Serializable
    private data class EditBody(val text: String, val revision: String)

    @Serializable
    private data class AppendBody(val text: String)

    @Serializable
    private data class PinsBody(val pinned: List<String>)

    private inline fun <reified T> encode(value: T): String = json.encodeToString(serializer<T>(), value)

    private suspend inline fun <reified T> send(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: String? = null,
        idempotencyKey: String? = null,
    ): T = send(serializer<T>(), method, path, query, body, idempotencyKey)

    private suspend fun <T> send(
        serializer: KSerializer<T>,
        method: String,
        path: String,
        query: List<Pair<String, String>>,
        body: String?,
        idempotencyKey: String?,
    ): T = withContext(Dispatchers.IO) {
        val address = buildString {
            append(baseUrl.trimEnd('/')).append('/').append(path)
            if (query.isNotEmpty()) {
                append('?')
                append(query.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" })
            }
        }
        val connection = try {
            URI(address).toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw ApiException(0, "unreadable", "That server address doesn't work.")
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", userAgent)
            idempotencyKey?.let { connection.setRequestProperty("Idempotency-Key", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val text = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                throw error(status, text, connection.getHeaderField("Retry-After"))
            }
            val text = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            @Suppress("UNCHECKED_CAST")
            if (serializer == Unit.serializer()) return@withContext Unit as T
            try {
                json.decodeFromString(serializer, text)
            } catch (e: Exception) {
                throw ApiException(status, "unreadable", "Release Notes answered in a way this version doesn't understand.")
            }
        } catch (e: IOException) {
            throw ApiException(0, "network", "Release Notes can't be reached right now.")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val PRODUCTION = "https://notes.yourversionnumber.com"

        /** `tools/dev-server.ts` in the Release Notes repo, as the emulator reaches it. */
        const val DEV_SERVER = "http://10.0.2.2:8790"

        val json = Json {
            namingStrategy = JsonNamingStrategy.SnakeCase
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

        internal fun error(status: Int, body: String, retryAfter: String?): ApiException {
            val seconds = retryAfter?.trim()?.toLongOrNull()
            val parsed = runCatching { json.decodeFromString(ErrorBody.serializer(), body) }.getOrNull()
            if (parsed != null) {
                val e = parsed.error
                return ApiException(status, e.code, e.message, e.field, e.revision, seconds)
            }
            val code = if (status >= 500) "unavailable" else "http_$status"
            val message = if (status >= 500) "Release Notes isn't answering right now." else "Release Notes refused that ($status)."
            return ApiException(status, code, message, retryAfterSeconds = seconds)
        }
    }
}
