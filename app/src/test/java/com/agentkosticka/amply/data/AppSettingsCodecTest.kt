package com.agentkosticka.amply.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsCodecTest {
    @Test
    fun oldJsonDefaultsPassThroughToFalse() {
        val decoded = AppSettingsCodec.decode(
            """{"com.example":{"appName":"Example","uid":42,"defaultVolume":0.4,"hiddenInOverlay":false}}"""
        ).getValue("com.example")

        assertFalse(decoded.passVolumeKeysToApp)
    }

    @Test
    fun passThroughSurvivesRoundTripAndCountsAsCustomized() {
        val original = AppSettings("com.example", "Example", 42, passVolumeKeysToApp = true)
        val decoded = AppSettingsCodec.decode(AppSettingsCodec.encode(mapOf(original.packageName to original)))
            .getValue(original.packageName)

        assertTrue(decoded.passVolumeKeysToApp)
        assertTrue(decoded.isCustomized)
        assertEquals(1f, decoded.defaultVolume, 0.001f)
    }
}
