package com.yourversionnumber.changelog.kit

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceLevelTest {
    private fun at(db: Double) = (32_767 * 10.0.pow(db / 20)).roundToInt()

    @Test
    fun silenceAndAnythingAtOrBelowFiftyDecibelsDownIsZero() {
        assertEquals(0.0, VoiceLevel.fromAmplitude(0))
        assertEquals(0.0, VoiceLevel.fromAmplitude(-1))
        assertEquals(0.0, VoiceLevel.fromAmplitude(at(-60.0)))
    }

    @Test
    fun aSoftVoiceIsLiftedAndFullScaleIsOne() {
        assertEquals(sqrt(0.2), VoiceLevel.fromAmplitude(at(-40.0)), 0.01)
        assertEquals(sqrt(0.6), VoiceLevel.fromAmplitude(at(-20.0)), 0.01)
        assertEquals(1.0, VoiceLevel.fromAmplitude(32_767))
    }
}
