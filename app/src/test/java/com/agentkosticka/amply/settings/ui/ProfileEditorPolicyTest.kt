package com.agentkosticka.amply.settings.ui

import com.agentkosticka.amply.settings.model.AppIdentity
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
    fun showsReducedAppsNormallyButFiltersThemDuringSearch() {
        assertTrue(shouldShowProfileApp("", "WhatsApp", 0.75f))
        assertFalse(shouldShowProfileApp("Signal", "WhatsApp", 0.75f))
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

    @Test
    fun changingSearchReleasesAppsRetainedByVolumeInteraction() {
        val identity = AppIdentity(0, "com.example.chat")

        assertTrue(
            retainedProfileAppsAfterSearchChange("chat", "chat", setOf(identity)).contains(identity)
        )
        assertTrue(
            retainedProfileAppsAfterSearchChange("chat", "music", setOf(identity)).isEmpty()
        )
    }
}
