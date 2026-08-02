package com.agentkosticka.amply.audio.routing

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

    @Test fun `variable cast routes media action remotely`() {
        assertEquals(
            VolumeKeyStreamAction.AdjustRemoteMedia(7L),
            MediaVolumeActionPolicy.resolve(
                VolumeKeyStreamAction.Adjust(VolumeTarget.MEDIA),
                MediaRouteVolumeState(MediaOutputRoute.CAST, 7L, 4, 12, variableVolume = true)
            )
        )
    }

    @Test fun `fixed cast passes through while non-media stays local`() {
        val fixed = MediaRouteVolumeState(MediaOutputRoute.CAST, 3L, 4, 12, variableVolume = false)
        assertEquals(
            VolumeKeyStreamAction.PassThrough,
            MediaVolumeActionPolicy.resolve(VolumeKeyStreamAction.Adjust(VolumeTarget.MEDIA), fixed)
        )
        assertEquals(
            VolumeKeyStreamAction.Adjust(VolumeTarget.ALARM),
            MediaVolumeActionPolicy.resolve(VolumeKeyStreamAction.Adjust(VolumeTarget.ALARM), fixed)
        )
    }
}
