package com.yourversionnumber.changelog.kit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.time.LocalDate
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The client against the real `/v1`, on Release Notes' dev server and its
 * fictional account. It skips unless CHANGELOG_DEV_TOKEN is set (the server
 * prints one when it starts), and runs only against localhost, never
 * production, because it writes. CHANGELOG_DEV_URL picks another port.
 *
 *     CHANGELOG_DEV_TOKEN=rn_... ./gradlew :kit:test --tests '*DevServerTest'
 */
class DevServerTest {
    private val url = System.getenv("CHANGELOG_DEV_URL") ?: "http://localhost:8790"
    private val token = System.getenv("CHANGELOG_DEV_TOKEN").orEmpty()
    private val api get() = Api(url, token, "Changelog/0.0.0 (DevServerTest)")

    @BeforeTest
    fun onlyTheDevServer() {
        assumeTrue("Set CHANGELOG_DEV_TOKEN to run against the dev server", token.isNotEmpty())
        val host = URI(url).host
        check(host == "localhost" || host == "127.0.0.1") { "DevServerTest runs only against localhost" }
    }

    private fun key() = UUID.randomUUID().toString()

    @Test
    fun aNoteFilesOnceEvenSentTwice() = runTest {
        val me = api.me()
        val key = key()
        val request = NoteRequest("DevServerTest note #devtest", writtenAt = Timestamp.now())
        val first = api.createNote(request, key)
        val again = api.createNote(request, key)
        assertEquals(first.note.id, again.note.id)
        assertEquals(me.today, first.note.date)
        assertEquals(me.version, first.note.version)

        val read = api.note(first.note.date, first.note.id)
        val edited = api.editNote(read.date, read.id, read.text + " (edited)", read.revision, idempotencyKey = key())
        assertEquals("DevServerTest note #devtest (edited)", api.note(edited.date, edited.id).text)
        val stale = assertFailsWith<ApiException> { api.editNote(read.date, read.id, "stale", read.revision, idempotencyKey = key()) }
        assertEquals("revision_mismatch", stale.code)

        api.deleteNote(edited.date, edited.id, key())
        assertEquals("not_found", assertFailsWith<ApiException> { api.note(edited.date, edited.id) }.code)
    }

