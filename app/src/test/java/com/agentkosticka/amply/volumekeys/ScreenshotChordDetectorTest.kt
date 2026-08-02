package com.agentkosticka.amply.volumekeys

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotChordDetectorTest {
    @Test
    fun powerBeforeVolumeMatchesWithinInclusiveWindow() {
        val detector = ScreenshotChordDetector()

        assertFalse(detector.onPowerDown(1_000L))
        assertTrue(detector.onVolumeDown(1_250L))
    }

    @Test
    fun volumeBeforePowerMatchesWithinInclusiveWindow() {
        val detector = ScreenshotChordDetector()

        assertFalse(detector.onVolumeDown(1_000L))
        assertTrue(detector.onPowerDown(1_250L))
    }

    @Test
    fun eventsOutsideWindowDoNotMatch() {
        val powerFirst = ScreenshotChordDetector()
        assertFalse(powerFirst.onPowerDown(1_000L))
        assertFalse(powerFirst.onVolumeDown(1_251L))

        val volumeFirst = ScreenshotChordDetector()
        assertFalse(volumeFirst.onVolumeDown(2_000L))
        assertFalse(volumeFirst.onPowerDown(2_251L))
    }

    @Test
    fun resetClearsPreviousKeyTimes() {
        val detector = ScreenshotChordDetector()
        detector.onPowerDown(1_000L)

        detector.reset()

        assertFalse(detector.onVolumeDown(1_100L))
    }
}
