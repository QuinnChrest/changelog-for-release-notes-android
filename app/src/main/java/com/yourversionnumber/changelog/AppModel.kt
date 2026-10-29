package com.yourversionnumber.changelog

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.yourversionnumber.changelog.kit.MediaType
import com.yourversionnumber.changelog.kit.QueuedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourversionnumber.changelog.kit.ActivityItem
import com.yourversionnumber.changelog.kit.ActivityPage
import com.yourversionnumber.changelog.kit.ApiException
import com.yourversionnumber.changelog.kit.Hashtags
import com.yourversionnumber.changelog.kit.NoteDetail
import com.yourversionnumber.changelog.kit.OutboxNotKept
import com.yourversionnumber.changelog.kit.OutboxOperation
import com.yourversionnumber.changelog.kit.TagRow
import com.yourversionnumber.changelog.kit.TokenFormat
import com.yourversionnumber.changelog.kit.isMine
import com.yourversionnumber.changelog.kit.isOn
import kotlinx.coroutines.launch

/** A note this token wrote, from Log's Today or its own sent list. */
data class NoteTarget(val date: String, val id: String, val version: String?)

val Sent.target get() = NoteTarget(note.date, note.id, note.version)

val ActivityItem.target get() = NoteTarget(day!!, note!!, version)

/** The screens' state, over AppGraph. */
class AppModel(application: Application) : AndroidViewModel(application) {
    val graph = application.graph

    val signedIn = graph.signedIn
    val waiting = graph.outbox.items
    val sent = graph.sent
    val problem = graph.problem

    var me by mutableStateOf(graph.me)
        private set
    var tags by mutableStateOf(graph.tags)
        private set
    var sticky by mutableStateOf(graph.sticky)
        private set
    var draft by mutableStateOf("")
    var server by mutableStateOf(graph.server)

    /** Today's activity from every channel, newest first. */
    var today by mutableStateOf<List<ActivityItem>>(emptyList())
        private set

    val hasActivity: Boolean get() = me?.token?.scopes?.contains("activity") == true

    val tagRow: List<String> get() = TagRow.tags(tags)
    val canCommit: Boolean get() = draft.isNotBlank() || draftFiles.isNotEmpty()
    /** Checks a pasted token against the server, then keeps it. Answers a problem, or null. */
    suspend fun signIn(pasted: String): String? {
        val token = TokenFormat.extract(pasted) ?: return "That doesn't look like a Release Notes token. It starts with rn_."
        return try {
            val me = graph.api(token, server).me()
            graph.signIn(token, server, me)
            this.me = me
            refresh()
            null
        } catch (e: ApiException) {
            e.message
        }
    }

    /** Reads today's version and the tag row again. Versions only ever come from here. */
    fun refresh() {
        val api = graph.api() ?: return
        viewModelScope.launch {
            try {
                val me = api.me()
                graph.me = me
                this@AppModel.me = me
                val tags = api.tags()
                graph.tags = tags
                this@AppModel.tags = tags
            } catch (e: ApiException) {
                if (e.signedOut) graph.tokenLost(e.message)
            }
            loadToday()
            graph.send()
        }
    }

    fun toggle(tag: String) {
        draft = Hashtags.toggling(tag, draft)
    }

    /** Touch and hold: this tag goes on every note until stopped in Settings. */
    fun stick(tag: String?) {
        graph.sticky = tag
        sticky = tag
    }

    /** Save first, then send: the note, and its files, are on the phone before anything goes. */
    fun commit() {
        val files = draftFiles.toList()
        val typed = draft.trim()
        if (typed.isEmpty() && files.isEmpty()) return
        val words = sticky?.let { Hashtags.adding(it, typed) } ?: typed
        viewModelScope.launch {
            try {
                graph.outbox.enqueueNote(words, files = files)
                draft = ""
                draftFiles.clear()
                files.forEach { thumbnails.remove(it.id) }
                graph.send()
            } catch (e: OutboxNotKept) {
                graph.report(e.message)
            }
        }
    }

    // Files on the draft. They wait in the Outbox's folder from the moment
    // they're added, and go with the note when it's committed.

    val draftFiles = mutableStateListOf<QueuedFile>()
    val thumbnails = mutableStateMapOf<String, Bitmap>()
    private val media = MediaReader(application)

    val canAddFiles: Boolean get() = me?.token?.scopes?.contains("media:write") == true
    val roomForFiles: Int get() = (MediaType.MAX_FILES - draftFiles.size).coerceAtLeast(0)

    fun addPhotos(uris: List<Uri>) = viewModelScope.launch {
        for (uri in uris) add { media.photo(uri) }
    }

    /** The camera's photo, written into our own scratch file, which goes once it's read. */
    fun addCameraPhoto(file: File) = viewModelScope.launch {
        try {
            add { media.photo(Uri.fromFile(file)) }
        } finally {
            file.delete()
        }
    }

