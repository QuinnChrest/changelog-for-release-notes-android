package com.yourversionnumber.changelog.kit

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tags through `/v1` (API 1.21.0): a note's, a stay's and a remembered
 * place's, and every tag's totals, read and sent as the OpenAPI file has them.
 * Against a stand-in server on localhost, never Release Notes itself.
 */
class TagsApiTest {
    init {
        English.use()
    }

    private lateinit var server: HttpServer
    private val seen = mutableListOf<Pair<String, String>>()
    private val bodies = mutableListOf<String>()
    private var answer: (HttpExchange) -> Pair<Int, String> = { 200 to "{}" }

    private val api get() = Api("http://127.0.0.1:${server.address.port}", "rn_" + "t".repeat(43), "Changelog/0.0.0 (Android 16)")

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            seen += exchange.requestMethod to exchange.requestURI.toString()
            bodies += exchange.requestBody.readBytes().toString(Charsets.UTF_8)
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

    private fun body(i: Int = 0): JsonObject = Api.json.decodeFromString(JsonObject.serializer(), bodies[i])

    private fun JsonObject.names(key: String = "tags") = (this[key] as JsonArray).map { it.jsonPrimitive.content }

    private fun note(extra: String, text: String = "At the #lake") =
        Api.json.decodeFromString(NoteDetail.serializer(), """{"id":"t-1","date":"2026-11-08","text":"$text","revision":"r-1"$extra}""")

    private val stay =
        """{"id":"c-1","ids":["c-1"],"at":"2026-11-08T09:00:00-06:00","lat":47.9,"lon":-91.8,"day":"2026-11-08","label":"Cabin","place":"p-1","revision":1}"""

    private val trip = Tag("road-trip", "Road Trip")

    // Notes

    @Test
    fun aNotesTagsReadWithTheirNames() {
        val n = note(""","tags":["road-trip","lake"],"tag_names":["Road Trip","lake"]""")
        assertEquals(listOf(trip, Tag("lake", "lake")), n.tagList)
        assertEquals(listOf(trip), n.fieldTags)
    }

    /** A server before 1.21.0 sends keys alone: a #word is named as typed, any other tag by its key. */
    @Test
    fun aNoteFromBeforeNamesItsTagsFromItsWords() {
        assertEquals(listOf(Tag("lake", "lake")), note(""","tags":["lake"]""").tagList)
        val typed = note(""","tags":["москва"]""", text = "Back in #Москва")
        assertEquals(listOf(Tag("москва", "Москва")), typed.tagList)
        assertTrue(typed.fieldTags.isEmpty())
        assertEquals(listOf(Tag("lake", "lake")), note("").tagList)
    }

    @Test
    fun anEditSendsItsTagsByNameOrLeavesThemOut() = runTest {
        answer = { 200 to """{"note":{"id":"t-1","date":"2026-11-08","version":"4.5.136","revision":"r-2"}}""" }
        api.editNote("2026-11-08", "t-1", "At the lake", "r-1", tags = listOf("Road Trip", "🚗"), idempotencyKey = "k-1")
        api.editNote("2026-11-08", "t-1", "At the lake", "r-1", idempotencyKey = "k-2")
        assertEquals(listOf("PUT" to "/v1/notes/2026-11-08/t-1", "PUT" to "/v1/notes/2026-11-08/t-1"), seen)
        assertEquals(listOf("Road Trip", "🚗"), body(0).names())
        assertNull(body(1)["tags"], "no tags leaves them as they are")
    }

    @Test
    fun aNewNoteSendsItsFieldTags() = runTest {
        val outbox = Outbox(null)
        val tagged = outbox.enqueueNote("Off to the lake", tags = listOf(trip)).operation as OutboxOperation.Post
        assertEquals(listOf("Road Trip"), tagged.tags)
        val sent = Api.json.encodeToString(NoteRequest.serializer(), tagged.request)
        assertTrue(""""tags":["Road Trip"]""" in sent, sent)
        // With none it's the plain note it always was, and sends no tags.
        val bare = outbox.enqueueNote("Off to the lake").operation
        assertTrue(bare is OutboxOperation.Note)
        assertFalse("tags" in Api.json.encodeToString(NoteRequest.serializer(), NoteRequest("Off to the lake")))
    }

