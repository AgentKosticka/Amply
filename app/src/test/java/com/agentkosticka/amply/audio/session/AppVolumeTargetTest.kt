package com.agentkosticka.amply.audio.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppVolumeTargetTest {
    private val packageName = "com.example.chat"
    private val personal = session(sessionId = 11, uid = 12_345, appName = "Chat")
    private val work = session(sessionId = 22, uid = 1_012_345, appName = "Chat (Work)")
    private val workTarget = AppVolumeTarget(packageName, work.uid, work.appName)

    @Test fun profileCopiesUseDifferentPendingUpdateSlots() {
        val personalTarget = AppVolumeTarget(packageName, personal.uid, personal.appName)

        assertNotEquals(personalTarget.pendingUpdateKey, workTarget.pendingUpdateKey)
    }

    @Test fun queuedUpdateTargetsMatchingProfileWhenPackageNamesAreShared() {
        assertEquals(
            work,
            resolveAppVolumeSession(
                sessions = listOf(personal, work),
                requestedSessionId = work.sessionId,
                target = workTarget
            )
        )
    }

    @Test fun disappearedSessionFallsBackOnlyWithinTheRequestedProfile() {
        val replacementWorkSession = work.copy(sessionId = 33)

        assertEquals(
            replacementWorkSession,
            resolveAppVolumeSession(
                sessions = listOf(personal, replacementWorkSession),
                requestedSessionId = work.sessionId,
                target = workTarget
            )
        )
    }

    @Test fun disappearedProfileDoesNotFallBackToAnotherProfileWithTheSamePackage() {
        assertNull(
            resolveAppVolumeSession(
                sessions = listOf(personal),
                requestedSessionId = work.sessionId,
                target = workTarget
            )
        )
    }

    private fun session(sessionId: Int, uid: Int, appName: String) = AudioSession(
        sessionId = sessionId,
        uid = uid,
        packageName = packageName,
        appName = appName,
        appIcon = null,
        streamType = 3,
        volume = 0.5f,
        lastSeenTimestamp = 1L
    )
}
