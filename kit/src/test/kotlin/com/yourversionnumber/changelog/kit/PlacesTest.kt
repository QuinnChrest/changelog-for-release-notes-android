package com.yourversionnumber.changelog.kit

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Remembered places, ignoring one, and moving Not at home onto one (API 1.20.0), against a stand-in server. */
class PlacesTest {
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

    private val home = Spot(44.9778, -93.2650)

    private fun place(id: String, name: String, lat: Double, lon: Double, ignored: Boolean? = null) =
        """{"id":"$id","name":"$name","spots":[{"lat":$lat,"lon":$lon}]""" +
            (ignored?.let { ""","ignored":$it""" } ?: "") + ""","revision":1}"""

    private fun places(vararg p: String) = """{"places":[${p.joinToString(",")}]}"""

    @Test
    fun aPlaceReadsWithOrWithoutIgnored() {
        val before = Api.json.decodeFromString(RememberedPlace.serializer(), place("plc_1", "Cafe", 44.0, -93.0))
        assertFalse(before.ignored)
        assertNull(before.checkIns)
        val now = Api.json.decodeFromString(
            RememberedPlaceAnswer.serializer(),
            """{"place":{"id":"plc_1","name":"Cafe","spots":[{"lat":44.0,"lon":-93.0}],"ignored":true,"revision":2,"check_ins":7}}""",
        ).place
        assertTrue(now.ignored)
        assertEquals(7, now.checkIns)
    }

    @Test
    fun aCheckInIsKeptUnlessTheServerSaysNot() {
        val stay = """{"id":"c1","at":"2026-11-08T12:00:00Z","lat":44.0,"lon":-93.0,"day":"2026-11-08","label":"Cafe"}"""
        assertTrue(Api.json.decodeFromString(CheckInWritten.serializer(), """{"check_in":$stay}""").kept)
        assertTrue(Api.json.decodeFromString(CheckInWritten.serializer(), """{"check_in":$stay,"kept":true}""").kept)
        assertFalse(Api.json.decodeFromString(CheckInWritten.serializer(), """{"check_in":$stay,"kept":false}""").kept)
    }

    @Test
    fun aCheckInNotKeptIsDoneAndNeverKept() = runTest {
        val stay = """{"id":"c1","at":"2026-11-08T12:00:00Z","lat":44.0,"lon":-93.0,"day":"2026-11-08","label":"Uptown"}"""
        answer = { 200 to """{"check_in":$stay,"kept":false}""" }
        val outbox = Outbox(null)
        outbox.enqueue(OutboxOperation.CheckIn(CheckInReport(arrived = "2026-11-08T12:00:00Z", lat = 44.0, lon = -93.0, source = "visit")))
        val report = outbox.flush { api.perform(it) }
        assertNull(report.stopped)
        assertEquals(OutboxResult.CheckInNotKept(null), report.sent.single().result)
        assertTrue(outbox.items.value.isEmpty())
        // Named by the ignored place itself: the name comes too, for a tap's words.
        val named = """{"id":"c2","at":"2026-11-08T12:00:00Z","lat":44.0,"lon":-93.0,"day":"2026-11-08","label":"Home","named_by":"place","place":"plc_1"}"""
        answer = { 200 to """{"check_in":$named,"kept":false}""" }
        outbox.enqueue(OutboxOperation.CheckIn(CheckInReport(arrived = "2026-11-08T12:00:00Z", lat = 44.0, lon = -93.0, source = "tap")))
        assertEquals(OutboxResult.CheckInNotKept("Home"), outbox.flush { api.perform(it) }.sent.single().result)
    }

    @Test
    fun aPlaceIsIgnoredAndItsCheckInsCountedAndDeleted() = runTest {
        answer = { exchange ->
            when (exchange.requestMethod) {
                "PATCH" -> 200 to """{"place":${place("plc_1", "Cafe", 44.0, -93.0, ignored = true)}}"""
                "GET" -> 200 to """{"place":{"id":"plc_1","name":"Cafe","spots":[],"ignored":true,"check_ins":3}}"""
                else -> 200 to """{"deleted":3}"""
            }
        }
        assertTrue(api.changePlace("plc_1", ignored = true, idempotencyKey = "k1").ignored)
        assertEquals(3, api.rememberedPlace("plc_1").checkIns)
        assertEquals(3, api.deleteCheckInsAt("plc_1", 3, "k2"))
        assertEquals(
            listOf(
                "PATCH" to "/v1/check-ins/places/plc_1",
                "GET" to "/v1/check-ins/places/plc_1",
                "DELETE" to "/v1/check-ins/places/plc_1/check-ins?count=3",
            ),
            seen,
        )
        // Only what changes is sent: no name, so a rename can't ride along.
        assertEquals("""{"ignored":true}""", bodies[0])
    }

