package com.agentkosticka.amply.overlay.ui

import android.media.AudioManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CancelPresentation
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.RingVolume
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.agentkosticka.amply.audio.ringer.NotificationAlertMode
import com.agentkosticka.amply.audio.routing.StreamIcon
import com.agentkosticka.amply.audio.routing.VolumeBarModel
import com.agentkosticka.amply.audio.routing.VolumeLimitFeedback
import com.agentkosticka.amply.audio.routing.VolumeLimitFeedbackPolicy
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.ui.theme.NothingColors
import kotlin.math.roundToInt

private val CollapsedPillWidth = 54.dp
private val ExpandControlHeight = 48.dp
private val ExpandControlIconSize = 16.dp
private val ExpandControlSideInset = (CollapsedPillWidth - ExpandControlHeight) / 2
private val OverlayCornerRadius = 27.dp

/**
 * Reads animated width state during measurement instead of composition. This confines
 * per-frame work to layout and keeps the expensive stream/app subtrees skippable.
 */

@Composable
internal fun MainVolumePill(
    modifier: Modifier = Modifier,
    streams: List<VolumeBarModel>,
    expandedWidth: Dp,
    fullExpandedWidth: Dp,
    selectedTarget: VolumeTarget,
    volumeLimitFeedback: VolumeLimitFeedback?,
    isExpanded: Boolean,
    chevronRotation: State<Float>,
    keepMediaAtEnd: Boolean,
    iconType: String,
    onStreamVolumeChange: (Int, Int) -> Unit,
    onStreamSelected: (VolumeTarget) -> Unit,
    onMuteToggle: (Int) -> Unit,
    onExpandToggle: () -> Unit,
    onInteraction: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val expandInteractionSource = remember { MutableInteractionSource() }
    val pillShape = RoundedCornerShape(OverlayCornerRadius)
    val expandControlShape = RoundedCornerShape(ExpandControlHeight / 2)
    val streamScroll = rememberScrollState()
    val animatedExpandedWidth = animateDpAsState(
        targetValue = expandedWidth,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "dynamicExpandedPillWidth"
    )
    val expansionProgress = animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "pillExpansionProgress"
    )
    val selectedStreamType = streams
        .firstOrNull { selectedTarget.streamType in it.aliases }
        ?.target?.streamType
        ?: AudioManager.STREAM_MUSIC

    LaunchedEffect(isExpanded, selectedStreamType, streams.size) {
        if (!isExpanded && streamScroll.value != 0) {
            streamScroll.animateScrollTo(0, tween(250, easing = FastOutSlowInEasing))
        } else if (fullExpandedWidth > expandedWidth) {
            val index = streams.indexOfFirst { it.target.streamType == selectedStreamType }.coerceAtLeast(0)
            val targetScroll = with(density) { (CollapsedPillWidth * index).roundToPx() }
            // Reveal the selected slot without introducing a second movement clock.
            if (streamScroll.value != targetScroll) {
                streamScroll.animateScrollTo(
                    targetScroll,
                    tween(250, easing = FastOutSlowInEasing)
                )
            }
        }
    }

    Column(
        modifier = modifier
            .dynamicWidth {
                val collapsed = CollapsedPillWidth.toPx()
                collapsed +
                    (animatedExpandedWidth.value.toPx() - collapsed) * expansionProgress.value
            }
            .wrapContentHeight()
            .graphicsLayer {
                shape = pillShape
                clip = true
            }
            .background(
                color = Color(0xFF1C1C1C),
                shape = pillShape
            )
            .padding(top = 14.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fullExpandedWidth > expandedWidth) Modifier.horizontalScroll(streamScroll) else Modifier),
        ) {
            Spacer(modifier = Modifier.width(fullExpandedWidth).height(1.dp))
            streams.forEachIndexed { index, stream ->
                val isSelected = stream.target.streamType == selectedStreamType
                val streamLimitFeedback = volumeLimitFeedback?.takeIf { feedback ->
                    feedback.target == stream.target || feedback.target.streamType in stream.aliases
                }
                val expandedSlot = if (keepMediaAtEnd) streams.lastIndex - index else index
                val expandedSlotOffsetPx = with(density) {
                    (CollapsedPillWidth * expandedSlot).toPx()
                }

                StreamVolumeColumn(
                    stream = stream,
                    enabled = stream.enabled && (isExpanded || isSelected),
                    iconType = iconType,
                    limitFeedback = streamLimitFeedback,
                    onVolumeChange = { newVolume ->
                        if (isExpanded) {
                            onStreamSelected(stream.target)
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onInteraction()
                        onStreamVolumeChange(stream.target.streamType, newVolume)
                    },
                    onMuteToggle = {
                        if (isExpanded) {
                            onStreamSelected(stream.target)
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onInteraction()
                        onMuteToggle(stream.target.streamType)
                    },
                    modifier = Modifier
                        .graphicsLayer {
                            translationX = expandedSlotOffsetPx * expansionProgress.value
                            alpha = if (isSelected) 1f else expansionProgress.value
                        }
                        .zIndex(if (isSelected) 1f else 0f)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .dynamicWidth {
                    val collapsed = ExpandControlHeight.toPx()
                    val expanded = animatedExpandedWidth.value.toPx() -
                        (ExpandControlSideInset.toPx() * 2f)
                    collapsed + (expanded - collapsed) * expansionProgress.value
                }
                .height(ExpandControlHeight)
                .clip(expandControlShape)
                .background(
                    color = if (isExpanded) NothingColors.Red.copy(alpha = 0.2f)
                    else Color(0xFF2A2A2A),
                    shape = expandControlShape
                )
                .clickable(
                    interactionSource = expandInteractionSource,
                    indication = null
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onExpandToggle()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (isExpanded) "Collapse Amply" else "Expand Amply",
                tint = if (isExpanded) NothingColors.Red else NothingColors.GreyMedium,
                modifier = Modifier
                    .size(ExpandControlIconSize)
                    .graphicsLayer {
                        rotationZ = chevronRotation.value
                    }
            )
        }
    }
}

@Composable
private fun StreamVolumeColumn(
    stream: VolumeBarModel,
    onVolumeChange: (Int) -> Unit,
    onMuteToggle: () -> Unit,
    iconType: String,
    limitFeedback: VolumeLimitFeedback?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val percentageBoundaryShake = remember { Animatable(0f) }
    val usesPercentageBoundaryFeedback = VolumeLimitFeedbackPolicy.usesPercentageBoundaryFeedback(
        isUp = limitFeedback?.isUpperBound ?: false,
        min = stream.minVolume,
        max = stream.maxVolume
    )
    val usesDotBoundaryFeedback = VolumeLimitFeedbackPolicy.usesDotBoundaryFeedback(
        isUp = limitFeedback?.isUpperBound ?: false,
        min = stream.minVolume,
        max = stream.maxVolume,
        referenceMax = stream.referenceMaxVolume
    )
    LaunchedEffect(limitFeedback?.eventId, usesPercentageBoundaryFeedback) {
        if (limitFeedback == null || !usesPercentageBoundaryFeedback) return@LaunchedEffect
        percentageBoundaryShake.snapTo(0f)
        percentageBoundaryShake.animateTo(
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
    val displayedPercentage = if (stream.maxVolume > 0) {
        ((stream.currentVolume.toFloat() / stream.maxVolume.toFloat()) * 100f)
            .roundToInt()
            .coerceIn(0, 100)
    } else {
        0
    }
    val isMediaStream = stream.target == VolumeTarget.MEDIA
    val isNotificationStream = stream.target == VolumeTarget.NOTIFICATION || stream.combinedRinger
    val isRingStream = stream.target == VolumeTarget.RING && !stream.combinedRinger
    val supportsMuteToggle = isMediaStream || isNotificationStream || isRingStream
    val semanticIcon = streamIcon(
        if (stream.combinedRinger) StreamIcon.NOTIFICATION else stream.target.icon
    )

    Column(
        modifier = modifier
            .width(CollapsedPillWidth)
            .padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(
            onClick = {
                onMuteToggle()
            },
            enabled = enabled && supportsMuteToggle,
            modifier = Modifier.size(26.dp)
        ) {
            when {
                isMediaStream -> MediaAlertIcon(
                    route = iconType,
                    muted = stream.currentVolume <= stream.minVolume,
                    label = stream.label
                )

                isNotificationStream -> NotificationAlertIcon(
                    mode = stream.notificationAlertMode ?: NotificationAlertMode.LOUD
                )

                isRingStream -> RingerAlertIcon(
                    mode = stream.notificationAlertMode ?: NotificationAlertMode.LOUD
                )

                else -> Icon(
                    imageVector = semanticIcon,
                    contentDescription = stream.label,
                    tint = if (stream.enabled) NothingColors.White else Color(0xFF444444),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "$displayedPercentage",
            color = NothingColors.White,
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .width(40.dp)
                .graphicsLayer {
                    translationX = percentageBoundaryShake.value * 4.dp.toPx()
                }
        )

        Spacer(modifier = Modifier.height(8.dp))

        DraggableDotSlider(
            currentVolume = stream.currentVolume,
            minVolume = stream.minVolume,
            maxVolume = stream.maxVolume,
            referenceMaxVolume = stream.referenceMaxVolume,
            dotCount = stream.dotCount,
            onVolumeChange = onVolumeChange,
            enabled = enabled,
            // Input stops immediately when a stream begins collapsing, but its dots
            // keep their enabled colors while the parent column fades away.
            visuallyEnabled = stream.enabled,
            limitFeedbackLevel = limitFeedback?.dotLevel?.takeIf { usesDotBoundaryFeedback },
            limitFeedbackEventId = limitFeedback?.eventId?.takeIf { usesDotBoundaryFeedback },
            accessibilityLabel = stream.label,
            modifier = Modifier
                .height(130.dp)
                .width(40.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        Icon(
            imageVector = semanticIcon,
            contentDescription = stream.label,
            tint = NothingColors.GreyMedium,
            modifier = Modifier.size(16.dp)
        )
    }
}

private fun streamIcon(icon: StreamIcon): ImageVector = when (icon) {
    // Bottom icons identify streams. Routing is shown only by the large media action icon.
    StreamIcon.MEDIA -> Icons.Default.MusicNote
    StreamIcon.ALARM -> Icons.Default.Alarm
    StreamIcon.NOTIFICATION -> Icons.Default.Notifications
    StreamIcon.CALL -> Icons.Default.Call
    StreamIcon.SYSTEM -> Icons.Default.Settings
    StreamIcon.RING -> Icons.Default.RingVolume
    StreamIcon.BLUETOOTH -> Icons.Rounded.Bluetooth
    StreamIcon.LOCKED_SOUND -> Icons.Default.Lock
    StreamIcon.DIAL_PAD -> Icons.Default.Dialpad
    StreamIcon.SPOKEN_TEXT -> Icons.Default.RecordVoiceOver
    StreamIcon.ACCESSIBILITY -> Icons.Default.Accessibility
    StreamIcon.ASSISTANT -> Icons.Default.AutoAwesome
}

@Composable
private fun MediaAlertIcon(route: String, muted: Boolean, label: String) {
    val icon = when (route) {
        "BLUETOOTH" -> if (muted) Icons.Default.BluetoothDisabled else Icons.Rounded.Bluetooth
        "CAST" -> if (muted) Icons.Default.CancelPresentation else Icons.Default.Cast
        else -> if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp
    }
    val destination = when (route) {
        "BLUETOOTH" -> "Bluetooth"
        "CAST" -> "Cast device"
        else -> "device speaker"
    }
    Icon(
        imageVector = icon,
        contentDescription = if (muted) {
            "$label muted on $destination"
        } else {
            "Mute $label on $destination"
        },
        tint = if (muted) NothingColors.Red else NothingColors.White,
        modifier = Modifier.size(18.dp)
    )
}

@Composable
private fun NotificationAlertIcon(mode: NotificationAlertMode) {
    val tint = if (mode == NotificationAlertMode.MUTED) {
        NothingColors.Red
    } else {
        NothingColors.White
    }
    val description = when (mode) {
        NotificationAlertMode.LOUD -> "Notifications use sound"
        NotificationAlertMode.VIBRATIONS -> "Notifications vibrate only"
        NotificationAlertMode.MUTED -> "Notifications are muted"
    }

    Box(
        modifier = Modifier.size(22.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (mode == NotificationAlertMode.MUTED) {
                Icons.Default.NotificationsOff
            } else {
                Icons.Default.Notifications
            },
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )

        if (mode == NotificationAlertMode.VIBRATIONS) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 1.2.dp.toPx()
                val short = size.height * 0.16f
                val long = size.height * 0.30f
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.13f, size.height * 0.34f),
                    end = Offset(size.width * 0.04f, size.height * 0.34f + short),
                    strokeWidth = stroke
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.04f, size.height * 0.50f),
                    end = Offset(size.width * 0.13f, size.height * 0.50f + short),
                    strokeWidth = stroke
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.87f, size.height * 0.35f),
                    end = Offset(size.width * 0.96f, size.height * 0.35f + long / 2),
                    strokeWidth = stroke
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.96f, size.height * 0.50f),
                    end = Offset(size.width * 0.87f, size.height * 0.50f + long / 2),
                    strokeWidth = stroke
                )
            }
        }
    }
}

@Composable
private fun RingerAlertIcon(mode: NotificationAlertMode) {
    val tint = if (mode == NotificationAlertMode.MUTED) NothingColors.Red else NothingColors.White
    val description = when (mode) {
        NotificationAlertMode.LOUD -> "Ringtone uses sound"
        NotificationAlertMode.VIBRATIONS -> "Phone vibrates for calls"
        NotificationAlertMode.MUTED -> "Ringtone is muted"
    }

    Box(
        modifier = Modifier.size(22.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = when (mode) {
                NotificationAlertMode.LOUD -> Icons.Default.RingVolume
                NotificationAlertMode.VIBRATIONS -> Icons.Default.Vibration
                NotificationAlertMode.MUTED -> Icons.Default.PhoneDisabled
            },
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(19.dp)
        )
    }
}

/**
 * Amply Panel - Per-app volume controls
 */
