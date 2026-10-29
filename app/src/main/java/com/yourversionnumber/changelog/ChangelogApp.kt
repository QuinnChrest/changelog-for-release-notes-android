package com.yourversionnumber.changelog

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.yourversionnumber.changelog.kit.Api
import com.yourversionnumber.changelog.kit.FlushReport
import com.yourversionnumber.changelog.kit.Me
import com.yourversionnumber.changelog.kit.NoteRef
import com.yourversionnumber.changelog.kit.Outbox
import com.yourversionnumber.changelog.kit.OutboxResult
import com.yourversionnumber.changelog.kit.Tags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.concurrent.TimeUnit

class ChangelogApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

val Context.graph: AppGraph get() = (applicationContext as ChangelogApp).graph

/** A note this phone filed, kept so Log can show it and take it back. */
@Serializable
data class Sent(val note: NoteRef, val text: String)

/**
 * The app's one set of shared state, for the screens and for SendWorker,
 * which run in the same process.
 */
class AppGraph(private val context: Context) {
    private val prefs = context.getSharedPreferences("changelog", Context.MODE_PRIVATE)
    private val tokens = TokenStore(context)

    // noBackupFilesDir: the queue holds words and never goes to a backup.
    val outbox = Outbox(File(context.noBackupFilesDir, "outbox.json"))
    private val sentFile = File(context.noBackupFilesDir, "sent.json")

    private val _signedIn = MutableStateFlow(tokens.read() != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _sent = MutableStateFlow(loadSent())
    val sent: StateFlow<List<Sent>> = _sent.asStateFlow()

    /** Why the last send stopped or what it refused, in Release Notes' words. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    var server: String
        // Debug builds start on the dev server: development never touches real notes.
        get() = if (BuildConfig.DEBUG) prefs.getString("server", BuildConfig.DEV_SERVER)!! else Api.PRODUCTION
        set(value) = prefs.edit().putString("server", value).apply()

    /** A tag added to every note until the owner stops it. */
    var sticky: String?
        get() = prefs.getString("sticky", null)
        set(value) = prefs.edit().putString("sticky", value).apply()

    var me: Me?
        get() = prefs.getString("me", null)?.let { runCatching { Api.json.decodeFromString(Me.serializer(), it) }.getOrNull() }
        set(value) = prefs.edit().putString("me", value?.let { Api.json.encodeToString(Me.serializer(), it) }).apply()

    var tags: Tags?
        get() = prefs.getString("tags", null)?.let { runCatching { Api.json.decodeFromString(Tags.serializer(), it) }.getOrNull() }
        set(value) = prefs.edit().putString("tags", value?.let { Api.json.encodeToString(Tags.serializer(), it) }).apply()

    val userAgent: String = "Changelog/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"

    fun api(token: String, server: String = this.server) = Api(server, token, userAgent)

    fun api(): Api? = tokens.read()?.let { api(it) }

    fun signIn(token: String, server: String, me: Me) {
        this.server = server
        tokens.write(token)
        this.me = me
        _signedIn.value = true
        _problem.value = null
        send()
    }

    /** The token stopped working: stop sending, keep the queue, ask for a new one. */
    fun tokenLost(message: String) {
        tokens.clear()
        _signedIn.value = false
        _problem.value = message
    }

    /** Signing out keeps whatever is still waiting, for the next sign-in. */
    fun signOut() {
        tokens.clear()
        me = null
        tags = null
        sticky = null
        _sent.value = emptyList()
        sentFile.delete()
        _signedIn.value = false
    }

    fun report(problem: String?) {
        _problem.value = problem
    }

    /** What a send run did: notes filed join Log's list, refusals say why. */
    fun record(report: FlushReport) {
        val filed = report.sent.mapNotNull { sent ->
            (sent.result as? OutboxResult.Filed)?.let { Sent(it.receipt.note, sent.item.text.orEmpty()) }
        }
        val deleted = report.sent.mapNotNull { (it.item.operation as? com.yourversionnumber.changelog.kit.OutboxOperation.Delete)?.id }.toSet()
        if (filed.isNotEmpty() || deleted.isNotEmpty()) {
            saveSent((filed.reversed() + _sent.value).filterNot { it.note.id in deleted }.take(50))
        }
        report.sent.firstNotNullOfOrNull { (it.result as? OutboxResult.Pinned)?.tags }?.let { tags = it }
        _problem.value = report.refused.lastOrNull()?.refusal ?: report.stopped?.takeUnless { it.code == "network" }?.message
    }

    /** Takes a note off Log at once; its delete waits in the queue. */
    fun forget(id: String) = saveSent(_sent.value.filterNot { it.note.id == id })

    /** Sends the queue now if there is a connection, or as soon as there is one. */
    fun send(delaySeconds: Long = 0) {
        val request = OneTimeWorkRequestBuilder<SendWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .apply {
                if (delaySeconds > 0) setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                else setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SendWorker.NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private fun loadSent(): List<Sent> = runCatching {
        Api.json.decodeFromString(sentSerializer, sentFile.readText())
    }.getOrDefault(emptyList())

    private fun saveSent(list: List<Sent>) {
        _sent.value = list
        runCatching { sentFile.writeText(Api.json.encodeToString(sentSerializer, list)) }
    }

    private companion object {
        val sentSerializer = ListSerializer(Sent.serializer())
    }
}
