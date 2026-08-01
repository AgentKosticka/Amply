package com.agentkosticka.amply.service

internal class ScreenshotChordDetector(
    private val chordWindowMs: Long = DEFAULT_CHORD_WINDOW_MS
) {
    companion object {
        const val DEFAULT_CHORD_WINDOW_MS = 250L
    }

    private var lastPowerDownAt = Long.MIN_VALUE
    private var lastVolumeDownAt = Long.MIN_VALUE

    fun onPowerDown(eventTime: Long): Boolean {
        val matchesVolumePress = isWithinWindow(eventTime, lastVolumeDownAt)
        lastPowerDownAt = eventTime
        return matchesVolumePress
    }

    fun onVolumeDown(eventTime: Long): Boolean {
        val matchesPowerPress = isWithinWindow(eventTime, lastPowerDownAt)
        lastVolumeDownAt = eventTime
        return matchesPowerPress
    }

    fun reset() {
        lastPowerDownAt = Long.MIN_VALUE
        lastVolumeDownAt = Long.MIN_VALUE
    }

    private fun isWithinWindow(laterEventTime: Long, earlierEventTime: Long): Boolean {
        if (earlierEventTime == Long.MIN_VALUE || laterEventTime < earlierEventTime) {
            return false
        }
        return laterEventTime - earlierEventTime <= chordWindowMs
    }
}
