package com.yourversionnumber.changelog

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourversionnumber.changelog.kit.ApiException
import com.yourversionnumber.changelog.kit.Hashtags
import com.yourversionnumber.changelog.kit.OutboxNotKept
import com.yourversionnumber.changelog.kit.OutboxOperation
import com.yourversionnumber.changelog.kit.TagRow
import com.yourversionnumber.changelog.kit.TokenFormat
import kotlinx.coroutines.launch

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

    fun delete(sent: Sent) = viewModelScope.launch {
        try {
            graph.outbox.enqueue(OutboxOperation.Delete(sent.note.date, sent.note.id))
            graph.forget(sent.note.id)
            graph.send()
        } catch (e: OutboxNotKept) {
            graph.report(e.message)
        }
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
    }

    fun clearProblem() = graph.report(null)
}
