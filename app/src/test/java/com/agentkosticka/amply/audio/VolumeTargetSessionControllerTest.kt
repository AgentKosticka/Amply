package com.agentkosticka.amply.audio

import android.media.AudioAttributes
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeTargetSessionControllerTest {
    @Test
    fun automaticPriorityIsCallAlarmNotificationMedia() {
        val now = 5_000L
        assertEquals(
            VolumeTarget.CALL,
            VolumeTargetPolicy.automaticTarget(
                SystemVolumeContext(
                    audioMode = AudioManager.MODE_IN_CALL,
                    activeUsages = setOf(AudioAttributes.USAGE_ALARM, AudioAttributes.USAGE_NOTIFICATION)
                ),
                now
            )
        )
        assertEquals(
            VolumeTarget.ALARM,
            VolumeTargetPolicy.automaticTarget(
                SystemVolumeContext(activeUsages = setOf(AudioAttributes.USAGE_ALARM, AudioAttributes.USAGE_NOTIFICATION)),
                now
            )
        )
        assertEquals(
            VolumeTarget.NOTIFICATION,
            VolumeTargetPolicy.automaticTarget(
                SystemVolumeContext(activeUsages = setOf(AudioAttributes.USAGE_NOTIFICATION)),
                now
            )
        )
        assertEquals(VolumeTarget.MEDIA, VolumeTargetPolicy.automaticTarget(SystemVolumeContext(), now))
    }

    @Test
    fun notificationGraceExpiresAfterOneSecond() {
        val context = SystemVolumeContext(lastNotificationElapsedMs = 1_000L)
        assertEquals(VolumeTarget.NOTIFICATION, VolumeTargetPolicy.automaticTarget(context, 2_000L))
        assertEquals(VolumeTarget.MEDIA, VolumeTargetPolicy.automaticTarget(context, 2_001L))
    }

    @Test
    fun voiceCommunicationUsageSelectsCallWithoutTelephonyMode() {
        assertEquals(
            VolumeTarget.CALL,
            VolumeTargetPolicy.automaticTarget(
                SystemVolumeContext(
                    audioMode = AudioManager.MODE_NORMAL,
                    activeUsages = setOf(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                ),
                nowElapsedMs = 1_000L
            )
        )
    }

    @Test
    fun incomingRingerAlwaysPassesThroughEvenWithManualTarget() {
        var now = 1_000L
        val controller = VolumeTargetSessionController { now }
        controller.onOverlayShown()
        controller.onUserSelected(VolumeTarget.ALARM)

        assertEquals(
            VolumeKeyStreamAction.SilenceIncomingRinger,
            controller.resolveForInitialKeyDown(AudioManager.MODE_RINGTONE)
        )
        assertEquals(VolumeTarget.ALARM, controller.selectedTarget.value)
    }

    @Test
    fun manualTargetPersistsUntilOverlayHides() {
        val controller = VolumeTargetSessionController { 1_000L }
        controller.onOverlayShown()
        controller.onUserSelected(VolumeTarget.ALARM)
        controller.onAudioModeChanged(AudioManager.MODE_IN_CALL)

        assertTrue(controller.hasManualTarget())
        assertEquals(VolumeTarget.ALARM, controller.selectedTarget.value)
        assertEquals(
            VolumeKeyStreamAction.Adjust(VolumeTarget.ALARM),
            controller.resolveForInitialKeyDown(AudioManager.MODE_IN_CALL)
        )

        controller.onOverlayHidden()
        assertFalse(controller.hasManualTarget())
        assertEquals(VolumeTarget.CALL, controller.selectedTarget.value)
    }

    @Test
    fun playbackStopStartsNotificationGraceAtStopTime() {
        var now = 100L
        val controller = VolumeTargetSessionController { now }
        controller.onPlaybackUsagesChanged(setOf(AudioAttributes.USAGE_NOTIFICATION))
        now = 5_000L
        controller.onPlaybackUsagesChanged(emptySet())
        assertEquals(VolumeTarget.NOTIFICATION, controller.selectedTarget.value)
        now = 6_001L
        controller.onTimeAdvanced()
        assertEquals(VolumeTarget.MEDIA, controller.selectedTarget.value)
    }
}
