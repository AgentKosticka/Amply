package com.agentkosticka.amply.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundAppResolverTest {
    private val resolver = ForegroundAppResolver(
        ignoredPackages = setOf(
            "com.agentkosticka.amply",
            "com.keyboard",
            "com.android.permissioncontroller",
            "com.autofill"
        )
    )

    @Test fun ordinaryAppsAndLauncherAreAccepted() {
        assertEquals("com.example", resolver.resolve(ForegroundWindowCandidate("com.example", "MainActivity")))
        assertEquals("com.launcher", resolver.resolve(ForegroundWindowCandidate("com.launcher", "Launcher")))
    }

    @Test fun systemInputPermissionAndAutofillWindowsAreIgnored() {
        assertNull(resolver.resolve(ForegroundWindowCandidate("com.android.systemui", "VolumeDialog")))
        assertNull(resolver.resolve(ForegroundWindowCandidate("com.keyboard", "SoftInputWindow")))
        assertNull(resolver.resolve(ForegroundWindowCandidate("com.android.permissioncontroller", "GrantPermissionsActivity")))
        assertNull(resolver.resolve(ForegroundWindowCandidate("com.autofill", "AutofillWindow")))
    }

    @Test fun chooserClassIsIgnoredEvenFromAnotherPackage() {
        assertNull(resolver.resolve(ForegroundWindowCandidate("com.vendor.overlay", "ResolverActivity")))
    }
}
