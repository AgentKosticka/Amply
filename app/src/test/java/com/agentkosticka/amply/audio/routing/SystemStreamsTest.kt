package com.agentkosticka.amply.audio.routing

import android.media.AudioAttributes
import com.agentkosticka.amply.settings.model.VolumeDotScaleConfig
import com.agentkosticka.amply.settings.model.VolumeDotScaleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemStreamsTest {
    @Test
    fun mapsUsagesAndFlagsToLegacyStreams() {
        assertEquals(VolumeTarget.RING, LegacyStreamResolver.resolve(AudioAttributes.USAGE_NOTIFICATION_RINGTONE, 0))
        assertEquals(VolumeTarget.SYSTEM, LegacyStreamResolver.resolve(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, 0))
        assertEquals(VolumeTarget.DTMF, LegacyStreamResolver.resolve(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING, 0))
        assertEquals(VolumeTarget.ACCESSIBILITY, LegacyStreamResolver.resolve(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, 0))
        assertEquals(VolumeTarget.ASSISTANT, LegacyStreamResolver.resolve(AudioAttributes.USAGE_ASSISTANT, 0))
        assertEquals(VolumeTarget.BLUETOOTH_SCO, LegacyStreamResolver.resolve(0, LegacyStreamResolver.FLAG_SCO))
        assertEquals(VolumeTarget.TTS, LegacyStreamResolver.resolve(0, LegacyStreamResolver.FLAG_BEACON))
        assertEquals(VolumeTarget.ENFORCED_AUDIBLE, LegacyStreamResolver.resolve(0, LegacyStreamResolver.FLAG_AUDIBILITY_ENFORCED))
        assertEquals(
            VolumeTarget.ENFORCED_AUDIBLE,
            LegacyStreamResolver.resolve(
                AudioAttributes.USAGE_ASSISTANT,
                LegacyStreamResolver.FLAG_AUDIBILITY_ENFORCED or
                    LegacyStreamResolver.FLAG_SCO or LegacyStreamResolver.FLAG_BEACON
            )
        )
    }

    @Test
    fun topologyCombinesOnlyKnownAliases() {
        val unknown = StreamTopology(aliases = mapOf(5 to 2))
        assertFalse(unknown.aliasesTogether(VolumeTarget.NOTIFICATION, VolumeTarget.RING))
        val known = unknown.copy(aliasKnown = true)
        assertTrue(known.aliasesTogether(VolumeTarget.NOTIFICATION, VolumeTarget.RING))
        assertEquals(VolumeTarget.RING, known.canonicalTarget(VolumeTarget.NOTIFICATION))
    }

    @Test
    fun optionalBarsLatchUntilOverlayHides() {
        val controller = SystemStreamSessionController()
        controller.onStreamsChanged(
            ActiveSystemStreams(setOf(10), StreamTopology(aliasKnown = true), shizukuConnected = true)
        )
        assertTrue(controller.state.value.visibleOptionalTargets.isEmpty())
        controller.onOverlayShown()
        assertEquals(setOf(VolumeTarget.ACCESSIBILITY), controller.state.value.visibleOptionalTargets)
        controller.onStreamsChanged(ActiveSystemStreams(emptySet(), StreamTopology(aliasKnown = true), true))
        assertEquals(setOf(VolumeTarget.ACCESSIBILITY), controller.state.value.visibleOptionalTargets)
        controller.onOverlayHidden()
        assertTrue(controller.state.value.visibleOptionalTargets.isEmpty())
    }

    @Test
    fun unknownAliasesKeepRingAndNotificationIndependent() {
        val state = DynamicStreamState(
            visibleOptionalTargets = setOf(VolumeTarget.RING),
            topology = StreamTopology.UNKNOWN,
            shizukuConnected = true
        )
        assertEquals(
            listOf(
                VolumeTarget.MEDIA,
                VolumeTarget.ALARM,
                VolumeTarget.NOTIFICATION,
                VolumeTarget.CALL,
                VolumeTarget.RING
            ),
            state.visibleTargets()
        )
    }

    @Test
    fun confirmedAliasesCollapseOptionalActivityIntoCoreBar() {
        val topology = StreamTopology(
            aliasKnown = true,
            aliases = (0..11).associateWith { if (it == 10) 3 else it }
        )
        val controller = SystemStreamSessionController()
        controller.onStreamsChanged(ActiveSystemStreams(setOf(10), topology, true))
        controller.onOverlayShown()

        assertEquals(setOf(VolumeTarget.MEDIA), controller.state.value.activeTargets)
        assertTrue(controller.state.value.visibleOptionalTargets.isEmpty())
        assertEquals(
            listOf(
                VolumeTarget.MEDIA,
                VolumeTarget.ALARM,
                VolumeTarget.NOTIFICATION,
                VolumeTarget.RING
            ),
            controller.state.value.visibleTargets()
        )
    }

    @Test
    fun disconnectClearsOptionalLatchesAndDisabledBars() {
        val controller = SystemStreamSessionController()
        controller.onStreamsChanged(ActiveSystemStreams(setOf(11), StreamTopology.UNKNOWN, true))
        controller.onOverlayShown()
        controller.disable(VolumeTarget.ASSISTANT)

        controller.onStreamsChanged(ActiveSystemStreams())

        assertTrue(controller.state.value.visibleOptionalTargets.isEmpty())
        assertTrue(controller.state.value.disabledTargets.isEmpty())
    }

    @Test
    fun fixedDotScaleMapsLevelsDirectlyAndClampsUnavailableEnds() {
        assertEquals(0, VolumeDotScale.levelForFraction(0f, min = 0, max = 16, referenceMax = 16))
        assertEquals(1, VolumeDotScale.levelForFraction(0f, min = 1, max = 16, referenceMax = 16))
        assertEquals(15, VolumeDotScale.levelForFraction(1f, min = 1, max = 15, referenceMax = 16))
        assertEquals(8, VolumeDotScale.levelForFraction(0.5f, min = 1, max = 15, referenceMax = 16))
        assertFalse(VolumeDotScale.isLevelAvailable(16, min = 1, max = 15, referenceMax = 16, dotCount = 16))
        assertTrue(VolumeDotScale.isLevelAvailable(15, min = 1, max = 15, referenceMax = 16, dotCount = 16))
    }

    @Test fun adaptiveDotScalePreservesNativeRangeAndProjectsBlockedEndpoints() {
        assertEquals(15, VolumeDotScale.displayLevel(current = 30, referenceMax = 30, dotCount = 15))
        assertEquals(30, VolumeDotScale.levelForFraction(1f, min = 1, max = 30, referenceMax = 30))
        assertEquals(15, VolumeDotScale.levelForFraction(0.5f, min = 1, max = 30, referenceMax = 30))
        assertEquals(1, VolumeDotScale.projectedLevel(1, referenceMax = 30, dotCount = 30))
        assertFalse(
            VolumeDotScale.isLevelAvailable(30, min = 1, max = 25, referenceMax = 30, dotCount = 30)
        )
    }

    @Test fun autoDotsCapAtThirtyAndCustomDotsClampToSupportedRange() {
        assertEquals(30, VolumeDotScaleConfig().resolvedDotCount(100))
        assertEquals(
            60,
            VolumeDotScaleConfig(VolumeDotScaleMode.CUSTOM, customDotCount = 99).resolvedDotCount(16)
        )
        assertEquals(
            4,
            VolumeDotScaleConfig(VolumeDotScaleMode.CUSTOM, customDotCount = 1).resolvedDotCount(16)
        )
    }

    @Test
    fun independentRingerReplacesCallUntilCallBecomesActive() {
        val identity = StreamTopology(
            aliasKnown = true,
            aliases = (0..11).associateWith { it }
        )
        val controller = SystemStreamSessionController()
        controller.onStreamsChanged(ActiveSystemStreams(emptySet(), identity, true))

        assertEquals(
            listOf(
                VolumeTarget.MEDIA,
                VolumeTarget.ALARM,
                VolumeTarget.NOTIFICATION,
                VolumeTarget.RING
            ),
            controller.state.value.coreTargets()
        )

        controller.onCallModeChanged(true)

        assertEquals(
            listOf(
                VolumeTarget.MEDIA,
                VolumeTarget.ALARM,
                VolumeTarget.NOTIFICATION,
                VolumeTarget.CALL
            ),
            controller.state.value.coreTargets()
        )
        assertFalse(VolumeTarget.RING in controller.state.value.visibleTargets())
    }

    @Test
    fun linkedRingerKeepsExistingNotificationAndCallLayout() {
        val aliases = (0..11).associateWith { stream ->
            if (stream == VolumeTarget.NOTIFICATION.streamType) VolumeTarget.RING.streamType else stream
        }
        val state = DynamicStreamState(
            topology = StreamTopology(aliasKnown = true, aliases = aliases),
            shizukuConnected = true
        )

        assertEquals(
            listOf(
                VolumeTarget.MEDIA,
                VolumeTarget.ALARM,
                VolumeTarget.RING,
                VolumeTarget.CALL
            ),
            state.coreTargets()
        )
    }

    @Test
    fun rejectedStepsShakePinnedOrFirstUnavailableDot() {
        assertEquals(
            1,
            VolumeLimitFeedbackPolicy.rejectedDotLevel(isUp = false, min = 1, max = 16)
        )
        assertEquals(
            16,
            VolumeLimitFeedbackPolicy.rejectedDotLevel(isUp = true, min = 1, max = 15)
        )
        assertEquals(
            16,
            VolumeLimitFeedbackPolicy.rejectedDotLevel(isUp = true, min = 0, max = 16)
        )
    }
}
