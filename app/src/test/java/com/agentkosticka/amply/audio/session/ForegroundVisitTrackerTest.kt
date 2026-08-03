package com.agentkosticka.amply.audio.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundVisitTrackerTest {
    private fun session(packageName: String) = AudioSession(
        sessionId = packageName.hashCode(),
        uid = packageName.hashCode(),
        packageName = packageName,
        appName = packageName,
        appIcon = null,
        streamType = 3,
        volume = 1f,
        lastSeenTimestamp = 1L
    )

    @Test fun samePackageWindowEventsKeepTheVisit() {
        val tracker = ForegroundVisitTracker()
        tracker.onForegroundChanged("app")
        val visitId = tracker.state.value.visitId
        tracker.onForegroundChanged("app")
        assertEquals(visitId, tracker.state.value.visitId)
    }

    @Test fun foregroundSoundLatchesWithoutTimeout() {
        val tracker = ForegroundVisitTracker()
        tracker.onForegroundChanged("app")
        tracker.onSessionsChanged(listOf(session("app")))
        tracker.onSessionsChanged(emptyList())

        assertTrue(tracker.state.value.heardAudio)
        assertEquals("app", tracker.state.value.lastAudioSession?.packageName)
    }

    @Test fun leavingAndReturningRequiresNewSound() {
        val tracker = ForegroundVisitTracker()
        tracker.onForegroundChanged("app")
        tracker.onSessionsChanged(listOf(session("app")))
        tracker.onForegroundChanged("launcher")
        tracker.onSessionsChanged(emptyList())
        tracker.onForegroundChanged("app")

        assertFalse(tracker.state.value.heardAudio)
        assertNull(tracker.state.value.lastAudioSession)
    }

    @Test fun backgroundSoundThatStopsDoesNotQualifyLaterVisit() {
        val tracker = ForegroundVisitTracker()
        tracker.onForegroundChanged("other")
        tracker.onSessionsChanged(listOf(session("app")))
        tracker.onSessionsChanged(emptyList())
        tracker.onForegroundChanged("app")

        assertFalse(tracker.state.value.heardAudio)
    }

    @Test fun alreadyPlayingAppQualifiesWhenItComesToForeground() {
        val tracker = ForegroundVisitTracker()
        tracker.onSessionsChanged(listOf(session("app")))
        tracker.onForegroundChanged("app")
        assertTrue(tracker.state.value.heardAudio)
    }

    @Test fun singlePlayingAppIsLatchedWhileForegroundIsTemporarilyUnknown() {
        val tracker = ForegroundVisitTracker()
        tracker.onSessionsChanged(listOf(session("app")))
        tracker.onSessionsChanged(emptyList())

        assertTrue(tracker.state.value.heardAudio)
        assertEquals("app", tracker.state.value.lastAudioSession?.packageName)
    }

    @Test fun inferredAudioCarriesIntoMatchingForegroundVisitButNotAnotherApp() {
        val matching = ForegroundVisitTracker()
        matching.onSessionsChanged(listOf(session("app")))
        matching.onSessionsChanged(emptyList())
        matching.onForegroundChanged("app")
        assertTrue(matching.state.value.heardAudio)

        val different = ForegroundVisitTracker()
        different.onSessionsChanged(listOf(session("app")))
        different.onSessionsChanged(emptyList())
        different.onForegroundChanged("other")
        assertFalse(different.state.value.heardAudio)
        assertNull(different.state.value.lastAudioSession)
    }
}
