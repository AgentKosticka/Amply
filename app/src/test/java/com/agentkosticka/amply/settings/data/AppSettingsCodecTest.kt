package com.agentkosticka.amply.settings.data

import com.agentkosticka.amply.settings.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsCodecTest {
    @Test
    fun oldJsonDefaultsPassThroughToFalse() {
        val decoded = AppSettingsCodec.decode(
            """{"com.example":{"appName":"Example","uid":42,"defaultVolume":0.4,"hiddenInOverlay":false}}"""
        ).values.single()

        assertFalse(decoded.passVolumeKeysToApp)
        assertEquals(OverlayAppMode.AUTO, decoded.overlayMode)
    }

    @Test
    fun oldHiddenJsonMigratesToHiddenMode() {
        val decoded = AppSettingsCodec.decode(
            """{"com.example":{"appName":"Example","uid":42,"hiddenInOverlay":true}}"""
        ).values.single()

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
        val decoded = AppSettingsCodec.decode(AppSettingsCodec.encode(mapOf(original.identity to original)))
            .getValue(original.identity)

        assertTrue(decoded.passVolumeKeysToApp)
        assertTrue(decoded.isCustomized)
        assertEquals(OverlayAppMode.PINNED, decoded.overlayMode)
        assertEquals(0.45f, decoded.defaultVolume, 0.001f)
        assertEquals(123L, decoded.lastSeenTimestamp)
    }

    @Test fun samePackageInTwoProfilesSurvivesRoundTrip() {
        val personal = AppSettings("com.example", "Example", 1234, userId = 0, defaultVolume = 0.3f)
        val work = AppSettings("com.example", "Example Work", 1_001_234, userId = 10, defaultVolume = 0.8f)
        val decoded = AppSettingsCodec.decode(
            AppSettingsCodec.encode(mapOf(personal.identity to personal, work.identity to work))
        )
        assertEquals(2, decoded.size)
        assertEquals(0.3f, decoded.getValue(personal.identity).defaultVolume, 0.001f)
        assertEquals(0.8f, decoded.getValue(work.identity).defaultVolume, 0.001f)
    }

    @Test fun malformedJsonIsReportedInsteadOfSilentlyDecodedAsEmpty() {
        assertTrue(AppSettingsCodec.decodeResult("{broken") is AppSettingsDecodeResult.Corrupt)
    }

    @Test fun corruptPrimaryRecoversFromPreviousGoodSnapshot() {
        val setting = AppSettings("com.example", "Example", 42)
        val backup = AppSettingsCodec.encode(mapOf(setting.identity to setting))
        val result = AppSettingsRecovery.resolve("{broken", backup)
        assertEquals(AppSettingsStoreHealth.RECOVERED_FROM_BACKUP, result.health)
        assertEquals(setting.packageName, result.settings.values.single().packageName)
    }

    @Test fun corruptPrimaryAndBackupRefuseToInventSettings() {
        val result = AppSettingsRecovery.resolve("{broken", "also broken")
        assertEquals(AppSettingsStoreHealth.CORRUPT, result.health)
        assertTrue(result.settings.isEmpty())
    }
}