    @Test
    fun aNoteWithAFileAndAPlaceGoesThroughTheOutbox() = runTest {
        val folder = Files.createTempDirectory("dev server").toFile()
        val outbox = Outbox(File(folder, "outbox.json"))
        // The smallest PNG: one transparent pixel.
        val png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
        )
        val file = QueuedFile(type = "image/png", name = "Pixel.png", size = png.size.toLong())
        outbox.keep(png.inputStream(), file)
        outbox.enqueueNote("A pixel and a place", files = listOf(file), spot = Spot(44.98, -93.26, 20.0))
        val report = outbox.flush(prepare = { api.prepare(it, outbox) }) { api.perform(it) }
        assertEquals(null, report.stopped?.code, report.stopped?.message)
        val receipt = (report.sent.single().result as OutboxResult.Filed).receipt
        assertEquals(1, receipt.note.files)
        // API 1.9.0: the day lists it with its file, and the file has a small copy and an address.
        val listed = api.notes(receipt.note.date).items.single { it.id == receipt.note.id }
        assertTrue(listed.mine)
        val media = listed.media.single()
        assertEquals("image", media.kind)
        // A one-pixel PNG may have no small copy (and the server then answers not_found); a file that has one serves it.
        if (media.thumb != null) assertTrue(api.fetch(api.noteFile(listed.date, listed.id, media.n, thumb = true).url).isNotEmpty())
        assertEquals(png.size, api.fetch(api.noteFile(listed.date, listed.id, media.n).url).size)
        api.deleteNote(receipt.note.date, receipt.note.id, key())
    }

    @Test
    fun aDayListsThisTokensNotesOldestFirstAndAWideRangeIsRefused() = runTest {
        val day = api.me().today
        val first = api.createNote(NoteRequest("DevServerTest first #devtest", writtenAt = java.time.Instant.now().minusSeconds(120).toString()), key())
        val second = api.createNote(NoteRequest("DevServerTest second #devtest", writtenAt = Timestamp.now()), key())
        val ids = api.notes(day, mine = true).items.map { it.id }
        assertTrue(ids.indexOf(first.note.id) in 0 until ids.indexOf(second.note.id))
        // Without notes:all a day lists only this token's notes; with it, every channel's.
        if (!api.me().token.readsAll) assertTrue(api.notes(day, to = day).items.all { it.mine })
        assertEquals("range_too_wide", assertFailsWith<ApiException> { api.notes("2026-01-01", "2026-12-31") }.code)
        assertTrue(api.notes("2026-10-01", "2026-10-01").items.none { it.id == first.note.id })
        api.deleteNote(first.note.date, first.note.id, key())
        api.deleteNote(second.note.date, second.note.id, key())
    }

    @Test
    fun upcomingAddsChecksRemindsAndRemoves() = runTest {
        val today = LocalDate.parse(api.me().today)
        val soon = today.plusDays(3)
        val draft = UpcomingDraft.new(today.toString()).copy(
            title = "DevServerTest",
            month = soon.monthValue,
            day = soon.dayOfMonth,
            year = "1990",
        )
        val dry = api.saveUpcoming(draft.batch(null, dryRun = true).getOrThrow(), null)
        assertTrue(dry.dryRun)
        val saved = api.saveUpcoming(draft.batch(null).getOrThrow(), key()).saved
        assertNotNull(saved)
        assertTrue(api.upcoming().entries.any { it.id == saved.id })
        val reminders = api.reminders()
        assertTrue(reminders.days.flatMap { it.reminders }.any { it.upcoming == saved.id })
        assertTrue(ReminderPlan.plan(reminders, java.time.Instant.EPOCH).any { saved.id in it.id })
        api.deleteUpcoming(saved.id, saved.revision, key())
        assertTrue(api.upcoming().entries.none { it.id == saved.id })
    }

    @Test
    fun theActivityLogTellsOfThisTokensNoteWithoutItsWords() = runTest {
        val receipt = api.createNote(NoteRequest("DevServerTest activity words"), key())
        // The row may land a moment after the note: read again for up to two seconds.
        var row: ActivityItem? = null
        for (attempt in 1..10) {
            row = api.activity(kinds = ActivityNotices.families, limit = 50).items.firstOrNull { it.note == receipt.note.id }
            if (row != null) break
            withContext(Dispatchers.IO) { Thread.sleep(200) }
        }
        assertNotNull(row, "the note's row in the activity log")
        assertEquals("note.added", row.kind)
        val me = api.me()
        // API 1.4.0: the account and the token have ids, and the row names the token by its id.
        assertTrue(me.account?.startsWith("acct_") == true)
        assertTrue(me.token.id != null && row.tokenId == me.token.id)
        assertTrue(ActivityNotices.isOwn(row, me.token))
        assertTrue("DevServerTest activity words" !in Api.json.encodeToString(ActivityItem.serializer(), row))
        api.deleteNote(receipt.note.date, receipt.note.id, key())
    }

    @Test
    fun tagsArePinnedAndPutBack() = runTest {
        // By name since API 1.21.0: a pin keeps its spaces and its capitals, and goes back as it was.
        val before = api.tags().pinnedTags.map { it.name }
        val name = "Dev Test " + UUID.randomUUID().toString().take(6)
        val pin = Hashtags.spelling(name).getOrThrow()
        val pinned = api.setPinned(before + name, key()).pinnedTags
        assertTrue(pin in pinned, pinned.toString())
        assertEquals(before, api.setPinned(before, key()).pinnedTags.map { it.name })
    }

    /** A tag of its own each run, with a space and capitals, as the field takes one. */
    private fun aTag(what: String): Tag = Hashtags.spelling("DevServerTest $what " + UUID.randomUUID().toString().take(6)).getOrThrow()

    @Test
    fun aNoteKeepsItsTagsByNameAndAnEditSetsTheWholeList() = runTest {
        // API 1.21.0: a note's tags field by name, beside its #words; an edit sends the field's whole new list.
        val me = api.me()
        if (!HomeMove.atLeast(me.apiVersion, "1.21.0")) return@runTest
        val trip = aTag("Trip")
        val lake = aTag("Lake")
        val receipt = api.createNote(
            NoteRequest("DevServerTest a tagged note #devtest", writtenAt = Timestamp.now(), tags = listOf(trip.name)),
            key(),
        )
        try {
            val read = api.note(receipt.note.date, receipt.note.id)
            assertEquals(listOf(trip), read.fieldTags)
            assertTrue(read.tagList.any { it.key == "devtest" }, read.tagList.toString())
            // The whole new list, by name, as typed: one more, then none.
            val both = api.editNote(read.date, read.id, read.text, read.revision, tags = listOf(trip.name, lake.name), idempotencyKey = key())
            val twice = api.note(both.date, both.id)
            assertEquals(listOf(trip, lake), twice.fieldTags)
            assertEquals(null, twice.tagsSent(listOf(trip, lake)))
            val none = api.editNote(twice.date, twice.id, twice.text, twice.revision, tags = emptyList(), idempotencyKey = key())
            val bare = api.note(none.date, none.id)
            assertEquals(emptyList(), bare.fieldTags)
            assertEquals(listOf("devtest"), bare.tagList.map { it.key })
            // A refused name is the server's too.
            val long = assertFailsWith<ApiException> {
                api.editNote(bare.date, bare.id, bare.text, bare.revision, tags = listOf("x".repeat(Hashtags.MAX_TAG + 1)), idempotencyKey = key())
            }
            assertEquals(400, long.status)

            if (me.token.readsNotes) {
                // Found by its tag's key, and listed with every tag by its latest name.
                api.editNote(bare.date, bare.id, bare.text, bare.revision, tags = listOf(trip.name), idempotencyKey = key())
                assertTrue(api.search(tag = trip.key).items.any { it.id == receipt.note.id })
                val all = api.allTags().tags.single { it.tag == trip.key }
                assertEquals(trip, all.named)
                assertTrue(all.days >= 1)
            }
        } finally {
            api.deleteNote(receipt.note.date, receipt.note.id, key())
        }
    }

    @Test
    fun aStayIsTaggedFromItsRevisionAndItsTagFindsIt() = runTest {
        // API 1.21.0: a stay's whole list of tags, from the revision read; a stale one is refused. Its tag's
        // check-ins come a page at a time to the last.
        val me = api.me()
        if (!HomeMove.atLeast(me.apiVersion, "1.21.0") || "activity" !in me.token.scopes || "notes:write" !in me.token.scopes) return@runTest
        val lat = 45.6100 + Math.random() / 100
        val lon = -94.2400
        val stay = api.addCheckIn(
            CheckInReport(arrived = Timestamp.now(), lat = lat, lon = lon, accuracyM = 10.0, timeZone = "America/Chicago", source = "tap"),
            key(),
        ).checkIn
        val walk = aTag("Walk")
        val tagged = api.tagCheckIn(stay.id, listOf(walk), stay.revision, key())
        assertEquals(listOf(walk), tagged.tagList)
        val stale = assertFailsWith<ApiException> { api.tagCheckIn(stay.id, emptyList(), stay.revision, key()) }
        assertEquals("revision_mismatch", stale.code)
        assertTrue(api.checkInsTagged(walk.key).any { it.id == stay.id || stay.id in it.ids })
        assertTrue(MapTags.stays(listOf(tagged), walk.key).isNotEmpty())
        assertEquals(emptyList(), api.tagCheckIn(stay.id, emptyList(), tagged.revision, key()).tagList)
        assertTrue(api.checkInsTagged(walk.key).none { it.id == stay.id || stay.id in it.ids })
    }

    @Test
    fun aPlacesTagGoesOnItsNewCheckInsAndOnTheOldOnesForTheCountShown() = runTest {
        // Call 6: a remembered place's tags go on each new stop there; its check-ins already there are tagged only
        // when asked, for the count shown, and another count now is refused with that count.
        val me = api.me()
        if (!HomeMove.atLeast(me.apiVersion, "1.21.0") || !me.token.readsAll || "activity" !in me.token.scopes) return@runTest
        val lat = 45.6300 + Math.random() / 100
        val lon = -94.2600
        fun tap() = CheckInReport(arrived = Timestamp.now(), lat = lat, lon = lon, accuracyM = 10.0, timeZone = "America/Chicago", source = "tap")
        val before = api.addCheckIn(tap(), key()).checkIn
        val place = api.rememberPlace("DevServerTest " + UUID.randomUUID().toString().take(8), lat, lon, idempotencyKey = key())
        try {
            val cabin = aTag("Cabin")
            val saved = api.changePlace(place.id, tags = listOf(cabin), idempotencyKey = key())
            assertEquals(listOf(cabin), saved.tagList)
            assertEquals(1, saved.checkIns)
            // The one already there keeps its tags until asked.
            assertTrue(api.checkInsTagged(cabin.key).none { it.id == before.id || before.id in it.ids })
            val stale = assertFailsWith<ApiException> { api.tagCheckInsAt(place.id, listOf(cabin), 3, key()) }
            assertEquals("revision_mismatch", stale.code)
            assertEquals(1, stale.count)
            assertEquals(1, api.tagCheckInsAt(place.id, listOf(cabin), 1, key()))
            assertTrue(api.checkInsTagged(cabin.key).any { it.id == before.id || before.id in it.ids })
            // Taken off the place, the check-ins keep it.
            assertEquals(emptyList(), api.changePlace(place.id, tags = emptyList(), idempotencyKey = key()).tagList)
            assertTrue(api.checkInsTagged(cabin.key).any { it.id == before.id || before.id in it.ids })
            api.deleteCheckInsAt(place.id, 1, key())
        } finally {
            raw("DELETE", "v1/check-ins/places/${place.id}", null)
        }
    }

    @Test
    fun aFileIsAddedToANoteAlreadyFiled() = runTest {
        val folder = Files.createTempDirectory("dev server attach").toFile()
        val outbox = Outbox(File(folder, "outbox.json"))
        val first = api.createNote(NoteRequest("DevServerTest note for a file #devtest", writtenAt = Timestamp.now()), key())
        val png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
        )
        val file = QueuedFile(type = "image/png", name = "Later.png", size = png.size.toLong())
        outbox.keep(png.inputStream(), file)
        outbox.enqueue(OutboxOperation.Attach(first.note.date, first.note.id, listOf(file)))
        val report = outbox.flush(prepareAttach = { api.prepare(it, outbox) }) { api.perform(it) }
        assertEquals(null, report.stopped?.code, report.stopped?.message)
        assertTrue(report.sent.single().result is OutboxResult.Attached)
        assertEquals("image", api.note(first.note.date, first.note.id).media.single().kind)
        api.deleteNote(first.note.date, first.note.id, key())
    }

    @Test
    fun theTimelineAndADayReadWithATokenThatReadsNotes() = runTest {
        // API 1.15.0: only a token with notes:all or notes:read reads the days; any other is refused by scope.
        val me = api.me()
        if (!me.token.readsNotes) {
            val refused = assertFailsWith<ApiException> { api.days() }
            assertEquals("scope_required", refused.code)
            return@runTest
        }
        val receipt = api.createNote(NoteRequest("DevServerTest a day's note", writtenAt = Timestamp.now()), key())
        val page = api.days(limit = 3)
        val today = page.days.first()
        assertEquals(page.today, today.date)
        assertTrue(today.notesTotal >= 1)
        assertTrue(page.days.zipWithNext().all { (a, b) -> a.date > b.date }, "newest first")
        val day = api.day(receipt.note.date)
        assertEquals(today.version, day.version)
        assertTrue(day.notes.any { it.id == receipt.note.id })
        api.deleteNote(receipt.note.date, receipt.note.id, key())
    }

    @Test
    fun searchAndOnThisDayReadWithATokenThatReadsNotes() = runTest {
        // API 1.15.0: search and On this day read the whole archive, so they too need notes:all or notes:read.
        if (!api.me().token.readsNotes) {
            assertEquals("scope_required", assertFailsWith<ApiException> { api.search(q = "lake") }.code)
            return@runTest
        }
        val word = "zq" + UUID.randomUUID().toString().take(8)
        val receipt = api.createNote(NoteRequest("DevServerTest $word #devtest", writtenAt = Timestamp.now()), key())
        val found = api.search(q = word)
        assertEquals(1, found.total)
        assertEquals(receipt.note.id, found.items.single().id)
        assertTrue(api.search(tag = "devtest").items.any { it.id == receipt.note.id })
        val earlier = api.onThisDay()
        assertEquals(api.me().today, earlier.date)
        assertTrue(earlier.days.all { it.date < earlier.date && it.notesTotal >= it.notes.size })
        api.deleteNote(receipt.note.date, receipt.note.id, key())
    }

    @Test
    fun aStopFoundOnThePhoneAndATapGoThroughTheOutbox() = runTest {
        // API 1.18.0: a stop the detector found, its end, and a tap, each through the Outbox under its key.
        val start = java.time.Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        fun fix(minutes: Long, lat: Double) = Fix(lat, -93.2650, 15.0, start.plusSeconds(minutes * 60))
        var detector = StopDetector()
        val reports = mutableListOf<CheckInReport>()
        for (f in listOf(fix(0, 44.9778), fix(12, 44.9778), fix(30, 44.9900))) {
            val (next, found) = detector.next(f, "America/Chicago")
            detector = next
            reports += found
        }
        val tap = CheckInReport(arrived = Timestamp.now(), lat = 44.9537, lon = -93.0900, accuracyM = 10.0, timeZone = "America/Chicago", source = "tap")
        val outbox = Outbox(null)
        for (report in reports + tap) outbox.enqueue(OutboxOperation.CheckIn(report))
        val sent = outbox.flush { api.perform(it) }
        assertEquals(null, sent.stopped?.code, sent.stopped?.message)
        val stays = sent.sent.map { (it.result as OutboxResult.CheckedIn).checkIn }
        // The arrival and its end are one stay, filled in; the tap is its own.
        assertEquals(stays[0].id, stays[1].id)
        assertEquals(stays[1].left?.let { java.time.Instant.parse(it) }, start.plusSeconds(12 * 60))
        assertEquals("tap", stays[2].source)
        // A stay is named by any of its check-ins: one left by an earlier run at the same spot takes these in.
        val read = api.checkIns(limit = 100).items
        fun holding(id: String) = read.firstOrNull { id == it.id || id in it.ids }
        assertTrue(holding(stays[0].id) != null && holding(stays[2].id) != null, "both stays come back")
        assertTrue(CheckInMap.pins(read).sumOf { it.stays.size } == read.size)
        for (stay in stays.mapNotNull { holding(it.id) }.distinct()) raw("DELETE", "v1/check-ins/${stay.id}", null)
    }

    @Test
    fun notesWithAPlaceArePinsForTheMap() = runTest {
        // API 1.15.0: only a token that reads every note reads the map's pins.
        if (!api.me().token.readsNotes) {
            assertEquals("scope_required", assertFailsWith<ApiException> { api.mapPins() }.code)
            return@runTest
        }
        val here = api.here(Spot(44.9778, -93.2650, 15.0)).place
        val receipt = api.createNote(NoteRequest("DevServerTest a note with a place", writtenAt = Timestamp.now(), place = here), key())
        var after: String? = null
        val pins = mutableListOf<MapPin>()
        do {
            val page = api.mapPins(after)
            pins += page.pins
            after = page.next
        } while (after != null)
        val pin = pins.single { it.id == receipt.note.id }
        assertEquals(receipt.note.date, pin.date)
        assertTrue(pin.place.isNotBlank())
        api.deleteNote(receipt.note.date, receipt.note.id, key())
    }

    /** A request the client has no call for (deleting a check-in). Answers the id in the answer, if any. */
    private fun raw(method: String, path: String, body: String?): String {
        val connection = URI("$url/$path").toURL().openConnection() as java.net.HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Idempotency-Key", key())
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val text = (if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        check(status in 200..299) { "$method $path: $status" }
        return Regex(""""id"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1).orEmpty()
    }

    @Test
    fun aFileComesOffANoteAndTheLastOfAWordlessNoteStays() = runTest {
        val folder = Files.createTempDirectory("dev server remove").toFile()
        val outbox = Outbox(File(folder, "outbox.json"))
        val png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
        )
        val files = listOf("One.png", "Two.png").map { QueuedFile(type = "image/png", name = it, size = png.size.toLong()) }
        files.forEach { outbox.keep(png.inputStream(), it) }
        outbox.enqueueNote("", files = files)
        val filed = outbox.flush(prepare = { api.prepare(it, outbox) }) { api.perform(it) }
        val note = (filed.sent.single().result as OutboxResult.Filed).receipt.note
        val before = api.note(note.date, note.id)
        assertEquals(2, before.media.size)
        // API 1.17.0: one comes off through the Outbox, and the other keeps its number.
        val first = before.media.first().n
        outbox.enqueue(OutboxOperation.RemoveFile(note.date, note.id, first))
        val removed = outbox.flush { api.perform(it) }
        assertEquals(null, removed.stopped?.code, removed.stopped?.message)
        assertTrue(removed.sent.single().result is OutboxResult.FileRemoved)
        val after = api.note(note.date, note.id)
        assertEquals(listOf(before.media.last().n), after.media.map { it.n })
        // A note with no words keeps its last file.
        val last = assertFailsWith<ApiException> { api.removeFile(note.date, note.id, after.media.single().n, key()) }
        assertEquals("text_required", last.code)
        api.deleteNote(note.date, note.id, key())
    }

    @Test
    fun anIgnoredPlaceKeepsNoCheckInAndHidesItsOwnUntilUnignored() = runTest {
        // API 1.20.0: Ignore this location, and Delete existing check-ins here. Places change with notes:all.
        val me = api.me()
        if (!HomeMove.atLeast(me.apiVersion, HomeMove.API) || !me.token.readsAll || "activity" !in me.token.scopes) return@runTest
        // A spot of its own each run, far from the other tests' and the account's own.
        val lat = 45.5600 + Math.random() / 100
        val lon = -94.1600
        val name = "DevServerTest " + UUID.randomUUID().toString().take(8)
        fun tap() = CheckInReport(arrived = Timestamp.now(), lat = lat, lon = lon, accuracyM = 10.0, timeZone = "America/Chicago", source = "tap")
        val before = api.addCheckIn(tap(), key())
        assertTrue(before.kept)
        val place = api.rememberPlace(name, lat, lon, idempotencyKey = key())
        try {
            assertFalse(place.ignored)
            assertEquals(1, api.rememberedPlace(place.id).checkIns)
            suspend fun shown() = api.checkIns(limit = 100).items.any { before.checkIn.id == it.id || before.checkIn.id in it.ids }

            // Ignored: nothing new is kept, and the stay there hides, still counted.
            assertTrue(api.changePlace(place.id, ignored = true, idempotencyKey = key()).ignored)
            assertTrue(api.rememberedPlaces().single { it.id == place.id }.ignored)
            val after = api.addCheckIn(tap(), key())
            assertFalse(after.kept)
            assertFalse(shown())
            assertEquals(1, api.rememberedPlace(place.id).checkIns)
            // Through the Outbox, a stop not kept is done, not kept or retried.
            val outbox = Outbox(null)
            outbox.enqueue(OutboxOperation.CheckIn(tap()))
            val sent = outbox.flush { api.perform(it) }
            assertIs<OutboxResult.CheckInNotKept>(sent.sent.single().result)
            assertTrue(outbox.items.value.isEmpty())

            // Unignored: it shows again.
            assertFalse(api.changePlace(place.id, ignored = false, idempotencyKey = key()).ignored)
            assertTrue(shown())

            // Deleting asks for the count read; another is refused with the count now.
            val stale = assertFailsWith<ApiException> { api.deleteCheckInsAt(place.id, 5, key()) }
            assertEquals("revision_mismatch", stale.code)
            assertEquals(1, stale.count)
            assertEquals(1, api.deleteCheckInsAt(place.id, 1, key()))
            assertEquals(0, api.rememberedPlace(place.id).checkIns)
            assertFalse(shown())
            // The place stays.
            assertTrue(api.rememberedPlaces().any { it.id == place.id })
        } finally {
            raw("DELETE", "v1/check-ins/places/${place.id}", null)
        }
    }

    @Test
    fun aHomeOnThePhoneMovesToARememberedPlaceIgnored() = runTest {
        // Not at home from before API 1.20.0: the place within 100 m of home is ignored; with none, Home is
        // remembered there, ignored.
        val me = api.me()
        if (HomeMove.lack(me) != HomeMove.Lack.Nothing) return@runTest
        val lat = 45.5900 + Math.random() / 100
        val lon = -94.2000
        val near = api.rememberPlace("DevServerTest " + UUID.randomUUID().toString().take(8), lat + 0.0004, lon, idempotencyKey = key())
        try {
            val moved = HomeMove.move(api, Spot(lat, lon))
            assertEquals(near.id, moved.id)
            assertTrue(moved.ignored)
            // Asked again, as a lost answer would be: nothing more to do.
            assertEquals(near.id, HomeMove.move(api, Spot(lat, lon)).id)
        } finally {
            raw("DELETE", "v1/check-ins/places/${near.id}", null)
        }
        // Nothing near: Home is remembered, unless the account has a Home already, which this would join and ignore.
        if (api.rememberedPlaces().any { it.name.equals(HomeMove.NAME, ignoreCase = true) }) return@runTest
        val home = HomeMove.move(api, Spot(lat, lon))
        try {
            assertEquals(HomeMove.NAME, home.name)
            assertTrue(home.ignored)
        } finally {
            raw("DELETE", "v1/check-ins/places/${home.id}", null)
        }
    }
}