    // The edit sheet

    @Test
    fun aChangeOfTagsAloneIsAnEditWithTheWordsAsTheyAre() {
        val n = note(""","tags":["road-trip","lake"],"tag_names":["Road Trip","lake"]""")
        // The same tags, by key and name, are no change.
        assertNull(n.tagsSent(listOf(trip)))
        assertEquals(emptyList(), n.tagsSent(emptyList()))
        // A respelling is a change: the name is kept as typed.
        assertEquals(listOf("road trip"), n.tagsSent(listOf(Tag("road-trip", "road trip"))))
        assertNull(n.tagsRefusal(n.text, emptyList()))
    }

    @Test
    fun aNoteWithNoWordsTakesNoTags() {
        val n = Api.json.decodeFromString(NoteDetail.serializer(), """{"id":"t-3","date":"2026-11-08","text":"","revision":"r-1","files":1}""")
        assertEquals("Tags go on a note with words. Write some first.", n.tagsRefusal("", listOf(Tag("lake", "lake"))))
        assertNull(n.tagsRefusal("", emptyList()))
    }

    /** A queue written before tags keeps its edits: one without `tags` reads as one that leaves them as they are. */
    @Test
    fun anEditQueuedBeforeTagsStillReads() = runTest {
        val file = java.io.File(java.nio.file.Files.createTempDirectory("outbox tags").toFile(), "outbox.json")
        file.writeText(
            """[{"id":"q-1","operation":{"type":"edit","date":"2026-11-08","id":"t-1","text":"better","revision":"r-1"},"queuedAt":"2026-11-08T09:00:00-06:00"}]""",
        )
        assertEquals(OutboxOperation.Edit("2026-11-08", "t-1", "better", "r-1", tags = null), Outbox(file).items.value.single().operation)
        // One with tags keeps them across a relaunch.
        val now = OutboxOperation.Edit("2026-11-08", "t-1", "better", "r-1", tags = listOf("Road Trip"))
        val again = java.io.File(file.parentFile, "again.json")
        Outbox(again).enqueue(now)
        assertEquals(now, Outbox(again).items.value.single().operation)
    }

    @Test
    fun aQueuedEditSendsItsTags() = runTest {
        answer = { 200 to """{"note":{"id":"t-1","date":"2026-11-08","version":"4.5.136","revision":"r-2"}}""" }
        val outbox = Outbox(null)
        outbox.enqueue(OutboxOperation.Edit("2026-11-08", "t-1", "At the #lake", "r-1", tags = listOf("Road Trip")))
        val report = outbox.flush { api.perform(it) }
        assertEquals(1, report.sent.size)
        assertEquals(listOf("Road Trip"), body().names())
    }

    // Check-ins and places

    @Test
    fun aStaysTagsReadWithTheirNames() {
        val tagged = stay.replace(""""place":"p-1"""", """"place":"p-1","tags":["road-trip"],"tag_names":["Road Trip"]""")
        assertEquals(listOf(trip), Api.json.decodeFromString(CheckIn.serializer(), tagged).tagList)
        assertTrue(Api.json.decodeFromString(CheckIn.serializer(), stay).tagList.isEmpty())
    }

    @Test
    fun aPlacesTagsReadAndAreLeftOutWhenNone() {
        val place = Api.json.decodeFromString(
            RememberedPlace.serializer(),
            """{"id":"p-1","name":"Cabin","spots":[{"lat":47.9,"lon":-91.8}],"revision":5,"tags":["road-trip","🚗"],"tag_names":["Road Trip","🚗"]}""",
        )
        assertEquals(listOf(trip, Tag("🚗", "🚗")), place.tagList)
        val bare = Api.json.decodeFromString(RememberedPlace.serializer(), """{"id":"p-2","name":"Home","spots":[],"revision":1}""")
        assertTrue(bare.tagList.isEmpty())
    }

