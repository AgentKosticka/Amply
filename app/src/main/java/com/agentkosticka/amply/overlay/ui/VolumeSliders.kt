package com.agentkosticka.amply.overlay.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
    accessibilityLabel: String = "Volume",
    currentVolumeFloat: Float? = null,
    onVolumeFloatChange: ((Float) -> Unit)? = null
) {
    // Use rememberUpdatedState so pointerInput blocks always see the latest callbacks
    // without needing to restart the gesture when the lambda identity changes.
    val latestOnVolumeChange by rememberUpdatedState(onVolumeChange)
    val latestOnVolumeFloatChange by rememberUpdatedState(onVolumeFloatChange)

    val rejectedDotShake = remember { Animatable(0f) }

    // Local drag state — owned by this composable so it updates the canvas
    // frame-synchronously without waiting for the round-trip to the backend.
    var isDragging by remember { mutableStateOf(false) }
    var dragFloatValue by remember { mutableFloatStateOf(0f) }

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

    // During a drag we display the locally-tracked float so the dots update
    // every frame. Outside a drag we fall back to the value pushed by the model.
    val modelVolume = currentVolumeFloat ?: currentVolume.toFloat()
    val animatedModelVolume by animateFloatAsState(
        targetValue = modelVolume,
        animationSpec = tween(110, easing = FastOutSlowInEasing),
        label = "streamVolumeDots"
    )
    val activeVolumeFloat = if (isDragging) dragFloatValue else animatedModelVolume

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics {
                contentDescription = accessibilityLabel
                stateDescription = "$currentVolume of $maxVolume"
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = activeVolumeFloat,
                    range = minVolume.toFloat()..maxVolume.toFloat(),
                    steps = (maxVolume - minVolume - 1).coerceAtLeast(0)
                )
                if (enabled) {
                    setProgress { requested ->
                        latestOnVolumeChange(requested.roundToInt().coerceIn(minVolume, maxVolume))
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
                            fun fractionFor(offset: Offset): Float {
                                val height = size.height.coerceAtLeast(1)
                                return 1f - (offset.y / height).coerceIn(0f, 1f)
                            }

                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var dragging = false
                                var lastEmittedVolume: Int? = null
                                var lastEmittedFloat: Float? = null

                                fun emitAt(offset: Offset, force: Boolean = false) {
                                    val fraction = fractionFor(offset)
                                    val floatFn = latestOnVolumeFloatChange
                                    if (floatFn != null) {
                                        val value = (fraction * referenceMaxVolume.coerceAtLeast(1))
                                            .coerceIn(minVolume.toFloat(), maxVolume.toFloat())
                                        dragFloatValue = value
                                        if (force || lastEmittedFloat == null ||
                                            kotlin.math.abs(value - lastEmittedFloat!!) >= 0.01f
                                        ) {
                                            lastEmittedFloat = value
                                            floatFn(value)
                                        }
                                    } else {
                                        val value = VolumeDotScale.levelForFraction(
                                            fraction, minVolume, maxVolume, referenceMaxVolume
                                        )
                                        dragFloatValue = value.toFloat()
                                        if (value != lastEmittedVolume) {
                                            lastEmittedVolume = value
                                            latestOnVolumeChange(value)
                                        }
                                    }
                                }

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        emitAt(change.position, force = dragging)
                                        break
                                    }
                                    if (!dragging &&
                                        (change.position - down.position).getDistance() >= viewConfiguration.touchSlop
                                    ) {
                                        dragging = true
                                        isDragging = true
                                    }
                                    if (dragging) {
                                        change.consume()
                                        emitAt(change.position)
                                    }
                                }
                                isDragging = false
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

            val filledDotsFloat =
                VolumeDotScale.displayLevelFloat(activeVolumeFloat, referenceMaxVolume, dotCount)
            val fullLitDots = filledDotsFloat.toInt()
            val partialFraction = filledDotsFloat - fullLitDots
            val partialDotLevel = fullLitDots + 1

            val projectedMin = VolumeDotScale.projectedLevel(
                minVolume, referenceMaxVolume, dotCount
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
                    level, minVolume, maxVolume, referenceMaxVolume, dotCount
                )

                val activeColor = if (dotPercentage > 0.75f) NothingColors.Red else NothingColors.White
                val inactiveColor = Color(0xFF444444)
                val disabledColor = Color(0xFF343434)

                when {
                    !visuallyEnabled || !available -> drawCircle(
                        color = disabledColor, radius = dotRadius, center = Offset(x, y)
                    )
                    level <= fullLitDots -> drawCircle(
                        color = activeColor, radius = dotRadius, center = Offset(x, y)
                    )
                    level == partialDotLevel && partialFraction > 0.01f -> {
                        drawCircle(color = inactiveColor, radius = dotRadius, center = Offset(x, y))
                        drawCircle(
                            color = activeColor.copy(alpha = partialFraction),
                            radius = dotRadius * (0.72f + 0.28f * partialFraction),
                            center = Offset(x, y)
                        )
                    }
                    else -> drawCircle(
                        color = inactiveColor, radius = dotRadius, center = Offset(x, y)
                    )
                }

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
