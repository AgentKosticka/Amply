package com.agentkosticka.amply.audio.ringer

/**
 * Remembers stream levels muted through an overlay icon.
 *
 * Reaching a stream's minimum through any other input clears the remembered level,
 * so the icon then restores only the first usable tick above minimum.
 */
class StreamMuteToggleController {
    private val restoreVolumes = mutableMapOf<Int, Int>()

    @Synchronized
    fun nextVolume(
        streamType: Int,
        currentVolume: Int,
        minVolume: Int,
        maxVolume: Int
    ): Int {
        if (currentVolume > minVolume) {
            restoreVolumes[streamType] = currentVolume
            return minVolume
        }

        val firstTick = (minVolume + 1).coerceAtMost(maxVolume)
        return restoreVolumes
            .remove(streamType)
            ?.coerceIn(firstTick, maxVolume)
            ?: firstTick
    }

    @Synchronized
    fun onVolumeChangedOutsideToggle(streamType: Int) {
        restoreVolumes.remove(streamType)
    }
}
