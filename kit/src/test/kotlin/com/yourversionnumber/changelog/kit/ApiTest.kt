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
    fun noServerIsANetworkFailure() = runTest {
        val gone = Api("http://127.0.0.1:1", token, "Changelog/0.0.0")
        val e = assertFailsWith<ApiException> { gone.me() }
        assertEquals("network", e.code)
        assertTrue(e.retryable)
    }
}
