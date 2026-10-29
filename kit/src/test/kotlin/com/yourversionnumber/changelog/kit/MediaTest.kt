package com.yourversionnumber.changelog.kit

import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun sniffsWhatReleaseNotesKeeps() {
        assertEquals("image/jpeg", MediaType.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("image/png", MediaType.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D)))
        assertEquals("image/webp", MediaType.sniff(ascii("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ")))
        assertEquals("application/pdf", MediaType.sniff(ascii("%PDF-1.7")))
        assertEquals("image/heic", MediaType.sniff(ascii("\u0000\u0000\u0000\u0018ftypheic")))
        assertEquals("audio/mp4", MediaType.sniff(ascii("\u0000\u0000\u0000\u0018ftypM4A ")))
        // An MP4 could be a video: not taken as a voice memo.
        assertNull(MediaType.sniff(ascii("\u0000\u0000\u0000\u0018ftypisom")))
        assertNull(MediaType.sniff(ascii("hello")))
    }

    @Test
    fun countsAWaitingNotesFiles() {
        val files = listOf(
            QueuedFile(type = "image/jpeg", name = "Photo 1.jpg", size = 1),
            QueuedFile(type = "image/jpeg", name = "Photo 2.jpg", size = 1),
            QueuedFile(type = "audio/mp4", name = "Voice memo.m4a", size = 1),
            QueuedFile(type = "application/pdf", name = "a.pdf", size = 1),
        )
        assertEquals("2 photos, 1 voice memo, 1 file", MediaType.count(files))
    }

    @Test
    fun aFormHasItsFieldsThenTheFileLast() {
        val file = File.createTempFile("photo", ".jpg").apply { writeBytes(ascii("JPEGBYTES")) }
        val out = ByteArrayOutputStream()
        val fields = mapOf("policy" to "p", "key" to "media/u/api/1")
        Multipart.write(fields, file, "Photo \"1\".jpg", "image/jpeg", "b", out)
        val body = out.toString(Charsets.UTF_8)
        assertEquals(Multipart.length(fields, file, "Photo \"1\".jpg", "image/jpeg", "b"), out.size().toLong())
        assertTrue(body.indexOf("name=\"key\"") < body.indexOf("name=\"policy\""))
        assertTrue(body.indexOf("name=\"policy\"") < body.indexOf("name=\"file\""))
        assertTrue(body.contains("filename=\"Photo %221%22.jpg\""))
        assertTrue(body.endsWith("JPEGBYTES\r\n--b--\r\n"))
    }

    // The Outbox with files

    private fun outbox(): Outbox {
        val folder = Files.createTempDirectory("outbox files").toFile()
        return Outbox(File(folder, "outbox.json"))
    }

    private suspend fun Outbox.photo(name: String = "Photo 1.jpg"): QueuedFile {
        val f = QueuedFile(type = "image/jpeg", name = name, size = 4)
        keep("JPEG".byteInputStream(), f)
        return f
    }

    private val receipt = OutboxResult.Filed(NoteReceipt(NoteRef("t-1", "2026-10-29", "0.0.0", "r1", files = 1)))

    @Test
    fun filesArePostedAndSavedBeforeTheNoteGoes() = runTest {
        val outbox = outbox()
        val photo = outbox.photo()
        outbox.enqueueNote("", files = listOf(photo))
        var sentWith: OutboxOperation.Post? = null
        val report = outbox.flush(
            prepare = { post -> post.copy(files = post.files.map { it.copy(upload = "u-1") }) },
        ) { item ->
            sentWith = item.operation as OutboxOperation.Post
            receipt
        }
        assertEquals(1, report.sent.size)
        assertEquals("u-1", sentWith!!.files.single().upload)
        // Words are optional with files; the request leaves them out.
        assertNull(sentWith!!.request.text)
        assertEquals(listOf(UploadRef("u-1", "image/jpeg", "Photo 1.jpg")), sentWith!!.request.uploads)
        // A sent note's bytes are gone from the phone.
        assertFalse(outbox.fileFor(photo)!!.exists())
    }

    @Test
    fun aResendAfterALostAnswerCarriesTheSameUploadsAndKey() = runTest {
        val outbox = outbox()
        outbox.enqueueNote("A walk", files = listOf(outbox.photo()))
        var forms = 0
        val prepare: suspend (OutboxOperation.Post) -> OutboxOperation.Post = { post ->
            forms++
            post.copy(files = post.files.map { it.copy(upload = "u-$forms") })
        }
        val first = outbox.flush(prepare) { throw ApiException(0, "network", "offline") }
        assertEquals("network", first.stopped?.code)
        val keyBefore = outbox.items.value.single().id
        val ready = outbox.items.value.single().operation as OutboxOperation.Post
        assertEquals("u-1", ready.files.single().upload)

        var key: String? = null
        outbox.flush(prepare) { key = it.idempotencyKey; receipt }
        assertEquals(1, forms)
        assertEquals(keyBefore, key)
    }

    @Test
    fun goneUploadsAreMadeAgainOnceUnderANewKey() = runTest {
        val outbox = outbox()
        val first = outbox.enqueueNote("A walk", files = listOf(outbox.photo()))
        var forms = 0
        val keys = mutableListOf<String>()
        val report = outbox.flush(
            prepare = { post -> forms++; post.copy(files = post.files.map { it.copy(upload = "u-$forms") }) },
        ) { item ->
            keys += item.idempotencyKey
            if (keys.size == 1) throw ApiException(400, "invalid_request", "That upload is gone.", field = "uploads") else receipt
        }
        assertEquals(1, report.sent.size)
        assertEquals(2, forms)
        assertEquals(first.id, keys[0])
        assertNotEquals(keys[0], keys[1])
    }

    @Test
    fun discardingANoteTakesItsFilesOffThePhone() = runTest {
        val outbox = outbox()
        val photo = outbox.photo()
        val item = outbox.enqueueNote("", files = listOf(photo))
        outbox.discard(item.id)
        assertFalse(outbox.fileFor(photo)!!.exists())
    }

    @Test
    fun aSweepKeepsTheQueuesAndTheDraftsFiles() = runTest {
        val outbox = outbox()
        val queued = outbox.photo("Photo 1.jpg")
        val draft = outbox.photo("Photo 2.jpg")
        val stray = outbox.photo("Photo 3.jpg")
        outbox.enqueueNote("", files = listOf(queued))
        outbox.sweep(keeping = setOf(draft.id))
        assertTrue(outbox.fileFor(queued)!!.exists())
        assertTrue(outbox.fileFor(draft)!!.exists())
        assertFalse(outbox.fileFor(stray)!!.exists())
    }

    @Test
    fun aMissingFileIsARefusalToFix() = runTest {
        val outbox = outbox()
        val photo = outbox.photo()
        outbox.enqueueNote("Words too", files = listOf(photo))
        outbox.removeFiles(listOf(photo))
        val api = Api("http://127.0.0.1:1", "rn_" + "t".repeat(43), "Changelog/0.0.0")
        val report = outbox.flush(prepare = { api.prepare(it, outbox) }) { receipt }
        assertEquals(Api.FILE_MISSING, report.refused.single().refusal)
        // Fixed, it goes as the words alone.
        outbox.retry(outbox.items.value.single().id, "Words too")
        assertTrue(outbox.items.value.single().files.isEmpty())
    }
}
