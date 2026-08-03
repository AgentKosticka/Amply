package com.agentkosticka.amply.settings.data

import com.agentkosticka.amply.settings.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsCodecTest {
    @Test
    fun oldJsonDefaultsToAutomaticOverlayMode() {
        val decoded = AppSettingsCodec.decode(
            """{"com.example":{"appName":"Example","uid":42,"defaultVolume":0.4,"hiddenInOverlay":false}}"""
        ).values.single()

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
    fun schemaThreeRoundTripKeepsProfileAwareSettings() {
        val original = AppSettings(
            "com.example",
            "Example",
            42,
            defaultVolume = 0.45f,
            overlayMode = OverlayAppMode.PINNED,
            lastSeenTimestamp = 123L
        )
        val decoded = AppSettingsCodec.decode(AppSettingsCodec.encode(mapOf(original.identity to original)))
            .getValue(original.identity)

        assertTrue(decoded.isCustomized)
        assertEquals(OverlayAppMode.PINNED, decoded.overlayMode)
        assertEquals(0.45f, decoded.defaultVolume, 0.001f)
        assertEquals(123L, decoded.lastSeenTimestamp)
    }

    @Test fun legacyPassThroughMigratesToPackageSet() {
        val raw = """{"_schemaVersion":2,"apps":{"0|com.example":{"packageName":"com.example","uid":42,"passVolumeKeysToApp":true}}}"""
        assertEquals(setOf("com.example"), AppSettingsCodec.legacyPassThroughPackages(raw))
        assertEquals("com.example", AppSettingsCodec.decode(raw).values.single().packageName)
    }

    @Test fun futureSchemaIsRejected() {
        assertTrue(
            AppSettingsCodec.decodeResult("""{"_schemaVersion":99,"apps":{}}""") is
                AppSettingsDecodeResult.Corrupt
        )
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

    @Test fun invalidPackageRangeAndNonFiniteVolumeAreRejected() {
        assertTrue(
            AppSettingsCodec.decodeResult(
                """{"apps":{"bad":{"packageName":"not a package!","uid":42}}}"""
            ) is AppSettingsDecodeResult.Corrupt
        )
        assertTrue(
            AppSettingsCodec.decodeResult(
                """{"apps":{"com.example":{"packageName":"com.example","uid":42,"defaultVolume":1.5}}}"""
            ) is AppSettingsDecodeResult.Corrupt
        )
        assertTrue(
            AppSettingsCodec.decodeResult(
                """{"apps":{"com.example":{"packageName":"com.example","uid":42,"defaultVolume":"NaN"}}}"""
            ) is AppSettingsDecodeResult.Corrupt
        )
    }

    @Test fun duplicateProfileIdentityIsRejected() {
        val raw = """{"apps":{
            "0|com.example":{"packageName":"com.example","uid":42},
            "legacyAlias":{"packageName":"com.example","uid":42}
        }}""".trimIndent()
        assertTrue(AppSettingsCodec.decodeResult(raw) is AppSettingsDecodeResult.Corrupt)
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
