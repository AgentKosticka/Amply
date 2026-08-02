package com.agentkosticka.amply.overlay.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.agentkosticka.amply.audio.routing.VolumeDotScale
import com.agentkosticka.amply.ui.theme.NothingColors

private val CollapsedPillWidth = 54.dp
private val ExpandControlHeight = 48.dp

/**
 * Reads animated width state during measurement instead of composition. This confines
 * per-frame work to layout and keeps the expensive stream/app subtrees skippable.
 */

@Composable
fun DraggableDotSlider(
    currentVolume: Int,
    minVolume: Int = 0,
    maxVolume: Int,
    referenceMaxVolume: Int = maxVolume,
    dotCount: Int = 16,
    onVolumeChange: (Int) -> Unit,
    enabled: Boolean = true,
    limitFeedbackLevel: Int? = null,
    limitFeedbackEventId: Long? = null,
    modifier: Modifier = Modifier
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

    Canvas(
        modifier = modifier
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
            )
    ) {
        val spacing = if (dotCount <= 1) 0f else size.height / (dotCount - 1)
        val dotRadius = minOf(3.5.dp.toPx(), (spacing * 0.34f).coerceAtLeast(0.75.dp.toPx()))
        val filledDots = VolumeDotScale.displayLevel(currentVolume, referenceMaxVolume, dotCount)
        val projectedMin = VolumeDotScale.projectedLevel(
            minVolume,
            referenceMaxVolume,
            dotCount
        ).coerceAtLeast(1)

        for (i in 0 until dotCount) {
            val y = size.height - (i * spacing)
            val level = i + 1
            val shakeX = if (level == limitFeedbackLevel) {
                rejectedDotShake.value * 3.5.dp.toPx()
            } else {
                0f
            }
            val x = size.width / 2 + shakeX
            val dotPercentage = i.toFloat() / (dotCount - 1)
            val available = VolumeDotScale.isLevelAvailable(
                level,
                minVolume,
                maxVolume,
                referenceMaxVolume,
                dotCount
            )

            val dotColor = when {
                !enabled || !available -> Color(0xFF343434)
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
            if (enabled && minVolume > 0 && level == projectedMin && available) {
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
