package com.yourversionnumber.changelog

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
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
import com.yourversionnumber.changelog.kit.OutboxRecipient
import com.yourversionnumber.changelog.kit.OutboxResult
import com.yourversionnumber.changelog.kit.Tags
import com.yourversionnumber.changelog.kit.Words
import com.yourversionnumber.changelog.ui.ThemeMode
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

class ChangelogApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        words = this
        Words.book = AppPhrases
    }

    companion object {
        /** The app itself, for its words in strings.xml. Set before any activity, service or receiver. */
        lateinit var words: Context
    }
}

/** The app's words from strings.xml, for screens and for code with no Context at hand. */
fun say(@StringRes id: Int, vararg args: Any): String = ChangelogApp.words.getString(id, *args)

/** The app's words that change with a count, from strings.xml's plurals. */
fun sayCount(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    ChangelogApp.words.resources.getQuantityString(id, count, *args)

val Context.graph: AppGraph get() = (applicationContext as ChangelogApp).graph

/**
 * One of the next dates in Upcoming, kept for the Coming up widget: its next day, its status and its title. A
 * title is the owner's words, kept on the phone as a reminder's line is, never logged; signing out drops them.
 */
@Serializable
data class Soon(val date: String, val status: String, val title: String, val line: String = "") {
    /** The server's own line ("Robin turns 42."), as Upcoming shows it; the title for one kept before lines were. */
    val words: String get() = line.ifBlank { title }
}

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

    // noBackupFilesDir: the queue holds words and never goes to a backup. Each write is stamped with the
    // account signed in, and goes only to it.
    val outbox = Outbox(File(context.noBackupFilesDir, "outbox.json")) { account }
    private val sentFile = File(context.noBackupFilesDir, "sent.json")

    /**
     * The account the token signed in opens (`/v1/me`'s `account`), set as it signs in. It stays through a sign-out
     * or a lost token, so the next sign-in knows whether it's another account. Kept on this phone only, never logged.
     */
    var account: String?
        get() = prefs.getString("account", null)
        private set(value) = prefs.edit().putString("account", value).apply()

    /**
     * Whose waiting writes go now: this account's, or everyone's from a server before API 1.4.0, which names no
     * account. A write from another account waits on Log for the owner to send it here or discard it.
     */
    val recipient: OutboxRecipient get() = account?.let { OutboxRecipient.Account(it) } ?: OutboxRecipient.Anyone

    init {
        // A new process has no draft: files only a draft held when it last
        // stopped, and no queued note holds, go. Before anything can add one.
        runBlocking { outbox.sweep() }
        // Development builds before 0.0.0's alarms scheduled reminders as jobs of a worker
        // that's gone now; WorkManager would keep trying them.
        WorkManager.getInstance(context).cancelAllWorkByTag("upcoming-reminder")
        // Scratch copies from the picker, the camera and the recorder are only ever mid-way.
        File(context.cacheDir, "picked").deleteRecursively()
        stampEarlierQueue()
    }

    /**
     * Once, after an update from a build that didn't stamp accounts: the writes it queued carry none. With its token
     * still signed in they were written under it, so they take its account, from the `/v1/me` it last read. With no
     * token signed in nothing says whose they are, so they stay unstamped and wait on Log for the owner to send each
     * to an account or discard it. As the iPhone app does.
     */
    private fun stampEarlierQueue() {
        if (prefs.getBoolean("outbox.accounts", false)) return
        val known = if (tokens.read() != null) me?.account else null
        if (known != null) {
            // Unsaved, they stay held, and the next start tries again.
            if (runBlocking { runCatching { outbox.stamp(known) } }.isFailure) return
            if (account == null) account = known
        }
        prefs.edit().putBoolean("outbox.accounts", true).apply()
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
        // Debug builds start on the dev server: development never touches real notes. A debug build
        // made with -Pchangelog.production, and every release build, talk only to Release Notes.
        get() = if (BuildConfig.DEBUG && !BuildConfig.START_ON_PRODUCTION) {
            prefs.getString("server", BuildConfig.DEV_SERVER)!!
        } else {
            Api.PRODUCTION
        }
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

    /** "Add where I am": every note from this phone takes its place without asking. */
    var hereByDefault: Boolean
        get() = prefs.getBoolean("here", false)
        set(value) = prefs.edit().putBoolean("here", value).apply()

    /** The next few dates in Upcoming, for the Coming up widget; set whenever the app reads Upcoming. */
    var soon: List<Soon>
        get() = runCatching { Api.json.decodeFromString(soonSerializer, prefs.getString("soon", null) ?: "[]") }.getOrDefault(emptyList())
        set(value) {
            prefs.edit().putString("soon", Api.json.encodeToString(soonSerializer, value)).apply()
            VersionWidget.refresh(context)
        }

    /** Keeps the next dates from a read of Upcoming, for the widget. */
    fun keepSoon(list: com.yourversionnumber.changelog.kit.UpcomingList) {
        soon = list.entries.filter { it.status != "past" }.take(4).map { Soon(it.next, it.status, it.title, it.line) }
    }

    /**
     * Not at home from a build before API 1.20.0, set from where the phone was: Record my stops sends no stop near
     * it until it has moved to the server (HomeMove), when it is set to null. Nothing sets a new one. Kept on this
     * phone only (no backups), never logged; signing out forgets it.
     */
    var home: com.yourversionnumber.changelog.kit.Spot?
        get() = runCatching { Api.json.decodeFromString(com.yourversionnumber.changelog.kit.Spot.serializer(), prefs.getString("home", null)!!) }.getOrNull()
        set(value) = prefs.edit().putString("home", value?.let { Api.json.encodeToString(com.yourversionnumber.changelog.kit.Spot.serializer(), it) }).apply()

    /** Recording stops in the background (check-ins), off until the owner turns it on in Settings. */
    var recordingStops: Boolean
        get() = prefs.getBoolean("stops", false)
        set(value) = prefs.edit().putBoolean("stops", value).apply()

    /** Says something in Log's ink capsule, as "Filed in 4.5.137." is. */
    fun tell(text: String) {
        _toast.value = text
    }

    /** Light, dark or as the phone is: a choice about this phone, so signing out keeps it. */
    var themeMode: String
        get() = prefs.getString("theme", null) ?: ThemeMode.System.name
        set(value) = prefs.edit().putString("theme", value).apply()

    /** A tag added to every note until the owner stops it. */
    var sticky: String?
        get() = prefs.getString("sticky", null)
        set(value) = prefs.edit().putString("sticky", value).apply()

    var me: Me?
        get() = prefs.getString("me", null)?.let { runCatching { Api.json.decodeFromString(Me.serializer(), it) }.getOrNull() }
        set(value) {
            prefs.edit().putString("me", value?.let { Api.json.encodeToString(Me.serializer(), it) }).apply()
            VersionWidget.refresh(context)
        }

    var tags: Tags?
        get() = prefs.getString("tags", null)?.let { runCatching { Api.json.decodeFromString(Tags.serializer(), it) }.getOrNull() }
        set(value) = prefs.edit().putString("tags", value?.let { Api.json.encodeToString(Tags.serializer(), it) }).apply()

    val userAgent: String = "Changelog/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"

    fun api(token: String, server: String = this.server) = Api(server, token, userAgent)

    fun api(): Api? = tokens.read()?.let { api(it) }

    fun signIn(token: String, server: String, me: Me) {
        // Another account than the last one (after a lost token, say): nothing the last one left here is this one's.
        val last = account ?: this.me?.account
        if (me.account != null && last != null && me.account != last) forgetAccount()
        // The account before the token: SendWorker reads the token first, so once it sees this token it sends
        // only this account's writes.
        account = me.account
        this.me = me
        this.server = server
        tokens.write(token)
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

    /** Signing out keeps whatever is still waiting: it goes when this account signs in again, and is held for any other. */
    fun signOut() {
        tokens.clear()
        me = null
        forgetAccount()
        remindersOn = false
        promptOn = false
        hereByDefault = false
        recordingStops = false
        Stops.stop(context)
        noticeKinds = emptySet()
        PhoneNotices.clear(context)
        _signedIn.value = false
        VersionWidget.refresh(context)
    }

    /**
     * What an account left on this phone, forgotten as it signs out, or as another account signs in after its token
     * stopped working: its tags and sticky tag, its old home, the next dates for the widget, where Activity was
     * read to, and the words of the notes this phone filed for it. Its writes still waiting stay, held for it.
     */
    private fun forgetAccount() {
        tags = null
        sticky = null
        seenActivity = null
        activityCursor = null
        prefs.edit().remove("soon").remove("thisday").remove("home").apply() // "thisday": an earlier build's, for its widget
        _sent.value = emptyList()
        sentFile.delete()
        VersionWidget.refresh(context)
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
            _toast.value = if (badges.isEmpty()) say(R.string.filed_in, receipt.note.version) else say(R.string.unlocked, badges.joinToString { it.name })
        }
        // Check in now at a place the owner ignores: nothing was kept, so "Checked in." would be wrong.
        report.sent.lastOrNull { (it.item.operation as? OutboxOperation.CheckIn)?.report?.source == "tap" }?.let { tap ->
            (tap.result as? OutboxResult.CheckInNotKept)?.let { notKept ->
                _toast.value = notKept.place?.let { say(R.string.not_checked_in_at, it) } ?: say(R.string.not_checked_in_here)
            }
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
        val soonSerializer = ListSerializer(Soon.serializer())
    }
}
