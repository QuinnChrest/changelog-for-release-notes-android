package com.yourversionnumber.changelog

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * A voice memo: AAC in an .m4a, mono, which Release Notes keeps as
 * `audio/mp4`. At 64 kbit/s the 50 MB a file may be is over an hour and a
 * half, so recording stops itself at 100 minutes.
 */
class Recorder(private val context: Context, val file: File) {
    private var recorder: MediaRecorder? = null
    private var startedAt = 0L
    private var finished = false

    val elapsedMillis: Long get() = if (recorder == null) 0 else System.currentTimeMillis() - startedAt

    /** Starts recording; false if the microphone couldn't be had. */
    fun start(onLongest: () -> Unit): Boolean = try {
        recorder = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(64_000)
            setMaxDuration(LONGEST_MS)
            setOutputFile(file.path)
            setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onLongest() }
            prepare()
            start()
        }
        startedAt = System.currentTimeMillis()
        true
    } catch (e: Exception) {
        release()
        file.delete()
        false
    }

    /** Stops and answers the memo, or null if nothing usable was recorded. The memo is the caller's from here. */
    fun stop(): File? {
        if (finished) return null
        finished = true
        val ok = runCatching { recorder?.stop() }.isSuccess
        release()
        return if (ok && file.length() > 0) file else null.also { file.delete() }
    }

    /** Throws the recording away, unless it was already stopped and handed on. */
    fun cancel() {
        if (finished) return
        finished = true
        runCatching { recorder?.stop() }
        release()
        file.delete()
    }

    private fun release() {
        recorder?.release()
        recorder = null
    }

    companion object {
        const val LONGEST_MS = 100 * 60 * 1000
    }
}
