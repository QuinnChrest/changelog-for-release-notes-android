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
import kotlin.test.assertTrue

/** The client against a stand-in server on localhost, never Release Notes itself. */
class ApiTest {
    init {
        English.use()
    }

    private lateinit var server: HttpServer
    private val seen = mutableListOf<HttpExchange>()
    private val bodies = mutableListOf<String>()
    private var answer: (HttpExchange) -> Pair<Int, String> = { 200 to "{}" }

    private val token = "rn_" + "t".repeat(43)
    private val api get() = Api("http://127.0.0.1:${server.address.port}", token, "Changelog/0.0.0 (Android 16)")

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            seen += exchange
            bodies += exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val (status, body) = answer(exchange)
            val bytes = body.toByteArray()
            if (status == 204) {
                exchange.sendResponseHeaders(204, -1)
            } else {
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun sendsANoteWithItsKeyAndNameInSnakeCase() = runTest {
        answer = {
            201 to """{"note":{"id":"t-1","date":"2026-10-28","version":"4.5.136","revision":"r","token":"Phone","files":0},"unlocked":[{"key":"api","name":"API release"}],"later":"ignored"}"""
        }
        val receipt = api.createNote(NoteRequest("Ran #running", writtenAt = "2026-10-28T23:58:40-05:00"), "key-1")
        assertEquals("4.5.136", receipt.note.version)
        assertEquals("API release", receipt.unlocked?.single()?.name)
        val request = seen.single()
        assertEquals("POST", request.requestMethod)
        assertEquals("/v1/notes", request.requestURI.path)
        assertEquals("Bearer $token", request.requestHeaders.getFirst("Authorization"))
        assertEquals("key-1", request.requestHeaders.getFirst("Idempotency-Key"))
        assertTrue(request.requestHeaders.getFirst("User-Agent").startsWith("Changelog/"))
        assertEquals("""{"text":"Ran #running","written_at":"2026-10-28T23:58:40-05:00"}""", bodies.single())
    }

    @Test
    fun readsMe() = runTest {
        answer = {
            200 to """{"today":"2026-10-29","version":"4.5.137","tz":"America/Chicago","send_time":"06:30","wrote_today":true,"streak":12,"paused_until":null,"status":"active","show_badges":true,"days":[{"date":"2026-10-30","version":"4.5.138"}],"token":{"name":"Terminal","scopes":["notes:write"],"expires_at":null},"api_version":"1.2.0"}"""
        }
        val me = api.me()
        assertEquals("06:30", me.sendTime)
        assertEquals("4.5.138", me.versionOn("2026-10-30"))
    }

    @Test
    fun anErrorKeepsItsCodeAndWaitTime() = runTest {
        answer = { exchange ->
            exchange.responseHeaders.add("Retry-After", "120")
            429 to """{"error":{"code":"rate_limited","message":"That's 100 notes today."}}"""
        }
        val e = assertFailsWith<ApiException> { api.createNote(NoteRequest("x"), "k") }
        assertEquals("rate_limited", e.code)
        assertEquals(120L, e.retryAfterSeconds)
        assertTrue(e.retryable)
    }

    @Test
    fun aRevokedTokenIsSignedOut() = runTest {
        answer = { 401 to """{"error":{"code":"token_revoked","message":"This token was revoked."}}""" }
        val e = assertFailsWith<ApiException> { api.me() }
        assertTrue(e.signedOut)
    }

    @Test
    fun revokingAnswersNothing() = runTest {
        answer = { 204 to "" }
        api.revokeToken()
        assertEquals("DELETE", seen.single().requestMethod)
        assertEquals("/v1/token", seen.single().requestURI.path)
    }

    @Test
    fun aFilesFormGoesToStorageWithoutTheToken() = runTest {
        answer = { exchange ->
            if (exchange.requestURI.path == "/v1/uploads") {
                201 to """{"upload":"up-1","type":"image/jpeg","url":"/dev-upload/","fields":{"key":"media/u/api/up-1","policy":"p"},"expires_in":300}"""
            } else {
                204 to ""
            }
        }
        val file = java.io.File.createTempFile("photo", ".jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, 1, 2, 3)) }
        val form = api.startUpload(UploadStart("Photo 1.jpg", "image/jpeg", file.length()))
        api.postFile(file, "Photo 1.jpg", form)
        assertEquals("""{"name":"Photo 1.jpg","type":"image/jpeg","size":6}""", bodies[0])
        val upload = seen[1]
        // The dev server's form is a path on itself.
        assertEquals("/dev-upload/", upload.requestURI.path)
        assertEquals(null, upload.requestHeaders.getFirst("Authorization"))
        assertTrue(upload.requestHeaders.getFirst("Content-Type").startsWith("multipart/form-data; boundary="))
        assertTrue(bodies[1].contains("name=\"key\"\r\n\r\nmedia/u/api/up-1"))
    }

    @Test
    fun aStorageRefusalIsWorthAnotherForm() = runTest {
        answer = { 403 to "<Error>AccessDenied</Error>" }
        val file = java.io.File.createTempFile("photo", ".jpg").apply { writeBytes(byteArrayOf(1)) }
        val e = assertFailsWith<ApiException> {
            api.postFile(file, "Photo 1.jpg", UploadForm("up-1", "image/jpeg", "/dev-upload/", emptyMap(), 300))
        }
        assertEquals("upload_failed", e.code)
        assertTrue(e.retryable)
    }

    private val placeJson =
        """{"place":{"name":"Minneapolis","region":"Minnesota","country":"United States","tz":"America/Chicago","district":"Old Town","lat":44.98,"lon":-93.26,"accuracy_m":20,"from":"device"},"nearby":[{"name":"Reading Room","lat":44.98,"lon":-93.2596,"distance_m":41}]}"""

    @Test
    fun hereNamesTheSpot() = runTest {
        answer = { 200 to placeJson }
        val here = api.here(Spot(44.98, -93.26, 20.0))
        assertEquals("Old Town", here.place.label)
        assertEquals("Reading Room", here.nearby.single().name)
        assertEquals("""{"lat":44.98,"lon":-93.26,"accuracy_m":20.0}""", bodies.single())
    }

    @Test
    fun aQueuedNotesSpotIsNamedAsItSends() = runTest {
        answer = { 200 to placeJson }
        val outbox = Outbox(null)
        val item = outbox.enqueueNote("Coffee", spot = Spot(44.98, -93.26, 20.0))
        val post = item.operation as OutboxOperation.Post
        assertTrue(!post.ready)
        val ready = api.prepare(post, outbox)
        assertTrue(ready.ready)
        assertEquals("Minneapolis", ready.request.place?.name)
        assertEquals(null, ready.request.uploads)
    }

    @Test
    fun aPlaceThatCantBeNamedIsLeftOff() = runTest {
        answer = { 429 to """{"error":{"code":"rate_limited","message":"That's 100 places today."}}""" }
        val outbox = Outbox(null)
        val post = outbox.enqueueNote("Coffee", spot = Spot(44.98, -93.26)).operation as OutboxOperation.Post
        val ready = api.prepare(post, outbox)
        assertTrue(ready.ready)
        assertEquals(null, ready.place)
        assertEquals(null, ready.spot)
    }

    @Test
    fun aPlaceAlreadyNamedGoesAsItCame() = runTest {
        val outbox = Outbox(null)
        val place = DevicePlace("Minneapolis", district = "Old Town", venue = "Reading Room", lat = 44.98, lon = -93.26)
        val post = outbox.enqueueNote("Coffee", place = place).operation as OutboxOperation.Post
        assertTrue(post.ready)
        assertEquals("Reading Room", post.request.place?.label)
    }

    @Test
    fun noServerIsANetworkFailure() = runTest {
        val gone = Api("http://127.0.0.1:1", token, "Changelog/0.0.0")
        val e = assertFailsWith<ApiException> { gone.me() }
        assertEquals("network", e.code)
        assertTrue(e.retryable)
    }

    @Test
    fun aLoneHalfOfAnEmojiBecomesAReplacementCharacter() {
        val high = Char(0xD83D)
        val low = Char(0xDE00)
        val gone = Char(0xFFFD)
        val whole = "ok $high$low done"
        assertEquals(whole, whole.wellFormed())
        assertEquals("cut $gone and $gone", "cut $high and $low".wellFormed())
        assertEquals("end $gone", "end $high".wellFormed())
    }
}
