package com.yourversionnumber.changelog

import android.content.Context
import android.media.MediaRecorder
import com.yourversionnumber.changelog.kit.VoiceLevel
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

    /** How loud it has been since the last reading, 0 to 1 (`VoiceLevel`); 0 when not recording. */
    fun level(): Double = VoiceLevel.fromAmplitude(runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0))

    /**
     * Starts recording; false if the microphone couldn't be had. It asks for
     * the voice microphone first, as a call has it: noise suppression and
     * gain control, Android's counterpart to the voice processing the
     * iPhone app records through and a browser's noise suppression on the
     * web, so a room's hum stays out of the bars and the memo. A phone that
     * won't give it records from the plain microphone.
     */
    fun start(onLongest: () -> Unit): Boolean {
        for (source in SOURCES) {
            try {
                recorder = begin(source, onLongest)
                startedAt = System.currentTimeMillis()
                return true
            } catch (e: Exception) {
                release()
                file.delete()
            }
        }
        return false
    }

    private fun begin(source: Int, onLongest: () -> Unit): MediaRecorder {
        val made = MediaRecorder(context)
        recorder = made
        return made.apply {
            setAudioSource(source)
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

        /** The voice microphone, then the plain one. */
        private val SOURCES = listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)
    }
}
