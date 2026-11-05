package com.yourversionnumber.changelog

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourversionnumber.changelog.kit.ActivityNoticeKind
import com.yourversionnumber.changelog.kit.ActivityPage
import com.yourversionnumber.changelog.kit.ApiException
import com.yourversionnumber.changelog.kit.Hashtags
import com.yourversionnumber.changelog.kit.Here
import com.yourversionnumber.changelog.kit.MediaType
import com.yourversionnumber.changelog.kit.NoteDetail
import com.yourversionnumber.changelog.kit.OutboxNotKept
import com.yourversionnumber.changelog.kit.OutboxOperation
import com.yourversionnumber.changelog.kit.QueuedFile
import com.yourversionnumber.changelog.kit.RequestKey
import com.yourversionnumber.changelog.kit.Spot
import com.yourversionnumber.changelog.kit.TagRow
import com.yourversionnumber.changelog.kit.TokenFormat
import com.yourversionnumber.changelog.kit.UpcomingBatch
import com.yourversionnumber.changelog.kit.UpcomingDraft
import com.yourversionnumber.changelog.kit.UpcomingEntry
import com.yourversionnumber.changelog.kit.UpcomingList
import com.yourversionnumber.changelog.kit.UpcomingRemoval
import com.yourversionnumber.changelog.kit.upcomingSentence
import com.yourversionnumber.changelog.ui.ThemeMode
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A note on Log, from the day's notes or this phone's sent list. */
data class NoteTarget(val date: String, val id: String, val version: String?)

val Sent.target get() = NoteTarget(note.date, note.id, note.version)

