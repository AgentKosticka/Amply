package com.agentkosticka.amply.audio.session

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGainCompositionTest {
    @Test
    fun fractionalMediaFactorPreservesPerAppGain() {
        assertEquals(0.30f, composePlayerGain(baseGain = 0.60f, streamFactor = 0.50f), 0.0001f)
    }

    @Test
    fun unityFactorRestoresSavedPerAppGain() {
        assertEquals(0.36f, composePlayerGain(baseGain = 0.36f, streamFactor = 1f), 0.0001f)
    }

    @Test
    fun invalidOrOutOfRangeInputsCannotEscapePlayerRange() {
        assertEquals(1f, composePlayerGain(baseGain = 2f, streamFactor = 2f), 0.0001f)
        assertEquals(0f, composePlayerGain(baseGain = Float.NaN, streamFactor = 0.5f), 0.0001f)
    }
}
