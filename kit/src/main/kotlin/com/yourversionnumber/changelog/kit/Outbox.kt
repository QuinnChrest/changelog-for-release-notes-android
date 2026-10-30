package com.yourversionnumber.changelog.kit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** What a queued write does. Every write goes through here, so a lost answer is resent under its key. */
@Serializable
sealed interface OutboxOperation {
    /** A new note, with the moment its words were typed. */
    @Serializable
    @SerialName("note")
    data class Note(val text: String, val date: String? = null, val writtenAt: String) : OutboxOperation

    /** New words for a note this token wrote, from the revision it read. */
    @Serializable
    @SerialName("edit")
    data class Edit(val date: String, val id: String, val text: String, val revision: String) : OutboxOperation

    @Serializable
    @SerialName("delete")
    data class Delete(val date: String, val id: String) : OutboxOperation

    @Serializable
    @SerialName("pins")
    data class Pins(val pinned: List<String>) : OutboxOperation

    /**
     * A new note with files. The files' bytes wait in the Outbox's folder;
     * each `upload` is filled in, and saved, before the note is sent, so
     * every resend is the same request.
     */
    @Serializable
    @SerialName("post")
    data class Post(
        val text: String,
        val date: String? = null,
        val writtenAt: String,
        val files: List<QueuedFile>,
        /** Times the uploads were made again after the note found them gone. */
        val reuploads: Int = 0,
        /** Where the phone was, named by Here when the note is sent if it wasn't named already. */
        val spot: Spot? = null,
        /** The place, as Here named it (with a business, if one was picked). */
        val place: DevicePlace? = null,
    ) : OutboxOperation {
        /** Every file posted and the place named: ready to send. */
        val ready: Boolean get() = files.all { it.upload != null } && (spot == null || place != null)

        val request: NoteRequest
            get() = NoteRequest(
                text = text.ifBlank { null },
                date = date,
                writtenAt = writtenAt,
                uploads = files.map { UploadRef(it.upload.orEmpty(), it.type, it.name) }.ifEmpty { null },
                place = place,
            )
    }
}

/** A write waiting to be sent. Its `id` is the request's idempotency key. */
@Serializable
data class OutboxItem(
    val id: String,
    val operation: OutboxOperation,
    val queuedAt: String,
    val attempts: Int = 0,
    /** Set when Release Notes refused it for good. It stays for the owner to fix or discard. */
    val refusal: String? = null,
) {
    val idempotencyKey: String get() = id

    val text: String?
        get() = when (operation) {
            is OutboxOperation.Note -> operation.text
            is OutboxOperation.Edit -> operation.text
            is OutboxOperation.Post -> operation.text
            else -> null
        }

    val isNote: Boolean get() = operation is OutboxOperation.Note || operation is OutboxOperation.Post

    val files: List<QueuedFile> get() = (operation as? OutboxOperation.Post)?.files.orEmpty()
}

/** What Release Notes answered for each kind of write. */
sealed interface OutboxResult {
    data class Filed(val receipt: NoteReceipt) : OutboxResult
    data class Edited(val note: NoteRef) : OutboxResult
    data object Deleted : OutboxResult
    data class Pinned(val tags: Tags) : OutboxResult
}

data class SentItem(val item: OutboxItem, val result: OutboxResult)

data class FlushReport(
    val sent: List<SentItem> = emptyList(),
    val refused: List<OutboxItem> = emptyList(),
    /** Why sending stopped early: offline, rate limited, or signed out. The rest wait, in order. */
    val stopped: ApiException? = null,
)

/** The queue could not be written to storage. The write was not taken, so the caller keeps the words. */
class OutboxNotKept : Exception("This phone couldn't save the note. Free up some space and try again.")

/**
 * Every write goes through here, online or not ("never lose a note"). Items
 * are kept as one JSON file in the app's own storage, oldest first, and a
 * note's files in `outbox-files` beside it. `file` null keeps everything in
 * memory (tests, previews), with files in `files` if given.
 */
class Outbox(private val file: File?, files: File? = null) {
    /** Where a queued note's files wait. */
    val filesFolder: File? = files ?: file?.parentFile?.let { File(it, "outbox-files") }

    private val mutex = Mutex()
    private val flushing = AtomicBoolean(false)
    private val _items = MutableStateFlow(load())

    val items: StateFlow<List<OutboxItem>> = _items.asStateFlow()

    /** Queues a write, answering only once it is on disk. */
    suspend fun enqueue(operation: OutboxOperation, now: OffsetDateTime = OffsetDateTime.now()): OutboxItem = mutex.withLock {
        val item = OutboxItem(UUID.randomUUID().toString(), operation, Timestamp.format(now))
        commit(_items.value + item)
        item
    }

