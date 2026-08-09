package com.agentkosticka.amply.settings.ui

import com.agentkosticka.amply.settings.model.AppIdentity
import com.agentkosticka.amply.settings.model.appDisplayName
import com.agentkosticka.amply.settings.model.appProfileFallbackLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDisplayNameTest {
    @Test fun defaultPrivacyRemovesWorkIdentityFromTheVisibleName() {
        assertEquals(
            "WhatsApp",
            appDisplayName("WhatsApp (Work)", userId = 10, personalUserId = 0, hideProfileIdentity = true)
        )
    }

    @Test fun customSecondaryProfileIdentityIsHidden() {
        assertEquals(
            "WhatsApp",
            appDisplayName("WhatsApp (Studio)", userId = 10, personalUserId = 0, hideProfileIdentity = true)
        )
    }

    @Test fun turningPrivacyOffPreservesTheIdentityName() {
        assertEquals(
            "WhatsApp (Work)",
            appDisplayName("WhatsApp (Work)", userId = 10, personalUserId = 0, hideProfileIdentity = false)
        )
    }

    @Test fun legitimatePersonalAppSuffixIsNotRemoved() {
        assertEquals(
            "Authenticator (Beta)",
            appDisplayName("Authenticator (Beta)", userId = 0, personalUserId = 0, hideProfileIdentity = true)
        )
    }

    @Test fun missingAndroidProfileSuffixGetsAReadableFallbackWhenIdentityIsVisible() {
        assertEquals(
            "(Work)",
            appProfileFallbackLabel(
                "WhatsApp",
                userId = 10,
                personalUserId = 0,
                hideProfileIdentity = false
            )
        )
        assertEquals(
            "(Personal)",
            appProfileFallbackLabel(
                "WhatsApp",
                userId = 0,
                personalUserId = 0,
                hideProfileIdentity = false
            )
        )
    }

    @Test fun suppliedProfileSuffixDoesNotGetDuplicated() {
        assertEquals(
            null,
            appProfileFallbackLabel(
                "WhatsApp (Studio)",
                userId = 10,
                personalUserId = 0,
                hideProfileIdentity = false
            )
        )
    }

    @Test fun profilePrivacyToggleStaysHiddenForSingleProfileUsers() {
        assertFalse(
            shouldShowAppProfilePrivacy(
                accessibleProfileCount = 1,
                knownIdentities = listOf(AppIdentity(0, "com.example.personal")),
                personalUserId = 0
            )
        )
    }

    @Test fun profilePrivacyToggleAppearsWhenAndroidReportsAnotherProfile() {
        assertTrue(
            shouldShowAppProfilePrivacy(
                accessibleProfileCount = 2,
                knownIdentities = emptyList(),
                personalUserId = 0
            )
        )
    }

    @Test fun observedSecondaryAppKeepsProfilePrivacyToggleAvailable() {
        assertTrue(
            shouldShowAppProfilePrivacy(
                accessibleProfileCount = 1,
                knownIdentities = listOf(AppIdentity(10, "com.example.work")),
                personalUserId = 0
            )
        )
    }
}
