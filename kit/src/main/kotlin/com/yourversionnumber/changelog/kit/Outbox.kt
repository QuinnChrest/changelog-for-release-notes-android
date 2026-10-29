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
            else -> null
        }

    val isNote: Boolean get() = operation is OutboxOperation.Note
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
 * are kept as one JSON file in the app's own storage, oldest first. `file`
 * null keeps everything in memory (tests, previews).
 */
class Outbox(private val file: File?) {
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

    /** Queues a new note, stamped with the moment it was typed. */
    suspend fun enqueueNote(text: String, date: String? = null, writtenAt: OffsetDateTime = OffsetDateTime.now()): OutboxItem =
        enqueue(OutboxOperation.Note(text, date, Timestamp.format(writtenAt)), writtenAt)

    /** Drops a write the owner gave up on. */
    suspend fun discard(id: String) = mutex.withLock {
        runCatching { commit(_items.value.filterNot { it.id == id }) }
        Unit
    }

    /**
     * A refused note, edited by the owner, goes back in line under a new key:
     * the old key's receipt belongs to the request that was refused.
     */
    suspend fun retry(id: String, text: String) = mutex.withLock {
        val list = _items.value
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return@withLock
        val op = list[i].operation as? OutboxOperation.Note ?: return@withLock
        val fixed = list[i].copy(id = UUID.randomUUID().toString(), operation = op.copy(text = text), attempts = 0, refusal = null)
        commit(list.toMutableList().also { it[i] = fixed })
    }

    /**
     * Sends what is waiting, oldest first. A refusal sets that item aside and
     * carries on; anything worth retrying stops the run so order is kept.
     * Items queued while a run is sending join that run. One run at a time.
     */
    suspend fun flush(send: suspend (OutboxItem) -> OutboxResult): FlushReport {
        if (!flushing.compareAndSet(false, true)) return FlushReport()
        try {
            val sent = mutableListOf<SentItem>()
            val refused = mutableListOf<OutboxItem>()
            val tried = mutableSetOf<String>()
            while (true) {
                val item = _items.value.firstOrNull { it.refusal == null && it.id !in tried } ?: break
                tried += item.id
                try {
                    val result = send(item)
                    sent += SentItem(item, result)
                    // Should the file not take this, it is resent after a relaunch under
                    // its own key, and the receipt answers it: still no second note.
                    update { list -> list.filterNot { it.id == item.id } }
                } catch (e: ApiException) {
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
        is OutboxOperation.Edit -> OutboxResult.Edited(editNote(op.date, op.id, op.text, op.revision, key))
        is OutboxOperation.Delete -> {
            deleteNote(op.date, op.id, key)
            OutboxResult.Deleted
        }
        is OutboxOperation.Pins -> OutboxResult.Pinned(setPinned(op.pinned, key))
    }
}

object Timestamp {
    /** RFC 3339 with the local offset: "2026-10-28T23:58:40-05:00". */
    fun format(time: OffsetDateTime): String =
        time.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun now(zone: ZoneId = ZoneId.systemDefault()): String = format(OffsetDateTime.now(zone))
}
