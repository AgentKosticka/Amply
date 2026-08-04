package com.agentkosticka.amply.audio.session

import android.media.AudioManager
import com.agentkosticka.amply.settings.model.AppSettings
import com.agentkosticka.amply.settings.model.OverlayAppMode
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
        ).associateBy { it.identity }

        assertEquals(
            listOf("foreground", "pinned", "playing"),
            selectOverlayPackages(
                activeSessions = listOf(session("pinned"), session("playing")),
                appSettings = settings,
                foregroundVisitSession = session("foreground"),
                shizukuConnected = true
            ).map { it.packageName }
        )
    }

    @Test fun hiddenAppsNeverAppear() {
        val hidden = setting("hidden", OverlayAppMode.HIDDEN)
        assertEquals(
            emptyList<String>(),
            selectOverlayPackages(listOf(session("hidden")), mapOf(hidden.identity to hidden), session("hidden"), true)
                .map { it.packageName }
        )
    }

    @Test fun unknownForegroundDoesNotAppear() {
        assertEquals(
            listOf("playing"),
            selectOverlayPackages(
                listOf(session("playing")),
                listOf(setting("playing"), setting("unknown", seen = false)).associateBy { it.identity },
                null,
                true
            ).map { it.packageName }
        )
    }

    @Test fun disconnectedStateReturnsNoApps() {
        assertEquals(
            emptyList<String>(),
            selectOverlayPackages(
                listOf(session("playing")),
                listOf(setting("pinned", OverlayAppMode.PINNED)).associateBy { it.identity },
                session("playing"),
                false
            ).map { it.packageName }
        )
    }

    @Test fun savedAppOrderOverridesDiscoveryOrder() {
        val first = setting("first", OverlayAppMode.PINNED)
        val second = setting("second", OverlayAppMode.PINNED)
        val third = setting("third")

        assertEquals(
            listOf("third", "second", "first"),
            selectOverlayPackages(
                activeSessions = listOf(session("first"), session("third")),
                appSettings = listOf(first, second, third).associateBy { it.identity },
                foregroundVisitSession = session("first"),
                shizukuConnected = true,
                appOrder = listOf(third.identity, second.identity, first.identity)
            ).map { it.packageName }
        )
    }
}
