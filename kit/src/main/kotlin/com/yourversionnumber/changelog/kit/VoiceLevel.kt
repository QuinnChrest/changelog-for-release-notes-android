package com.yourversionnumber.changelog.kit

import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How loud the microphone is, 0 (silence) to 1, for the bars under a voice
 * memo's clock, as the iPhone app and the web draw them: the 50 dB below
 * full scale make the span, and the square root lifts a soft voice off the
 * floor.
 */
object VoiceLevel {
    /** Bars kept: more than a phone's width shows. */
    const val KEPT = 120

    /** A reading every this many milliseconds, one bar each. */
    const val EVERY_MS = 50L

    private const val FLOOR_DB = -50.0
    private const val FULL_SCALE = 32_767.0

    /** `MediaRecorder.getMaxAmplitude()`'s 0 to 32,767 as 0 to 1. */
    fun fromAmplitude(amplitude: Int): Double {
        if (amplitude <= 0) return 0.0
        val db = 20 * log10(amplitude / FULL_SCALE)
        if (db <= FLOOR_DB) return 0.0
        return sqrt(min(1.0, (db - FLOOR_DB) / -FLOOR_DB))
    }
}
