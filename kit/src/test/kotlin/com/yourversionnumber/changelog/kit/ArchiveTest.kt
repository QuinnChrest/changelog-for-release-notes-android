package com.yourversionnumber.changelog.kit

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import java.time.YearMonth
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What came with reading the archive and taking a file off (API 1.13.0 to 1.17.0): search, On this day,
 * removing one file through the Outbox, the weeks and days lived, Upcoming's countdowns and months, and what
 * an Activity row opens. Against a stand-in server on localhost, never Release Notes itself.
 */
class ArchiveTest {
    init {
        English.use()
    }

    private lateinit var server: HttpServer
    private val seen = mutableListOf<HttpExchange>()
    private var answer: (HttpExchange) -> Pair<Int, String> = { 200 to "{}" }
    private val api get() = Api("http://127.0.0.1:${server.address.port}", "rn_" + "t".repeat(43), "Changelog/0.0.0 (Android 16)")

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            seen += exchange
            exchange.requestBody.readBytes()
            val (status, body) = answer(exchange)
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private val note = """{"id":"n1","date":"2025-11-07","text":"Lake day","revision":"r1","media":[],"mine":false,"source":"web","version":"4.4.140"}"""

    @Test
    fun searchSendsWordsOrATagAndReadsThePage() = runTest {
        answer = { 200 to """{"terms":["lake"],"tag":null,"from":"1981-06-20","to":"2026-11-07","total":3,"items":[$note],"next":"c2"}""" }
        val page = api.search(q = " lake ", after = "c1")
        assertEquals(3, page.total)
        assertEquals("Lake day", page.items.single().text)
        assertEquals("c2", page.next)
        val query = seen.last().requestURI.rawQuery
        assertTrue("q=lake" in query && "after=c1" in query, query)
        assertEquals("/v1/search", seen.last().requestURI.path)

        api.search(tag = "#family")
        val tagged = seen.last().requestURI.rawQuery
        assertTrue("tag=family" in tagged && "q=" !in tagged, tagged)
    }

    @Test
    fun onThisDayReadsEachEarlierDay() = runTest {
        answer = { 200 to """{"date":"2026-11-07","version":"4.5.146","days":[{"date":"2025-11-07","version":"4.4.146","notes_total":4,"notes":[$note]}],"next":"2025-11-07"}""" }
        val page = api.onThisDay(date = "2026-11-07", limit = 5)
        val day = page.days.single()
        assertEquals(4, day.notesTotal)
        assertEquals(1, day.notes.size)
        assertEquals("2025-11-07", page.next)
        assertEquals("/v1/on-this-day", seen.last().requestURI.path)
        assertTrue("date=2026-11-07" in seen.last().requestURI.rawQuery)
    }

    @Test
    fun aFileComesOffThroughTheOutboxUnderItsKey() = runTest {
        answer = { 200 to """{"note":{"id":"n1","date":"2026-11-07","version":"4.5.146","revision":"r2","files":1}}""" }
        val outbox = Outbox(null)
        val item = outbox.enqueue(OutboxOperation.RemoveFile("2026-11-07", "n1", 2))
        val report = outbox.flush { api.perform(it) }
        val removed = assertIs<OutboxResult.FileRemoved>(report.sent.single().result)
        assertEquals("r2", removed.note.revision)
        val request = seen.single()
        assertEquals("DELETE", request.requestMethod)
        assertEquals("/v1/notes/2026-11-07/n1/media/2", request.requestURI.path)
        assertEquals(item.id, request.requestHeaders.getFirst("Idempotency-Key"))
        assertTrue(outbox.items.value.isEmpty())
    }

    @Test
    fun theLastFileOfANoteWithNoWordsIsRefusedAndKept() = runTest {
        answer = { 400 to """{"error":{"code":"text_required","message":"A note with no words keeps its last file."}}""" }
        val outbox = Outbox(null)
        outbox.enqueue(OutboxOperation.RemoveFile("2026-11-07", "n1", 1))
        val report = outbox.flush { api.perform(it) }
        assertEquals("A note with no words keeps its last file.", report.refused.single().refusal)
    }

    @Test
    fun livedReadsAsTheWebSaysIt() {
        val me = Api.json.decodeFromString(
            Me.serializer(),
            """{"today":"2026-11-07","version":"4.5.146","tz":"America/Chicago","token":{"name":"Phone","scopes":[]},"api_version":"1.18.2","lived":{"week":2369,"day":16583}}""",
        )
        assertEquals("Week 2,369 — Day 16,583", me.lived?.text)
        val older = Api.json.decodeFromString(
            Me.serializer(),
            """{"today":"2026-11-07","version":"4.5.146","tz":"America/Chicago","token":{"name":"Phone","scopes":[]},"api_version":"1.12.0"}""",
        )
        assertNull(older.lived)
    }

    private fun entry(next: String, status: String = "upcoming") = UpcomingEntry(
        id = next, revision = 1, next = next, status = status, line = "x", kind = UpcomingKind.Event, title = "x", date = next,
    )

    @Test
    fun countdownsAreWholeDaysThenWeeks() {
        val today = "2026-11-07"
        assertEquals(1, Countdown.of(entry("2026-11-08"), today)?.days)
        assertEquals(false, Countdown.of(entry("2026-11-20"), today)?.inWeeks)
        val far = Countdown.of(entry("2026-11-30"), today)!!
        assertEquals(23, far.days)
        assertEquals(3, far.weeks)
        assertEquals(2, far.rest)
        assertTrue(far.inWeeks)
        // Over a clock change still whole days.
        assertEquals(7, Countdown.of(entry("2027-03-15"), "2027-03-08")?.days)
        assertNull(Countdown.of(entry("2026-11-07", "today"), today))
        assertNull(Countdown.of(entry("2026-11-07", "ongoing"), today))
    }

    @Test
    fun datesGoUnderTheirMonthsInOrder() {
        val list = UpcomingList("2026-11-07", listOf(entry("2026-11-07", "ongoing"), entry("2026-11-20"), entry("2027-01-02"), entry("2027-01-30")))
        val months = list.byMonth()
        assertEquals(listOf(YearMonth.of(2026, 11), YearMonth.of(2027, 1)), months.map { it.first })
        assertEquals(listOf(2, 2), months.map { it.second.size })
    }

    private fun row(kind: String, outcome: String? = null, tokenId: String? = null) = ActivityItem(
        id = "a", at = "2026-11-07T12:00:00Z", kind = kind, via = "token", tokenId = tokenId, day = "2026-11-07", note = "n1", outcome = outcome,
    )

    @Test
    fun activityRowsOpenTheirNotesWhereTheTokenMayRead() {
        val reader = TokenInfo("Phone", listOf("notes:write", "notes:all"), id = "me")
        val writer = TokenInfo("Phone", listOf("notes:write"), id = "me")
        assertEquals(ActivityOpens.Note("2026-11-07", "n1"), row("transcript.added").opens(reader))
        assertEquals(ActivityOpens.Day("2026-11-07"), row("mail", "filed").opens(reader))
        assertNull(row("note.deleted").opens(reader))
        assertNull(row("mail", "failed").opens(reader))
        // Without reading every note: its own notes only, and no days.
        assertEquals(ActivityOpens.Note("2026-11-07", "n1"), row("note.edited", tokenId = "me").opens(writer))
        assertNull(row("note.edited", tokenId = "other").opens(writer))
        assertNull(row("mail", "filed").opens(writer))
    }
}
