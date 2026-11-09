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
import com.yourversionnumber.changelog.kit.DayDetail
import com.yourversionnumber.changelog.kit.DaySummary
import com.yourversionnumber.changelog.kit.Hashtags
import com.yourversionnumber.changelog.kit.Here
import com.yourversionnumber.changelog.kit.HomeMove
import com.yourversionnumber.changelog.kit.MediaType
import com.yourversionnumber.changelog.kit.NoteSearch
import com.yourversionnumber.changelog.kit.NoteDetail
import com.yourversionnumber.changelog.kit.NoteMedia
import com.yourversionnumber.changelog.kit.OnThisDay
import com.yourversionnumber.changelog.kit.OutboxItem
import com.yourversionnumber.changelog.kit.OutboxNotKept
import com.yourversionnumber.changelog.kit.OutboxOperation
import com.yourversionnumber.changelog.kit.QueuedFile
import com.yourversionnumber.changelog.kit.RememberedPlace
import com.yourversionnumber.changelog.kit.RequestKey
import com.yourversionnumber.changelog.kit.Spot
import com.yourversionnumber.changelog.kit.Tag
import com.yourversionnumber.changelog.kit.TagRefusal
import com.yourversionnumber.changelog.kit.TagRow
import com.yourversionnumber.changelog.kit.TagTotals
import com.yourversionnumber.changelog.kit.TagTyping
import com.yourversionnumber.changelog.kit.TokenFormat
import com.yourversionnumber.changelog.kit.UpcomingBatch
import com.yourversionnumber.changelog.kit.UpcomingDraft
import com.yourversionnumber.changelog.kit.UpcomingEntry
import com.yourversionnumber.changelog.kit.UpcomingList
import com.yourversionnumber.changelog.kit.UpcomingRemoval
import com.yourversionnumber.changelog.kit.awaitingWords
import com.yourversionnumber.changelog.kit.upcomingSentence
import com.yourversionnumber.changelog.ui.ThemeMode
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    /**
     * A tag, by name, that rides on every note until stopped (a trip, a project), in its tags field, so "Road Trip"
     * stays one tag. Kept on this phone only; one kept before tags had names is its key, which reads as a name.
     */
    var sticky by mutableStateOf(graph.sticky)
        private set
    var draft by mutableStateOf("")

    /** The tags added in the box's tags field, by name (API 1.21.0). Its #words are tags too, after these. */
    val draftTags = mutableStateListOf<Tag>()

    /** What is typed in the tags field and isn't a chip yet: Commit takes it, or stops to say why (Codex on iPhone #57). */
    var draftTyping by mutableStateOf(TagTyping())

    /** The draft's tags as chips: its field's, then its #words. */
    val draftChips: List<Tag> get() = Hashtags.chips(draft, draftTags)

    /** The sticky tag as a tag; null for none. */
    val stickyTag: Tag? get() = sticky?.let { Hashtags.spelling(it).getOrNull() }

    /** Opened from the widget or the tile: the note box takes the keyboard. */
    var writeNow by mutableStateOf(false)
    var server by mutableStateOf(graph.server)

    val hasActivity: Boolean get() = me?.token?.scopes?.contains("activity") == true

    /** `notes:all`: every note of the account is read, edited and deleted here, not only this token's own. */
    val readsAll: Boolean get() = me?.token?.readsAll == true

    /** `notes:all` or `notes:read`: Notes reads the days, and every note on them. */
    val readsNotes: Boolean get() = me?.token?.readsNotes == true

    /** The day Log shows: today, as the account's own day. */
    val day: String? get() = me?.today

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
                // The widget's line about today: numbers only, never the words.
                dayNotes?.let { graph.todayNotes = TodayNotes.of(shown, it, more = page.next != null) }
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

    /**
     * The day's notes with a recording still being written out, each read again, for its words. A transcript
     * arriving doesn't change a note's `revision`, so only a fresh read finds it. Quiet: a read that fails waits
     * for the next.
     */
    suspend fun rereadWriting() {
        val shown = day ?: return
        for (note in dayNotes.orEmpty().filter { it.awaitingWords }) {
            val fresh = reread(note) ?: continue
            if (day == shown) dayNotes = dayNotes?.map { if (it.id == fresh.id) fresh else it }
        }
    }

    /** A note read again, quietly; null when it can't be had. */
    suspend fun reread(note: NoteDetail): NoteDetail? = try {
        graph.api()?.note(note.date, note.id)
    } catch (e: ApiException) {
        null
    }

    /** One note, as the server has it now (for its files, where only its words came with it); null when it can't be read. */
    suspend fun readNote(date: String, id: String): NoteDetail? = try {
        graph.api()?.note(date, id)
    } catch (e: ApiException) {
        null
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

    val tagRow: List<Tag> get() = TagRow.tags(tags)
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
                if ("upcoming" in me.token.scopes) runCatching { api.upcoming() }.getOrNull()?.let(graph::keepSoon)
                moveHome(api, me)
            } catch (e: ApiException) {
                if (e.signedOut) graph.tokenLost(e.message)
            }
            graph.send()
            // Opening the app asks again for stops, should Android have dropped the request.
            Stops.start(getApplication())
            // Opening the app tops up the reminders, as the client guide asks.
            PhoneNotices.refresh(getApplication())
        }
    }

    /** A tag from the row: on the draft, it comes off, its #words too (call 1); off, it goes in the tags field. */
    fun toggle(tag: Tag) {
        if (draftChips.any { it.key == tag.key }) removeTag(tag) else addTag(tag)
    }

    /** A tag in the draft's tags field. A 21st is refused, saying why; one the draft has already, by any spelling, stays. */
    fun addTag(tag: Tag) {
        val chips = draftChips
        if (chips.any { it.key == tag.key }) return
        if (chips.size >= Hashtags.MAX_TAGS) {
            draftTyping = draftTyping.copy(problem = TagRefusal.Many.said)
            return
        }
        draftTags += tag
    }

    /** Takes a tag off the draft: out of its field, and its #words out of the words, with the space beside each (call 1). */
    fun removeTag(tag: Tag) {
        draftTags.removeAll { it.key == tag.key }
        if (Hashtags.contains(tag.key, draft)) draft = Hashtags.removing(tag.key, draft)
    }

    /**
     * Pinned tags, the same here, on the web and on every phone, by name since API 1.21.0. Shown at
     * once, queued through the Outbox, and Release Notes' answer replaces them.
     */
    fun setPinned(pinned: List<Tag>) {
        val before = tags
        tags = (tags ?: com.yourversionnumber.changelog.kit.Tags()).copy(pinned = pinned.map { it.key }, pinnedNames = pinned.map { it.name })
        viewModelScope.launch {
            try {
                graph.outbox.enqueue(OutboxOperation.Pins(pinned.map { it.name }))
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

    /** Touch and hold: this tag goes on every note until stopped in Settings. Kept by name. */
    fun stick(tag: Tag?) {
        graph.sticky = tag?.name
        sticky = tag?.name
    }

    /** Every tag, by its latest name, with how many notes, Upcoming dates and check-ins have it. */
    suspend fun allTags(): Result<TagTotals> = reading { it.allTags() }

    /** A read for a screen of its own: the answer, or the server's sentence; a lost token signs out. */
    private suspend fun <T> reading(read: suspend (com.yourversionnumber.changelog.kit.Api) -> T): Result<T> {
        val api = graph.api() ?: return Result.failure(ApiException(401, "signed_out", say(R.string.sign_in_again)))
        return try {
            Result.success(read(api))
        } catch (e: ApiException) {
            if (e.signedOut) graph.tokenLost(e.message)
            Result.failure(e)
        }
    }

    // A tag's page, in Notes (call 5): every tag's list, and one tag's notes, Upcoming dates and check-ins. Opened
    // from Notes, or from a note's tags. Memory only, as the rest of Notes; its notes change with an edit or a
    // delete wherever they show (changeShown).

    /** Every tag's list is open in Notes. */
    var tagsOpen by mutableStateOf(false)

    /** The tag whose page is open in Notes; null for none. */
    var shownTag by mutableStateOf<Tag?>(null)
        private set

    /** Its notes, newest first, a page at a time; null until the first page is read. */
    var tagNotes by mutableStateOf<List<NoteDetail>?>(null)
        private set
    var tagNotesTotal by mutableIntStateOf(0)
        private set
    private var tagNotesNext by mutableStateOf<String?>(null)
    val moreTagNotes: Boolean get() = tagNotesNext != null

    /** Its Upcoming dates (with `upcoming`) and its check-ins, every one (with `activity`); empty without. */
    var tagDates by mutableStateOf<List<UpcomingEntry>>(emptyList())
        private set
    var tagStays by mutableStateOf<List<com.yourversionnumber.changelog.kit.CheckIn>>(emptyList())
        private set
    var tagLoading by mutableStateOf(false)
        private set
    var tagProblem by mutableStateOf<String?>(null)
        private set
    private var tagJob: Job? = null

    /** Opens a tag's page, in Notes, from wherever its name was tapped. */
    fun openTag(tag: Tag) {
        if (shownTag?.key != tag.key) {
            tagNotes = null
            tagNotesNext = null
            tagNotesTotal = 0
            tagDates = emptyList()
            tagStays = emptyList()
            tagProblem = null
        }
        shownTag = tag
        screen = "notes"
        readTag()
    }

    fun closeTag() {
        tagJob?.cancel()
        tagLoading = false
        shownTag = null
        tagNotes = null
        tagNotesNext = null
        tagNotesTotal = 0
        tagDates = emptyList()
        tagStays = emptyList()
        tagProblem = null
    }

    /**
     * Reads the open tag's page: its notes' first page beside its dates and every page of its check-ins (Codex on
     * iPhone #57: the page and its lede count them all), or with `more` the next page of notes. A date or check-in
     * that can't be read leaves its section out, as the notes are what the page is.
     */
    fun readTag(more: Boolean = false) {
        val tag = shownTag ?: return
        val api = graph.api() ?: return
        if (more && (tagNotesNext == null || tagLoading)) return
        tagJob?.cancel()
        tagJob = viewModelScope.launch {
            tagLoading = true
            try {
                if (more) {
                    val page = api.search(tag = tag.key, after = tagNotesNext)
                    if (shownTag?.key == tag.key) {
                        tagNotes = tagNotes.orEmpty() + page.items
                        tagNotesNext = page.next
                    }
                } else {
                    val dates = async {
                        if (!hasUpcoming) emptyList()
                        else try { api.upcoming().entries.filter { e -> e.tagList.any { it.key == tag.key } } } catch (_: ApiException) { null }
                    }
                    val stays = async {
                        if (!hasActivity) emptyList()
                        else try { api.checkInsTagged(tag.key) } catch (_: ApiException) { null }
                    }
                    val page = api.search(tag = tag.key)
                    val datesRead = dates.await()
                    val staysRead = stays.await()
                    if (shownTag?.key == tag.key) {
                        datesRead?.let { tagDates = it }
                        staysRead?.let { tagStays = it }
                        tagNotes = page.items
                        tagNotesNext = page.next
                        tagNotesTotal = page.total
                        tagProblem = null
                    }
                }
            } catch (e: ApiException) {
                if (shownTag?.key == tag.key && tagNotes == null) tagProblem = e.message
                if (e.signedOut) handle(e)
            } finally {
                if (tagJob == coroutineContext[Job]) tagLoading = false
            }
        }
    }

    /**
     * Save first, then send: the note, and its files, are on the phone before anything goes. Its tags go by name:
     * the field's, then the sticky one; its #words come after them (API 1.21.0).
     */
    fun commit() {
        // A tag typed without Return goes with the note, or stays in its box saying why, and nothing is committed yet.
        val (left, taken) = draftTyping.take(draftChips)
        draftTyping = left
        when (taken) {
            TagTyping.Taken.Refused -> return
            is TagTyping.Taken.Added -> addTag(taken.tag)
            TagTyping.Taken.None -> {}
        }
        val files = draftFiles.toList()
        val typed = draft.trim()
        if (typed.isEmpty() && files.isEmpty()) return
        val field = draftTags.toList()
        val tags = Hashtags.merge(field, listOfNotNull(stickyTag))
        // Named by Here already, with the business picked; or a spot still to name as it sends.
        val place = here?.place?.copy(venue = venue)
        val unnamed = if (place == null) spot else null
        viewModelScope.launch {
            try {
                graph.outbox.enqueueNote(typed, files = files, spot = unnamed, place = place, tags = tags)
                draft = ""
                draftTags.clear()
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

    /** Written while signed in to another account, or before this one signed in: Log asks before it goes. */
    fun isHeld(item: OutboxItem): Boolean = item.isHeld(graph.recipient)

    /** "Send to this account": a held write goes here at the owner's word, under a key this account hasn't seen, its files posted again. */
    fun sendHere(item: OutboxItem) = viewModelScope.launch {
        val account = graph.account ?: return@launch
        try {
            graph.outbox.move(item.id, account)
            graph.send()
        } catch (e: OutboxNotKept) {
            graph.report(e.message)
        }
    }

    fun fix(id: String, text: String) = viewModelScope.launch {
        graph.outbox.retry(id, text)
        graph.send()
    }

    // Notes this token wrote: the only ones it may read back, change or delete.

    /** Reads a note as it is now, so an edit made on the web isn't lost. */
    suspend fun load(target: NoteTarget): NoteDetail? = try {
        graph.api()?.note(target.date, target.id)
    } catch (e: ApiException) {
        // Opened from an older row (Activity, say), a note may have gone since.
        if (e.code == "not_found") graph.report(say(R.string.this_note_was_deleted_since)) else handle(e)
        null
    }

    /**
     * Queues an edit from the revision read, then the files that came with it.
     * The day shows the new words at once; a refusal (changed since) shows
     * under Waiting to send.
     */
    /**
     * Saves an edit: new words, the tags field's whole new list (`tags`, the ones its words don't carry, or null to
     * leave them as they are; API 1.21.0), and files to add.
     */
    fun saveEdit(note: NoteDetail, text: String, files: List<QueuedFile>, tags: List<String>? = null) {
        val changed = text != note.text || tags != null
        if (changed) {
            // Shown at once as Release Notes will keep it: the field's tags (the new list, or the ones it had),
            // then the new words' #words.
            changeShown(note.id) { shown ->
                val field = tags?.mapNotNull { Hashtags.spelling(it).getOrNull() } ?: shown.fieldTags
                val now = Hashtags.merge(field, Hashtags.textTags(text))
                shown.copy(text = text, tags = now.map { it.key }, tagNames = now.map { it.name })
            }
        }
        editFiles.clear()
        viewModelScope.launch {
            try {
                if (changed) graph.outbox.enqueue(OutboxOperation.Edit(note.date, note.id, text, note.revision, tags))
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
        changeShown(target.id) { null }
    }

    /** Whether a file can come off this note: a note with no words keeps its last file (`text_required`). */
    fun canRemoveFile(note: NoteDetail): Boolean =
        canChange(note) && canAddFiles && (note.text.isNotBlank() || note.media.size > 1)

    /**
     * Takes one file off a note (API 1.17.0), through the Outbox under its own key. It goes from every list
     * at once; a refusal shows under Waiting to send, and the next read brings it back.
     */
    fun removeFile(note: NoteDetail, file: NoteMedia) {
        if (!canRemoveFile(note)) return
        changeShown(note.id) { shown -> shown.copy(media = shown.media.filterNot { it.n == file.n }) }
        queue(OutboxOperation.RemoveFile(note.date, note.id, file.n))
    }

    /** Changes a note wherever it shows (Log, the open day, a search, On this day, a tag's page), or with null takes it off them. */
    private fun changeShown(id: String, change: (NoteDetail) -> NoteDetail?) {
        fun List<NoteDetail>.changed() = mapNotNull { if (it.id == id) change(it) else it }
        dayNotes = dayNotes?.changed()
        shownDayNotes = shownDayNotes.changed()
        found = found?.changed()
        onThisDay = onThisDay?.let { o -> o.copy(days = o.days.map { d -> d.copy(notes = d.notes.changed()) }) }
        tagNotes = tagNotes?.changed()
    }

    private fun queue(operation: OutboxOperation) = viewModelScope.launch {
        try {
            graph.outbox.enqueue(operation)
            graph.send()
        } catch (e: OutboxNotKept) {
            graph.report(e.message)
        }
    }

    // Notes: the timeline, and one day of it opened. Memory only, as Log's notes are; it needs `notes:all` or
    // `notes:read`, and a refusal shows where the list would be.

    /** The days, newest first, today among them; null until the first read. */
    var days by mutableStateOf<List<DaySummary>?>(null)
        private set
    private var daysNext by mutableStateOf<String?>(null)
    val moreDays: Boolean get() = daysNext != null
    var daysLoading by mutableStateOf(false)
        private set

    /** Why the days couldn't be read, while none are shown. */
    var daysProblem by mutableStateOf<String?>(null)
        private set

    /** Shown wherever the weather is. */
    var weatherCredit by mutableStateOf<String?>(null)
        private set
    private var daysJob: Job? = null

    /** Reads the days afresh, or with `more` the page after the last. */
    fun loadDays(more: Boolean = false) {
        val api = graph.api() ?: return
        if (more && (daysNext == null || daysLoading)) return
        daysJob?.cancel()
        daysJob = viewModelScope.launch {
            daysLoading = true
            try {
                val page = api.days(before = if (more) daysNext else null)
                days = if (more) days.orEmpty() + page.days else page.days
                daysNext = page.next
                page.weatherCredit?.let { weatherCredit = it }
                daysProblem = null
            } catch (e: ApiException) {
                if (days == null) daysProblem = e.message
                if (e.signedOut) handle(e)
            } finally {
                if (daysJob == coroutineContext[Job]) daysLoading = false
            }
        }
    }

    /** The day opened in Notes, once read; its notes so far, oldest first. */
    var shownDay by mutableStateOf<DayDetail?>(null)
        private set
    var shownDate by mutableStateOf<String?>(null)
        private set
    var shownDayNotes by mutableStateOf<List<NoteDetail>>(emptyList())
        private set
    private var shownDayNext by mutableStateOf<String?>(null)
    val moreOnShownDay: Boolean get() = shownDayNext != null
    var shownDayProblem by mutableStateOf<String?>(null)
        private set
    private var shownDayJob: Job? = null

    /** Opens a day in Notes and reads it. */
    fun showDay(date: String) {
        if (shownDate != date) {
            shownDay = null
            shownDayNotes = emptyList()
            shownDayNext = null
            shownDayProblem = null
        }
        shownDate = date
        readShownDay()
    }

    /** Reads the open day again, or with `more` its next notes. */
    fun readShownDay(more: Boolean = false) {
        val api = graph.api() ?: return
        val date = shownDate ?: return
        if (more && shownDayNext == null) return
        shownDayJob?.cancel()
        shownDayJob = viewModelScope.launch {
            try {
                if (more) {
                    val page = api.notes(date, after = shownDayNext)
                    if (shownDate == date) {
                        shownDayNotes = shownDayNotes + page.items
                        shownDayNext = page.next
                    }
                } else {
                    val read = api.day(date)
                    if (shownDate == date) {
                        shownDay = read
                        shownDayNotes = read.notes
                        shownDayNext = read.next
                        shownDayProblem = null
                    }
                }
            } catch (e: ApiException) {
                if (shownDate == date && shownDay == null) shownDayProblem = e.message
                if (e.signedOut) handle(e)
            }
        }
    }

    fun closeDay() {
        shownDayJob?.cancel()
        shownDate = null
        shownDay = null
        shownDayNotes = emptyList()
        shownDayNext = null
        shownDayProblem = null
    }

    /** A note on Log, on the day open in Notes, in a search or On this day, as last read. */
    fun noteShown(id: String): NoteDetail? =
        dayNotes?.firstOrNull { it.id == id }
            ?: shownDayNotes.firstOrNull { it.id == id }
            ?: found?.firstOrNull { it.id == id }
            ?: onThisDay?.days?.firstNotNullOfOrNull { d -> d.notes.firstOrNull { it.id == id } }
            ?: tagNotes?.firstOrNull { it.id == id }

    // Search (API 1.15.0): every word in a note's words, places, link names, transcripts and descriptions, or a
    // tag's page; newest first, a page at a time. Memory only, as everything Notes reads.

    /** What was searched for, as typed; null with no search open. */
    var searched by mutableStateOf<String?>(null)
        private set
    /** The notes found so far, newest first; null until the first page comes. */
    var found by mutableStateOf<List<NoteDetail>?>(null)
        private set
    var foundTotal by mutableIntStateOf(0)
        private set
    private var foundNext by mutableStateOf<String?>(null)
    val moreFound: Boolean get() = foundNext != null
    var searching by mutableStateOf(false)
        private set
    var searchProblem by mutableStateOf<String?>(null)
        private set
    private var searchJob: Job? = null

    /** Searches afresh: "#family" alone is that tag's page, anything else words. `more` takes the next page. */
    fun search(typed: String? = searched, more: Boolean = false) {
        val api = graph.api() ?: return
        val query = typed?.trim().orEmpty()
        if (query.isEmpty()) return closeSearch()
        if (more && (foundNext == null || searching)) return
        if (!more) {
            searched = query
            found = null
            foundNext = null
            searchProblem = null
        }
        // A hashtag alone is that tag's notes, read by the server's own rules, so "#lake," or "#Road Trip" are words.
        val tag = (NoteSearch.of(query) as? NoteSearch.Tagged)?.key
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            searching = true
            try {
                val page = if (tag != null) api.search(tag = tag, after = if (more) foundNext else null)
                else api.search(q = query, after = if (more) foundNext else null)
                if (searched == query) {
                    found = if (more) found.orEmpty() + page.items else page.items
                    foundNext = page.next
                    foundTotal = page.total
                }
            } catch (e: ApiException) {
                if (searched == query && found == null) searchProblem = e.message
                if (e.signedOut) handle(e)
            } finally {
                if (searchJob == coroutineContext[Job]) searching = false
            }
        }
    }

    fun closeSearch() {
        searchJob?.cancel()
        searching = false
        searched = null
        found = null
        foundNext = null
        foundTotal = 0
        searchProblem = null
    }

    // On this day (API 1.15.0): today's date in each earlier year of releases, as the morning email has it.

    var onThisDay by mutableStateOf<OnThisDay?>(null)
        private set

    /** Reads On this day for today. Quiet: a read that fails leaves the section out. */
    fun loadOnThisDay() {
        val api = graph.api() ?: return
        if (!readsNotes) return
        viewModelScope.launch {
            try {
                onThisDay = api.onThisDay(date = day, limit = 5)
            } catch (e: ApiException) {
                if (e.signedOut) handle(e)
            }
        }
    }

    // Check-ins (API 1.18.0), as the iPhone app sends them: read whole for the map, in memory only. This phone
    // never sends them.

    /** Every stay read so far, newest first; null until the first page. */
    var checkIns by mutableStateOf<List<com.yourversionnumber.changelog.kit.CheckIn>?>(null)
        private set
    var checkInsLoading by mutableStateOf(false)
        private set
    var checkInsProblem by mutableStateOf<String?>(null)
        private set
    private var checkInsJob: Job? = null

    /**
     * Reads every page in turn. The first read fills in as it goes; a later one takes the old pins away only once
     * it has them all, as the iPhone app's map does.
     */
    fun loadCheckIns() {
        val api = graph.api() ?: return
        if (!hasActivity) return
        checkInsJob?.cancel()
        checkInsJob = viewModelScope.launch {
            checkInsLoading = true
            checkInsProblem = null
            val read = mutableListOf<com.yourversionnumber.changelog.kit.CheckIn>()
            var before: String? = null
            try {
                do {
                    val page = api.checkIns(before = before)
                    read += page.items
                    if (read.size > (checkIns?.size ?: 0)) checkIns = read.toList()
                    before = page.next
                } while (before != null)
                checkIns = read.toList()
            } catch (e: ApiException) {
                checkInsProblem = e.message
                if (e.signedOut) handle(e)
            } finally {
                if (checkInsJob == coroutineContext[Job]) checkInsLoading = false
            }
        }
    }

    /** Checking in sends with `notes:write`, which every Changelog token has. */
    val canCheckIn: Boolean get() = me?.token?.scopes?.contains("notes:write") == true

    /** Check in now is finding the phone. */
    var checkingIn by mutableStateOf(false)
        private set

    /** Check in now: one fix, sent as a tap; the map reads again once it's gone. */
    fun checkInNow() {
        if (checkingIn) return
        viewModelScope.launch {
            checkingIn = true
            val problem = Stops.tap(getApplication())
            checkingIn = false
            if (problem != null) graph.report(problem) else graph.tell(say(R.string.checked_in))
        }
    }

    // Not at home, from builds before API 1.20.0: a home kept on this phone alone, which Record my stops held
    // back. It moves to Release Notes as a remembered place the owner ignores (HomeMove), and only once the server
    // says so is it forgotten here. No new home is set on the phone: any remembered place can be ignored instead.

    /** A home kept on this phone and not moved yet; null once moved, or when there never was one. */
    var home by mutableStateOf(graph.home)
        private set

    /** Why the last try to move home didn't, in the server's words; null offline or untried. */
    var homeProblem by mutableStateOf<String?>(null)
        private set
    private var movingHome = false

    /**
     * Moves a home kept on the phone to the server, when this sign-in may (HomeMove.lack). Tried on each refresh
     * until it lands; asking again is safe, since an ignored place at home asks nothing more.
     */
    private suspend fun moveHome(api: com.yourversionnumber.changelog.kit.Api, me: com.yourversionnumber.changelog.kit.Me) {
        val kept = graph.home ?: return
        if (movingHome || HomeMove.lack(me) != HomeMove.Lack.Nothing) return
        movingHome = true
        try {
            val place = HomeMove.move(api, kept)
            graph.home = null
            home = null
            homeProblem = null
            graph.tell(say(R.string.home_moved, place.name))
            if (places != null) loadPlaces()
            if (checkIns != null) loadCheckIns()
        } catch (e: ApiException) {
            if (e.signedOut) graph.tokenLost(e.message)
            homeProblem = e.message.takeUnless { e.status == 0 }
        } finally {
            movingHome = false
        }
    }

    /** Forgets a home kept on the phone without moving it: stops near it go to the server from now on. */
    fun forgetHome() {
        graph.home = null
        home = null
        homeProblem = null
    }

    // Remembered places (API 1.18.0), each of which can be ignored (API 1.20.0): read with `activity`; ignoring
    // one, or deleting the check-ins at one, needs `notes:all`. In memory only.

    /** The account's remembered places, by name; null until read. */
    var places by mutableStateOf<List<RememberedPlace>?>(null)
        private set

    /** What went wrong with the places last, said under them. */
    var placesProblem by mutableStateOf<String?>(null)
        private set

    /** A place is being ignored, unignored, counted or emptied: its buttons wait. */
    var placeBusy by mutableStateOf(false)
        private set
    private val ignoreKey = RequestKey<Pair<String, Boolean>>()
    private val deleteAtKey = RequestKey<Pair<String, Int>>()

    private fun List<RememberedPlace>.byName() = sortedBy { it.name.lowercase() }

    fun loadPlaces() {
        val api = graph.api() ?: return
        if (!hasActivity) return
        viewModelScope.launch {
            try {
                places = api.rememberedPlaces().byName()
                placesProblem = null
            } catch (e: ApiException) {
                placeProblem(e)
            }
        }
    }

    private fun placeProblem(e: ApiException) {
        if (e.signedOut) graph.tokenLost(e.message)
        placesProblem = e.message
    }

    /** Ignore this location, on or off: the box moves at once and goes back if the server says no. */
    fun setIgnored(place: RememberedPlace, on: Boolean) {
        val api = graph.api() ?: return
        if (placeBusy) return
        fun shown(ignored: Boolean) {
            places = places?.map { if (it.id == place.id) it.copy(ignored = ignored) else it }
        }
        shown(on)
        viewModelScope.launch {
            placeBusy = true
            try {
                val changed = api.changePlace(place.id, ignored = on, idempotencyKey = ignoreKey.key(place.id to on))
                ignoreKey.reset()
                places = places?.map { if (it.id == changed.id) changed else it }
                placesProblem = null
                // Its check-ins hide, or come back: the map reads again.
                if (checkIns != null) loadCheckIns()
            } catch (e: ApiException) {
                shown(!on)
                placeProblem(e)
            } finally {
                placeBusy = false
            }
        }
    }

    /** How many check-ins a place holds now, read just before asking to delete them; null when it couldn't be read. */
    suspend fun checkInsAt(place: RememberedPlace): Int? {
        val api = graph.api() ?: return null
        placeBusy = true
        return try {
            api.rememberedPlace(place.id).checkIns ?: 0
        } catch (e: ApiException) {
            placeProblem(e)
            null
        } finally {
            placeBusy = false
        }
    }

    /**
     * Delete existing check-ins here, as many as were counted. Answers null once they've gone (or it couldn't be
     * done, said under the places), or the count there now when it changed since: ask again with that.
     */
    suspend fun deleteCheckInsAt(place: RememberedPlace, count: Int): Int? {
        val api = graph.api() ?: return null
        placeBusy = true
        return try {
            val gone = api.deleteCheckInsAt(place.id, count, deleteAtKey.key(place.id to count))
            deleteAtKey.reset()
            placesProblem = null
            graph.tell(sayCount(R.plurals.deleted_check_ins_at, gone, gone, place.name))
            if (checkIns != null) loadCheckIns()
            null
        } catch (e: ApiException) {
            if (e.code == "revision_mismatch" && e.count != null) return e.count
            placeProblem(e)
            null
        } finally {
            placeBusy = false
        }
    }

    // Tags on remembered places and stays (API 1.21.0, call 6). Each is saved as it's added or taken off, at once
    // rather than through the Outbox, as Ignore this location is: a stay's is made from its revision, and a refusal
    // is for the owner to answer there and then.

    private val placeTagsKey = RequestKey<String>()
    private val tagAtKey = RequestKey<String>()
    private val stayTagsKey = RequestKey<String>()

    private fun List<Tag>.names() = joinToString("\n") { it.name }

    /**
     * A place's whole list of tags: every new check-in there gets them. The answer replaces it in the list, and
     * counts the check-ins already there (`checkIns`) when it gained one. Needs `notes:all`.
     */
    suspend fun setPlaceTags(place: RememberedPlace, tags: List<Tag>): Result<RememberedPlace> = reading { api ->
        try {
            val saved = api.changePlace(place.id, tags = tags, idempotencyKey = placeTagsKey.key("${place.id}=" + tags.names()))
            placeTagsKey.reset()
            places = places?.map { if (it.id == saved.id) saved else it }
            placesProblem = null
            saved
        } catch (e: ApiException) {
            if (!e.retryable) placeTagsKey.reset()
            throw e
        }
    }

    /**
     * Tags the check-ins already at a place, for the count shown: how many it tagged. A count that changed since
     * comes back as `revision_mismatch` with the count now, to ask again with.
     */
    suspend fun tagCheckInsAt(place: RememberedPlace, tags: List<Tag>, count: Int): Result<Int> = reading { api ->
        try {
            val tagged = api.tagCheckInsAt(place.id, tags, count, tagAtKey.key("${place.id} count=$count " + tags.names()))
            tagAtKey.reset()
            if (checkIns != null) loadCheckIns()
            tagged
        } catch (e: ApiException) {
            if (!e.retryable) tagAtKey.reset()
            throw e
        }
    }

    /** A stay's whole list of tags, from the revision read. The map's stays take the answer. Needs `notes:write`. */
    suspend fun tagStay(stay: com.yourversionnumber.changelog.kit.CheckIn, tags: List<Tag>): Result<com.yourversionnumber.changelog.kit.CheckIn> =
        reading { api ->
            try {
                val changed = api.tagCheckIn(stay.id, tags, stay.revision, stayTagsKey.key("${stay.id}@${stay.revision}=" + tags.names()))
                stayTagsKey.reset()
                checkIns = checkIns?.map { if (it.id == changed.id) changed else it }
                changed
            } catch (e: ApiException) {
                if (!e.retryable) stayTagsKey.reset()
                throw e
            }
        }

    /** Recording stops in the background, as this phone's switch says. */
    var recordingStops by mutableStateOf(graph.recordingStops)
        private set

    /** Turns recording on or off; answers whether it runs (on needs location all the time). */
    fun recordStops(on: Boolean): Boolean {
        graph.recordingStops = on
        recordingStops = on
        return if (on) {
            Stops.start(getApplication())
        } else {
            Stops.stop(getApplication())
            false
        }
    }

    // Notes with a place of their own, for the map (`GET /v1/map/pins`, `notes:read` or `notes:all`). Their opening
    // words are held in memory only.

    var notePins by mutableStateOf<List<com.yourversionnumber.changelog.kit.MapPin>?>(null)
        private set
    private var notePinsJob: Job? = null

    /** Reads every page in turn. Quiet: a token that can't read every note leaves notes off the map. */
    fun loadNotePins() {
        val api = graph.api() ?: return
        if (!readsNotes) return
        notePinsJob?.cancel()
        notePinsJob = viewModelScope.launch {
            val read = mutableListOf<com.yourversionnumber.changelog.kit.MapPin>()
            var after: String? = null
            try {
                do {
                    val page = api.mapPins(after)
                    read += page.pins
                    if (read.size > (notePins?.size ?: 0)) notePins = read.toList()
                    after = page.next
                } while (after != null)
                notePins = read.toList()
            } catch (e: ApiException) {
                if (e.signedOut) handle(e)
            }
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
        dayNotes = null
        dayRead = null
        days = null
        daysNext = null
        weatherCredit = null
        closeDay()
        closeSearch()
        onThisDay = null
        checkIns = null
        notePins = null
        recordingStops = false
        home = null
        homeProblem = null
        places = null
        placesProblem = null
        tags = null
        sticky = null
        draftTags.clear()
        draftTyping = TagTyping()
        tagsOpen = false
        closeTag()
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
                upcoming = api.upcoming().also(graph::keepSoon)
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
        runCatching { upcoming = api.upcoming().also(graph::keepSoon) }
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
