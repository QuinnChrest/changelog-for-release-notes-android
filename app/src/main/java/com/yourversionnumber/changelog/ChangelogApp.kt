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
import com.yourversionnumber.changelog.kit.ActivityNoticeKind
import com.yourversionnumber.changelog.kit.Api
import com.yourversionnumber.changelog.kit.DailyPrompt
import com.yourversionnumber.changelog.kit.FlushReport
import com.yourversionnumber.changelog.kit.Me
import com.yourversionnumber.changelog.kit.NoteRef
import com.yourversionnumber.changelog.kit.Outbox
import com.yourversionnumber.changelog.kit.OutboxOperation
import com.yourversionnumber.changelog.kit.OutboxResult
import com.yourversionnumber.changelog.kit.Tags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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

    init {
        // A new process has no draft: files only a draft held when it last
        // stopped, and no queued note holds, go. Before anything can add one.
        runBlocking { outbox.sweep() }
        // Development builds before 0.0.0's alarms scheduled reminders as jobs of a worker
        // that's gone now; WorkManager would keep trying them.
        WorkManager.getInstance(context).cancelAllWorkByTag("upcoming-reminder")
        // Scratch copies from the picker, the camera and the recorder are only ever mid-way.
        File(context.cacheDir, "picked").deleteRecursively()
    }

    private val _signedIn = MutableStateFlow(tokens.read() != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _sent = MutableStateFlow(loadSent())
    val sent: StateFlow<List<Sent>> = _sent.asStateFlow()

    /** Counts send runs that delivered something, so screens know to read the server again. */
    private val _sends = MutableStateFlow(0)
    val sends: StateFlow<Int> = _sends.asStateFlow()

    /** The newest activity row seen when Activity was last left. Kept on the phone, never sent. */
    var seenActivity: String?
        get() = prefs.getString("activity.seen", null)
        set(value) = prefs.edit().putString("activity.seen", value).apply()

    /** A word after a note is filed: its version, or the badges it unlocked. */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun clearToast() {
        _toast.value = null
    }

    /** Why the last send stopped or what it refused, in Release Notes' words. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    var server: String
        // Debug builds start on the dev server: development never touches real notes.
        get() = if (BuildConfig.DEBUG) prefs.getString("server", BuildConfig.DEV_SERVER)!! else Api.PRODUCTION
        set(value) = prefs.edit().putString("server", value).apply()

    /** Upcoming reminders as this phone's own notifications. Off until the owner turns them on. */
    var remindersOn: Boolean
        get() = prefs.getBoolean("reminders", false)
        set(value) = prefs.edit().putBoolean("reminders", value).apply()

    /** The daily prompt, "You're 4.5.137 today. What changed?" Off until the owner turns it on. */
    var promptOn: Boolean
        get() = prefs.getBoolean("prompt", false)
        set(value) = prefs.edit().putBoolean("prompt", value).apply()

    /** The kinds of activity to be told about. None until the owner chooses. */
    var noticeKinds: Set<ActivityNoticeKind>
        get() = prefs.getStringSet("notices", emptySet())!!.mapNotNull { runCatching { ActivityNoticeKind.valueOf(it) }.getOrNull() }.toSet()
        set(value) = prefs.edit().putStringSet("notices", value.map { it.name }.toSet()).apply()

    /** The newest activity row already told of. Kept on the phone, never sent. */
    var activityCursor: String?
        get() = prefs.getString("activity.cursor", null)
        set(value) = prefs.edit().putString("activity.cursor", value).apply()

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
        seenActivity = null
        remindersOn = false
        promptOn = false
        noticeKinds = emptySet()
        activityCursor = null
        PhoneNotices.clear(context)
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
        val deleted = report.sent.mapNotNull { (it.item.operation as? OutboxOperation.Delete)?.id }.toSet()
        // An edit's answer is the note's new revision; its words are the ones sent.
        val edited = report.sent.mapNotNull { sent ->
            val note = (sent.result as? OutboxResult.Edited)?.note ?: return@mapNotNull null
            note.id to Sent(note, (sent.item.operation as OutboxOperation.Edit).text)
        }.toMap()
        report.sent.lastOrNull { it.result is OutboxResult.Filed }?.let { last ->
            val receipt = (last.result as OutboxResult.Filed).receipt
            val badges = report.sent.flatMap { (it.result as? OutboxResult.Filed)?.receipt?.unlocked.orEmpty() }
            _toast.value = if (badges.isEmpty()) "Filed in ${receipt.note.version}." else "Unlocked: ${badges.joinToString { it.name }}"
        }
        // Written today: today's prompt has nothing left to ask.
        me?.let { me ->
            if (filed.any { it.note.date == me.today }) {
                this.me = me.copy(wroteToday = true)
                PhoneNotices.takeBack(context, DailyPrompt.id(me.today))
            }
        }
        if (filed.isNotEmpty() || deleted.isNotEmpty() || edited.isNotEmpty()) {
            saveSent((filed.reversed() + _sent.value).filterNot { it.note.id in deleted }.map { edited[it.note.id] ?: it }.take(50))
        }
        if (report.sent.isNotEmpty()) _sends.value += 1
        report.sent.firstNotNullOfOrNull { (it.result as? OutboxResult.Pinned)?.tags }?.let { tags = it }
        // A run with nothing to say leaves whatever the owner is being told (a file that
        // couldn't be read, say) in place.
        (report.refused.lastOrNull()?.refusal ?: report.stopped?.takeUnless { it.code == "network" }?.message)
            ?.let { _problem.value = it }
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
