package com.agentkosticka.amply.audio.session

import android.graphics.drawable.Drawable
import com.agentkosticka.amply.settings.model.AppIdentity

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

