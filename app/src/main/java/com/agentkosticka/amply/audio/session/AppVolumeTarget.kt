package com.agentkosticka.amply.audio.session

import androidx.compose.runtime.Immutable
import com.agentkosticka.amply.settings.model.AppIdentity

/** Stable command data required to change or persist one app's volume. */
@Immutable
data class AppVolumeTarget(
    val packageName: String,
    val uid: Int,
    val appName: String
) {
    val identity: AppIdentity get() = AppIdentity.fromUid(packageName, uid)
}

internal val AppVolumeTarget.pendingUpdateKey: String get() = identity.storageKey

internal fun OverlayAppEntry.toVolumeTarget() = AppVolumeTarget(
    packageName = packageName,
    uid = uid,
    appName = appName
)

/** Resolves a queued update without ever crossing Android profile boundaries. */
internal fun resolveAppVolumeSession(
    sessions: List<AudioSession>,
    requestedSessionId: Int,
    target: AppVolumeTarget
): AudioSession? = sessions.firstOrNull {
    it.sessionId == requestedSessionId && it.identity == target.identity
} ?: sessions.firstOrNull {
    it.identity == target.identity
}
