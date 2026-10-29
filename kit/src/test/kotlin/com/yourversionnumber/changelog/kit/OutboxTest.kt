package com.yourversionnumber.changelog.kit

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutboxTest {
    // A folder with a space in it, as an app's storage may have.
    private fun folder(): File = Files.createTempDirectory("outbox test").toFile()

    private fun receipt(id: String) = OutboxResult.Filed(NoteReceipt(NoteRef(id, "2026-10-29", "0.0.0", "r1")))

    @Test
    fun keepsWhatIsQueuedAcrossARelaunch() = runTest {
        val file = File(folder(), "outbox.json")
        val typed = OffsetDateTime.of(2026, 10, 28, 23, 58, 40, 123, ZoneOffset.ofHours(-5))
        val item = Outbox(file).enqueueNote("Ran the river loop #running", writtenAt = typed)
        val again = Outbox(file).items.value
        assertEquals(listOf(item), again)
        assertEquals("2026-10-28T23:58:40-05:00", (again[0].operation as OutboxOperation.Note).writtenAt)
    }

    @Test
    fun sendsOldestFirstUnderEachItemsOwnKey() = runTest {
        val outbox = Outbox(null)
        val a = outbox.enqueueNote("one")
        val b = outbox.enqueueNote("two")
        val keys = mutableListOf<String>()
        val report = outbox.flush { keys += it.idempotencyKey; receipt(it.id) }
        assertEquals(listOf(a.id, b.id), keys)
        assertEquals(2, report.sent.size)
        assertTrue(outbox.items.value.isEmpty())
    }

    @Test
    fun aRetryableFailureStopsAndKeepsTheKey() = runTest {
        val outbox = Outbox(null)
        val a = outbox.enqueueNote("one")
        outbox.enqueueNote("two")
        val report = outbox.flush { throw ApiException(0, "network", "offline") }
        assertEquals("network", report.stopped?.code)
        assertEquals(2, outbox.items.value.size)
        assertEquals(a.id, outbox.items.value[0].id)
        assertEquals(1, outbox.items.value[0].attempts)
        assertEquals(0, outbox.items.value[1].attempts)
    }

    @Test
    fun aRefusalIsSetAsideAndTheRestGo() = runTest {
        val outbox = Outbox(null)
        outbox.enqueueNote("")
        val b = outbox.enqueueNote("two")
        val report = outbox.flush {
            if (it.text == "") throw ApiException(400, "text_required", "Write something first.") else receipt(it.id)
        }
        assertNull(report.stopped)
        assertEquals(listOf(b.id), report.sent.map { it.item.id })
        assertEquals("Write something first.", outbox.items.value.single().refusal)
    }

    @Test
    fun aFixedNoteGoesAgainUnderANewKey() = runTest {
        val outbox = Outbox(null)
        val a = outbox.enqueueNote("")
        outbox.flush { throw ApiException(400, "text_required", "Write something first.") }
        outbox.retry(a.id, "Now with words")
        val fixed = outbox.items.value.single()
        assertNotEquals(a.id, fixed.id)
        assertNull(fixed.refusal)
        assertEquals("Now with words", fixed.text)
    }

    @Test
    fun signedOutStopsAndKeepsTheQueue() = runTest {
        val outbox = Outbox(null)
        outbox.enqueueNote("one")
        val report = outbox.flush { throw ApiException(401, "token_revoked", "This token was revoked.") }
        assertTrue(report.stopped!!.signedOut)
        assertEquals(1, outbox.items.value.size)
    }
}
