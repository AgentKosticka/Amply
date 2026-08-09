package com.agentkosticka.amply.shizuku.server

import com.agentkosticka.amply.shizuku.protocol.FractionalVolumeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FractionalStreamVolumeBackendTest {
    @Before
    fun resetFakeSetter() {
        FakeAudioSystem.result = 0
        FakeAudioSystem.calls = 0
    }

    @Test
    fun absentOemMethodDoesNotAdvertiseSupport() {
        val backend = FractionalStreamVolumeBackend(
            methodResolver = { null },
            snapshotReader = { StreamVolumeSnapshot(0, 15, 7) }
        )

        assertFalse(backend.isAvailable(3))
        assertEquals(FractionalApplyResult.Unsupported, backend.apply(3, 7.5f))
    }

    @Test
    fun malformedMethodIsRejectedBeforeProbe() {
        val malformed = FakeAudioSystem::class.java.getDeclaredMethod(
            "wrongName",
            Integer.TYPE,
            java.lang.Float.TYPE,
            Integer.TYPE
        )
        val backend = FractionalStreamVolumeBackend(
            methodResolver = { malformed },
            snapshotReader = { StreamVolumeSnapshot(0, 15, 7) }
        )

        assertFalse(backend.isAvailable(3))
        assertEquals(0, FakeAudioSystem.calls)
    }

    @Test
    fun successfulProbeEnablesFractionalStateWithoutPlayerFallback() {
        val backend = backend(currentIndex = { 7 })

        assertTrue(backend.isAvailable(3))
        val applied = backend.apply(3, 7.5f)

        assertTrue(applied is FractionalApplyResult.Applied)
        assertEquals(FractionalVolumeStatus.CACHED, backend.state(3).status)
        assertEquals(7.5f, backend.state(3).value)
        assertEquals(2, FakeAudioSystem.calls)
    }

    @Test
    fun nativeIndexChangeInvalidatesCachedFraction() {
        var index = 7
        val backend = backend(currentIndex = { index })
        assertTrue(backend.apply(3, 7.5f) is FractionalApplyResult.Applied)

        index = 8

        assertFalse(backend.state(3).available)
    }

    @Test
    fun failedInvocationDisablesBackendForServiceLifetime() {
        val backend = backend(currentIndex = { 7 })
        assertTrue(backend.isAvailable(3))
        FakeAudioSystem.result = -1

        assertEquals(FractionalApplyResult.Unsupported, backend.apply(3, 7.5f))
        FakeAudioSystem.result = 0
        assertFalse(backend.isAvailable(3))
    }

    private fun backend(currentIndex: () -> Int): FractionalStreamVolumeBackend {
        val setter = FakeAudioSystem::class.java.getDeclaredMethod(
            "setStreamVolume",
            Integer.TYPE,
            java.lang.Float.TYPE,
            Integer.TYPE
        )
        return FractionalStreamVolumeBackend(
            methodResolver = { setter },
            snapshotReader = { StreamVolumeSnapshot(0, 15, currentIndex()) }
        )
    }

    private object FakeAudioSystem {
        var result: Int = 0
        var calls: Int = 0

        @JvmStatic
        fun setStreamVolume(stream: Int, gain: Float, flags: Int): Int {
            check(stream >= 0 && gain.isFinite() && flags >= 0)
            calls += 1
            return result
        }

        @JvmStatic
        fun wrongName(stream: Int, gain: Float, flags: Int): Int =
            setStreamVolume(stream, gain, flags)
    }
}
