package com.agentkosticka.amply.audio

import com.agentkosticka.amply.data.AppSettings
import com.agentkosticka.amply.data.AppIdentity
import com.agentkosticka.amply.data.AudioSession
import com.agentkosticka.amply.data.OverlayAppMode

internal fun selectOverlayPackages(
    activeSessions: List<AudioSession>,
    appSettings: Map<AppIdentity, AppSettings>,
    foregroundVisitSession: AudioSession?,
    shizukuConnected: Boolean
): List<AppIdentity> {
    if (!shizukuConnected) return emptyList()

    val activeIdentities = activeSessions.mapTo(linkedSetOf()) { it.identity }
    val knownSettings = appSettings.values.filter { it.lastSeenTimestamp > 0L }
    val result = linkedSetOf<AppIdentity>()

    foregroundVisitSession
        ?.identity
        ?.takeIf { appSettings[it]?.overlayMode != OverlayAppMode.HIDDEN }
        ?.let(result::add)

    knownSettings
        .asSequence()
        .filter { it.overlayMode == OverlayAppMode.PINNED }
        .sortedBy { it.appName.lowercase() }
        .mapTo(result) { it.identity }

    activeIdentities
        .filterTo(result) { appSettings[it]?.overlayMode != OverlayAppMode.HIDDEN }

    return result.toList()
}