    @Test
    fun taggingAStaySendsItsWholeListAndNoName() = runTest {
        answer = { 200 to """{"check_in":$stay}""" }
        api.tagCheckIn("c-1", listOf(trip), revision = 1, idempotencyKey = "k-5")
        assertEquals(listOf("PATCH" to "/v1/check-ins/c-1"), seen)
        val sent = body()
        assertEquals(listOf("Road Trip"), sent.names())
        assertEquals(1, sent["revision"]!!.jsonPrimitive.int)
        assertFalse("name" in sent, "a name sent as null would take the stay's own name off")

        // A stale revision: a stay's revision now comes as a number, a note's as a string; either is the refusal.
        answer = { 409 to """{"error":{"code":"revision_mismatch","message":"That check-in changed since you read it. Read it again.","revision":2}}""" }
        val stale = assertFailsWith<ApiException> { api.tagCheckIn("c-1", emptyList(), revision = 1, idempotencyKey = "k-9") }
        assertEquals("revision_mismatch", stale.code)
        assertEquals("2", stale.revision)
        answer = { 409 to """{"error":{"code":"revision_mismatch","message":"That note changed.","revision":"r-7"}}""" }
        assertEquals("r-7", assertFailsWith<ApiException> { api.tagCheckIn("c-1", emptyList(), revision = 1, idempotencyKey = "k-10") }.revision)
    }

    @Test
    fun aPlacesTagsGoAsAWholeList() = runTest {
        answer = {
            200 to """{"place":{"id":"p-1","name":"Cabin","spots":[{"lat":47.9,"lon":-91.8}],"revision":6,"tags":["road-trip"],"tag_names":["Road Trip"],"check_ins":4}}"""
        }
        val place = api.changePlace("p-1", tags = listOf(trip), idempotencyKey = "k-6")
        assertEquals(4, place.checkIns)
        assertEquals(listOf("Road Trip"), place.tagList.map { it.name })
        assertEquals(listOf("PATCH" to "/v1/check-ins/places/p-1"), seen)
        assertEquals(setOf("tags"), body().keys)
        assertEquals(listOf("Road Trip"), body().names())
    }

    @Test
    fun taggingAPlacesStaysSendsTheCountShown() = runTest {
        answer = { 200 to """{"tagged":4}""" }
        assertEquals(4, api.tagCheckInsAt("p-1", listOf(trip), count = 4, idempotencyKey = "k-7"))
        assertEquals(listOf("PATCH" to "/v1/check-ins/places/p-1/check-ins"), seen)
        assertEquals(listOf("Road Trip"), body().names())
        assertEquals(4, body()["count"]!!.jsonPrimitive.int)

        answer = { 409 to """{"error":{"code":"revision_mismatch","message":"The check-ins there changed since you counted them.","count":6}}""" }
        val e = assertFailsWith<ApiException> { api.tagCheckInsAt("p-1", listOf(Tag("lake", "lake")), count = 4, idempotencyKey = "k-8") }
        assertEquals("revision_mismatch", e.code)
        assertEquals(6, e.count)
    }

    @Test
    fun checkInsNarrowToATag() = runTest {
        answer = { 200 to """{"items":[],"next":null}""" }
        api.checkIns(tag = "🚗-road-trip")
        val (method, uri) = seen.single()
        assertEquals("GET", method)
        val query = java.net.URI(uri).query.split("&")
        assertTrue("tag=🚗-road-trip" in query, uri)
    }

