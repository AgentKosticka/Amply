package com.agentkosticka.amply.settings.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileEditorPolicyTest {
    @Test
    fun hidesFullVolumeAppsUntilTheyMatchASearch() {
        assertFalse(shouldShowProfileApp("", "WhatsApp", 1f))
        assertFalse(shouldShowProfileApp("Signal", "WhatsApp", 1f))
        assertTrue(shouldShowProfileApp("what", "WhatsApp", 1f))
    }

    @Test
    fun alwaysShowsAppsBelowFullVolume() {
        assertTrue(shouldShowProfileApp("", "WhatsApp", 0.75f))
        assertTrue(shouldShowProfileApp("Signal", "WhatsApp", 0.75f))
    }

    @Test
    fun keepsRevealedAppVisibleAfterItReachesFullVolume() {
        assertTrue(
            shouldShowProfileApp(
                search = "",
                displayName = "WhatsApp",
                volume = 1f,
                alreadyRevealed = true
            )
        )
    }
}
