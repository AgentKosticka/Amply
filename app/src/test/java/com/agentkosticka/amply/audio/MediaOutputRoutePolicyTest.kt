package com.agentkosticka.amply.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaOutputRoutePolicyTest {
    @Test
    fun `local route stays local`() {
        assertEquals(MediaOutputRoute.LOCAL, MediaOutputRoutePolicy.resolve(MediaRouteSnapshot()))
    }

    @Test
    fun `actual bluetooth route is recognized`() {
        assertEquals(
            MediaOutputRoute.BLUETOOTH,
            MediaOutputRoutePolicy.resolve(
                MediaRouteSnapshot(routedDevices = setOf(MediaRouteDeviceKind.BLUETOOTH))
            )
        )
    }

    @Test
    fun `actual remote route is recognized as cast`() {
        assertEquals(
            MediaOutputRoute.CAST,
            MediaOutputRoutePolicy.resolve(
                MediaRouteSnapshot(routedDevices = setOf(MediaRouteDeviceKind.REMOTE))
            )
        )
    }

    @Test
    fun `older Android selected bluetooth route is recognized`() {
        assertEquals(
            MediaOutputRoute.BLUETOOTH,
            MediaOutputRoutePolicy.resolve(
                MediaRouteSnapshot(selectedRouteDevice = MediaRouteDeviceKind.BLUETOOTH)
            )
        )
    }

    @Test
    fun `older Android selected remote route is recognized as cast`() {
        assertEquals(
            MediaOutputRoute.CAST,
            MediaOutputRoutePolicy.resolve(MediaRouteSnapshot(selectedRouteIsRemote = true))
        )
    }
}
