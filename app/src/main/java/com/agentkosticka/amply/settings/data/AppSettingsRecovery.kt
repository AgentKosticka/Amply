package com.agentkosticka.amply.settings.data

import com.agentkosticka.amply.settings.model.*


internal object AppSettingsRecovery {
    fun resolve(primary: String?, backup: String?): AppSettingsResolution =
        when (val decoded = AppSettingsCodec.decodeResult(primary)) {
            is AppSettingsDecodeResult.Success -> AppSettingsResolution(
                decoded.settings,
                AppSettingsStoreHealth.HEALTHY
            )
            is AppSettingsDecodeResult.Corrupt -> if (backup.isNullOrBlank()) {
                AppSettingsResolution(emptyMap(), AppSettingsStoreHealth.CORRUPT)
            } else when (val fallback = AppSettingsCodec.decodeResult(backup)) {
                is AppSettingsDecodeResult.Success -> AppSettingsResolution(
                    fallback.settings,
                    AppSettingsStoreHealth.RECOVERED_FROM_BACKUP
                )
                is AppSettingsDecodeResult.Corrupt -> AppSettingsResolution(
                    emptyMap(),
                    AppSettingsStoreHealth.CORRUPT
                )
            }
        }
}
