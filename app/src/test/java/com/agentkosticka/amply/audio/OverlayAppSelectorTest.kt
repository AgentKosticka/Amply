package com.agentkosticka.amply.audio

import android.media.AudioManager
import com.agentkosticka.amply.data.AppSettings
import com.agentkosticka.amply.data.AudioSession
import com.agentkosticka.amply.data.OverlayAppMode
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayAppSelectorTest {
    private fun setting(
        packageName: String,
        mode: OverlayAppMode = OverlayAppMode.AUTO,
        seen: Boolean = true
    ) = AppSettings(
        packageName = packageName,
        appName = packageName,
        uid = packageName.hashCode(),
        overlayMode = mode,
        lastSeenTimestamp = if (seen) 100L else 0L
    )

    private fun session(packageName: String) = AudioSession(
        sessionId = packageName.hashCode(),
        uid = packageName.hashCode(),
        packageName = packageName,
        appName = packageName,
        appIcon = null,
        streamType = AudioManager.STREAM_MUSIC,
        volume = 1f,
        lastSeenTimestamp = 100L
    )

    @Test fun foregroundThenPinnedThenPlayingAreOrderedAndDeduplicated() {
        val settings = listOf(
            setting("foreground"),
            setting("pinned", OverlayAppMode.PINNED),
            setting("playing")
        ).associateBy { it.packageName }

        assertEquals(
            listOf("foreground", "pinned", "playing"),
            selectOverlayPackages(
                activeSessions = listOf(session("pinned"), session("playing")),
                appSettings = settings,
                foregroundVisitSession = session("foreground"),
                shizukuConnected = true
            )
        )
    }

    @Test fun hiddenAppsNeverAppear() {
        val hidden = setting("hidden", OverlayAppMode.HIDDEN)
        assertEquals(
            emptyList<String>(),
            selectOverlayPackages(listOf(session("hidden")), mapOf("hidden" to hidden), session("hidden"), true)
        )
    }

    @Test fun unknownForegroundDoesNotAppear() {
        assertEquals(
            listOf("playing"),
            selectOverlayPackages(
                listOf(session("playing")),
                mapOf("playing" to setting("playing"), "unknown" to setting("unknown", seen = false)),
                null,
                true
            )
        )
    }

    @Test fun disconnectedStateReturnsNoApps() {
        assertEquals(
            emptyList<String>(),
            selectOverlayPackages(
                listOf(session("playing")),
                mapOf("pinned" to setting("pinned", OverlayAppMode.PINNED)),
                session("playing"),
                false
            )
        )
    }
}
