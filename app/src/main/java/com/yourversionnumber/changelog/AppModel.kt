package com.yourversionnumber.changelog

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
    val canCommit: Boolean get() = draft.isNotBlank()

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

    /** Save first, then send: the note is on the phone before anything goes. */
    fun commit() {
        val words = sticky?.let { Hashtags.adding(it, draft) } ?: draft
        if (words.isBlank()) return
        viewModelScope.launch {
            try {
                graph.outbox.enqueueNote(words)
                draft = ""
                graph.send()
            } catch (e: OutboxNotKept) {
                graph.report(e.message)
            }
        }
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
