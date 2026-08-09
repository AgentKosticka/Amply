package com.agentkosticka.amply.overlay.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agentkosticka.amply.audio.session.AppVolumeTarget
import com.agentkosticka.amply.audio.session.AppVolumeControlState
import com.agentkosticka.amply.settings.model.appDisplayName
import com.agentkosticka.amply.settings.model.appProfileFallbackLabel
import com.agentkosticka.amply.ui.theme.NothingColors
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

private val OverlayCornerRadius = 27.dp

/**
 * Reads animated width state during measurement instead of composition. This confines
 * per-frame work to layout and keeps the expensive stream/app subtrees skippable.
 */

@Composable
internal fun AmplyPanel(
    panelWidth: Dp,
    maxHeight: Dp,
    apps: List<OverlayAppPresentation>,
    showAppProfileIdentity: Boolean = false,
    onAppVolumeChange: (AppVolumeTarget, Float) -> Unit,
    onTouchStart: () -> Unit = {},
    onTouchEnd: () -> Unit = {}
) {
    val panelShape = RoundedCornerShape(OverlayCornerRadius)

    Column(
        modifier = Modifier
            .width(panelWidth)
            .heightIn(min = 100.dp, max = maxHeight)
            .clip(panelShape)
            .background(
                color = Color(0xFF1C1C1C),
                shape = panelShape
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
                key = { _, app -> app.identity.storageKey },
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
                        showProfileIdentity = showAppProfileIdentity,
                        onVolumeChange = { newVolume -> onAppVolumeChange(app.target, newVolume) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun ShizukuDisconnectedPanel(panelWidth: Dp, shizukuIcon: Bitmap?) {
    val panelShape = RoundedCornerShape(OverlayCornerRadius)
    val shizukuImage = remember(shizukuIcon) { shizukuIcon?.asImageBitmap() }
    Row(
        modifier = Modifier
            .width(panelWidth)
            .heightIn(min = 82.dp)
            .clip(panelShape)
            .background(Color(0xFF1C1C1C), panelShape)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            if (shizukuImage != null) {
                Image(
                    bitmap = shizukuImage,
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
    app: OverlayAppPresentation,
    showProfileIdentity: Boolean,
    onVolumeChange: (Float) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var localVolume by remember(app.identity) { mutableFloatStateOf(app.volume) }
    var isDragging by remember(app.identity) { mutableStateOf(false) }
    var pendingCommittedVolume by remember(app.identity) { mutableStateOf<Float?>(null) }
    val latestBackendVolume by rememberUpdatedState(app.volume)
    var lastHapticStep by remember(app.identity) {
        mutableIntStateOf((app.volume.coerceIn(0f, 1f) * 20f).toInt())
    }
    LaunchedEffect(app.volume) {
        if (!isDragging) {
            val pending = pendingCommittedVolume
            if (pending == null || abs(app.volume - pending) < 0.002f) {
                localVolume = app.volume
                pendingCommittedVolume = null
            }
        }
    }
    LaunchedEffect(pendingCommittedVolume) {
        val pending = pendingCommittedVolume ?: return@LaunchedEffect
        delay(600L.milliseconds)
        if (pendingCommittedVolume == pending && !isDragging) {
            pendingCommittedVolume = null
            localVolume = latestBackendVolume
        }
    }
    val volumePercent = (localVolume * 100).toInt()
    val personalUserId = android.os.Process.myUid() / 100_000
    val displayName = appDisplayName(
        appName = app.target.appName,
        userId = app.identity.userId,
        personalUserId = personalUserId,
        hideProfileIdentity = !showProfileIdentity
    )
    val profileFallbackLabel = appProfileFallbackLabel(
        appName = app.target.appName,
        userId = app.identity.userId,
        personalUserId = personalUserId,
        hideProfileIdentity = !showProfileIdentity
    )
    val accessibleName = listOfNotNull(displayName, profileFallbackLabel).joinToString(" ")

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
                        app.icon?.let { icon ->
                            Image(
                                bitmap = icon,
                                contentDescription = accessibleName,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(7.dp))
                            )
                        } ?: Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = accessibleName,
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

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        color = NothingColors.White,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    profileFallbackLabel?.let { label ->
                        Text(
                            text = label,
                            color = NothingColors.GreyMedium,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (app.controlState == AppVolumeControlState.PARTIAL ||
                    app.controlState == AppVolumeControlState.UNAVAILABLE
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = if (app.controlState == AppVolumeControlState.PARTIAL) {
                            "Some players could not be controlled"
                        } else {
                            "Volume unavailable for this playback"
                        },
                        tint = if (app.controlState == AppVolumeControlState.UNAVAILABLE) {
                            NothingColors.Red
                        } else NothingColors.GreyMedium,
                        modifier = Modifier.size(13.dp)
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
        }

        HorizontalVolumeRail(
            volume = localVolume,
            accessibilityLabel = "$accessibleName volume",
            enabled = app.controlState != AppVolumeControlState.UNAVAILABLE,
            onDragStart = {
                pendingCommittedVolume = null
                isDragging = true
            },
            onVolumeChange = { newVolume ->
                localVolume = newVolume
                val hapticStep = (newVolume.coerceIn(0f, 1f) * 20f).toInt()
                if (hapticStep != lastHapticStep) {
                    lastHapticStep = hapticStep
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                onVolumeChange(newVolume)
            },
            onValueCommitted = { finalVolume ->
                localVolume = finalVolume
                pendingCommittedVolume = finalVolume
                isDragging = false
                onVolumeChange(finalVolume)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
    onDragStart: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onValueCommitted: (Float) -> Unit,
    accessibilityLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Canvas(
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = accessibilityLabel
                stateDescription = "${(volume.coerceIn(0f, 1f) * 100).toInt()} percent"
                progressBarRangeInfo = ProgressBarRangeInfo(volume.coerceIn(0f, 1f), 0f..1f, 99)
                if (enabled) {
                    setProgress { requested ->
                        val adjusted = requested.coerceIn(0f, 1f)
                        onVolumeChange(adjusted)
                        onValueCommitted(adjusted)
                        true
                    }
                } else {
                    disabled()
                }
            }
            .then(if (enabled) Modifier.pointerInput(Unit) {
                var latestVolume = volume
                detectHorizontalDragGestures(
                    onDragStart = {
                        latestVolume = volume
                        onDragStart()
                    },
                    onDragEnd = { onValueCommitted(latestVolume) },
                    onDragCancel = { onValueCommitted(latestVolume) }
                ) { change, _ ->
                    change.consume()
                    val inset = 5.dp.toPx()
                    val usableWidth = (size.width - inset * 2f).coerceAtLeast(1f)
                    latestVolume = ((change.position.x - inset) / usableWidth).coerceIn(0f, 1f)
                    onVolumeChange(latestVolume)
                }
            } else Modifier)
            .then(if (enabled) Modifier.pointerInput(Unit) {
                detectTapGestures { offset ->
                    val inset = 5.dp.toPx()
                    val usableWidth = (size.width - inset * 2f).coerceAtLeast(1f)
                    val newVolume = ((offset.x - inset) / usableWidth).coerceIn(0f, 1f)
                    onVolumeChange(newVolume)
                    onValueCommitted(newVolume)
                }
            } else Modifier)
    ) {
        val thumbRadius = 5.dp.toPx()
        val trackWidth = 4.dp.toPx()
        val endX = size.width - thumbRadius
        val usableWidth = (endX - thumbRadius).coerceAtLeast(1f)
        val valueX = thumbRadius + usableWidth * volume.coerceIn(0f, 1f)
        val warningX = thumbRadius + usableWidth * 0.75f
        val centerY = size.height / 2f

        drawLine(
            color = if (enabled) Color(0xFF444444) else Color(0xFF303030),
            start = Offset(thumbRadius, centerY),
            end = Offset(endX, centerY),
            strokeWidth = trackWidth,
            cap = StrokeCap.Round
        )
        if (valueX > thumbRadius) {
            drawLine(
                color = if (enabled) NothingColors.White else NothingColors.GreyDim,
                start = Offset(thumbRadius, centerY),
                end = Offset(valueX.coerceAtMost(warningX), centerY),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round
            )
        }
        if (valueX > warningX) {
            drawLine(
                color = if (enabled) NothingColors.Red else NothingColors.GreyDim,
                start = Offset(warningX, centerY),
                end = Offset(valueX, centerY),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round
            )
        }
        drawCircle(
            color = if (!enabled) NothingColors.GreyDim else if (volume > 0.75f) NothingColors.Red else NothingColors.White,
            radius = thumbRadius,
            center = Offset(valueX, centerY)
        )
    }
}

/**
 * Vertical dot slider for main volume pill
 */
