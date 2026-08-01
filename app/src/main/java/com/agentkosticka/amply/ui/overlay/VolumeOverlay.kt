package com.agentkosticka.amply.ui.overlay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioManager
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.PowerSettingsNew
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.agentkosticka.amply.data.OverlayAppEntry
import com.agentkosticka.amply.data.OverlaySide
import com.agentkosticka.amply.audio.NotificationAlertMode
import com.agentkosticka.amply.audio.FixedVolumeDotScale
import com.agentkosticka.amply.audio.StreamIcon
import com.agentkosticka.amply.audio.VolumeBarModel
import com.agentkosticka.amply.audio.VolumeLimitFeedback
import com.agentkosticka.amply.audio.VolumeTarget
import com.agentkosticka.amply.shizuku.VolumeServiceConnectionState
import com.agentkosticka.amply.ui.theme.NothingColors
import kotlin.math.roundToInt

private const val TAG = "VolumeOverlay"
private val CollapsedPillWidth = 54.dp
private val ExpandedPillWidth = 216.dp
private val PanelSpacing = 16.dp
private val ExpandControlHeight = 48.dp
private val ExpandControlIconSize = 16.dp
private val ExpandControlSideInset = (CollapsedPillWidth - ExpandControlHeight) / 2
private val PauseControlSlotHeight = 58.dp
private val OverlayCornerRadius = 27.dp

/**
 * Volume overlay with Nothing OS design
 * Layout: Volume pill left, per-app controls expand to the right (side-by-side)
 * Expand button at the bottom of the pill
 */
