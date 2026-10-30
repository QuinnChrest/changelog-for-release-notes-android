package com.yourversionnumber.changelog.kit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

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

    /** A signed form for one file. Needs `media:write`. No receipt: a retry gets a new form, and an unused one expires. */
    suspend fun startUpload(start: UploadStart): UploadForm = send("POST", "v1/uploads", body = encode(start))

    /**
     * Posts a file on disk to its signed form, streamed, so it's never in
     * memory whole. The form goes to file storage, not to Release Notes, so
     * it carries no token and no cookie.
     */
    suspend fun postFile(file: File, name: String, form: UploadForm) = withContext(Dispatchers.IO) {
        if (!file.exists()) throw ApiException(400, "file_missing", FILE_MISSING)
        // S3's form is a full URL; the dev server's is a path on itself.
        val address = URI(baseUrl.trimEnd('/') + "/").resolve(form.url).toString()
        val boundary = "changelog-" + UUID.randomUUID()
        val connection = try {
            URI(address).toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw ApiException(0, "unreadable", "That upload address doesn't work.")
        }
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 300_000
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setFixedLengthStreamingMode(Multipart.length(form.fields, file, name, form.type, boundary))
            connection.outputStream.use { Multipart.write(form.fields, file, name, form.type, boundary, it) }
            val status = connection.responseCode
            if (status !in 200..299) {
                // A storage refusal (the form expired, the size was wrong) is worth one
                // more try with a new form; its body is storage's, never shown.
                throw ApiException(if (status >= 500) status else 0, "upload_failed", "A file didn't upload. It will be tried again.")
            }
        } catch (e: IOException) {
            throw ApiException(0, "network", "Release Notes can't be reached right now.")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Names the spot the phone is at, with the businesses nearby. Nothing is
     * kept until a note takes the place. Each is a lookup Release Notes pays
     * for, 100 a day: ask once a draft, never as words change.
     */
    suspend fun here(spot: Spot): Here = send("POST", "v1/places/here", body = encode(spot))

    // Upcoming, all needing the `upcoming` scope. Writes go at once, each with
    // a RequestKey, so a Save tapped again after a lost answer is the same save.

    /** Every date ahead, soonest first, each with the server's own line. */
    suspend fun upcoming(): UpcomingList = UpcomingList.from(send<JsonObject>("GET", "v1/upcoming"))

    /**
     * Adds, changes and removes dates, all or nothing. A refusal's `field`
     * names the entry (`add[0]`); `revision_mismatch` means read the list
     * again. A dry run saves nothing and needs no key.
     */
    suspend fun saveUpcoming(batch: UpcomingBatch, idempotencyKey: String?): UpcomingSaved =
        send("POST", "v1/upcoming/batch", body = encode(batch), idempotencyKey = idempotencyKey)

    /** Removes one date, from the revision read. */
    suspend fun deleteUpcoming(id: String, revision: Int, idempotencyKey: String) {
        send<Unit>("DELETE", "v1/upcoming/$id", query = listOf("revision" to revision.toString()), idempotencyKey = idempotencyKey)
    }

    /** The reminders each morning brings, from today through `days` ahead (at most 31). */
    suspend fun reminders(days: Int = ReminderPlan.DAYS): Reminders =
        send("GET", "v1/upcoming/reminders", query = listOf("days" to days.toString()))

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

        const val FILE_MISSING = "A file for this note is no longer on this phone. Discard it, or fix it to send the words alone."

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
