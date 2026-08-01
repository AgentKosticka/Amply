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
        assertEquals(OverlayAppMode.AUTO, decoded.overlayMode)
    }

    @Test
    fun oldHiddenJsonMigratesToHiddenMode() {
        val decoded = AppSettingsCodec.decode(
            """{"com.example":{"appName":"Example","uid":42,"hiddenInOverlay":true}}"""
        ).getValue("com.example")

        assertEquals(OverlayAppMode.HIDDEN, decoded.overlayMode)
    }

    @Test
    fun passThroughSurvivesRoundTripAndCountsAsCustomized() {
        val original = AppSettings(
            "com.example",
            "Example",
            42,
            defaultVolume = 0.45f,
            overlayMode = OverlayAppMode.PINNED,
            passVolumeKeysToApp = true,
            lastSeenTimestamp = 123L
        )
        val decoded = AppSettingsCodec.decode(AppSettingsCodec.encode(mapOf(original.packageName to original)))
            .getValue(original.packageName)

        assertTrue(decoded.passVolumeKeysToApp)
        assertTrue(decoded.isCustomized)
        assertEquals(OverlayAppMode.PINNED, decoded.overlayMode)
        assertEquals(0.45f, decoded.defaultVolume, 0.001f)
        assertEquals(123L, decoded.lastSeenTimestamp)
    }
}
