package com.agentkosticka.amply.overlay.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.agentkosticka.amply.audio.routing.VolumeDotScale
import com.agentkosticka.amply.ui.theme.NothingColors
import kotlin.math.roundToInt

/**
 * Reads animated width state during measurement instead of composition. This confines
 * per-frame work to layout and keeps the expensive stream/app subtrees skippable.
 */

@Composable
fun DraggableDotSlider(
    currentVolume: Int,
    maxVolume: Int,
    onVolumeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minVolume: Int = 0,
    referenceMaxVolume: Int = maxVolume,
    dotCount: Int = 16,
    enabled: Boolean = true,
    visuallyEnabled: Boolean = enabled,
    limitFeedbackLevel: Int? = null,
    limitFeedbackEventId: Long? = null,
    accessibilityLabel: String = "Volume"
) {
    val rejectedDotShake = remember { Animatable(0f) }

    LaunchedEffect(limitFeedbackEventId) {
        if (limitFeedbackEventId == null || limitFeedbackLevel == null) return@LaunchedEffect
        rejectedDotShake.snapTo(0f)
        rejectedDotShake.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 230
                0f at 0
                -1f at 35
                1f at 75
                -0.75f at 115
                0.55f at 155
                -0.25f at 195
                0f at 230
            }
        )
    }

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics {
                contentDescription = accessibilityLabel
                stateDescription = "$currentVolume of $maxVolume"
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = currentVolume.toFloat(),
                    range = minVolume.toFloat()..maxVolume.toFloat(),
                    steps = (maxVolume - minVolume - 1).coerceAtLeast(0)
                )
                if (enabled) {
                    setProgress { requested ->
                        onVolumeChange(requested.roundToInt().coerceIn(minVolume, maxVolume))
                        true
                    }
                } else {
                    disabled()
                }
            }
            .then(
                if (enabled) {
                    Modifier
                        .pointerInput(minVolume, maxVolume, referenceMaxVolume) {
                            var lastEmittedVolume: Int? = null
                            detectDragGestures(
                                onDragStart = { lastEmittedVolume = null },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val y = change.position.y
                                    val height = size.height
                                    val percentage = 1f - (y / height).coerceIn(0f, 1f)
                                    val newVolume = VolumeDotScale.levelForFraction(
                                        percentage,
                                        minVolume,
                                        maxVolume,
                                        referenceMaxVolume
                                    )
                                    if (newVolume != lastEmittedVolume) {
                                        lastEmittedVolume = newVolume
                                        onVolumeChange(newVolume)
                                    }
                                }
                            )
                        }
                        .pointerInput(minVolume, maxVolume, referenceMaxVolume) {
                            detectTapGestures { offset ->
                                val y = offset.y
                                val height = size.height
                                val percentage = 1f - (y / height).coerceIn(0f, 1f)
                                val newVolume = VolumeDotScale.levelForFraction(
                                    percentage,
                                    minVolume,
                                    maxVolume,
                                    referenceMaxVolume
                                )
                                onVolumeChange(newVolume)
                            }
                        }
                } else {
                    Modifier
                }
            ),
        propagateMinConstraints = true
    ) {
        Canvas(modifier = modifier) {
            val safeDotCount = dotCount.coerceAtLeast(1)
            val spacing = if (safeDotCount == 1) 0f else size.height / (safeDotCount - 1)
            val dotRadius = minOf(3.5.dp.toPx(), (spacing * 0.34f).coerceAtLeast(0.75.dp.toPx()))
            val filledDots = VolumeDotScale.displayLevel(currentVolume, referenceMaxVolume, dotCount)
            val projectedMin = VolumeDotScale.projectedLevel(
                minVolume,
                referenceMaxVolume,
                dotCount
            ).coerceAtLeast(1)

            for (i in 0 until safeDotCount) {
                val y = size.height - (i * spacing)
                val level = i + 1
                val shakeX = if (level == limitFeedbackLevel) {
                    rejectedDotShake.value * 3.5.dp.toPx()
                } else {
                    0f
                }
                val x = size.width / 2 + shakeX
                val dotPercentage = if (safeDotCount == 1) 0f else i.toFloat() / (safeDotCount - 1)
                val available = VolumeDotScale.isLevelAvailable(
                    level,
                    minVolume,
                    maxVolume,
                    referenceMaxVolume,
                    dotCount
                )

                val dotColor = when {
                    !visuallyEnabled || !available -> Color(0xFF343434)
                    level <= filledDots -> {
                        if (dotPercentage > 0.75f) NothingColors.Red else NothingColors.White
                    }
                    else -> Color(0xFF444444)
                }

                drawCircle(
                    color = dotColor,
                    radius = dotRadius,
                    center = Offset(x, y)
                )
                if (visuallyEnabled && minVolume > 0 && level == projectedMin && available) {
                    drawCircle(
                        color = NothingColors.GreyMedium.copy(alpha = 0.7f),
                        radius = dotRadius + 2.dp.toPx(),
                        center = Offset(x, y),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
                if (!available) {
                    val crossRadius = dotRadius * 0.72f
                    val crossColor = NothingColors.GreyMedium.copy(alpha = 0.55f)
                    drawLine(
                        color = crossColor,
                        start = Offset(x - crossRadius, y - crossRadius),
                        end = Offset(x + crossRadius, y + crossRadius),
                        strokeWidth = 1.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = crossColor,
                        start = Offset(x + crossRadius, y - crossRadius),
                        end = Offset(x - crossRadius, y + crossRadius),
                        strokeWidth = 1.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}
