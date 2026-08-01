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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.agentkosticka.amply.data.OverlayAppEntry
import com.agentkosticka.amply.data.OverlaySide
import com.agentkosticka.amply.audio.NotificationAlertMode
import com.agentkosticka.amply.audio.VolumeTarget
import com.agentkosticka.amply.shizuku.VolumeServiceConnectionState
import com.agentkosticka.amply.ui.theme.NothingColors

private const val TAG = "VolumeOverlay"
private val CollapsedPillWidth = 54.dp
private val ExpandedPillWidth = 216.dp
private val PanelSpacing = 16.dp
private val ExpandControlHeight = 48.dp
private val ExpandControlIconSize = 16.dp
private val ExpandControlSideInset = (CollapsedPillWidth - ExpandControlHeight) / 2
private val PauseControlSlotHeight = 58.dp
private val OverlayCornerRadius = 27.dp

private data class OverlayStreamVolume(
    val streamType: Int,
    val currentVolume: Int,
    val maxVolume: Int,
    val icon: ImageVector,
    val contentDescription: String,
    val notificationAlertMode: NotificationAlertMode? = null
)

/**
 * Volume overlay with Nothing OS design
 * Layout: Volume pill left, per-app controls expand to the right (side-by-side)
 * Expand button at the bottom of the pill
 */
