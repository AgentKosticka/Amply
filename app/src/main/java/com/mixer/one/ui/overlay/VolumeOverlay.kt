package com.mixer.one.ui.overlay

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import kotlinx.coroutines.delay
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.mixer.one.data.AudioSession
import com.mixer.one.ui.theme.NothingColors

private const val TAG = "VolumeOverlay"

/**
 * Volume overlay with Nothing OS design
 * Layout: Volume pill left, mixer expands to the right (side-by-side)
 * Expand button at the bottom of the pill
 */
@Composable
fun VolumeOverlay(
    currentVolume: Int,
    maxVolume: Int,
    visible: Boolean = true,
    isMuted: Boolean = false,
    iconType: String = "MUSIC",
    sessions: List<AudioSession> = emptyList(),
    focusedApp: AudioSession? = null,
    onVolumeChange: (Int) -> Unit = {},
    onSessionVolumeChange: (Int, Float) -> Unit = { _, _ -> },
    onMuteToggle: () -> Unit = {},
    onInteraction: () -> Unit = {},
    onTouchStart: () -> Unit = {},
    onTouchEnd: () -> Unit = {}
) {
    var isExpanded by remember { mutableStateOf(false) }
    val hasActiveSessions = sessions.isNotEmpty()
    val pillTransitionState = remember {
        MutableTransitionState(false).apply {
            targetState = visible
        }
    }

    LaunchedEffect(visible) {
        pillTransitionState.targetState = visible
        if (!visible) {
            isExpanded = false
        }
    }

    // Chevron rotation animation
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "chevronRotation"
    )

    // Layout: Row with pill on left, mixer on right (side-expanding)
    Row(
        modifier = Modifier.wrapContentSize(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // Main Volume Pill
        AnimatedVisibility(
            visibleState = pillTransitionState,
            enter = slideInHorizontally(
                initialOffsetX = { -it / 2 },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) + scaleIn(
                initialScale = 0.88f,
                transformOrigin = TransformOrigin(0f, 0.5f),
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) + fadeIn(animationSpec = tween(200)),
            exit = slideOutHorizontally(
                targetOffsetX = { -it / 2 },
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) + scaleOut(
                targetScale = 0.88f,
                transformOrigin = TransformOrigin(0f, 0.5f),
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(150))
        ) {
            MainVolumePill(
                currentVolume = currentVolume,
                maxVolume = maxVolume,
                iconType = iconType,
                hasActiveSessions = hasActiveSessions,
                isExpanded = isExpanded,
                chevronRotation = chevronRotation,
                onVolumeChange = onVolumeChange,
                onMuteToggle = onMuteToggle,
                onExpandToggle = {
                    isExpanded = !isExpanded
                    onInteraction()
                },
                onInteraction = onInteraction
            )
        }

        // Mixer Panel (expands to the right, side-by-side)
        AnimatedVisibility(
            visible = isExpanded && hasActiveSessions,
            enter = slideInHorizontally(
                initialOffsetX = { -it },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) + fadeIn(animationSpec = tween(200)),
            exit = slideOutHorizontally(
                targetOffsetX = { -it },
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(150))
        ) {
            Spacer(modifier = Modifier.width(10.dp))

            MixerPanel(
                sessions = sessions,
                focusedApp = focusedApp,
                onSessionVolumeChange = { sessionId, volume ->
                    Log.d(TAG, "Session volume change: sessionId=$sessionId, volume=$volume")
                    onInteraction()
                    onSessionVolumeChange(sessionId, volume)
                },
                onClose = {
                    isExpanded = false
                    onInteraction()
                },
                onTouchStart = onTouchStart,
                onTouchEnd = onTouchEnd
            )
        }
    }
}

/**
 * Main Volume Pill - Expand button at the bottom
 */
