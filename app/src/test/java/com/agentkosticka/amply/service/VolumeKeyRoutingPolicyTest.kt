package com.agentkosticka.amply.service

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
}
