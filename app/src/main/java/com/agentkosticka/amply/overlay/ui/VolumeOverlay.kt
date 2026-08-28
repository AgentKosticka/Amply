package com.agentkosticka.amply.overlay.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.agentkosticka.amply.audio.routing.VolumeBarModel
import com.agentkosticka.amply.audio.routing.VolumeLimitFeedback
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.audio.session.AppVolumeTarget
import com.agentkosticka.amply.profiles.AudioProfile
import com.agentkosticka.amply.settings.model.OverlaySide
import com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState
import com.agentkosticka.amply.ui.theme.NothingColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val CollapsedPillWidth = 54.dp
private val ExpandedPillWidth = 216.dp
private val PanelSpacing = 16.dp
private val PauseControlSlotHeight = 58.dp

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
        placeable.place(0, 0)
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
    showAppProfileIdentity: Boolean = false,
    showStandDownButton: Boolean = true,
    showDndButton: Boolean = false,
    dndActive: Boolean = false,
    profiles: List<AudioProfile> = emptyList(),
    activeProfileId: String? = null,
    profileApplying: Boolean = false,
    overlaySide: OverlaySide = OverlaySide.LEFT,
    availableWidthDp: Float = 0f,
    initiallyExpanded: Boolean = false,
    expanded: Boolean? = null,
    onStreamVolumeChange: (Int, Int) -> Unit = { _, _ -> },
    onStreamSelected: (VolumeTarget) -> Unit = {},
    onAppVolumeChange: (AppVolumeTarget, Float) -> Unit = { _, _ -> },
    onMuteToggle: (Int) -> Unit = {},
    onInteraction: () -> Unit = {},
    onTouchStart: () -> Unit = {},
    onTouchEnd: () -> Unit = {},
    onExpandedChange: (Boolean) -> Unit = {},
    onPauseAmply: () -> Unit = {},
    onDndToggle: () -> Unit = {},
    onProfileActivate: (String) -> Unit = {},
    onDismissRequest: () -> Unit = {}
) {
    var internalExpanded by remember { mutableStateOf(initiallyExpanded) }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    var switchingProfileId by remember { mutableStateOf<String?>(null) }
    val isExpanded = expanded ?: internalExpanded
    val canSwitchProfiles = profiles.size > 1
    val showCollapsedDnd = selectedTarget == VolumeTarget.RING ||
        selectedTarget == VolumeTarget.NOTIFICATION
    val dndCollapsedSize = animateDpAsState(
        targetValue = if (!isExpanded && showCollapsedDnd) CollapsedPillWidth else 48.dp,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "dndCollapsedSize"
    )
    val dndCollapsedTranslation = animateFloatAsState(
        targetValue = with(LocalDensity.current) {
            if (!isExpanded && showCollapsedDnd) {
                val hiddenSlots = listOf(canSwitchProfiles, showStandDownButton).count { it }
                val direction = if (overlaySide == OverlaySide.RIGHT) 1f else -1f
                CollapsedPillWidth.toPx() * hiddenSlots * direction
            } else {
                0f
            }
        },
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "dndCollapsedTranslation"
    )
    fun updateExpanded(value: Boolean) {
        internalExpanded = value
        onExpandedChange(value)
    }
    val hasPanelContent = profileMenuExpanded || apps.isNotEmpty() ||
        (showShizukuDisconnectedWarning && shizukuConnectionState != VolumeServiceConnectionState.CONNECTED)
    val panelSwapTransition = updateTransition(
        targetState = profileMenuExpanded,
        label = "profilePanelSwap"
    )
    val panelSwapProgress by panelSwapTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(380, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
            } else {
                tween(280, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f))
            }
        },
        label = "profilePanelSwapProgress"
    ) { showProfiles ->
        if (showProfiles) 1f else 0f
    }
    val profilePanelAlpha by panelSwapTransition.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = if (targetState) 240 else 190,
                easing = LinearEasing
            )
        },
        label = "profilePanelSwapAlpha"
    ) { showProfiles ->
        if (showProfiles) 1f else 0f
    }
    val expandToStart = overlaySide == OverlaySide.RIGHT
    val density = LocalDensity.current
    val containerSize = LocalWindowInfo.current.containerSize
    var mainPillHeightPx by remember { mutableIntStateOf(0) }
    var appPanelHeightPx by remember { mutableIntStateOf(0) }
    var profilePanelHeightPx by remember { mutableIntStateOf(0) }
    val isLandscape = containerSize.width > containerSize.height
    val measuredAvailableWidth = if (availableWidthDp > 0f) {
        availableWidthDp.dp
    } else {
        with(density) { (containerSize.width - 32.dp.roundToPx()).coerceAtLeast(1).toDp() }
    }
    val desiredPillWidth = CollapsedPillWidth * volumeBars.size.coerceAtLeast(4)
    val maximumWidth = measuredAvailableWidth.coerceAtLeast(CollapsedPillWidth)
    val minimumExpandedWidth = ExpandedPillWidth.coerceAtMost(maximumWidth)
    val expandedPillWidth = desiredPillWidth.coerceIn(minimumExpandedWidth, maximumWidth)
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
            updateExpanded(false)
        }
    }

    LaunchedEffect(isExpanded, canSwitchProfiles) {
        if (!isExpanded || !canSwitchProfiles) {
            profileMenuExpanded = false
            switchingProfileId = null
        }
    }

    LaunchedEffect(activeProfileId, profileApplying, switchingProfileId) {
        val requestedId = switchingProfileId ?: return@LaunchedEffect
        if (activeProfileId == requestedId && !profileApplying) {
            delay(260)
            if (switchingProfileId == requestedId) {
                profileMenuExpanded = false
                switchingProfileId = null
                onInteraction()
            }
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
            horizontalAlignment = if (expandToStart) {
                androidx.compose.ui.AbsoluteAlignment.Right
            } else {
                androidx.compose.ui.AbsoluteAlignment.Left
            }
        ) {
            if (showStandDownButton || showDndButton || canSwitchProfiles) {
                Box(
                    modifier = Modifier
                        .width(CollapsedPillWidth * listOf(
                            showDndButton,
                            canSwitchProfiles,
                            showStandDownButton
                        ).count { it })
                        .height(PauseControlSlotHeight),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val dndControl: @Composable () -> Unit = {
                        if (showDndButton) {
                            Box(
                                Modifier
                                    .size(CollapsedPillWidth)
                                    .graphicsLayer {
                                        translationX = dndCollapsedTranslation.value
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = isExpanded || showCollapsedDnd,
                                    enter = fadeIn(tween(180)) + slideInVertically { it },
                                    exit = fadeOut(tween(120)) + slideOutVertically { it }
                                ) {
                                val dndBackground by animateColorAsState(
                                    targetValue = if (dndActive) NothingColors.Red else Color(0xFF1C1C1C),
                                    animationSpec = tween(180),
                                    label = "dndBackground"
                                )
                                Box(
                                    modifier = Modifier
                                        .size(dndCollapsedSize.value)
                                        .clip(CircleShape)
                                        .background(dndBackground)
                                        .clickable {
                                            onDndToggle()
                                            onInteraction()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DoNotDisturbOn,
                                        contentDescription = if (dndActive) {
                                            "Turn Do Not Disturb off"
                                        } else {
                                            "Turn Do Not Disturb on"
                                        },
                                        tint = NothingColors.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            }
                        }
                    }
                    val profileControl: @Composable () -> Unit = {
                        if (canSwitchProfiles) {
                            val activeProfileName = profiles.firstOrNull { it.id == activeProfileId }?.name
                            val activeProfileIndex = profiles.indexOfFirst { it.id == activeProfileId }.coerceAtLeast(0)
                            val profileButtonBackground by animateColorAsState(
                                targetValue = if (profileMenuExpanded) {
                                    NothingColors.Red
                                } else {
                                    Color(0xFF1C1C1C)
                                },
                                animationSpec = tween(220, easing = FastOutSlowInEasing),
                                label = "profileButtonBackground"
                            )
                            val profileIconRotation by animateFloatAsState(
                                targetValue = activeProfileIndex * 180f + if (profileMenuExpanded) 90f else 0f,
                                animationSpec = tween(360, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)),
                                label = "profileIconRotation"
                            )
                            Box(Modifier.size(CollapsedPillWidth), contentAlignment = Alignment.Center) {
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = isExpanded,
                                    enter = fadeIn(tween(180)) + slideInVertically { it },
                                    exit = fadeOut(tween(120)) + slideOutVertically { it }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(profileButtonBackground)
                                            .semantics {
                                                stateDescription = activeProfileName?.let { "Current profile, $it" }
                                                    ?: "No active profile"
                                            }
                                            .clickable {
                                                profileMenuExpanded = !profileMenuExpanded
                                                if (!profileMenuExpanded) switchingProfileId = null
                                                onInteraction()
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SwapHoriz,
                                            contentDescription = "Switch profile",
                                            tint = NothingColors.White,
                                            modifier = Modifier
                                                .size(24.dp)
                                                .graphicsLayer { rotationZ = profileIconRotation }
                                        )
                                    }
                                }
                            }
                            }
                        }
                    val standDownControl: @Composable () -> Unit = {
                        if (showStandDownButton) {
                            Box(Modifier.size(CollapsedPillWidth), contentAlignment = Alignment.Center) {
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
                        }
                    }
                    Row {
                        if (overlaySide == OverlaySide.RIGHT) {
                            dndControl()
                            profileControl()
                            standDownControl()
                        } else {
                            standDownControl()
                            profileControl()
                            dndControl()
                        }
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
                profileAnimationKey = activeProfileId,
                onStreamVolumeChange = { streamType, newVolume ->
                    onStreamVolumeChange(streamType, newVolume)
                },
                onStreamSelected = onStreamSelected,
                onMuteToggle = onMuteToggle,
                onExpandToggle = {
                    val nextExpanded = !isExpanded
                    updateExpanded(nextExpanded)
                },
                onInteraction = onInteraction
            )
        }
    }

    val panelBody: @Composable (Dp, Dp) -> Unit = { panelWidth, maxHeight ->
        val travelPx = with(density) { panelWidth.toPx() * 0.1f }
        val visiblePanelHeightPx = if (panelSwapTransition.isRunning) {
            maxOf(appPanelHeightPx, profilePanelHeightPx)
        } else if (profileMenuExpanded) {
            profilePanelHeightPx
        } else {
            appPanelHeightPx
        }
        val blankPanelDismissModifier = Modifier.pointerInput(
            visiblePanelHeightPx,
            profileMenuExpanded,
            panelSwapTransition.isRunning
        ) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (visiblePanelHeightPx > 0 && down.position.y >= visiblePanelHeightPx) {
                    down.consume()
                    onDismissRequest()
                }
            }
        }
        Box(
            modifier = Modifier
                .width(panelWidth)
                .heightIn(min = 1.dp, max = maxHeight)
                .then(blankPanelDismissModifier),
            contentAlignment = Alignment.TopStart
        ) {
            Box(
                Modifier
                    .onSizeChanged { appPanelHeightPx = it.height }
                    .zIndex(if (profileMenuExpanded) 0f else 2f)
                    .graphicsLayer {
                        alpha = 1f - profilePanelAlpha
                        translationX = -travelPx * panelSwapProgress
                        scaleX = 1f - panelSwapProgress * 0.018f
                        scaleY = 1f - panelSwapProgress * 0.018f
                    }
                    .semantics { if (profileMenuExpanded) hideFromAccessibility() }
            ) {
                if (shizukuConnectionState == VolumeServiceConnectionState.CONNECTED || profiles.isNotEmpty()) {
                    AmplyPanel(
                        panelWidth = panelWidth,
                        maxHeight = maxHeight,
                        apps = apps,
                        shizukuDisconnected = shizukuConnectionState != VolumeServiceConnectionState.CONNECTED,
                        showAppProfileIdentity = showAppProfileIdentity,
                        onAppVolumeChange = { app, volume ->
                            if (!profileMenuExpanded) onAppVolumeChange(app, volume)
                        }
                    )
                } else if (showShizukuDisconnectedWarning) {
                    ShizukuDisconnectedPanel(panelWidth, shizukuIcon)
                }
            }
            Box(
                Modifier
                    .onSizeChanged { profilePanelHeightPx = it.height }
                    .zIndex(if (profileMenuExpanded) 2f else 0f)
                    .graphicsLayer {
                        alpha = profilePanelAlpha
                        translationX = travelPx * (1f - panelSwapProgress)
                        scaleX = 0.982f + panelSwapProgress * 0.018f
                        scaleY = 0.982f + panelSwapProgress * 0.018f
                    }
                    .semantics { if (!profileMenuExpanded) hideFromAccessibility() }
            ) {
                OverlayProfileSelectorPanel(
                    panelWidth = panelWidth,
                    maxHeight = maxHeight,
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    switchingProfileId = switchingProfileId,
                    profileApplying = profileApplying,
                    onProfileActivate = { profileId ->
                        if (profileMenuExpanded) {
                            if (profileId == activeProfileId) {
                                profileMenuExpanded = false
                                switchingProfileId = null
                            } else if (switchingProfileId == null) {
                                switchingProfileId = profileId
                                onProfileActivate(profileId)
                                onInteraction()
                            }
                        }
                    }
                )
            }
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
        val currentOnTouchStart by rememberUpdatedState(onTouchStart)
        val currentOnTouchEnd by rememberUpdatedState(onTouchEnd)
        val touchHoldModifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                currentOnTouchStart()
                do {
                    val event = awaitPointerEvent()
                } while (event.changes.any { it.pressed })
                currentOnTouchEnd()
            }
        }
        val collapsedHitTestModifier = Modifier.pointerInput(
            isExpanded,
            expandToStart,
            isLandscape,
            showDndButton,
            showStandDownButton,
            showCollapsedDnd
        ) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!isExpanded) {
                    val collapsedWidth = CollapsedPillWidth.toPx()
                    val isInsideVisiblePill = if (expandToStart) {
                        down.position.x >= size.width - collapsedWidth
                    } else {
                        down.position.x <= collapsedWidth
                    }
                    val isInsideVisibleDnd = showDndButton && showCollapsedDnd &&
                        down.position.y <= PauseControlSlotHeight.toPx() &&
                        if (expandToStart) {
                            down.position.x >= size.width - collapsedWidth
                        } else {
                            down.position.x <= collapsedWidth
                        }

                    if (!isInsideVisiblePill && !isInsideVisibleDnd) {
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
                    .then(touchHoldModifier)
                    .then(collapsedHitTestModifier),
                horizontalArrangement = if (expandToStart) {
                    Arrangement.Absolute.Right
                } else {
                    Arrangement.Absolute.Left
                },
                verticalAlignment = Alignment.Bottom
            ) {
                if (expandToStart) {
                    androidx.compose.animation.AnimatedVisibility(
                        visibleState = panelTransitionState,
                        enter = slideInHorizontally(
                            initialOffsetX = { it / 5 },
                            animationSpec = tween(
                                380,
                                easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
                            )
                        ) + fadeIn(animationSpec = tween(250, easing = LinearEasing)),
                        exit = slideOutHorizontally(
                            targetOffsetX = { it / 8 },
                            animationSpec = tween(260, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(220))
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
                            initialOffsetX = { -it / 5 },
                            animationSpec = tween(
                                380,
                                easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
                            )
                        ) + fadeIn(animationSpec = tween(250, easing = LinearEasing)),
                        exit = slideOutHorizontally(
                            targetOffsetX = { -it / 8 },
                            animationSpec = tween(260, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(220))
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
                    .then(touchHoldModifier)
                    .then(collapsedHitTestModifier),
                horizontalAlignment = if (expandToStart) {
                    androidx.compose.ui.AbsoluteAlignment.Right
                } else {
                    androidx.compose.ui.AbsoluteAlignment.Left
                }
            ) {
                pillContent()
                AnimatedVisibility(
                    visibleState = panelTransitionState,
                    enter = fadeIn(animationSpec = tween(250, easing = LinearEasing)) + slideInVertically(
                        initialOffsetY = { -it / 10 },
                        animationSpec = tween(
                            380,
                            easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
                        )
                    ),
                    exit = fadeOut(animationSpec = tween(220)) + slideOutVertically(
                        targetOffsetY = { -it / 14 },
                        animationSpec = tween(280, easing = FastOutSlowInEasing)
                    )
                ) {
                    val horizontalReveal = transition.animateFloat(
                        transitionSpec = {
                            if (targetState == EnterExitState.Visible) {
                                tween(380, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
                            } else {
                                tween(260, easing = FastOutSlowInEasing)
                            }
                        },
                        label = "portraitPanelHorizontalReveal"
                    ) { state ->
                        if (state == EnterExitState.Visible) 1f else 0.96f
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
