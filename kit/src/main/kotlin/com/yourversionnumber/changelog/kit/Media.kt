package com.yourversionnumber.changelog.kit

import kotlinx.serialization.Serializable
import java.io.File
import java.io.OutputStream
import java.util.UUID

// Files on a note (docs/API.md, phase 3): photos, recordings and PDFs go up
// through a signed form, and the note names them in its `uploads`.

/** `POST /v1/uploads`: a form for one file of an exact size. */
@Serializable
data class UploadStart(val name: String? = null, val type: String, val size: Long)

/**
 * The signed form. Post `fields` and then the file, as `file`, to `url`
 * within `expiresIn` seconds. The token never goes with it.
 */
@Serializable
data class UploadForm(
    val upload: String,
    val type: String,
    val url: String,
    val fields: Map<String, String>,
    val expiresIn: Int,
)

/** A file posted to its form, as a note's `uploads` names it. */
@Serializable
data class UploadRef(val upload: String, val type: String, val name: String? = null)

/**
 * A file waiting on this phone for its note. The bytes live in the Outbox's
 * files folder under `id`; `upload` is set once they're posted, so a resend
 * names the same upload under the same idempotency key.
 */
@Serializable
data class QueuedFile(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val name: String,
    val size: Long,
    val upload: String? = null,
) {
    val fileName: String get() = "$id.${MediaType.fileExtension(type)}"
    val isPhoto: Boolean get() = type.startsWith("image/")
    val isRecording: Boolean get() = type.startsWith("audio/")
}

/** What Release Notes keeps on a note: photos, recordings and PDFs, each at most 50 MB, at most 20 a note. */
object MediaType {
    const val MAX_BYTES = 50L * 1024 * 1024
    const val MAX_FILES = 20

    /** Photos Release Notes thumbnails and shows in the email. Anything else becomes a JPEG first. */
    val webPhotos = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

    /** The type the bytes say they are, from their first bytes, for the types Release Notes keeps; null for anything else. */
    fun sniff(head: ByteArray): String? {
        fun at(i: Int, vararg bytes: Int) = head.size >= i + bytes.size && bytes.indices.all { head[i + it] == bytes[it].toByte() }
        fun ascii(from: Int, to: Int) = if (head.size >= to) String(head, from, to - from, Charsets.ISO_8859_1) else null
        return when {
            at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
            at(0, 0x89, 0x50, 0x4E, 0x47) -> "image/png"
            ascii(0, 4) == "GIF8" -> "image/gif"
            ascii(0, 4) == "RIFF" && ascii(8, 12) == "WEBP" -> "image/webp"
            ascii(0, 4) == "%PDF" -> "application/pdf"
            ascii(4, 8) == "ftyp" -> when (ascii(8, 12)) {
                "heic", "heix", "heim", "heis" -> "image/heic"
                "mif1", "msf1", "heif" -> "image/heif"
                // Not isom or mp42: those are as often video. A voice memo
                // from the recorder is named audio/mp4 where it's made.
                "M4A ", "M4B " -> "audio/mp4"
                else -> null
            }
            else -> null
        }
    }

    fun fileExtension(type: String): String = when (type) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "audio/mp4" -> "m4a"
        "audio/mpeg" -> "mp3"
        "application/pdf" -> "pdf"
        else -> "bin"
    }

    /** "2 photos, 1 voice memo", for a waiting note. */
    fun count(files: List<QueuedFile>): String {
        val photos = files.count { it.isPhoto }
        val memos = files.count { it.isRecording }
        val others = files.size - photos - memos
        fun say(n: Int, one: String, many: String) = when (n) { 0 -> null; 1 -> "1 $one"; else -> "$n $many" }
        return listOfNotNull(say(photos, "photo", "photos"), say(memos, "voice memo", "voice memos"), say(others, "file", "files"))
            .joinToString(", ")
    }
}

/** A `multipart/form-data` body for an upload form: every field, then the file last, as `file`, as S3's POST policy wants it. */
object Multipart {
    fun head(fields: Map<String, String>, name: String, type: String, boundary: String): ByteArray = buildString {
        for (key in fields.keys.sorted()) {
            append("--$boundary\r\nContent-Disposition: form-data; name=\"${escape(key)}\"\r\n\r\n${fields[key]}\r\n")
        }
        append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${escape(name)}\"\r\n")
        append("Content-Type: $type\r\n\r\n")
    }.toByteArray(Charsets.UTF_8)

    fun tail(boundary: String): ByteArray = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

    /** The body's length, so it can stream with a fixed length. */
    fun length(fields: Map<String, String>, file: File, name: String, type: String, boundary: String): Long =
        head(fields, name, type, boundary).size + file.length() + tail(boundary).size

    /** Writes the body, the file copied a piece at a time, so a 50 MB file never sits in memory. */
    fun write(fields: Map<String, String>, file: File, name: String, type: String, boundary: String, out: OutputStream) {
        out.write(head(fields, name, type, boundary))
        file.inputStream().use { it.copyTo(out, 1 shl 16) }
        out.write(tail(boundary))
    }

    /** A quote or a line break would end the header early. */
    private fun escape(s: String) = s.replace("\"", "%22").replace("\r", "").replace("\n", "")
}
