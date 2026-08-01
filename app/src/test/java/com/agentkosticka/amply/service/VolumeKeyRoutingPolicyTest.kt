package com.agentkosticka.amply.service

import com.agentkosticka.amply.audio.VolumeKeyStreamAction
import com.agentkosticka.amply.audio.VolumeTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeKeyRoutingPolicyTest {
    private fun state(
        loaded: Boolean = true,
        foreground: String? = "com.example",
        selected: Set<String> = emptySet(),
        pausedUntil: Long = 0L,
        camera: Boolean = false,
        now: Long = 1_000L
    ) = VolumeKeyRoutingState(loaded, foreground, selected, pausedUntil, camera, now)

    @Test fun selectedAppPassesThrough() = assertEquals(
        VolumeKeyRoute.PASS_THROUGH,
        VolumeKeyRoutingPolicy.route(state(selected = setOf("com.example")))
    )

    @Test fun ordinaryAppIsIntercepted() = assertEquals(
        VolumeKeyRoute.INTERCEPT,
        VolumeKeyRoutingPolicy.route(state())
    )

    @Test fun pausePassesThrough() = assertEquals(
        VolumeKeyRoute.PASS_THROUGH,
        VolumeKeyRoutingPolicy.route(state(pausedUntil = 2_000L))
    )

    @Test fun unloadedPreferencesFailOpenButUnknownForegroundUsesAmply() {
        assertEquals(VolumeKeyRoute.PASS_THROUGH, VolumeKeyRoutingPolicy.route(state(loaded = false)))
        assertEquals(VolumeKeyRoute.INTERCEPT, VolumeKeyRoutingPolicy.route(state(foreground = null)))
    }

    @Test fun cameraBypassStillPassesThrough() = assertEquals(
        VolumeKeyRoute.PASS_THROUGH,
        VolumeKeyRoutingPolicy.route(state(camera = true))
    )

    @Test fun routeIsLatchedForWholePress() {
        val router = VolumeKeySequenceRouter()
        assertEquals(VolumeKeyRoute.PASS_THROUGH, router.onDown(24, 0) { VolumeKeyRoute.PASS_THROUGH })
        assertEquals(VolumeKeyRoute.PASS_THROUGH, router.onDown(24, 1) { VolumeKeyRoute.INTERCEPT })
        assertEquals(VolumeKeyRoute.PASS_THROUGH, router.onUp(24))
    }

    @Test fun streamActionIsLatchedForWholePress() {
        val router = VolumeKeyStreamActionRouter()
        val initial = VolumeKeyStreamAction.Adjust(VolumeTarget.ALARM)
        val changed = VolumeKeyStreamAction.Adjust(VolumeTarget.MEDIA)
        assertEquals(initial, router.onDown(24, 0) { initial })
        assertEquals(initial, router.onDown(24, 1) { changed })
        assertEquals(initial, router.onUp(24))
    }

    @Test fun streamActionLatchesIncomingRingerPassThrough() {
        val router = VolumeKeyStreamActionRouter()
        assertEquals(
            VolumeKeyStreamAction.SilenceIncomingRinger,
            router.onDown(25, 0) { VolumeKeyStreamAction.SilenceIncomingRinger }
        )
        assertEquals(
            VolumeKeyStreamAction.SilenceIncomingRinger,
            router.onDown(25, 1) { VolumeKeyStreamAction.Adjust(VolumeTarget.MEDIA) }
        )
        assertEquals(VolumeKeyStreamAction.SilenceIncomingRinger, router.onUp(25))
    }

    @Test fun streamStepHonorsNonZeroMinimumAndMaximum() {
        assertEquals(2, VolumeStepPolicy.next(current = 2, min = 2, max = 7, isUp = false))
        assertEquals(7, VolumeStepPolicy.next(current = 7, min = 2, max = 7, isUp = true))
        assertEquals(4, VolumeStepPolicy.next(current = 3, min = 2, max = 7, isUp = true))
    }
}