val NoteDetail.target get() = NoteTarget(date, id, version)

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

    /** Opened from the widget or the tile: the note box takes the keyboard. */
    var writeNow by mutableStateOf(false)
    var server by mutableStateOf(graph.server)

    val hasActivity: Boolean get() = me?.token?.scopes?.contains("activity") == true

    /** `notes:all`: every note of the account is read, edited and deleted here, not only this token's own. */
    val readsAll: Boolean get() = me?.token?.readsAll == true

    /** The day Log shows; null is today. */
    var viewing by mutableStateOf<String?>(null)
        private set

    /** The day Log shows, as the account's own day. */
    val day: String? get() = viewing ?: me?.today
    val isToday: Boolean get() = viewing == null || viewing == me?.today

    /** The notes on that day, oldest first; null until the first read, or when it couldn't be made (offline). */
    var dayNotes by mutableStateOf<List<NoteDetail>?>(null)
        private set
    private var dayNext by mutableStateOf<String?>(null)
    val moreOnDay: Boolean get() = dayNext != null
    var dayLoading by mutableStateOf(false)
        private set

    /** The day whose first read has come back, or failed; until then Log shows nothing in its place, never a stand-in. */
    var dayRead by mutableStateOf<String?>(null)
        private set
    private var dayJob: Job? = null

    /** Whether a note on Log may be edited or deleted here. */
    fun canChange(note: NoteDetail) = note.mine || readsAll

    /** One day back (-1) or on (+1), never past today. */
    fun stepDay(by: Int) {
        val today = me?.today ?: return
        val to = LocalDate.parse(day ?: return).plusDays(by.toLong())
        viewing = if (to >= LocalDate.parse(today)) null else to.toString()
        loadDay()
    }

    fun showToday() {
        viewing = null
        loadDay()
    }

    /** Reads the day Log shows: this token's notes, or every channel's with `notes:all`. `more` takes the next page. */
    fun loadDay(more: Boolean = false) {
        val api = graph.api() ?: return
        val shown = day ?: return
        if (more && dayNext == null) return
        dayJob?.cancel()
        dayJob = viewModelScope.launch {
            dayLoading = true
            try {
                val page = api.notes(shown, after = if (more) dayNext else null)
                dayNotes = if (more) dayNotes.orEmpty() + page.items else page.items
                dayNext = page.next
                dayRead = shown
            } catch (e: ApiException) {
                if (!more) dayNotes = null
                dayRead = shown
                handle(e)
            } finally {
                // A read cancelled for a newer one leaves the newer one loading.
                if (dayJob == coroutineContext[Job]) dayLoading = false
            }
        }
    }

    /** A signed address for a file on a note, or its small copy. Null when it can't be had. */
    suspend fun fileAddress(note: NoteDetail, n: Int, thumb: Boolean): String? = try {
        graph.api()?.noteFile(note.date, note.id, n, thumb)?.url
    } catch (e: ApiException) {
        null
    }

    /** The bytes of a small copy, kept for the session. */
    suspend fun thumbnail(note: NoteDetail, n: Int): ByteArray? = try {
        val api = graph.api()
        fileAddress(note, n, thumb = true)?.let { api?.fetch(it, limit = 2 * 1024 * 1024) }
    } catch (e: ApiException) {
        null
    }

    /** The bytes of a photo itself, for the viewer; null when it can't be had. */
    suspend fun photo(note: NoteDetail, n: Int): ByteArray? = try {
        val api = graph.api()
        fileAddress(note, n, thumb = false)?.let { api?.fetch(it) }
    } catch (e: ApiException) {
        null
    }

    /** Where a file is read from outside the app, or the server's base for a path. */
    fun absolute(address: String): String = if (address.startsWith("/")) graph.server.trimEnd('/') + address else address

    val tagRow: List<String> get() = TagRow.tags(tags)
    val canCommit: Boolean get() = draft.isNotBlank() || draftFiles.isNotEmpty()
    /** Checks a pasted token against the server, then keeps it. Answers a problem, or null. */
    suspend fun signIn(pasted: String): String? {
        val token = TokenFormat.extract(pasted) ?: return say(R.string.that_doesnt_look_like_a)
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
            // Opening the app tops up the reminders, as the client guide asks.
            PhoneNotices.refresh(getApplication())
        }
    }

    fun toggle(tag: String) {
        draft = Hashtags.toggling(tag, draft)
    }

    /**
     * Pinned tags, the same here, on the web and on every phone. Shown at
     * once, queued through the Outbox, and Release Notes' answer replaces them.
     */
    fun setPinned(pinned: List<String>) {
        val before = tags
        tags = (tags ?: com.yourversionnumber.changelog.kit.Tags()).copy(pinned = pinned)
        viewModelScope.launch {
            try {
                graph.outbox.enqueue(OutboxOperation.Pins(pinned))
                graph.send()
            } catch (e: OutboxNotKept) {
                tags = before
                graph.report(e.message)
            }
        }
    }

    /** After a send: the tags as Release Notes answered them. */
    fun syncTags() {
        graph.tags?.let { tags = it }
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
        // Named by Here already, with the business picked; or a spot still to name as it sends.
        val place = here?.place?.copy(venue = venue)
        val unnamed = if (place == null) spot else null
        viewModelScope.launch {
            try {
                graph.outbox.enqueueNote(words, files = files, spot = unnamed, place = place)
                draft = ""
                draftFiles.clear()
                files.forEach { thumbnails.remove(it.id) }
                // With "Add where I am" on, the next note within ten minutes takes the
                // same place; a Here asked for once goes with this note alone.
                if (hereByDefault) placeOff = false else clearPlace(off = false)
                graph.send()
            } catch (e: OutboxNotKept) {
                graph.report(e.message)
            }
        }
    }

    // Where the draft was written. One fix and one Here a draft, when the
    // owner asks: each Here is a lookup Release Notes pays for, 100 a day.

    var here by mutableStateOf<Here?>(null)
        private set
    var spot by mutableStateOf<Spot?>(null)
        private set
    var venue by mutableStateOf<String?>(null)
        private set
    var locating by mutableStateOf(false)
        private set

    /** When the spot was found, so a draft reuses one from the last ten minutes. */
    private var spotAt = 0L

    /** The owner left the place off this draft. */
    private var placeOff = false

    /** Light, dark or as the phone is. */
    var theme by mutableStateOf(ThemeMode.of(graph.themeMode))
        private set

    fun chooseTheme(mode: ThemeMode) {
        graph.themeMode = mode.name
        theme = mode
    }

    /** "Add where I am": every note takes its place without asking. Kept on this phone. */
    var hereByDefault by mutableStateOf(graph.hereByDefault)
        private set

    fun addWhereIAm(on: Boolean) {
        graph.hereByDefault = on
        hereByDefault = on
        if (on) draftStarted()
    }

    /**
     * A new draft: with "Add where I am" on, find the phone now, while the words
     * are typed, so the note never waits for it. A spot more than ten minutes
     * old is found again.
     */
    fun draftStarted() {
        if (!hereByDefault || placeOff || locating || !Locator.allowed(getApplication())) return
        if (spot != null && System.currentTimeMillis() - spotAt < TEN_MINUTES) return
        locate()
    }

    /** What the note will be named by, or null with no place. */
    val placeLabel: String?
        get() = here?.place?.copy(venue = venue)?.label ?: spot?.let { say(R.string.here_named_as_it_sends) }

    fun locate() {
        if (locating) return
        viewModelScope.launch {
            locating = true
            placeOff = false
            val found = Locator.fix(getApplication())
            if (found == null) {
                graph.report(say(R.string.this_phone_couldnt_say_where))
            } else {
                spot = found
                spotAt = System.currentTimeMillis()
                here = null
                venue = null
                graph.api()?.let { api ->
                    try {
                        here = api.here(found)
                    } catch (e: ApiException) {
                        when {
                            e.signedOut -> graph.tokenLost(e.message)
                            // Offline: the note names its spot as it sends.
                            e.status == 0 || (e.retryable && e.status != 429) -> {}
                            else -> {
                                spot = null
                                graph.report(e.message)
                            }
                        }
                    }
                }
            }
            locating = false
        }
    }

    /** A business nearby, or null for the neighbourhood or city alone. */
    fun pick(venue: String?) {
        this.venue = venue
    }

    /** Leaves the place off this draft (`off`), or clears it once a note took it. */
    fun clearPlace(off: Boolean = true) {
        here = null
        spot = null
        venue = null
        spotAt = 0L
        placeOff = off
    }

    // Files on the draft. They wait in the Outbox's folder from the moment
    // they're added, and go with the note when it's committed.

    val draftFiles = mutableStateListOf<QueuedFile>()
    val thumbnails = mutableStateMapOf<String, Bitmap>()
    private val media = MediaReader(application)

    val canAddFiles: Boolean get() = me?.token?.scopes?.contains("media:write") == true

    /** Files picked for a note being edited, kept apart from the draft's. */
    val editFiles = mutableStateListOf<QueuedFile>()

    /** Files the note being edited holds already, so it stays within its twenty. */
    var editHeld by mutableIntStateOf(0)

    private fun slot(edit: Boolean) = if (edit) editFiles else draftFiles

    fun roomForFiles(edit: Boolean = false): Int =
        (MediaType.MAX_FILES - slot(edit).size - if (edit) editHeld else 0).coerceAtLeast(0)

    fun addPhotos(uris: List<Uri>, edit: Boolean = false) = viewModelScope.launch {
        for (uri in uris) add(edit) { media.photo(uri) }
    }

    /** The camera's photo, written into our own scratch file, which goes once it's read. */
    fun addCameraPhoto(file: File, edit: Boolean = false) = viewModelScope.launch {
        try {
            add(edit) { media.photo(Uri.fromFile(file)) }
        } finally {
            file.delete()
        }
    }

    fun addRecording(file: File, edit: Boolean = false) = viewModelScope.launch { add(edit) { Picked(file, "audio/mp4", null) } }

    /** Files shared from another app, each by what its bytes are. */
    fun addShared(uris: List<Uri>, declared: String?) = viewModelScope.launch {
        for (uri in uris) add(false) { media.shared(uri, getApplication<Application>().contentResolver.getType(uri) ?: declared) }
    }

    /** A file for the camera or the recorder to write. */
    fun scratch(extension: String): File = media.newScratch(extension)

    private suspend fun add(edit: Boolean, read: () -> Picked) {
        val into = slot(edit)
        if (me != null && !canAddFiles) return graph.report(say(R.string.this_token_cant_add_files))
        if (roomForFiles(edit) == 0) return graph.report(say(R.string.a_note_holds_up_to, MediaType.MAX_FILES))
        val picked = try {
            withContext(Dispatchers.IO) { read() }
        } catch (e: NotKept) {
            return graph.report(e.message)
        }
        try {
            val name = picked.name ?: when {
                picked.type.startsWith("image/") -> "Photo ${into.count { it.isPhoto } + 1}.${MediaType.fileExtension(picked.type)}"
                picked.type.startsWith("audio/") -> into.count { it.isRecording }.let { if (it == 0) "Voice memo.m4a" else "Voice memo ${it + 1}.m4a" }
                else -> "File.${MediaType.fileExtension(picked.type)}"
            }
            val queued = QueuedFile(type = picked.type, name = name, size = picked.file.length())
            withContext(Dispatchers.IO) {
                graph.outbox.keep(picked.file.inputStream(), queued)
                if (queued.isPhoto) graph.outbox.fileFor(queued)?.let(media::thumbnail)?.let { thumbnails[queued.id] = it }
            }
            into += queued
            if (!edit) draftStarted()
        } catch (e: OutboxNotKept) {
            graph.report(e.message)
        } catch (e: java.io.IOException) {
            graph.report(say(R.string.that_file_couldnt_be_read))
        } finally {
            picked.file.delete()
        }
    }

    fun remove(file: QueuedFile, edit: Boolean = false) {
        slot(edit).remove(file)
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

    /**
     * Queues an edit from the revision read, then the files that came with it.
     * The day shows the new words at once; a refusal (changed since) shows
     * under Waiting to send.
     */
    fun saveEdit(note: NoteDetail, text: String, files: List<QueuedFile>) {
        val changed = text != note.text
        if (changed) dayNotes = dayNotes?.map { if (it.id == note.id) it.copy(text = text) else it }
        editFiles.clear()
        viewModelScope.launch {
            try {
                if (changed) graph.outbox.enqueue(OutboxOperation.Edit(note.date, note.id, text, note.revision))
                if (files.isNotEmpty()) graph.outbox.enqueue(OutboxOperation.Attach(note.date, note.id, files))
                graph.send()
            } catch (e: OutboxNotKept) {
                graph.report(e.message)
            }
        }
    }

    fun delete(target: NoteTarget) {
        queue(OutboxOperation.Delete(target.date, target.id))
        graph.forget(target.id)
        dayNotes = dayNotes?.filterNot { it.id == target.id }
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

    /**
     * What Activity last showed under each filter, so coming back to the tab shows it at once while it
     * reads again. Memory only, gone with the app or a sign out; activity rows never carry a note's words.
     */
    val activityShown = mutableMapOf<String, ActivityPage>()

    suspend fun activity(before: String?, kinds: List<String>): ActivityPage? = try {
        graph.api()?.activity(before = before, kinds = kinds)
    } catch (e: ApiException) {
        handle(e)
        null
    }

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
        viewing = null
        dayNotes = null
        dayRead = null
        tags = null
        sticky = null
        activityShown.clear()
    }

    fun clearProblem() = graph.report(null)

    private companion object {
        const val TEN_MINUTES = 10 * 60 * 1000L
    }

    // Upcoming. Saves and deletes go at once, not through the Outbox: each is
    // made from a revision, and a refusal (a city the search doesn't know) is
    // for the owner to fix there and then. Each still carries a RequestKey.

    val hasUpcoming: Boolean get() = me?.token?.scopes?.contains("upcoming") == true

    var upcoming by mutableStateOf<UpcomingList?>(null)
        private set
    var upcomingLoading by mutableStateOf(false)
        private set
    private val upcomingKey = RequestKey<UpcomingBatch>()
    private val removeKey = RequestKey<UpcomingRemoval>()

    fun loadUpcoming() {
        val api = graph.api() ?: return
        if (!hasUpcoming) return
        viewModelScope.launch {
            upcomingLoading = true
            try {
                upcoming = api.upcoming()
            } catch (e: ApiException) {
                handle(e)
            }
            upcomingLoading = false
        }
    }

    /** Saves a date: answers null once saved, or the sentence to show. */
    suspend fun save(draft: UpcomingDraft, editing: UpcomingEntry?): String? {
        val batch = draft.batch(editing).getOrElse { return it.message }
        val api = graph.api() ?: return say(R.string.sign_in_again_to_keep)
        return try {
            api.saveUpcoming(batch, upcomingKey.key(batch))
            upcomingKey.reset()
            afterUpcomingChange(api)
            null
        } catch (e: ApiException) {
            whyNot(e)
        }
    }

    /** A dry run that finds the destination's city: answers the place as the server named it, or why not. */
    suspend fun checkPlace(draft: UpcomingDraft, editing: UpcomingEntry?): Result<String> {
        val batch = draft.placeCheck(editing).getOrElse { return Result.failure(it) }
        val api = graph.api() ?: return Result.failure(Exception(say(R.string.sign_in_again_to_keep)))
        return try {
            Result.success(api.saveUpcoming(batch, null).saved?.place.orEmpty())
        } catch (e: ApiException) {
            Result.failure(Exception(whyNot(e)))
        }
    }

    suspend fun remove(entry: UpcomingEntry): String? {
        val api = graph.api() ?: return say(R.string.sign_in_again_to_keep)
        val removal = UpcomingRemoval(entry.id, entry.revision)
        return try {
            api.deleteUpcoming(entry.id, entry.revision, removeKey.key(removal))
            removeKey.reset()
            afterUpcomingChange(api)
            null
        } catch (e: ApiException) {
            whyNot(e)
        }
    }

    private suspend fun afterUpcomingChange(api: com.yourversionnumber.changelog.kit.Api) {
        runCatching { upcoming = api.upcoming() }
        PhoneNotices.refresh(getApplication())
    }

    private fun whyNot(e: ApiException): String {
        if (e.signedOut) graph.tokenLost(e.message)
        if (e.code == "revision_mismatch") loadUpcoming()
        return when {
            e.code == "revision_mismatch" -> say(R.string.this_date_was_changed_somewhere)
            else -> e.upcomingSentence
        }
    }

    // This phone's own notifications, each off until turned on in Settings.
    // Reminders and prompts are scheduled again from the server's latest
    // read whenever either changes; turning both off takes back what waits.

    var remindersOn by mutableStateOf(graph.remindersOn)
        private set
    var promptOn by mutableStateOf(graph.promptOn)
        private set
    var noticeKinds by mutableStateOf(graph.noticeKinds)
        private set

    fun setReminders(on: Boolean) {
        graph.remindersOn = on
        remindersOn = on
        viewModelScope.launch { PhoneNotices.refresh(getApplication()) }
    }

    fun setPrompt(on: Boolean) {
        graph.promptOn = on
        promptOn = on
        viewModelScope.launch { PhoneNotices.refresh(getApplication()) }
    }

    fun setNotice(kind: ActivityNoticeKind, on: Boolean) {
        val wasOff = graph.noticeKinds.isEmpty()
        val kinds = if (on) graph.noticeKinds + kind else graph.noticeKinds - kind
        graph.noticeKinds = kinds
        noticeKinds = kinds
        viewModelScope.launch { PhoneNotices.activityChanged(getApplication(), wasOff) }
    }

    /** Which screen shows: Log, Upcoming, Activity or Settings. A tapped reminder opens Upcoming. */
    var screen by mutableStateOf("log")
}