@Composable
private fun MainVolumePill(
    currentVolume: Int,
    maxVolume: Int,
    iconType: String,
    hasActiveSessions: Boolean,
    isExpanded: Boolean,
    chevronRotation: Float,
    onVolumeChange: (Int) -> Unit,
    onMuteToggle: () -> Unit,
    onExpandToggle: () -> Unit,
    onInteraction: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    
    // OPTIMIZED: Use derivedStateOf to reduce recomputations
    val volumePercentage by remember(currentVolume, maxVolume) {
        derivedStateOf {
            if (maxVolume > 0) {
                ((currentVolume.toFloat() / maxVolume.toFloat()) * 100).toInt()
            } else {
                0
            }
        }
    }

    Column(
        modifier = Modifier
            .width(54.dp)
            .wrapContentHeight()
            .background(
                color = Color(0xFF1C1C1C),
                shape = RoundedCornerShape(27.dp)
            )
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Mute Button at top
        IconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onInteraction()
                onMuteToggle()
            },
            modifier = Modifier.size(26.dp)
        ) {
            Icon(
                imageVector = if (currentVolume == 0)
                    Icons.AutoMirrored.Filled.VolumeOff
                else
                    Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "Mute/Unmute",
                tint = if (currentVolume == 0) NothingColors.Red else NothingColors.White,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Volume Percentage
        Text(
            text = "$volumePercentage",
            color = NothingColors.White,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Vertical Dot Slider
        DraggableDotSlider(
            currentVolume = currentVolume,
            maxVolume = maxVolume,
            onVolumeChange = { newVolume ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onInteraction()
                onVolumeChange(newVolume)
            },
            modifier = Modifier
                .height(130.dp)
                .width(40.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Device Icon
        Icon(
            imageVector = when (iconType) {
                "BLUETOOTH" -> Icons.Rounded.Bluetooth
                "HEADPHONE" -> Icons.Rounded.Headphones
                else -> Icons.Default.MusicNote
            },
            contentDescription = "Audio Device",
            tint = NothingColors.GreyMedium,
            modifier = Modifier.size(16.dp)
        )

        // Expand Button at the bottom (only if sessions exist)
        if (hasActiveSessions) {
            Spacer(modifier = Modifier.height(10.dp))
            
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onExpandToggle()
                },
                modifier = Modifier
                    .size(26.dp)
                    .background(
                        color = if (isExpanded) NothingColors.Red.copy(alpha = 0.2f)
                        else Color(0xFF2A2A2A),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Expand Mixer",
                    tint = if (isExpanded) NothingColors.Red else NothingColors.GreyMedium,
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(chevronRotation)
                )
            }
        }
    }
}

/**
 * Mixer Panel - Per-app volume controls
 */
@Composable
private fun MixerPanel(
    sessions: List<AudioSession>,
    focusedApp: AudioSession?,
    onSessionVolumeChange: (Int, Float) -> Unit,
    onClose: () -> Unit,
    onTouchStart: () -> Unit = {},
    onTouchEnd: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .width(210.dp)
            .heightIn(min = 100.dp, max = 360.dp)
            .background(
                color = Color(0xFF1C1C1C),
                shape = RoundedCornerShape(18.dp)
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

            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = NothingColors.GreyDim,
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClose()
                    }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // App List
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sessions.forEach { session ->
                val isFocused = focusedApp?.uid == session.uid
                AppVolumeRow(
                    session = session,
                    isFocused = isFocused,
                    onVolumeChange = { newVolume ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        Log.d(TAG, "AppVolumeRow: ${session.appName} volume=$newVolume")
                        onSessionVolumeChange(session.sessionId, newVolume)
                    }
                )
            }
        }

        // Footer
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "${sessions.size} ${if (sessions.size == 1) "app" else "apps"}",
            color = NothingColors.GreyDim,
            fontSize = 9.sp
        )
    }
}

/**
 * Individual app volume row
 * OPTIMIZED: Debounced volume changes to reduce IPC calls
 */
@Composable
private fun AppVolumeRow(
    session: AudioSession,
    isFocused: Boolean = false,
    onVolumeChange: (Float) -> Unit
) {
    var localVolume by remember(session.sessionId) { mutableStateOf(session.volume) }
    val volumePercent = (localVolume * 100).toInt()
    
    // Debounce: Only trigger actual volume change after 80ms of no movement
    LaunchedEffect(localVolume) {
        delay(80L)
        onVolumeChange(localVolume)
    }

    val backgroundColor by animateColorAsState(
        targetValue = if (isFocused) 
            NothingColors.Red.copy(alpha = 0.1f) 
        else 
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
                    session.appIcon?.let { icon ->
                        Image(
                            bitmap = icon.toBitmap(52, 52).asImageBitmap(),
                            contentDescription = session.appName,
                            modifier = Modifier.size(20.dp)
                        )
                    } ?: run {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = session.appName,
                            tint = NothingColors.GreyMedium,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Text(
                    text = session.appName,
                    color = NothingColors.White,
                    fontSize = 11.sp,
                    fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 85.dp)
                )
                
                if (isFocused) {
                    Text(
                        text = "★",
                        color = NothingColors.Red,
                        fontSize = 9.sp
                    )
                }
            }

            Text(
                text = "$volumePercent%",
                color = if (volumePercent > 80) NothingColors.Red else NothingColors.GreyMedium,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        // Horizontal slider - only updates local state, debounce handles API call
        HorizontalDotSlider(
            volume = localVolume,
            onVolumeChange = { newVolume ->
                localVolume = newVolume
                // NOTE: Actual API call is debounced via LaunchedEffect above
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
    modifier: Modifier = Modifier
) {
    val dotCount = 12

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
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
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val y = offset.y
                    val height = size.height
                    val percentage = 1f - (y / height).coerceIn(0f, 1f)
                    val newVolume = (percentage * maxVolume).toInt().coerceIn(0, maxVolume)
                    onVolumeChange(newVolume)
                }
            }
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