    /** Queues a new note, stamped with the moment it was typed; with files or a place, a Post. */
    suspend fun enqueueNote(
        text: String,
        date: String? = null,
        writtenAt: OffsetDateTime = OffsetDateTime.now(),
        files: List<QueuedFile> = emptyList(),
        spot: Spot? = null,
        place: DevicePlace? = null,
    ): OutboxItem {
        val stamp = Timestamp.format(writtenAt)
        val op = if (files.isEmpty() && spot == null && place == null) {
            OutboxOperation.Note(text, date, stamp)
        } else {
            OutboxOperation.Post(text, date, stamp, files, spot = spot ?: place?.let { Spot(it.lat, it.lon, it.accuracyM) }, place = place)
        }
        return enqueue(op, writtenAt)
    }

    /** Where a queued file's bytes are. */
    fun fileFor(queued: QueuedFile): File? = filesFolder?.let { File(it, queued.fileName) }

    /** Keeps a file's bytes in the files folder, ready for a note. */
    fun keep(source: InputStream, queued: QueuedFile) {
        val target = fileFor(queued) ?: return
        try {
            target.parentFile?.mkdirs()
            source.use { input -> target.outputStream().use { input.copyTo(it) } }
        } catch (e: Exception) {
            target.delete()
            throw OutboxNotKept()
        }
    }

    /** Removes files' bytes: a draft's the owner took off, or a sent note's. */
    fun removeFiles(files: List<QueuedFile>) {
        for (f in files) fileFor(f)?.delete()
    }

    /** Removes files no queued note holds, except `keeping` (the draft's). */
    suspend fun sweep(keeping: Set<String> = emptySet()) = mutex.withLock {
        val held = _items.value.flatMap { it.files }.map { it.id }.toSet() + keeping
        filesFolder?.listFiles()?.forEach { if (it.name.substringBefore('.') !in held) it.delete() }
        Unit
    }

    /** Drops a write the owner gave up on, with its files. */
    suspend fun discard(id: String) = mutex.withLock {
        val gone = _items.value.firstOrNull { it.id == id }?.files.orEmpty()
        runCatching { commit(_items.value.filterNot { it.id == id }) }
        removeFiles(gone)
    }

    /**
     * A refused note, edited by the owner, goes back in line under a new key:
     * the old key's receipt belongs to the request that was refused.
     */
    suspend fun retry(id: String, text: String) = mutex.withLock {
        val list = _items.value
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return@withLock
        val op = when (val old = list[i].operation) {
            is OutboxOperation.Note -> old.copy(text = text)
            // A file never posted whose bytes are gone goes; the rest still do.
            is OutboxOperation.Post -> old.copy(
                text = text,
                files = old.files.filterNot { it.upload == null && fileFor(it)?.exists() == false },
                reuploads = 0,
            )
            else -> return@withLock
        }
        val fixed = list[i].copy(id = UUID.randomUUID().toString(), operation = op, attempts = 0, refusal = null)
        commit(list.toMutableList().also { it[i] = fixed })
    }