    @Test
    fun aDeleteWithAStaleCountSaysTheCountNow() = runTest {
        answer = { 409 to """{"error":{"code":"revision_mismatch","message":"The check-ins there changed.","count":5}}""" }
        val e = assertFailsWith<ApiException> { api.deleteCheckInsAt("plc_1", 3, "k1") }
        assertEquals("revision_mismatch", e.code)
        assertEquals(5, e.count)
    }

    @Test
    fun homeNearARememberedPlaceIgnoresThatPlace() = runTest {
        // 60 m from home, and a farther one 300 m off: the near one is home.
        val near = place("plc_near", "Our house", 44.9783, -93.2650)
        val far = place("plc_far", "Neighbours", 44.9805, -93.2650)
        assertIs<HomeMove.Plan.Ignore>(HomeMove.plan(home, Api.json.decodeFromString(RememberedPlaces.serializer(), places(far, near)).places)).also {
            assertEquals("plc_near", it.place.id)
        }
        answer = { exchange ->
            if (exchange.requestMethod == "GET") 200 to places(far, near)
            else 200 to """{"place":${place("plc_near", "Our house", 44.9783, -93.2650, ignored = true)}}"""
        }
        val moved = HomeMove.move(api, home) { "k1" }
        assertEquals("plc_near", moved.id)
        assertEquals(listOf("GET" to "/v1/check-ins/places", "PATCH" to "/v1/check-ins/places/plc_near"), seen)
        assertEquals("""{"ignored":true}""", bodies[1])
    }

    @Test
    fun homeWithNothingRememberedNearIsRememberedAsHomeIgnored() = runTest {
        val far = place("plc_far", "Office", 44.9900, -93.2650)
        assertEquals(HomeMove.Plan.Remember, HomeMove.plan(home, Api.json.decodeFromString(RememberedPlaces.serializer(), places(far)).places))
        answer = { exchange ->
            if (exchange.requestMethod == "GET") 200 to places(far)
            else 201 to """{"place":${place("plc_home", "Home", 44.9778, -93.2650, ignored = true)}}"""
        }
        assertEquals("plc_home", HomeMove.move(api, home) { "k1" }.id)
        assertEquals(listOf("GET" to "/v1/check-ins/places", "POST" to "/v1/check-ins/places"), seen)
        assertEquals("""{"name":"Home","lat":44.9778,"lon":-93.265,"ignored":true}""", bodies[1])
    }

    @Test
    fun homeAtAPlaceAlreadyIgnoredAsksNothingMore() = runTest {
        answer = { 200 to places(place("plc_near", "Our house", 44.9778, -93.2650, ignored = true)) }
        assertEquals("plc_near", HomeMove.move(api, home).id)
        assertEquals(listOf("GET" to "/v1/check-ins/places"), seen)
    }

    @Test
    fun aFailureLeavesHomeOnThePhone() = runTest {
        // Refused (a token without notes:all), or an older server that drops `ignored`: either way it throws,
        // and the app keeps its home and its filter.
        answer = { exchange ->
            if (exchange.requestMethod == "GET") 200 to places()
            else 403 to """{"error":{"code":"scope_required","message":"This needs notes:all."}}"""
        }
        assertEquals("scope_required", assertFailsWith<ApiException> { HomeMove.move(api, home) }.code)
        answer = { exchange ->
            if (exchange.requestMethod == "GET") 200 to places()
            else 201 to """{"place":${place("plc_home", "Home", 44.9778, -93.2650)}}"""
        }
        val e = assertFailsWith<ApiException> { HomeMove.move(api, home) }
        assertEquals("not_ignored", e.code)
        assertEquals("The server didn't ignore the place, so home stays on this phone for now.", e.message)
        server.stop(0)
        assertEquals("network", assertFailsWith<ApiException> { HomeMove.move(api, home) }.code)
        start()
    }

    @Test
    fun movingHomeNeedsBothScopesAndAServerThatIgnores() {
        fun me(scopes: List<String>, api: String) = Me(today = "2026-11-08", version = "4.5.147", tz = "America/Chicago", token = TokenInfo("Pixel", scopes), apiVersion = api)
        assertEquals(HomeMove.Lack.Nothing, HomeMove.lack(me(listOf("notes:all", "activity"), "1.20.0")))
        assertEquals(HomeMove.Lack.Nothing, HomeMove.lack(me(listOf("notes:all", "activity"), "1.21.3")))
        assertEquals(HomeMove.Lack.Scopes, HomeMove.lack(me(listOf("notes:all"), "1.20.0")))
        assertEquals(HomeMove.Lack.Scopes, HomeMove.lack(me(listOf("notes:write", "activity"), "1.20.0")))
        assertEquals(HomeMove.Lack.Server, HomeMove.lack(me(listOf("notes:all", "activity"), "1.19.2")))
        assertTrue(HomeMove.atLeast("2.0.0", "1.20.0"))
        assertFalse(HomeMove.atLeast("1.9.9", "1.20.0"))
        assertTrue(HomeMove.atLeast("1.20", "1.20.0"))
    }
}
