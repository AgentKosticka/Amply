package com.agentkosticka.amply.settings.model


data class AppIdentity(
    val userId: Int,
    val packageName: String
) {
    val storageKey: String get() = "$userId|$packageName"

    companion object {
        fun fromUid(packageName: String, uid: Int, fallbackUserId: Int = 0): AppIdentity {
            val userId = if (uid >= 0) {
                uid / 100_000
            } else fallbackUserId
            return AppIdentity(userId, packageName)
        }

        fun fromStorageKey(key: String): AppIdentity? {
            val separator = key.indexOf('|')
            if (separator <= 0 || separator == key.lastIndex) return null
            return AppIdentity(
                userId = key.substring(0, separator).toIntOrNull() ?: return null,
                packageName = key.substring(separator + 1)
            )
        }
    }
}

enum class AppSettingsStoreHealth {
    HEALTHY,
    RECOVERED_FROM_BACKUP,
    CORRUPT
}

sealed interface AppSettingsDecodeResult {
    data class Success(val settings: Map<AppIdentity, AppSettings>) : AppSettingsDecodeResult
    data class Corrupt(val raw: String?, val reason: String) : AppSettingsDecodeResult
}

data class AppSettingsResolution(
    val settings: Map<AppIdentity, AppSettings>,
    val health: AppSettingsStoreHealth
)

enum class OverlaySide {
    LEFT,
    RIGHT;

    companion object {
        fun fromStored(value: String?): OverlaySide =
            entries.firstOrNull { it.name == value } ?: LEFT
    }
}

enum class VolumeDotScaleMode { AUTO, CUSTOM }

data class VolumeDotScaleConfig(
    val mode: VolumeDotScaleMode = VolumeDotScaleMode.AUTO,
    val customDotCount: Int = 16
) {
    fun resolvedDotCount(deviceReferenceMax: Int): Int = when (mode) {
        VolumeDotScaleMode.AUTO -> deviceReferenceMax.coerceIn(1, 30)
        VolumeDotScaleMode.CUSTOM -> customDotCount.coerceIn(4, 60)
    }
}

data class SettingsImportPreview(
    val appCount: Int,
    val customizedAppCount: Int,
    val valid: Boolean,
    val standDownCount: Int = 0,
    val profileCount: Int = 0,
    val outputDeviceCount: Int = 0,
    val replacesGlobalSettings: Boolean = true,
    val error: String? = null
)

enum class ImportMode { MERGE, REPLACE }

sealed interface SettingsOperationResult {
    data object Success : SettingsOperationResult
    data object StoreCorrupt : SettingsOperationResult
    data class ValidationFailed(val reason: String) : SettingsOperationResult
    data class IoFailed(val reason: String) : SettingsOperationResult
}

enum class OverlayAppMode {
    HIDDEN,
    AUTO,
    PINNED;

    companion object {
        fun fromStored(value: String?, legacyHidden: Boolean = false): OverlayAppMode =
            entries.firstOrNull { it.name == value }
                ?: if (legacyHidden) HIDDEN else AUTO
    }
}

enum class AmplyPauseDuration(val minutes: Int?) {
    ONE_MINUTE(1),
    FIVE_MINUTES(5),
    FIFTEEN_MINUTES(15),
    THIRTY_MINUTES(30),
    MANUAL(null);

    companion object {
        fun fromStored(value: String?, legacyMinutes: Int? = null): AmplyPauseDuration =
            entries.firstOrNull { it.name == value }
                ?: entries.firstOrNull { it.minutes == legacyMinutes }
                ?: FIVE_MINUTES
    }
}

data class AppSettings(
    val packageName: String,
    val appName: String,
    val uid: Int,
    val userId: Int = AppIdentity.fromUid(packageName, uid).userId,
    val defaultVolume: Float = 1.0f,
    val overlayMode: OverlayAppMode = OverlayAppMode.AUTO,
    val lastSeenTimestamp: Long = 0L
) {
    val identity: AppIdentity get() = AppIdentity(userId, packageName)

    val isCustomized: Boolean
        get() = overlayMode != OverlayAppMode.AUTO ||
            kotlin.math.abs(defaultVolume - 1.0f) > 0.001f
}

const val DEFAULT_AMPLY_PAUSE_MINUTES = 5

internal fun calculateAmplyPauseUntil(nowEpochMs: Long, durationMinutes: Int): Long =
    nowEpochMs + durationMinutes.coerceIn(1, 120) * 60_000L

internal fun calculateAmplyPauseUntil(
    nowEpochMs: Long,
    duration: AmplyPauseDuration
): Long = duration.minutes?.let { calculateAmplyPauseUntil(nowEpochMs, it) } ?: Long.MAX_VALUE

/**
 * Manages app preferences using DataStore
 */