    /**
     * Sends what is waiting, oldest first. A note with files is first made
     * ready by `prepare` (its files posted) and saved, so a resend carries
     * the same uploads under the same key. A refusal sets that item aside
     * and carries on; anything worth retrying stops the run so order is
     * kept. Items queued while a run is sending join that run. One run at a time.
     */
    suspend fun flush(
        prepare: (suspend (OutboxOperation.Post) -> OutboxOperation.Post)? = null,
        send: suspend (OutboxItem) -> OutboxResult,
    ): FlushReport {
        if (!flushing.compareAndSet(false, true)) return FlushReport()
        try {
            val sent = mutableListOf<SentItem>()
            val refused = mutableListOf<OutboxItem>()
            val tried = mutableSetOf<String>()
            while (true) {
                var item = _items.value.firstOrNull { it.refusal == null && it.id !in tried } ?: break
                tried += item.id
                try {
                    val post = item.operation as? OutboxOperation.Post
                    if (prepare != null && post != null && !post.ready) {
                        val ready = prepare(post)
                        // Saved before sending: from here on, every resend is this request.
                        // Unsaved, it doesn't go, or a relaunch would post other uploads
                        // under the same key.
                        var discarded = false
                        val kept = mutex.withLock {
                            val list = _items.value
                            val i = list.indexOfFirst { it.id == item.id }
                            if (i < 0) {
                                discarded = true
                                return@withLock true
                            }
                            val next = list.toMutableList().also { it[i] = it[i].copy(operation = ready) }
                            runCatching { commit(next) }.isSuccess
                        }
                        if (discarded) continue
                        if (!kept) return FlushReport(sent, refused, ApiException(507, "not_kept", OutboxNotKept().message!!))
                        item = item.copy(operation = ready)
                    }
                    val result = send(item)
                    sent += SentItem(item, result)
                    // Should the file not take this, it is resent after a relaunch under
                    // its own key, and the receipt answers it: still no second note.
                    update { list -> list.filterNot { it.id == item.id } }
                    removeFiles(item.files)
                } catch (e: ApiException) {
                    // Uploads a note found gone (unused for a day, say) are made again
                    // once, under a new key: the old key's request was refused.
                    val post = item.operation as? OutboxOperation.Post
                    if (e.field == "uploads" && post != null && post.reuploads == 0) {
                        val again = post.copy(files = post.files.map { it.copy(upload = null) }, reuploads = 1)
                        val fresh = UUID.randomUUID().toString()
                        update { list -> list.map { if (it.id == item.id) it.copy(id = fresh, operation = again) else it } }
                        continue
                    }
                    if (e.retryable || e.signedOut) {
                        update { list -> list.map { if (it.id == item.id) it.copy(attempts = it.attempts + 1) else it } }
                        return FlushReport(sent, refused, e)
                    }
                    update { list ->
                        list.map {
                            if (it.id == item.id) it.copy(attempts = it.attempts + 1, refusal = e.message).also(refused::add) else it
                        }
                    }
                }
            }
            return FlushReport(sent, refused)
        } finally {
            flushing.set(false)
        }
    }

    private suspend fun update(change: (List<OutboxItem>) -> List<OutboxItem>) = mutex.withLock {
        val next = change(_items.value)
        _items.value = next
        runCatching { save(next) }
    }

    private fun commit(next: List<OutboxItem>) {
        save(next)
        _items.value = next
    }

    private fun load(): List<OutboxItem> {
        val f = file ?: return emptyList()
        if (!f.exists()) return emptyList()
        return runCatching { store.decodeFromString(serializer, f.readText()) }.getOrDefault(emptyList())
    }

    private fun save(list: List<OutboxItem>) {
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(store.encodeToString(serializer, list))
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            // Never logged: the queue holds words.
            throw OutboxNotKept()
        }
    }

    private companion object {
        val serializer = ListSerializer(OutboxItem.serializer())
        val store = Json { ignoreUnknownKeys = true }
    }
}

/** Sends one queued write under its own idempotency key. */
suspend fun Api.perform(item: OutboxItem): OutboxResult {
    val key = item.idempotencyKey
    return when (val op = item.operation) {
        is OutboxOperation.Note -> OutboxResult.Filed(createNote(NoteRequest(op.text, op.date, op.writtenAt), key))
        is OutboxOperation.Post -> OutboxResult.Filed(createNote(op.request, key))
        is OutboxOperation.Edit -> OutboxResult.Edited(editNote(op.date, op.id, op.text, op.revision, key))
        is OutboxOperation.Delete -> {
            deleteNote(op.date, op.id, key)
            OutboxResult.Deleted
        }
        is OutboxOperation.Pins -> OutboxResult.Pinned(setPinned(op.pinned, key))
    }
}

/**
 * Posts a queued note's files, each asked for its form only as it goes, and
 * names its place, so it is ready to send. A place that can't be named (no
 * city for the spot, or the day's 100 spots used) is left off: a note is
 * never held back for its place.
 */
suspend fun Api.prepare(post: OutboxOperation.Post, outbox: Outbox): OutboxOperation.Post {
    val files = post.files.map { queued ->
        if (queued.upload != null) return@map queued
        val bytes = outbox.fileFor(queued)?.takeIf { it.exists() }
            ?: throw ApiException(400, "file_missing", Api.FILE_MISSING)
        val form = startUpload(UploadStart(queued.name, queued.type, bytes.length()))
        postFile(bytes, queued.name, form)
        queued.copy(upload = form.upload, type = form.type)
    }
    val spot = post.spot
    if (spot != null && post.place == null) {
        val place = try {
            here(spot).place
        } catch (e: ApiException) {
            if (e.signedOut || (e.retryable && e.status != 429)) throw e
            null
        }
        return post.copy(files = files, spot = place?.let { spot }, place = place)
    }
    return post.copy(files = files)
}

object Timestamp {
    /** RFC 3339 with the local offset: "2026-10-28T23:58:40-05:00". */
    fun format(time: OffsetDateTime): String =
        time.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun now(zone: ZoneId = ZoneId.systemDefault()): String = format(OffsetDateTime.now(zone))
}
