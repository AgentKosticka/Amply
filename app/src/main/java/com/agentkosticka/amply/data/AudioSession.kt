package com.agentkosticka.amply.data

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents an active audio session from an app
 */
data class AudioSession(
    val sessionId: Int,
    val uid: Int,
    val packageName: String,
    val appName: String,
    val appIcon: Drawable?,
    val streamType: Int,
    val volume: Float,
    val lastSeenTimestamp: Long
) {
    val identity: AppIdentity get() = AppIdentity.fromUid(packageName, uid)
}

enum class AppVolumeControlState {
    ACTIVE,
    PARTIAL,
    UNAVAILABLE,
    SAVED_ONLY
}

data class AppVolumeApplyResult(
    val identity: AppIdentity,
    val attemptedPlayers: Int,
    val successfulPlayers: Int
) {
    val state: AppVolumeControlState
        get() = when {
            attemptedPlayers == 0 -> AppVolumeControlState.SAVED_ONLY
            successfulPlayers == 0 -> AppVolumeControlState.UNAVAILABLE
            successfulPlayers < attemptedPlayers -> AppVolumeControlState.PARTIAL
            else -> AppVolumeControlState.ACTIVE
        }
}

/** Package-level row displayed in Amply's expanded overlay. */
data class OverlayAppEntry(
    val packageName: String,
    val uid: Int,
    val appName: String,
    val appIcon: Drawable?,
    val appIconBitmap: Bitmap? = null,
    val volume: Float,
    val isPlaying: Boolean,
    val controlState: AppVolumeControlState = if (isPlaying) {
        AppVolumeControlState.ACTIVE
    } else {
        AppVolumeControlState.SAVED_ONLY
    }
) {
    val identity: AppIdentity get() = AppIdentity.fromUid(packageName, uid)
}

/**
 * Represents the current state of all audio sessions
 */
data class AudioSessionState(
    val sessions: List<AudioSession>,
    val globalVolume: Int,
    val maxVolume: Int,
    val timestamp: Long
) {
    companion object {
        fun empty() = AudioSessionState(
            sessions = emptyList(),
            globalVolume = 0,
            maxVolume = 15,
            timestamp = 0L
        )
    }
}

/**
 * Raw audio session data parsed from dumpsys (before enrichment with app metadata)
 */
data class RawAudioSession(
    val sessionId: Int,
    val uid: Int,
    val streamType: Int,
    val volumeLeft: Float,
    val volumeRight: Float
)

/**
 * Result of parsing dumpsys audio_flinger output
 */
sealed class DumpsysParseResult {
    data class Success(val sessions: List<RawAudioSession>) : DumpsysParseResult()
    data class Error(val message: String) : DumpsysParseResult()
    data object ShizukuNotAvailable : DumpsysParseResult()
    data object Empty : DumpsysParseResult()
}

/**
 * Parcelable version of AudioSession for passing via Intent
 */
@Parcelize
data class ParcelableAudioSession(
    val sessionId: Int,
    val uid: Int,
    val packageName: String,
    val appName: String,
    val streamType: Int,
    val volume: Float,
    val lastSeenTimestamp: Long
) : Parcelable

/**
 * Convert AudioSession to Parcelable version (drops non-parcelable appIcon)
 */
fun AudioSession.toParcelable() = ParcelableAudioSession(
    sessionId = sessionId,
    uid = uid,
    packageName = packageName,
    appName = appName,
    streamType = streamType,
    volume = volume,
    lastSeenTimestamp = lastSeenTimestamp
)

/**
 * Convert ParcelableAudioSession back to AudioSession
 * Note: appIcon will be loaded separately using PackageManager
 */
fun ParcelableAudioSession.toAudioSession(appIcon: Drawable? = null) = AudioSession(
    sessionId = sessionId,
    uid = uid,
    packageName = packageName,
    appName = appName,
    appIcon = appIcon,
    streamType = streamType,
    volume = volume,
    lastSeenTimestamp = lastSeenTimestamp
)
