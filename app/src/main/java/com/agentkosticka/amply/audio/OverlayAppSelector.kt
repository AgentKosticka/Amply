package com.agentkosticka.amply.audio

import com.agentkosticka.amply.data.AppSettings
import com.agentkosticka.amply.data.AudioSession
import com.agentkosticka.amply.data.OverlayAppMode

internal fun selectOverlayPackages(
    activeSessions: List<AudioSession>,
    appSettings: Map<String, AppSettings>,
    foregroundVisitSession: AudioSession?,
    shizukuConnected: Boolean
): List<String> {
    if (!shizukuConnected) return emptyList()

    val activePackages = activeSessions.mapTo(linkedSetOf()) { it.packageName }
    val knownSettings = appSettings.values.filter { it.lastSeenTimestamp > 0L }
    val result = linkedSetOf<String>()

    foregroundVisitSession
        ?.packageName
        ?.takeIf { appSettings[it]?.overlayMode != OverlayAppMode.HIDDEN }
        ?.let(result::add)

    knownSettings
        .asSequence()
        .filter { it.overlayMode == OverlayAppMode.PINNED }
        .sortedBy { it.appName.lowercase() }
        .mapTo(result) { it.packageName }

    activePackages
        .filterTo(result) { appSettings[it]?.overlayMode != OverlayAppMode.HIDDEN }

    return result.toList()
}