@Composable
fun VolumeOverlay(
    volumeBars: List<VolumeBarModel> = emptyList(),
    selectedTarget: VolumeTarget = VolumeTarget.MEDIA,
    volumeLimitFeedback: VolumeLimitFeedback? = null,
    visible: Boolean = true,
    iconType: String = "MUSIC",
    apps: List<OverlayAppEntry> = emptyList(),
    shizukuConnectionState: VolumeServiceConnectionState = VolumeServiceConnectionState.WAITING_FOR_PERMISSION,
    shizukuIcon: Bitmap? = null,
    overlaySide: OverlaySide = OverlaySide.LEFT,
    availableWidthDp: Float = 0f,
    onStreamVolumeChange: (Int, Int) -> Unit = { _, _ -> },
    onStreamSelected: (VolumeTarget) -> Unit = {},
    onAppVolumeChange: (OverlayAppEntry, Float) -> Unit = { _, _ -> },
    onMuteToggle: (Int) -> Unit = {},
    onInteraction: () -> Unit = {},
    onTouchStart: () -> Unit = {},
    onTouchEnd: () -> Unit = {},
    onExpandedChange: (Boolean) -> Unit = {},
    onPauseAmply: () -> Unit = {},
    onDismissRequest: () -> Unit = {}
) {
    var isExpanded by remember { mutableStateOf(false) }
    val hasPanelContent = shizukuConnectionState != VolumeServiceConnectionState.CONNECTED || apps.isNotEmpty()
    val expandToStart = overlaySide == OverlaySide.RIGHT
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    var mainPillHeightPx by remember { mutableIntStateOf(0) }
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val measuredAvailableWidth = if (availableWidthDp > 0f) {
        availableWidthDp.dp
    } else {
        configuration.screenWidthDp.dp - 32.dp
    }
    val desiredPillWidth = CollapsedPillWidth * volumeBars.size.coerceAtLeast(4)
    val expandedPillWidth = desiredPillWidth.coerceAtMost(measuredAvailableWidth)
        .coerceAtLeast(ExpandedPillWidth)
    val landscapeContainerWidth = measuredAvailableWidth.coerceAtLeast(expandedPillWidth)
    val landscapePanelWidth = ExpandedPillWidth
    val overlayContainerWidth = if (isLandscape) {
        landscapeContainerWidth
    } else {
        maxOf(ExpandedPillWidth, expandedPillWidth)
    }
    val landscapePanelMaxHeight = with(density) {
        if (mainPillHeightPx > 0) mainPillHeightPx.toDp() else 360.dp
    }
    val panelTransitionState = remember {
        MutableTransitionState(false)
    }
    val pillTransitionState = remember {
        MutableTransitionState(false).apply {
            targetState = visible
        }
    }

    LaunchedEffect(visible) {
        pillTransitionState.targetState = visible
        if (!visible) {
            isExpanded = false
            onExpandedChange(false)
        }
    }

    panelTransitionState.targetState = isExpanded && hasPanelContent

    // Chevron rotation animation
    val chevronRotation by animateFloatAsState(
        targetValue = if (expandToStart) {
            if (isExpanded) 0f else 180f
        } else {
            if (isExpanded) 180f else 0f
        },
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "chevronRotation"
    )

    val streamVolumes = volumeBars

    val pillContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier.zIndex(1f),
            horizontalAlignment = if (expandToStart) Alignment.End else Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .width(CollapsedPillWidth)
                    .height(PauseControlSlotHeight),
                contentAlignment = Alignment.TopCenter
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = isExpanded,
                    enter = fadeIn(tween(180)) + slideInVertically { it },
                    exit = fadeOut(tween(120)) + slideOutVertically { it }
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(NothingColors.Red)
                            .clickable {
                                onPauseAmply()
                                onInteraction()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Pause Amply temporarily",
                            tint = NothingColors.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
            MainVolumePill(
                modifier = Modifier.onSizeChanged { mainPillHeightPx = it.height },
                streams = streamVolumes,
                expandedWidth = expandedPillWidth,
                fullExpandedWidth = desiredPillWidth,
                selectedTarget = selectedTarget,
                volumeLimitFeedback = volumeLimitFeedback,
                isExpanded = isExpanded,
                chevronRotation = chevronRotation,
                keepMediaAtEnd = expandToStart,
                iconType = iconType,
                onStreamVolumeChange = { streamType, newVolume ->
                    onStreamVolumeChange(streamType, newVolume)
                },
                onStreamSelected = onStreamSelected,
                onMuteToggle = onMuteToggle,
                onExpandToggle = {
                    val nextExpanded = !isExpanded
                    isExpanded = nextExpanded
                    onExpandedChange(nextExpanded)
                },
                onInteraction = onInteraction
            )
        }
    }

    val panelBody: @Composable (Dp, Dp) -> Unit = { panelWidth, maxHeight ->
        if (shizukuConnectionState == VolumeServiceConnectionState.CONNECTED) {
            AmplyPanel(
                panelWidth = panelWidth,
                maxHeight = maxHeight,
                apps = apps,
                onAppVolumeChange = { app, volume ->
                    Log.d(TAG, "App volume change: package=${app.packageName}, volume=$volume")
                    onInteraction()
                    onAppVolumeChange(app, volume)
                },
                onClose = {
                    isExpanded = false
                    onExpandedChange(false)
                    onInteraction()
                },
                onTouchStart = onTouchStart,
                onTouchEnd = onTouchEnd
            )
        } else {
            ShizukuDisconnectedPanel(panelWidth, shizukuIcon)
        }
    }

    AnimatedVisibility(
        visibleState = pillTransitionState,
        enter = slideInHorizontally(
            initialOffsetX = { if (expandToStart) it / 2 else -it / 2 },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ) + fadeIn(animationSpec = tween(200)),
        exit = slideOutHorizontally(
            targetOffsetX = { if (expandToStart) it / 2 else -it / 2 },
            animationSpec = tween(200, easing = FastOutSlowInEasing)
        ) + fadeOut(animationSpec = tween(150))
    ) {
        val collapsedHitTestModifier = Modifier.pointerInput(isExpanded, expandToStart, isLandscape) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!isExpanded) {
                    val collapsedWidth = CollapsedPillWidth.toPx()
                    val isInsideVisiblePill = if (expandToStart) {
                        down.position.x >= size.width - collapsedWidth
                    } else {
                        down.position.x <= collapsedWidth
                    }

                    if (!isInsideVisiblePill) {
                        down.consume()
                        onDismissRequest()
                    }
                }
            }
        }

        if (isLandscape) {
            Row(
                modifier = Modifier
                    .width(overlayContainerWidth)
                    .then(collapsedHitTestModifier),
                horizontalArrangement = if (expandToStart) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.Bottom
            ) {
                if (expandToStart) {
                    androidx.compose.animation.AnimatedVisibility(
                        visibleState = panelTransitionState,
                        enter = slideInHorizontally(
                            initialOffsetX = { it }
                        ) + fadeIn(animationSpec = tween(180)),
                        exit = slideOutHorizontally(
                            targetOffsetX = { it }
                        ) + fadeOut(animationSpec = tween(120))
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Box(
                                modifier = Modifier.width(landscapePanelWidth),
                                contentAlignment = Alignment.BottomEnd
                            ) {
                                panelBody(landscapePanelWidth, landscapePanelMaxHeight)
                            }
                            Spacer(modifier = Modifier.width(PanelSpacing))
                        }
                    }
                    Box(
                        modifier = Modifier
                            .width(expandedPillWidth)
                            .zIndex(1f),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        pillContent()
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .width(expandedPillWidth)
                            .zIndex(1f),
                        contentAlignment = Alignment.BottomStart
                    ) {
                        pillContent()
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visibleState = panelTransitionState,
                        enter = slideInHorizontally(
                            initialOffsetX = { -it }
                        ) + fadeIn(animationSpec = tween(180)),
                        exit = slideOutHorizontally(
                            targetOffsetX = { -it }
                        ) + fadeOut(animationSpec = tween(120))
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Spacer(modifier = Modifier.width(PanelSpacing))
                            Box(
                                modifier = Modifier.width(landscapePanelWidth),
                                contentAlignment = Alignment.BottomStart
                            ) {
                                panelBody(landscapePanelWidth, landscapePanelMaxHeight)
                            }
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .width(overlayContainerWidth)
                    .then(collapsedHitTestModifier),
                horizontalAlignment = if (expandToStart) Alignment.End else Alignment.Start
            ) {
                pillContent()
                AnimatedVisibility(
                    visibleState = panelTransitionState,
                    enter = fadeIn(animationSpec = tween(170)),
                    exit = fadeOut(animationSpec = tween(120))
                ) {
                    val horizontalReveal by transition.animateFloat(
                        transitionSpec = {
                            if (targetState == EnterExitState.Visible) {
                                tween(210, easing = FastOutSlowInEasing)
                            } else {
                                tween(140, easing = FastOutSlowInEasing)
                            }
                        },
                        label = "portraitPanelHorizontalReveal"
                    ) { state ->
                        if (state == EnterExitState.Visible) 1f else 0.86f
                    }
                    Column {
                        Spacer(modifier = Modifier.height(PanelSpacing))
                        Box(
                            modifier = Modifier.graphicsLayer {
                                scaleX = horizontalReveal
                                scaleY = 1f
                                transformOrigin = TransformOrigin(
                                    pivotFractionX = if (expandToStart) 1f else 0f,
                                    pivotFractionY = 0.5f
                                )
                            }
                        ) {
                            panelBody(ExpandedPillWidth, 360.dp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Main Volume Pill - Expand button at the bottom
 */
@Composable
private fun MainVolumePill(
    modifier: Modifier = Modifier,
    streams: List<VolumeBarModel>,
    expandedWidth: Dp,
    fullExpandedWidth: Dp,
    selectedTarget: VolumeTarget,
    volumeLimitFeedback: VolumeLimitFeedback?,
    isExpanded: Boolean,
    chevronRotation: Float,
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
    val streamScroll = rememberScrollState()
    val animatedExpandedWidth by animateDpAsState(
        targetValue = expandedWidth,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "dynamicExpandedPillWidth"
    )
    val expansionProgress by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "pillExpansionProgress"
    )
    // Keep every moving part on the same animation clock. Deriving widths and stream
    // offsets from one progress value makes rapid direction changes remain coherent.
    val pillWidth = CollapsedPillWidth +
        ((animatedExpandedWidth - CollapsedPillWidth) * expansionProgress)
    val expandedArrowWidth = animatedExpandedWidth - (ExpandControlSideInset * 2)
    val arrowWidth = ExpandControlHeight +
        ((expandedArrowWidth - ExpandControlHeight) * expansionProgress)
    val selectedStreamType = streams
        .firstOrNull { selectedTarget.streamType in it.aliases }
        ?.target?.streamType
        ?: AudioManager.STREAM_MUSIC

    LaunchedEffect(isExpanded, selectedStreamType, streams.size) {
        if (!isExpanded) {
            streamScroll.animateScrollTo(0, tween(250, easing = FastOutSlowInEasing))
        } else if (fullExpandedWidth > expandedWidth) {
            val index = streams.indexOfFirst { it.target.streamType == selectedStreamType }.coerceAtLeast(0)
            // Reveal the selected slot without introducing a second movement clock.
            streamScroll.animateScrollTo(
                with(density) { (CollapsedPillWidth * index).roundToPx() },
                tween(250, easing = FastOutSlowInEasing)
            )
        }
    }

    Column(
        modifier = modifier
            .width(pillWidth)
            .wrapContentHeight()
            .clip(RoundedCornerShape(OverlayCornerRadius))
            .background(
                color = Color(0xFF1C1C1C),
                shape = RoundedCornerShape(OverlayCornerRadius)
            )
            .padding(top = 14.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(pillWidth)
                .then(if (fullExpandedWidth > expandedWidth) Modifier.horizontalScroll(streamScroll) else Modifier),
        ) {
            Spacer(modifier = Modifier.width(fullExpandedWidth).height(1.dp))
            streams.forEachIndexed { index, stream ->
                val isSelected = stream.target.streamType == selectedStreamType
                val streamLimitFeedback = volumeLimitFeedback?.takeIf { feedback ->
                    feedback.target == stream.target || feedback.target.streamType in stream.aliases
                }
                val expandedSlot = if (keepMediaAtEnd) streams.lastIndex - index else index
                val streamOffset = CollapsedPillWidth * expandedSlot * expansionProgress
                val streamAlpha = if (isSelected) 1f else expansionProgress

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
                        .offset(x = streamOffset)
                        .alpha(streamAlpha)
                        .zIndex(if (isSelected) 1f else 0f)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .width(arrowWidth)
                .height(ExpandControlHeight)
                .background(
                    color = if (isExpanded) NothingColors.Red.copy(alpha = 0.2f)
                    else Color(0xFF2A2A2A),
                    shape = RoundedCornerShape(ExpandControlHeight / 2)
                )
                .clickable {
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
                    .rotate(chevronRotation)
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
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
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
            modifier = Modifier.width(40.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        DraggableDotSlider(
            currentVolume = stream.currentVolume,
            minVolume = stream.minVolume,
            maxVolume = stream.maxVolume,
            onVolumeChange = onVolumeChange,
            enabled = enabled,
            limitFeedbackLevel = limitFeedback?.dotLevel,
            limitFeedbackEventId = limitFeedback?.eventId,
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
@Composable
private fun AmplyPanel(
    panelWidth: Dp,
    maxHeight: Dp,
    apps: List<OverlayAppEntry>,
    onAppVolumeChange: (OverlayAppEntry, Float) -> Unit,
    onClose: () -> Unit,
    onTouchStart: () -> Unit = {},
    onTouchEnd: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .width(panelWidth)
            .heightIn(min = 100.dp, max = maxHeight)
            .clip(RoundedCornerShape(OverlayCornerRadius))
            .background(
                color = Color(0xFF1C1C1C),
                shape = RoundedCornerShape(OverlayCornerRadius)
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Wait for first touch down
                    awaitFirstDown(requireUnconsumed = false)
                    onTouchStart()
                    
                    // Wait for all pointers to be up
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    
                    onTouchEnd()
                }
            },
        horizontalAlignment = Alignment.Start
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
        ) {
            itemsIndexed(
                items = apps,
                key = { _, app -> app.packageName },
                contentType = { _, _ -> "app-volume" }
            ) { index, app ->
                Column {
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                                .height(1.dp)
                                .background(Color(0xFF2D2D2D))
                        )
                    }
                    AppVolumeRow(
                        app = app,
                        onVolumeChange = { newVolume ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onAppVolumeChange(app, newVolume)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ShizukuDisconnectedPanel(panelWidth: Dp, shizukuIcon: Bitmap?) {
    Row(
        modifier = Modifier
            .width(panelWidth)
            .heightIn(min = 82.dp)
            .clip(RoundedCornerShape(OverlayCornerRadius))
            .background(Color(0xFF1C1C1C), RoundedCornerShape(OverlayCornerRadius))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            if (shizukuIcon != null) {
                Image(
                    bitmap = shizukuIcon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = NothingColors.GreyMedium,
                    modifier = Modifier.size(26.dp)
                )
            }
            Canvas(modifier = Modifier.matchParentSize()) {
                drawLine(
                    color = NothingColors.Red,
                    start = Offset(size.width * 0.12f, size.height * 0.88f),
                    end = Offset(size.width * 0.88f, size.height * 0.12f),
                    strokeWidth = 3.dp.toPx()
                )
            }
        }
        Text(
            text = "Shizuku disconnected",
            color = NothingColors.GreyMedium,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * Individual app volume row
 * Updates the backend immediately on every drag or tap.
 */
@Composable
private fun AppVolumeRow(
    app: OverlayAppEntry,
    onVolumeChange: (Float) -> Unit
) {
    var localVolume by remember(app.packageName) { mutableFloatStateOf(app.volume) }
    LaunchedEffect(app.volume) {
        localVolume = app.volume
    }
    val volumePercent = (localVolume * 100).toInt()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF343434)),
                        contentAlignment = Alignment.Center
                    ) {
                        app.appIconBitmap?.let { icon ->
                            Image(
                                bitmap = icon.asImageBitmap(),
                                contentDescription = app.appName,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(7.dp))
                            )
                        } ?: Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = app.appName,
                            tint = NothingColors.GreyMedium,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    if (app.isPlaying) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(10.dp)
                                .background(Color(0xFF1C1C1C), CircleShape)
                                .padding(2.dp)
                                .background(NothingColors.Red, CircleShape)
                        )
                    }
                }

                Text(
                    text = app.appName,
                    color = NothingColors.White,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "$volumePercent%",
                color = if (volumePercent > 75) NothingColors.Red else NothingColors.GreyMedium,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        HorizontalVolumeRail(
            volume = localVolume,
            onVolumeChange = { newVolume ->
                localVolume = newVolume
                onVolumeChange(newVolume)
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Rounded continuous volume rail for per-app volume.
 */
@Composable
private fun HorizontalVolumeRail(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .height(20.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val inset = 5.dp.toPx()
                    val usableWidth = (size.width - inset * 2f).coerceAtLeast(1f)
                    val newVolume = ((change.position.x - inset) / usableWidth).coerceIn(0f, 1f)
                    onVolumeChange(newVolume)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val inset = 5.dp.toPx()
                    val usableWidth = (size.width - inset * 2f).coerceAtLeast(1f)
                    val newVolume = ((offset.x - inset) / usableWidth).coerceIn(0f, 1f)
                    onVolumeChange(newVolume)
                }
            }
    ) {
        val thumbRadius = 5.dp.toPx()
        val trackWidth = 4.dp.toPx()
        val startX = thumbRadius
        val endX = size.width - thumbRadius
        val usableWidth = (endX - startX).coerceAtLeast(1f)
        val valueX = startX + usableWidth * volume.coerceIn(0f, 1f)
        val warningX = startX + usableWidth * 0.75f
        val centerY = size.height / 2f

        drawLine(
            color = Color(0xFF444444),
            start = Offset(startX, centerY),
            end = Offset(endX, centerY),
            strokeWidth = trackWidth,
            cap = StrokeCap.Round
        )
        if (valueX > startX) {
            drawLine(
                color = NothingColors.White,
                start = Offset(startX, centerY),
                end = Offset(valueX.coerceAtMost(warningX), centerY),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round
            )
        }
        if (valueX > warningX) {
            drawLine(
                color = NothingColors.Red,
                start = Offset(warningX, centerY),
                end = Offset(valueX, centerY),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round
            )
        }
        drawCircle(
            color = if (volume > 0.75f) NothingColors.Red else NothingColors.White,
            radius = thumbRadius,
            center = Offset(valueX, centerY)
        )
    }
}

/**
 * Vertical dot slider for main volume pill
 */
@Composable
fun DraggableDotSlider(
    currentVolume: Int,
    minVolume: Int = 0,
    maxVolume: Int,
    onVolumeChange: (Int) -> Unit,
    enabled: Boolean = true,
    limitFeedbackLevel: Int? = null,
    limitFeedbackEventId: Long? = null,
    modifier: Modifier = Modifier
) {
    val dotCount = FixedVolumeDotScale.MAX_LEVEL
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
                        .pointerInput(minVolume, maxVolume) {
                            detectDragGestures(
                                onDrag = { change, _ ->
                                    change.consume()
                                    val y = change.position.y
                                    val height = size.height
                                    val percentage = 1f - (y / height).coerceIn(0f, 1f)
                                    val newVolume = FixedVolumeDotScale.levelForFraction(
                                        percentage,
                                        minVolume,
                                        maxVolume
                                    )
                                    onVolumeChange(newVolume)
                                }
                            )
                        }
                        .pointerInput(minVolume, maxVolume) {
                            detectTapGestures { offset ->
                                val y = offset.y
                                val height = size.height
                                val percentage = 1f - (y / height).coerceIn(0f, 1f)
                                val newVolume = FixedVolumeDotScale.levelForFraction(
                                    percentage,
                                    minVolume,
                                    maxVolume
                                )
                                onVolumeChange(newVolume)
                            }
                        }
                } else {
                    Modifier
                }
            )
    ) {
        val dotRadius = 3.5.dp.toPx()
        val spacing = size.height / (dotCount - 1)
        val filledDots = FixedVolumeDotScale.displayLevel(currentVolume)

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
            val available = FixedVolumeDotScale.isLevelAvailable(level, minVolume, maxVolume)

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
            if (minVolume > 0 && level == minVolume && available) {
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
