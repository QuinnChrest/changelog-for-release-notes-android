package com.yourversionnumber.changelog.kit

import kotlinx.coroutines.test.runTest
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
        val edited = api.editNote(read.date, read.id, read.text + " (edited)", read.revision, key())
        assertEquals("DevServerTest note #devtest (edited)", api.note(edited.date, edited.id).text)
        val stale = assertFailsWith<ApiException> { api.editNote(read.date, read.id, "stale", read.revision, key()) }
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
        api.deleteNote(receipt.note.date, receipt.note.id, key())
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
        val rows = api.activity(kinds = ActivityNotices.families, limit = 20).items
        val row = rows.first { it.note == receipt.note.id }
        assertEquals("note.added", row.kind)
        assertTrue(ActivityNotices.isOwn(row, api.me().token.name))
        assertTrue("DevServerTest activity words" !in Api.json.encodeToString(ActivityItem.serializer(), row))
        api.deleteNote(receipt.note.date, receipt.note.id, key())
    }

    @Test
    fun tagsArePinnedAndPutBack() = runTest {
        val before = api.tags().pinned
        val pinned = api.setPinned(before + "devtest", key()).pinned
        assertTrue("devtest" in pinned)
        assertEquals(before, api.setPinned(before, key()).pinned)
    }
}
