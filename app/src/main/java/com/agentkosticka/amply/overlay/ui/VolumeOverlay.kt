package com.agentkosticka.amply.overlay.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.agentkosticka.amply.audio.session.AppVolumeTarget
import com.agentkosticka.amply.settings.model.OverlaySide
import com.agentkosticka.amply.audio.routing.VolumeBarModel
import com.agentkosticka.amply.audio.routing.VolumeLimitFeedback
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState
import com.agentkosticka.amply.ui.theme.NothingColors
import kotlin.math.roundToInt

private val CollapsedPillWidth = 54.dp
private val ExpandedPillWidth = 216.dp
private val PanelSpacing = 16.dp
private val ExpandControlHeight = 48.dp
private val PauseControlSlotHeight = 58.dp
private val OverlayCornerRadius = 27.dp

/**
 * Reads animated width state during measurement instead of composition. This confines
 * per-frame work to layout and keeps the expensive stream/app subtrees skippable.
 */
internal fun Modifier.dynamicWidth(widthPx: Density.() -> Float): Modifier = layout {
    measurable, constraints ->
    val width = widthPx().roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth)
    val placeable = measurable.measure(
        constraints.copy(minWidth = width, maxWidth = width)
    )
    layout(width, placeable.height) {
        placeable.placeRelative(0, 0)
    }
}

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
    apps: List<OverlayAppPresentation> = emptyList(),
    shizukuConnectionState: VolumeServiceConnectionState = VolumeServiceConnectionState.WAITING_FOR_PERMISSION,
    shizukuIcon: Bitmap? = null,
    showShizukuDisconnectedWarning: Boolean = true,
    overlaySide: OverlaySide = OverlaySide.LEFT,
    availableWidthDp: Float = 0f,
    onStreamVolumeChange: (Int, Int) -> Unit = { _, _ -> },
    onStreamSelected: (VolumeTarget) -> Unit = {},
    onAppVolumeChange: (AppVolumeTarget, Float) -> Unit = { _, _ -> },
    onMuteToggle: (Int) -> Unit = {},
    onInteraction: () -> Unit = {},
    onTouchStart: () -> Unit = {},
    onTouchEnd: () -> Unit = {},
    onExpandedChange: (Boolean) -> Unit = {},
    onPauseAmply: () -> Unit = {},
    onDismissRequest: () -> Unit = {}
) {
    var isExpanded by remember { mutableStateOf(false) }
    val hasPanelContent = apps.isNotEmpty() ||
        (showShizukuDisconnectedWarning && shizukuConnectionState != VolumeServiceConnectionState.CONNECTED)
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
    val chevronRotation = animateFloatAsState(
        targetValue = if (expandToStart) {
            if (isExpanded) 0f else 180f
        } else {
            if (isExpanded) 180f else 0f
        },
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "chevronRotation"
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
                streams = volumeBars,
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
        } else if (showShizukuDisconnectedWarning) {
            ShizukuDisconnectedPanel(panelWidth, shizukuIcon)
        }
    }

    AnimatedVisibility(
        visibleState = pillTransitionState,
        enter = slideInHorizontally(
            initialOffsetX = { if (expandToStart) it / 2 else -it / 2 },
            // A monotonic tween avoids the spring's late overshoot, which becomes
            // visible now that the overlay composition is reused between appearances.
            animationSpec = tween(220, easing = FastOutSlowInEasing)
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
                    val horizontalReveal = transition.animateFloat(
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
                                scaleX = horizontalReveal.value
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
