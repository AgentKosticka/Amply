package com.agentkosticka.amply.audio.routing

import com.agentkosticka.amply.audio.ringer.NotificationAlertMode
import kotlin.math.roundToInt

enum class StreamVolumeResolution { DISCRETE, FRACTIONAL }

/** Legacy Android volume streams Amply can present or route volume keys to. */

data class VolumeBarModel(
    val target: VolumeTarget,
    val aliases: Set<Int>,
    val label: String,
    val currentVolume: Int,
    val minVolume: Int,
    val maxVolume: Int,
    val active: Boolean,
    val enabled: Boolean,
    val referenceMaxVolume: Int = maxVolume.coerceAtLeast(1),
    val dotCount: Int = 16,
    val combinedRinger: Boolean = false,
    val notificationAlertMode: NotificationAlertMode? = null,
    val currentVolumeFloat: Float? = null,
    val resolution: StreamVolumeResolution = StreamVolumeResolution.DISCRETE
)

/**
 * Maps native stream indices onto the shared Nothing-style dot rail. Native
 * values are never quantized by this model; only their visual representation is.
 */
object VolumeDotScale {
    fun displayLevel(current: Int, referenceMax: Int, dotCount: Int): Int =
        ((current.coerceAtLeast(0).toFloat() / referenceMax.coerceAtLeast(1)) * dotCount)
            .roundToInt()
            .coerceIn(0, dotCount)

    fun displayLevelFloat(currentFloat: Float, referenceMax: Int, dotCount: Int): Float =
        ((currentFloat.coerceAtLeast(0f) / referenceMax.coerceAtLeast(1)) * dotCount)
            .coerceIn(0f, dotCount.toFloat())

    fun levelForFraction(
        fraction: Float,
        min: Int,
        max: Int,
        referenceMax: Int
    ): Int {
        val requested = (fraction.coerceIn(0f, 1f) * referenceMax.coerceAtLeast(1)).roundToInt()
        val lower = min.coerceAtLeast(0)
        val upper = max.coerceAtLeast(lower)
        return requested.coerceIn(lower, upper)
    }

    fun projectedLevel(nativeLevel: Int, referenceMax: Int, dotCount: Int): Int =
        ((nativeLevel.coerceAtLeast(0).toFloat() / referenceMax.coerceAtLeast(1)) * dotCount)
            .roundToInt()
            .coerceIn(0, dotCount)

    fun isLevelAvailable(
        visualLevel: Int,
        min: Int,
        max: Int,
        referenceMax: Int,
        dotCount: Int
    ): Boolean {
        val projectedMin = projectedLevel(min, referenceMax, dotCount).coerceAtLeast(1)
        val projectedMax = projectedLevel(max, referenceMax, dotCount)
        return visualLevel in projectedMin..projectedMax
    }
}

data class VolumeLimitFeedback(
    val target: VolumeTarget,
    val dotLevel: Int,
    val isUpperBound: Boolean,
    val eventId: Long
)

object VolumeLimitFeedbackPolicy {
    fun usesPercentageBoundaryFeedback(
        isUp: Boolean,
        min: Int,
        max: Int
    ): Boolean = if (isUp) max > 0 else min == 0

    fun usesDotBoundaryFeedback(
        isUp: Boolean,
        min: Int,
        max: Int,
        referenceMax: Int
    ): Boolean = if (isUp) max < referenceMax else min > 0

    fun rejectedDotLevel(
        isUp: Boolean,
        min: Int,
        max: Int,
        referenceMax: Int = 16,
        dotCount: Int = 16
    ): Int =
        if (isUp) {
            (VolumeDotScale.projectedLevel(max, referenceMax, dotCount) + 1)
                .coerceIn(1, dotCount)
        } else {
            VolumeDotScale.projectedLevel(min, referenceMax, dotCount).coerceIn(1, dotCount)
        }
}
