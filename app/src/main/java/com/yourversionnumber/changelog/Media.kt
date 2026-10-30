package com.yourversionnumber.changelog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.yourversionnumber.changelog.kit.MediaType
import java.io.File
import java.io.InputStream
import java.util.UUID

/** What a file from the picker, the camera or a share became, ready to keep with a note. */
class Picked(val file: File, val type: String, val name: String?)

/** Why a file couldn't go on a note, in words for the owner. */
class NotKept(message: String) : Exception(message)

/**
 * Reads photos, recordings and PDFs from Android into a temporary file in
 * the shape Release Notes keeps: JPEG, PNG, GIF and WebP as they are,
 * anything else (a HEIC, say, which would get no thumbnail and never show in
 * the email) made a JPEG with its time and place kept.
 */
class MediaReader(private val context: Context) {
    private val scratch get() = File(context.cacheDir, "picked").apply { mkdirs() }

    /** A photo, from the picker or the camera. */
    fun photo(uri: Uri): Picked {
        val raw = copy(uri)
        val type = MediaType.sniff(head(raw))
        if (type in MediaType.webPhotos) return Picked(raw, type!!, displayName(uri))
        return try {
            Picked(asJpeg(raw), "image/jpeg", displayName(uri)?.substringBeforeLast('.')?.plus(".jpg"))
        } finally {
            raw.delete()
        }
    }

    /** A file shared from another app: a photo, a recording or a PDF, by what its bytes say. */
    fun shared(uri: Uri, declared: String?): Picked {
        if (declared?.startsWith("image/") == true) return photo(uri)
        val raw = copy(uri)
        val sniffed = MediaType.sniff(head(raw))
        val type = when {
            sniffed == "application/pdf" -> sniffed
            sniffed == "audio/mp4" -> sniffed
            // A recorder's .m4a often names its brand as an MP4's; the sharing app knows.
            declared == "audio/mp4" || declared == "audio/x-m4a" || declared == "audio/m4a" -> "audio/mp4"
            declared == "audio/mpeg" -> "audio/mpeg"
            sniffed != null && sniffed.startsWith("image/") -> return photo(uri).also { raw.delete() }
            else -> null
        }
        if (type == null) {
            raw.delete()
            throw NotKept(
                if (declared?.startsWith("video/") == true) say(R.string.release_notes_doesnt_keep_video)
                else say(R.string.release_notes_keeps_photos_voice),
            )
        }
        return Picked(raw, type, displayName(uri))
    }

    /** A small picture of a photo for the composer, without decoding it whole. */
    fun thumbnail(file: File, px: Int = 240): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** A file to record into or for the camera to write, in the app's own cache. */
    fun newScratch(extension: String) = File(scratch, "${UUID.randomUUID()}.$extension")

    private fun copy(uri: Uri): File {
        val out = newScratch("bin")
        val input: InputStream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        } ?: throw NotKept(say(R.string.that_file_couldnt_be_read))
        input.use { source ->
            out.outputStream().use { sink ->
                val buffer = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MediaType.MAX_BYTES) {
                        sink.close()
                        out.delete()
                        throw NotKept(say(R.string.that_file_is_over_50))
                    }
                    sink.write(buffer, 0, n)
                }
            }
        }
        return out
    }

    private fun head(file: File): ByteArray = file.inputStream().use { input ->
        val b = ByteArray(16)
        val n = input.read(b)
        if (n <= 0) ByteArray(0) else b.copyOf(n)
    }

    /** Decodes any photo Android can read and writes it as a JPEG, keeping its time, place and turn. */
    private fun asJpeg(raw: File): File {
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(raw)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: Exception) {
            throw NotKept(say(R.string.that_photo_couldnt_be_read))
        }
        val out = newScratch("jpg")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        runCatching {
            val from = ExifInterface(raw.path)
            val to = ExifInterface(out.path)
            // ImageDecoder has already applied the turn, so the JPEG stands upright.
            for (tag in keptTags) from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
            to.saveAttributes()
        }
        return out
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private companion object {
        val keptTags = listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
        )
    }
}
