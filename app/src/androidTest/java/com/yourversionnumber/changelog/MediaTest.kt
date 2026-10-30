package com.yourversionnumber.changelog

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What a share makes of files this phone has no samples of to hand: a video
 * (refused, with a message) and a photo that isn't one of Release Notes' four
 * kinds (made a JPEG), as a HEIC from a camera is. The BMP stands in for the
 * HEIC: both go the way of ImageDecoder and back out as a JPEG.
 */
@RunWith(AndroidJUnit4::class)
class MediaTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reader = MediaReader(context)

    private fun asset(name: String): Uri {
        val file = File(context.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(file)
    }

    @Test
    fun aSharedVideoIsRefusedWithAMessage() {
        try {
            reader.shared(asset("clip.mp4"), "video/mp4")
            fail("a video went on a note")
        } catch (e: NotKept) {
            assertEquals(context.getString(R.string.release_notes_doesnt_keep_video), e.message)
        }
    }

    @Test
    fun aPhotoOfAnotherKindBecomesAJpeg() {
        val picked = reader.shared(asset("frame.bmp"), "image/bmp")
        assertEquals("image/jpeg", picked.type)
        assertTrue(picked.file.length() > 0)
        picked.file.inputStream().use { assertEquals(0xFF, it.read()); assertEquals(0xD8, it.read()) }
    }
}