    /** Call 8: the map's Tag menu lists each tag once, by name, and a tag keeps the notes and the stays with it. */
    @Test
    fun theMapsTagKeepsNotesAndStaysWithIt() {
        fun stay(id: String, tags: String) = Api.json.decodeFromString(
            CheckIn.serializer(),
            stay.replace("\"c-1\"", "\"$id\"").removeSuffix("}") + tags + "}",
        )
        fun pin(id: String, tags: String) = Api.json.decodeFromString(
            MapPin.serializer(),
            """{"date":"2026-11-08","id":"$id","lat":47.9,"lon":-91.8$tags}""",
        )
        val stays = listOf(
            stay("c-1", ""","tags":["road-trip"],"tag_names":["Road Trip"]"""),
            stay("c-2", ""),
        )
        val pins = listOf(
            pin("n-1", ""","tags":["road-trip","lake"],"tag_names":["road trip","lake"]"""),
            pin("n-2", ""","tags":["lake"]"""),
        )
        // Each once, by the first name met, in the order of their names.
        assertEquals(listOf(Tag("lake", "lake"), trip), MapTags.tags(stays, pins))
        assertEquals(listOf("c-1"), MapTags.stays(stays, "road-trip").map { it.id })
        assertEquals(listOf("n-1"), MapTags.notes(pins, "road-trip").map { it.id })
        assertEquals(listOf("n-1", "n-2"), MapTags.notes(pins, "lake").map { it.id })
        assertTrue(MapTags.stays(stays, "lake").isEmpty())
        // None picked: everything.
        assertEquals(2, MapTags.stays(stays, null).size)
        assertEquals(2, MapTags.notes(pins, null).size)
    }

    /** A tag's page counts every stay, not the first 100 (from Codex's review of Changelog for iPhone #57). */
    @Test
    fun aTagsCheckInsAreReadToTheLastPage() = runTest {
        fun page(id: String, next: String?) =
            """{"items":[${stay.replace("c-1", id)}],"next":${next?.let { "\"$it\"" } ?: "null"}}"""
        answer = { exchange ->
            val before = exchange.requestURI.query.split("&").firstOrNull { it.startsWith("before=") }?.substringAfter("=")
            200 to when (before) {
                null -> page("c-1", "b1")
                "b1" -> page("c-2", "b2")
                else -> page("c-3", null)
            }
        }
        assertEquals(listOf("c-1", "c-2", "c-3"), api.checkInsTagged("lake").map { it.id })
        assertEquals(3, seen.size)
        assertTrue(seen.all { "tag=lake" in it.second })

        // A cursor that comes round again ends it.
        seen.clear()
        answer = { 200 to page("c-9", "same") }
        assertEquals(2, api.checkInsTagged("lake").size)
        assertEquals(2, seen.size)
    }

    // Every tag

    @Test
    fun everyTagReadsWithItsCounts() = runTest {
        answer = {
            200 to """{"tags":[
                {"tag":"road-trip","name":"Road Trip","notes":3,"days":2,"first":"2026-06-01","last":"2026-11-08","upcoming":1,"check_ins":2},
                {"tag":"lake","name":"lake","notes":1,"days":1,"first":"2026-07-04","last":"2026-07-04"},
                {"tag":"house","notes":0,"days":0,"upcoming":2}
            ]}"""
        }
        val all = api.allTags()
        assertEquals(listOf(trip, Tag("lake", "lake"), Tag("house", "house")), all.tags.map { it.named })
        assertEquals(2, all.tags[0].checkIns)
        assertEquals(1, all.tags[0].upcoming)
        assertNull(all.tags[1].checkIns)
        assertEquals(listOf("GET" to "/v1/tags/all"), seen)
    }

    @Test
    fun pinsGoByName() = runTest {
        answer = { 200 to """{"pinned":["road-trip"],"pinned_names":["Road Trip"],"recent":[{"tag":"lake","count":2,"name":"Lake"}],"window_days":30}""" }
        val tags = api.setPinned(listOf("Road Trip"), "k-9")
        assertEquals(listOf(trip), tags.pinnedTags)
        assertEquals("Lake", tags.recent.single().name)
        assertEquals(listOf("Road Trip"), body().names("pinned"))
    }

    @Test
    fun aTaggedRowSaysWhatChanged() {
        fun row(json: String) = Api.json.decodeFromString(ActivityItem.serializer(), json)
        val place = row("""{"id":"a1","kind":"checkin.tagged","at":"2026-11-08T15:00:00Z","place":"p-1","count":4}""")
        assertEquals("Tagged the check-ins at a remembered place", place.headline)
        assertEquals("4 check-ins tagged.", place.more)
        val one = row("""{"id":"a2","kind":"checkin.tagged","at":"2026-11-08T15:00:00Z","checkin":"c-1","day":"2026-11-07"}""")
        assertEquals("Changed the tags on a check-in", one.headline)
        assertNull(one.more)
    }
}