@Composable
fun VolumeOverlay(
    currentVolume: Int,
    maxVolume: Int,
    alarmVolume: Int = 0,
    maxAlarmVolume: Int = 7,
    notificationVolume: Int = 0,
    maxNotificationVolume: Int = 7,
    notificationAlertMode: NotificationAlertMode = NotificationAlertMode.LOUD,
    callVolume: Int = 0,
    maxCallVolume: Int = 5,
    selectedTarget: VolumeTarget = VolumeTarget.MEDIA,
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
    val landscapeContainerWidth = measuredAvailableWidth
        .coerceAtLeast(ExpandedPillWidth)
    val landscapePanelWidth = ExpandedPillWidth
    val overlayContainerWidth = if (isLandscape) {
        landscapeContainerWidth
    } else {
        ExpandedPillWidth
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

    val streamVolumes = listOf(
        OverlayStreamVolume(
            streamType = AudioManager.STREAM_MUSIC,
            currentVolume = currentVolume,
            maxVolume = maxVolume,
            icon = when (iconType) {
                "BLUETOOTH" -> Icons.Rounded.Bluetooth
                "HEADPHONE" -> Icons.Rounded.Headphones
                else -> Icons.Default.MusicNote
            },
            contentDescription = "Media volume"
        ),
        OverlayStreamVolume(
            streamType = AudioManager.STREAM_ALARM,
            currentVolume = alarmVolume,
            maxVolume = maxAlarmVolume,
            icon = Icons.Default.Alarm,
            contentDescription = "Alarm volume"
        ),
        OverlayStreamVolume(
            streamType = AudioManager.STREAM_NOTIFICATION,
            currentVolume = notificationVolume,
            maxVolume = maxNotificationVolume,
            icon = Icons.Default.Notifications,
            contentDescription = "Notification volume",
            notificationAlertMode = notificationAlertMode
        ),
        OverlayStreamVolume(
            streamType = AudioManager.STREAM_VOICE_CALL,
            currentVolume = callVolume,
            maxVolume = maxCallVolume,
            icon = Icons.Default.Call,
            contentDescription = "Call volume"
        )
    )

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
                selectedTarget = selectedTarget,
                isExpanded = isExpanded,
                chevronRotation = chevronRotation,
                keepMediaAtEnd = expandToStart,
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
                            .width(ExpandedPillWidth)
                            .zIndex(1f),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        pillContent()
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .width(ExpandedPillWidth)
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
                    enter = slideInVertically(
                        initialOffsetY = { -it }
                    ) + fadeIn(animationSpec = tween(180)),
                    exit = slideOutVertically(
                        targetOffsetY = { -it }
                    ) + fadeOut(animationSpec = tween(120))
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(PanelSpacing))
                        panelBody(ExpandedPillWidth, 360.dp)
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
    streams: List<OverlayStreamVolume>,
    selectedTarget: VolumeTarget,
    isExpanded: Boolean,
    chevronRotation: Float,
    keepMediaAtEnd: Boolean,
    onStreamVolumeChange: (Int, Int) -> Unit,
    onStreamSelected: (VolumeTarget) -> Unit,
    onMuteToggle: (Int) -> Unit,
    onExpandToggle: () -> Unit,
    onInteraction: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val expansionProgress by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "pillExpansionProgress"
    )
    // Keep every moving part on the same animation clock. Deriving widths and stream
    // offsets from one progress value makes rapid direction changes remain coherent.
    val pillWidth = CollapsedPillWidth +
        ((ExpandedPillWidth - CollapsedPillWidth) * expansionProgress)
    val expandedArrowWidth = ExpandedPillWidth - (ExpandControlSideInset * 2)
    val arrowWidth = ExpandControlHeight +
        ((expandedArrowWidth - ExpandControlHeight) * expansionProgress)
    val selectedStreamType = streams
        .firstOrNull { it.streamType == selectedTarget.streamType }
        ?.streamType
        ?: AudioManager.STREAM_MUSIC

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
            modifier = Modifier.width(pillWidth),
        ) {
            streams.forEachIndexed { index, stream ->
                val isSelected = stream.streamType == selectedStreamType
                val expandedSlot = if (keepMediaAtEnd) streams.lastIndex - index else index
                val streamOffset = CollapsedPillWidth * expandedSlot * expansionProgress
                val streamAlpha = if (isSelected) 1f else expansionProgress

                StreamVolumeColumn(
                    stream = stream,
                    enabled = isExpanded || isSelected,
                    onVolumeChange = { newVolume ->
                        if (isExpanded) {
                            onStreamSelected(VolumeTarget.fromStreamType(stream.streamType))
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onInteraction()
                        onStreamVolumeChange(stream.streamType, newVolume)
                    },
                    onMuteToggle = {
                        if (isExpanded) {
                            onStreamSelected(VolumeTarget.fromStreamType(stream.streamType))
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onInteraction()
                        onMuteToggle(stream.streamType)
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
    stream: OverlayStreamVolume,
    onVolumeChange: (Int) -> Unit,
    onMuteToggle: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val volumePercentage by remember(stream.currentVolume, stream.maxVolume) {
        derivedStateOf {
            if (stream.maxVolume > 0) {
                ((stream.currentVolume.toFloat() / stream.maxVolume.toFloat()) * 100).toInt()
            } else {
                0
            }
        }
    }
    val isMediaStream = stream.streamType == AudioManager.STREAM_MUSIC
    val isNotificationStream = stream.streamType == AudioManager.STREAM_NOTIFICATION
    val supportsMuteToggle = isMediaStream || isNotificationStream

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
                isMediaStream -> Icon(
                    imageVector = if (stream.currentVolume == 0) {
                        Icons.AutoMirrored.Filled.VolumeOff
                    } else {
                        Icons.AutoMirrored.Filled.VolumeUp
                    },
                    contentDescription = "Mute or restore ${stream.contentDescription}",
                    tint = if (stream.currentVolume == 0) NothingColors.Red else NothingColors.White,
                    modifier = Modifier.size(18.dp)
                )

                isNotificationStream -> NotificationAlertIcon(
                    mode = stream.notificationAlertMode ?: NotificationAlertMode.LOUD
                )

                else -> Icon(
                    imageVector = stream.icon,
                    contentDescription = stream.contentDescription,
                    tint = NothingColors.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "$volumePercentage",
            color = NothingColors.White,
            fontSize = 16.sp,
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
            maxVolume = stream.maxVolume,
            onVolumeChange = onVolumeChange,
            enabled = enabled,
            modifier = Modifier
                .height(130.dp)
                .width(40.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        Icon(
            imageVector = stream.icon,
            contentDescription = stream.contentDescription,
            tint = NothingColors.GreyMedium,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun NotificationAlertIcon(mode: NotificationAlertMode) {
    val tint = if (mode == NotificationAlertMode.MUTED) {
        NothingColors.Red
    } else {
        NothingColors.GreyMedium
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
            .background(
                color = Color(0xFF1C1C1C),
                shape = RoundedCornerShape(OverlayCornerRadius)
            )
            .padding(14.dp)
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
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Dot grid icon
                Canvas(modifier = Modifier.size(10.dp)) {
                    val dotRadius = 1.2.dp.toPx()
                    val spacing = size.width / 3
                    for (row in 0..2) {
                        for (col in 0..2) {
                            drawCircle(
                                color = NothingColors.Red,
                                radius = dotRadius,
                                center = Offset(
                                    col * spacing + spacing / 2,
                                    row * spacing + spacing / 2
                                )
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = apps,
                key = { it.packageName },
                contentType = { "app-volume" }
            ) { app ->
                AppVolumeRow(
                    app = app,
                    onVolumeChange = { newVolume ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onAppVolumeChange(app, newVolume)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "${apps.size} ${if (apps.size == 1) "app" else "apps"}",
            color = NothingColors.GreyDim,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun ShizukuDisconnectedPanel(panelWidth: Dp, shizukuIcon: Bitmap?) {
    Row(
        modifier = Modifier
            .width(panelWidth)
            .heightIn(min = 82.dp)
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
            fontSize = 11.sp
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

    val backgroundColor by animateColorAsState(
        Color(0xFF262626),
        animationSpec = tween(200),
        label = "rowBackground"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // App info row
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
                // App Icon
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(
                            color = Color(0xFF3A3A3A),
                            shape = RoundedCornerShape(7.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    app.appIconBitmap?.let { icon ->
                        Image(
                            bitmap = icon.asImageBitmap(),
                            contentDescription = app.appName,
                            modifier = Modifier.size(20.dp)
                        )
                    } ?: run {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = app.appName,
                            tint = NothingColors.GreyMedium,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Text(
                    text = app.appName,
                    color = NothingColors.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "$volumePercent%",
                color = if (volumePercent > 80) NothingColors.Red else NothingColors.GreyMedium,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        // Horizontal slider - updates local state and backend immediately
        HorizontalDotSlider(
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
 * Horizontal dot slider for per-app volume
 */
@Composable
private fun HorizontalDotSlider(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val dotCount = 14

    Canvas(
        modifier = modifier
            .height(18.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val x = change.position.x
                    val width = size.width.toFloat()
                    val newVolume = (x / width).coerceIn(0f, 1f)
                    onVolumeChange(newVolume)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val x = offset.x
                    val width = size.width.toFloat()
                    val newVolume = (x / width).coerceIn(0f, 1f)
                    onVolumeChange(newVolume)
                }
            }
    ) {
        val dotRadius = 2.8.dp.toPx()
        val spacing = size.width / (dotCount - 1)
        val filledDots = (volume * dotCount).toInt()

        for (i in 0 until dotCount) {
            val x = i * spacing
            val y = size.height / 2
            val dotPercentage = i.toFloat() / (dotCount - 1)

            val dotColor = when {
                i < filledDots -> {
                    if (dotPercentage > 0.75f) NothingColors.Red else NothingColors.White
                }
                else -> Color(0xFF444444)
            }

            drawCircle(
                color = dotColor,
                radius = dotRadius,
                center = Offset(x, y)
            )
        }
    }
}

/**
 * Vertical dot slider for main volume pill
 */
@Composable
fun DraggableDotSlider(
    currentVolume: Int,
    maxVolume: Int,
    onVolumeChange: (Int) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val dotCount = 16

    Canvas(
        modifier = modifier
            .then(
                if (enabled) {
                    Modifier
                        .pointerInput(maxVolume) {
                            detectDragGestures(
                                onDrag = { change, _ ->
                                    change.consume()
                                    val y = change.position.y
                                    val height = size.height
                                    val percentage = 1f - (y / height).coerceIn(0f, 1f)
                                    val newVolume = (percentage * maxVolume).toInt().coerceIn(0, maxVolume)
                                    onVolumeChange(newVolume)
                                }
                            )
                        }
                        .pointerInput(maxVolume) {
                            detectTapGestures { offset ->
                                val y = offset.y
                                val height = size.height
                                val percentage = 1f - (y / height).coerceIn(0f, 1f)
                                val newVolume = (percentage * maxVolume).toInt().coerceIn(0, maxVolume)
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
        val filledDots = if (maxVolume > 0) {
            ((currentVolume.toFloat() / maxVolume.toFloat()) * dotCount).toInt()
        } else {
            0
        }

        for (i in 0 until dotCount) {
            val y = size.height - (i * spacing)
            val x = size.width / 2
            val dotPercentage = i.toFloat() / (dotCount - 1)

            val dotColor = when {
                i < filledDots -> {
                    if (dotPercentage > 0.75f) NothingColors.Red else NothingColors.White
                }
                else -> Color(0xFF444444)
            }

            drawCircle(
                color = dotColor,
                radius = dotRadius,
                center = Offset(x, y)
            )
        }
    }
}
