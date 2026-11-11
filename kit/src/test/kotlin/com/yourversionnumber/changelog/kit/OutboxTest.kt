package com.yourversionnumber.changelog.kit

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutboxTest {
    init {
        English.use()
    }

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
    fun filesAddedWithARefusedEditWaitBehindItAndGoWhenItIsGone() = runTest {
        val outbox = Outbox(null, folder())
        val photo = QueuedFile(type = "image/jpeg", name = "a.jpg", size = 3)
        outbox.enqueue(OutboxOperation.Edit("2026-11-07", "note_1", "new words", "r1"), group = "save-1")
        outbox.enqueue(OutboxOperation.Attach("2026-11-07", "note_1", listOf(photo)), group = "save-1")
        val other = outbox.enqueueNote("unrelated")
        val sent = mutableListOf<String>()
        val report = outbox.flush(prepareAttach = { it.copy(files = it.files.map { f -> f.copy(upload = "u1") }) }) {
            if (it.operation is OutboxOperation.Edit) throw ApiException(409, "revision_mismatch", "Changed elsewhere.")
            sent += it.id
            receipt(it.id)
        }
        // The lone note goes; the files stay behind the refused edit, unsent and unprepared.
        assertEquals(listOf(other.id), sent)
        assertEquals(listOf(other.id), report.sent.map { it.item.id })
        val left = outbox.items.value
        assertEquals(2, left.size)
        assertTrue(outbox.isBehindRefusal(left[1]))
        assertNull((left[1].operation as OutboxOperation.Attach).files.single().upload)

        // Discarding the edit discards the files that went with it.
        outbox.discard(left[0].id)
        assertTrue(outbox.items.value.isEmpty())
    }

    @Test
    fun aStorageRefusalOfAnUploadIsTriedOnceMoreWithANewFormThenRefused() = runTest {
        val outbox = Outbox(null, folder())
        val photo = QueuedFile(type = "image/jpeg", name = "a.jpg", size = 3)
        val note = outbox.enqueue(OutboxOperation.Post("with a photo", writtenAt = "2026-11-07T08:00:00-06:00", files = listOf(photo)))
        var forms = 0
        val report = outbox.flush(prepare = { forms++; throw ApiException(422, "upload_failed", "Upload failed.", field = "uploads") }) { receipt(it.id) }
        assertNull(report.stopped)
        assertEquals(2, forms)
        assertEquals("Upload failed.", outbox.items.value.single().refusal)
        assertNotEquals(note.id, outbox.items.value.single().id)
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

    // Accounts: a write goes only to the account it was written under, as in the iPhone app.

    private val first = "acct_" + "a".repeat(32)
    private val second = "acct_" + "b".repeat(32)

    @Test
    fun aWriteCarriesTheAccountItWasWrittenUnderAcrossARelaunch() = runTest {
        val file = File(folder(), "outbox.json")
        var signedIn: String? = first
        val outbox = Outbox(file) { signedIn }
        val a = outbox.enqueueNote("one")
        signedIn = second
        val b = outbox.enqueue(OutboxOperation.Pins(listOf("running")))
        assertEquals(first, a.account)
        assertEquals(second, b.account)
        assertEquals(listOf(first, second), Outbox(file).items.value.map { it.account })
    }

    @Test
    fun aRunSendsOnlyItsOwnAccountsWritesAndLeavesTheRestAsTheyAre() = runTest {
        var signedIn: String? = first
        val outbox = Outbox(null) { signedIn }
        val a = outbox.enqueueNote("first's")
        signedIn = second
        val b = outbox.enqueueNote("second's")
        signedIn = null
        val unknown = outbox.enqueueNote("nobody knows whose")
        val sent = mutableListOf<String>()
        val report = outbox.flush(recipient = OutboxRecipient.Account(second)) { sent += it.id; receipt(it.id) }
        assertEquals(listOf(b.id), sent)
        assertNull(report.stopped)
        assertTrue(report.refused.isEmpty())
        // Held, not tried: no attempt counted, no refusal.
        assertEquals(listOf(a, unknown), outbox.items.value)
        assertTrue(outbox.items.value.all { it.isHeld(OutboxRecipient.Account(second)) })
        assertFalse(a.isHeld(OutboxRecipient.Account(first)))
    }

    @Test
    fun aServerThatNamesNoAccountSendsEverything() = runTest {
        var signedIn: String? = first
        val outbox = Outbox(null) { signedIn }
        outbox.enqueueNote("one")
        signedIn = null
        outbox.enqueueNote("two")
        val report = outbox.flush(recipient = OutboxRecipient.Anyone) { receipt(it.id) }
        assertEquals(2, report.sent.size)
        assertTrue(outbox.items.value.isEmpty())
    }

    @Test
    fun anEarlierBuildsQueueIsStampedWithTheFirstAccountSeen() = runTest {
        // As a build before accounts saved it: no account on either item.
        val file = File(folder(), "outbox.json")
        file.writeText(
            """[{"id":"7d3c1f0e-0000-4000-8000-000000000001","operation":{"type":"note","text":"one","writtenAt":"2026-11-07T08:00:00-06:00"},"queuedAt":"2026-11-07T08:00:00-06:00"},""" +
                """{"id":"7d3c1f0e-0000-4000-8000-000000000002","operation":{"type":"pins","pinned":["running"]},"queuedAt":"2026-11-07T08:01:00-06:00","attempts":2}]""",
        )
        val outbox = Outbox(file) { second }
        assertEquals(listOf(null, null), outbox.items.value.map { it.account })
        // Unstamped, they may be anyone's: held.
        assertTrue(outbox.items.value.all { it.isHeld(OutboxRecipient.Account(first)) })
        val fresh = outbox.enqueueNote("written now")
        assertEquals(2, outbox.stamp(first))
        assertEquals(listOf(first, first, second), Outbox(file).items.value.map { it.account })
        assertEquals(0, outbox.stamp(first))
        val sent = mutableListOf<String>()
        outbox.flush(recipient = OutboxRecipient.Account(first)) { sent += it.id; receipt(it.id) }
        assertEquals(2, sent.size)
        assertEquals(listOf(fresh.id), outbox.items.value.map { it.id })
    }

    @Test
    fun aHeldNoteSentToThisAccountGoesWithItsFilesPostedAgain() = runTest {
        var signedIn: String? = first
        val outbox = Outbox(null, folder()) { signedIn }
        val photo = QueuedFile(type = "image/jpeg", name = "photo.jpg", size = 3, upload = "upl_first")
        val post = outbox.enqueue(
            OutboxOperation.Post("with a photo", writtenAt = "2026-11-07T08:00:00-06:00", files = listOf(photo), reuploads = 1),
        )
        outbox.flush(recipient = OutboxRecipient.Account(first)) { throw ApiException(403, "insufficient_scope", "This token can't add files.") }
        signedIn = second
        val later = outbox.enqueueNote("second's")
        assertTrue(outbox.items.value.first().isHeld(OutboxRecipient.Account(second)))

        outbox.move(post.id, second)
        val moved = outbox.items.value.first()
        assertEquals(post.id, moved.id)
        assertEquals(second, moved.account)
        assertNull(moved.refusal)
        assertEquals(0, moved.attempts)
        val op = moved.operation as OutboxOperation.Post
        assertNull(op.files.single().upload)
        assertEquals(0, op.reuploads)

        // It goes in its place, its file posted again first.
        val prepared = mutableListOf<String>()
        val sent = mutableListOf<String>()
        outbox.flush(
            recipient = OutboxRecipient.Account(second),
            prepare = { p -> prepared += p.text; p.copy(files = p.files.map { it.copy(upload = "upl_second") }) },
        ) { sent += it.id; receipt(it.id) }
        assertEquals(listOf("with a photo"), prepared)
        assertEquals(listOf(post.id, later.id), sent)
    }

    @Test
    fun aChangeToAnotherAccountsNoteNeverMoves() = runTest {
        val outbox = Outbox(null) { first }
        val held = listOf(
            OutboxOperation.Edit("2026-11-07", "note_1", "new words", "r1"),
            OutboxOperation.Delete("2026-11-07", "note_1"),
            OutboxOperation.Attach("2026-11-07", "note_1", listOf(QueuedFile(type = "image/jpeg", name = "a.jpg", size = 3))),
            OutboxOperation.RemoveFile("2026-11-07", "note_1", 1),
        ).map { outbox.enqueue(it) }
        val movable = listOf(
            outbox.enqueueNote("a note"),
            outbox.enqueue(OutboxOperation.Pins(listOf("running"))),
            outbox.enqueue(OutboxOperation.CheckIn(CheckInReport(arrived = "2026-11-07T08:00:00-06:00", lat = 44.95, lon = -93.09, source = "tap"))),
        )
        assertTrue(held.none { it.canMove })
        assertTrue(movable.all { it.canMove })
        for (item in held + movable) outbox.move(item.id, second)
        assertEquals(List(held.size) { first } + List(movable.size) { second }, outbox.items.value.map { it.account })
    }
}