    fun addRecording(file: File) = viewModelScope.launch { add { Picked(file, "audio/mp4", null) } }

    /** Files shared from another app, each by what its bytes are. */
    fun addShared(uris: List<Uri>, declared: String?) = viewModelScope.launch {
        for (uri in uris) add { media.shared(uri, getApplication<Application>().contentResolver.getType(uri) ?: declared) }
    }

    /** A file for the camera or the recorder to write. */
    fun scratch(extension: String): File = media.newScratch(extension)

    private suspend fun add(read: () -> Picked) {
        if (me != null && !canAddFiles) return graph.report("This token can't add files. Make a token with “Add files” ticked.")
        if (roomForFiles == 0) return graph.report("A note holds up to ${MediaType.MAX_FILES} files.")
        val picked = try {
            withContext(Dispatchers.IO) { read() }
        } catch (e: NotKept) {
            return graph.report(e.message)
        }
        try {
            val name = picked.name ?: when {
                picked.type.startsWith("image/") -> "Photo ${draftFiles.count { it.isPhoto } + 1}.${MediaType.fileExtension(picked.type)}"
                picked.type.startsWith("audio/") -> draftFiles.count { it.isRecording }.let { if (it == 0) "Voice memo.m4a" else "Voice memo ${it + 1}.m4a" }
                else -> "File.${MediaType.fileExtension(picked.type)}"
            }
            val queued = QueuedFile(type = picked.type, name = name, size = picked.file.length())
            withContext(Dispatchers.IO) {
                graph.outbox.keep(picked.file.inputStream(), queued)
                if (queued.isPhoto) graph.outbox.fileFor(queued)?.let(media::thumbnail)?.let { thumbnails[queued.id] = it }
            }
            draftFiles += queued
        } catch (e: OutboxNotKept) {
            graph.report(e.message)
        } catch (e: java.io.IOException) {
            graph.report("That file couldn't be read.")
        } finally {
            picked.file.delete()
        }
    }

    fun remove(file: QueuedFile) {
        draftFiles.remove(file)
        thumbnails.remove(file.id)
        graph.outbox.removeFiles(listOf(file))
    }

    fun discard(id: String) = viewModelScope.launch { graph.outbox.discard(id) }

    fun fix(id: String, text: String) = viewModelScope.launch {
        graph.outbox.retry(id, text)
        graph.send()
    }

    // Notes this token wrote: the only ones it may read back, change or delete.

    /** Reads a note as it is now, so an edit made on the web isn't lost. */
    suspend fun load(target: NoteTarget): NoteDetail? = try {
        graph.api()?.note(target.date, target.id)
    } catch (e: ApiException) {
        handle(e)
        null
    }

    /** Queues an edit from the revision read. A refusal (changed since) shows under Waiting to send. */
    fun save(note: NoteDetail, text: String) = queue(OutboxOperation.Edit(note.date, note.id, text, note.revision))

    fun delete(target: NoteTarget) {
        queue(OutboxOperation.Delete(target.date, target.id))
        graph.forget(target.id)
        today = today.filterNot { it.note == target.id }
    }

    private fun queue(operation: OutboxOperation) = viewModelScope.launch {
        try {
            graph.outbox.enqueue(operation)
            graph.send()
        } catch (e: OutboxNotKept) {
            graph.report(e.message)
        }
    }

    // Activity

    /** Today from every channel, for Log. Needs the `activity` scope. */
    fun loadToday() {
        val api = graph.api() ?: return
        val day = me?.today ?: return
        if (!hasActivity) return
        viewModelScope.launch {
            try {
                today = api.activity(limit = 100).items.filter { it.isOn(day) }
            } catch (e: ApiException) {
                handle(e)
            }
        }
    }

    suspend fun activity(before: String?, kinds: List<String>): ActivityPage? = try {
        graph.api()?.activity(before = before, kinds = kinds)
    } catch (e: ApiException) {
        handle(e)
        null
    }

    fun isMine(item: ActivityItem) = item.isMine(me?.token?.name)

    /** Offline is quiet: notes wait in the queue. Anything else, Release Notes says what to do. */
    private fun handle(e: ApiException) = when {
        e.signedOut -> graph.tokenLost(e.message)
        e.status == 0 -> {}
        else -> graph.report(e.message)
    }

    /** A share from another app becomes the start of a note. */
    fun receive(shared: String) {
        draft = if (draft.isBlank()) shared else draft.trimEnd() + "\n" + shared
    }

    /** Signs out; with `revoke`, the token ends on the server too. Waiting notes stay. */
    fun signOut(revoke: Boolean) = viewModelScope.launch {
        if (revoke) runCatching { graph.api()?.revokeToken() }
        graph.signOut()
        me = null
        tags = null
        sticky = null
        today = emptyList()
    }

    fun clearProblem() = graph.report(null)
}
