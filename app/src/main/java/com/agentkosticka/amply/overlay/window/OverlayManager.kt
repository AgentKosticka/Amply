package com.agentkosticka.amply.overlay.window

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.agentkosticka.amply.audio.session.AppVolumeTarget
import com.agentkosticka.amply.settings.model.OverlaySide
import com.agentkosticka.amply.settings.model.VolumeDotScaleConfig
import com.agentkosticka.amply.audio.ringer.NotificationAlertMode
import com.agentkosticka.amply.audio.routing.MediaRouteVolumeState
import com.agentkosticka.amply.audio.routing.DynamicStreamState
import com.agentkosticka.amply.audio.ringer.StreamMuteToggleController
import com.agentkosticka.amply.audio.routing.VolumeBarModel
import com.agentkosticka.amply.audio.routing.VolumeLimitFeedback
import com.agentkosticka.amply.audio.routing.VolumeLimitFeedbackPolicy
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState
import com.agentkosticka.amply.overlay.ui.VolumeOverlay
import com.agentkosticka.amply.overlay.ui.OverlayAppPresentation
import com.agentkosticka.amply.ui.theme.AmplyTheme
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Self-contained Lifecycle Owner for ComposeView in Service context
 * Provides the "Heartbeat" Compose needs to run outside an Activity
 */

object OverlayManager {
    private const val COLLAPSED_AUTO_HIDE_DELAY_MS = 2500L
    private const val EXPANDED_AUTO_HIDE_DELAY_MS = 6000L
    private const val EXIT_ANIMATION_SETTLE_MS = 240L
    private const val BASE_OVERLAY_HEIGHT_DP = 220
    private const val PAUSE_CONTROL_SLOT_HEIGHT_DP = 58

    private var windowManager: WindowManager? = null
    private var overlayContainerRef: WeakReference<FrameLayout>? = null
    private var composeView: ComposeView? = null
    private var lifecycleOwner: ComposeLifecycleOwner? = null
    private var recomposer: Recomposer? = null
    private var audioManager: AudioManager? = null
    private var currentWindowType: Int? = null
    private val streamMuteToggleController = StreamMuteToggleController()
    private data class StreamTemplateSignature(
        val targets: List<VolumeTarget>,
        val disabledTargets: Set<VolumeTarget>,
        val topology: com.agentkosticka.amply.audio.routing.StreamTopology,
        val route: com.agentkosticka.amply.audio.routing.MediaOutputRoute,
        val routeMax: Int,
        val routeVariable: Boolean,
        val dotConfig: VolumeDotScaleConfig
    )

    private data class StreamBarTemplate(
        val target: VolumeTarget,
        val aliases: Set<Int>,
        val label: String,
        val minVolume: Int,
        val maxVolume: Int,
        val enabled: Boolean,
        val referenceMaxVolume: Int,
        val dotCount: Int,
        val combinedRinger: Boolean,
        val ringerControl: Boolean
    )

    private var streamTemplateSignature: StreamTemplateSignature? = null
    private var streamBarTemplates: List<StreamBarTemplate> = emptyList()

    // Auto-hide timer
    private var managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var hideJob: Job? = null
    private var tutorialPreviewJob: Job? = null
    private var removeJob: Job? = null
    private var volumeObservationJob: Job? = null

    // State - persists across hide/show cycles
    private val volumeBars = mutableStateOf<List<VolumeBarModel>>(emptyList())
    private val dynamicStreamState = mutableStateOf(DynamicStreamState())
    private val selectedVolumeTarget = mutableStateOf(VolumeTarget.MEDIA)
    private val volumeLimitFeedback = mutableStateOf<VolumeLimitFeedback?>(null)
    private var volumeLimitFeedbackSequence = 0L
    private val iconType = mutableStateOf("MUSIC")
    private val mediaRouteVolumeState = mutableStateOf(MediaRouteVolumeState())
    private val currentApps = mutableStateOf<List<OverlayAppPresentation>>(emptyList())
    private val currentOverlaySide = mutableStateOf(OverlaySide.LEFT)
    private val currentOverlayVerticalFraction = mutableFloatStateOf(0.5f)
    private val availableOverlayWidthDp = mutableFloatStateOf(0f)
    private val volumeDotScaleConfig = mutableStateOf(VolumeDotScaleConfig())
    
    private val shizukuConnectionState = mutableStateOf(VolumeServiceConnectionState.WAITING_FOR_PERMISSION)
    private val shizukuIcon = mutableStateOf<Bitmap?>(null)
    private val showShizukuDisconnectedWarning = mutableStateOf(true)
    private val showPerAppVolumeControl = mutableStateOf(true)
    private val showStandDownButton = mutableStateOf(true)
    private val showDndButton = mutableStateOf(false)
    private val dndActive = mutableStateOf(false)

    // Callback for per-app volume changes (wired to the foreground runtime backend)
    private var onAppVolumeChangeCallback: ((AppVolumeTarget, Float) -> Unit)? = null
    private var onVolumeTargetSelectedCallback: ((VolumeTarget) -> Unit)? = null
    private var onOverlayShownCallback: (() -> Boolean)? = null
    private var onOverlayHiddenCallback: (() -> Unit)? = null
    private var onTutorialPreviewFinishedCallback: (() -> Unit)? = null
    private var onPauseAmplyCallback: (() -> Unit)? = null
    private var onDndToggleCallback: (() -> Unit)? = null
    private var onNotificationModeToggleCallback: (() -> Unit)? = null
    private var onSystemStreamVolumeChangeCallback: ((VolumeTarget, Int) -> Boolean)? = null
    private var onRemoteMediaVolumeChangeCallback: ((Long, Int) -> Boolean)? = null
    private val overlayVisible = mutableStateOf(false)
    private val overlayExpanded = mutableStateOf(false)
    private var presentationMode = OverlayPresentationMode.NORMAL

    private val overlayContainer: FrameLayout?
        get() = overlayContainerRef?.get()

    /**
     * Set the callback for per-app volume changes
     * This should be called by OverlayService to wire up to AudioSessionManager
     */
    fun setAppVolumeCallback(callback: (AppVolumeTarget, Float) -> Unit) {
        onAppVolumeChangeCallback = callback
    }

    fun clearAppVolumeCallback() {
        onAppVolumeChangeCallback = null
    }

    fun setVolumeTargetCallbacks(
        onSelected: (VolumeTarget) -> Unit,
        onShown: () -> Boolean,
        onHidden: () -> Unit,
        onTutorialPreviewFinished: () -> Unit
    ) {
        onVolumeTargetSelectedCallback = onSelected
        onOverlayShownCallback = onShown
        onOverlayHiddenCallback = onHidden
        onTutorialPreviewFinishedCallback = onTutorialPreviewFinished
    }

    fun clearVolumeTargetCallbacks() {
        onVolumeTargetSelectedCallback = null
        onOverlayShownCallback = null
        onOverlayHiddenCallback = null
        onTutorialPreviewFinishedCallback = null
    }

    fun updateSelectedVolumeTarget(target: VolumeTarget) {
        // Freeze the rendered target during the exit animation. The session controller
        // clears its manual target as soon as hiding begins, but that reset belongs to
        // the next overlay appearance and must not replace the bar that is fading out.
        if (overlayVisible.value) {
            selectedVolumeTarget.value = target
            refreshSystemStreamVolumes()
        }
    }

    private fun signalVolumeLimit(target: VolumeTarget, dotLevel: Int, isUp: Boolean) {
        volumeLimitFeedbackSequence += 1L
        volumeLimitFeedback.value = VolumeLimitFeedback(
            target = target,
            dotLevel = dotLevel,
            isUpperBound = isUp,
            eventId = volumeLimitFeedbackSequence
        )
    }

    fun signalVolumeLimit(target: VolumeTarget, isUp: Boolean) {
        val bar = volumeBars.value.firstOrNull { it.target == target } ?: return
        signalVolumeLimit(
            target,
            VolumeLimitFeedbackPolicy.rejectedDotLevel(
                isUp = isUp,
                min = bar.minVolume,
                max = bar.maxVolume,
                referenceMax = bar.referenceMaxVolume,
                dotCount = bar.dotCount
            ),
            isUp = isUp
        )
    }

    fun updateVolumeDotScaleConfig(config: VolumeDotScaleConfig) {
        if (volumeDotScaleConfig.value == config) return
        volumeDotScaleConfig.value = config
        refreshSystemStreamVolumes()
    }

    fun setPauseAmplyCallback(callback: () -> Unit) {
        onPauseAmplyCallback = callback
    }

    fun clearPauseAmplyCallback() {
        onPauseAmplyCallback = null
    }

    fun setDndToggleCallback(callback: () -> Unit) {
        onDndToggleCallback = callback
    }

    fun clearDndToggleCallback() {
        onDndToggleCallback = null
    }

    fun setNotificationModeToggleCallback(callback: () -> Unit) {
        onNotificationModeToggleCallback = callback
    }

    fun clearNotificationModeToggleCallback() {
        onNotificationModeToggleCallback = null
    }

    fun refreshStreamVolumes() {
        // Explicit refreshes come from route/device callbacks where min/max values can
        // change even when the logical stream topology is otherwise identical.
        streamTemplateSignature = null
        refreshSystemStreamVolumes()
    }

    fun updateDynamicStreams(state: DynamicStreamState) {
        dynamicStreamState.value = state
        refreshSystemStreamVolumes()
    }

    fun setSystemStreamVolumeCallback(callback: (VolumeTarget, Int) -> Boolean) {
        onSystemStreamVolumeChangeCallback = callback
    }

    fun clearSystemStreamVolumeCallback() {
        onSystemStreamVolumeChangeCallback = null
    }

    fun updateApps(
        apps: List<OverlayAppPresentation>,
        connectionState: VolumeServiceConnectionState
    ) {
        if (presentationMode == OverlayPresentationMode.LOCK_SCREEN_SYSTEM_ONLY) {
            currentApps.value = emptyList()
            shizukuConnectionState.value = VolumeServiceConnectionState.CONNECTED
            return
        }
        currentApps.value = apps
        shizukuConnectionState.value = connectionState
    }

    fun updateShizukuIcon(icon: Bitmap?) {
        if (shizukuIcon.value !== icon) shizukuIcon.value = icon
    }

    fun updateShizukuDisconnectedWarningEnabled(enabled: Boolean) {
        showShizukuDisconnectedWarning.value = enabled
    }

    fun updatePerAppVolumeControlEnabled(enabled: Boolean) {
        showPerAppVolumeControl.value = enabled
    }

    fun updateStandDownButtonEnabled(enabled: Boolean) {
        if (showStandDownButton.value == enabled) return
        showStandDownButton.value = enabled
        overlayContainer?.context?.let(::updateOverlayPosition)
    }

    fun updateDndButtonEnabled(enabled: Boolean) {
        if (showDndButton.value == enabled) return
        showDndButton.value = enabled
        overlayContainer?.context?.let(::updateOverlayPosition)
    }

    fun updateDndActive(active: Boolean) {
        dndActive.value = active
    }

    /**
     * Invoke the session volume callback
     * This method ensures the callback is always read fresh from the property
     */
    private fun invokeAppVolumeCallback(target: AppVolumeTarget, volume: Float) {
        onAppVolumeChangeCallback?.invoke(target, volume)
    }

    /** Update an already-visible media action icon without reopening or retiming the overlay. */
    fun updateMediaIconType(newIconType: String) {
        iconType.value = newIconType
    }

    fun updateMediaRouteVolumeState(state: MediaRouteVolumeState) {
        if (mediaRouteVolumeState.value == state) return
        if (mediaRouteVolumeState.value.generation != state.generation) {
            streamMuteToggleController.onVolumeChangedOutsideToggle(AudioManager.STREAM_MUSIC)
        }
        mediaRouteVolumeState.value = state
        iconType.value = state.outputRoute.wireName
        refreshSystemStreamVolumes()
    }

    fun setRemoteMediaVolumeCallback(callback: (Long, Int) -> Boolean) {
        onRemoteMediaVolumeChangeCallback = callback
    }

    fun clearRemoteMediaVolumeCallback() {
        onRemoteMediaVolumeChangeCallback = null
    }

    /** Show or update the overlay using the latest package-level app entries. */
    fun show(
        context: Context,
        selectedTarget: VolumeTarget,
        newIconType: String = "MUSIC",
        apps: List<OverlayAppPresentation> = emptyList(),
        connectionState: VolumeServiceConnectionState = VolumeServiceConnectionState.WAITING_FOR_PERMISSION,
        overlaySide: OverlaySide = OverlaySide.LEFT,
        overlayVerticalFraction: Float = 0.5f,
        requestedPresentationMode: OverlayPresentationMode = OverlayPresentationMode.NORMAL
    ): OverlayAttachResult {
        val windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        ensureManagerScope()
        removeJob?.cancel()
        removeJob = null

        // Initialize managers if needed
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (audioManager == null) {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }

        if (overlayContainer != null && currentWindowType != windowType) {
            removeOverlay()
        }

        val newAppearance = !overlayVisible.value
        if (newAppearance) {
            presentationMode = requestedPresentationMode
            overlayExpanded.value = false
        }

        // Update state
        updateAvailableOverlayWidth(context)
        refreshSystemStreamVolumes()
        streamMuteToggleController.onVolumeChangedOutsideToggle(selectedTarget.streamType)
        selectedVolumeTarget.value = selectedTarget
        iconType.value = newIconType
        if (presentationMode == OverlayPresentationMode.LOCK_SCREEN_SYSTEM_ONLY) {
            currentApps.value = emptyList()
            shizukuConnectionState.value = VolumeServiceConnectionState.CONNECTED
        } else {
            currentApps.value = apps
            shizukuConnectionState.value = connectionState
        }
        currentOverlaySide.value = overlaySide
        currentOverlayVerticalFraction.floatValue = overlayVerticalFraction.coerceIn(0f, 1f)
        
        val attachResult = if (overlayContainer == null) {
            createOverlay(context, windowType)
        } else {
            unparkOverlay()
            updateOverlayPosition(context)
            OverlayAttachResult.ALREADY_ATTACHED
        }
        if (attachResult == OverlayAttachResult.FAILED) {
            overlayVisible.value = false
            volumeObservationJob?.cancel()
            volumeObservationJob = null
            overlayExpanded.value = false
            presentationMode = OverlayPresentationMode.NORMAL
            removeJob = null
            return attachResult
        }
        overlayVisible.value = true
        startVolumeObservation()
        val handOffToTutorial = onOverlayShownCallback?.invoke() == true

        if (handOffToTutorial) {
            hideJob?.cancel()
            hideJob = null
            tutorialPreviewJob?.cancel()
            tutorialPreviewJob = managerScope.launch {
                delay(1_100L.milliseconds)
                tutorialPreviewJob = null
                hide()
                delay(EXIT_ANIMATION_SETTLE_MS.milliseconds)
                onTutorialPreviewFinishedCallback?.invoke()
            }
        } else {
            scheduleHide(currentAutoHideDelayMs())
        }
        return attachResult
    }

    /**
     * Create the overlay view with proper lifecycle
     * CRITICAL: Uses wrapper FrameLayout for proper view tree lifecycle propagation
     */
    private fun createOverlay(context: Context, windowType: Int): OverlayAttachResult {
        // Step 1: Create lifecycle owner FIRST
        val owner = ComposeLifecycleOwner()
        owner.performRestore(null)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner = owner

        // Step 2: Create wrapper FrameLayout - this holds the lifecycle for the view tree
        val container = OverlayFrameLayout(context).apply {
            onOutsideTouch = { hide() }
        }

        // Step 3: Set lifecycle owners on the CONTAINER (parent view)
        // This allows child views (ComposeView) to find the lifecycle via view tree traversal
        container.setViewTreeLifecycleOwner(owner)
        container.setViewTreeViewModelStoreOwner(owner)
        container.setViewTreeSavedStateRegistryOwner(owner)

        // Step 4: Create ComposeView as child
        val view = ComposeView(context)
        container.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        // Step 5: Create custom Recomposer for service context
        val coroutineContext = AndroidUiDispatcher.CurrentThread
        val newRecomposer = Recomposer(coroutineContext)
        view.compositionContext = newRecomposer
        recomposer = newRecomposer

        // Step 6: Start recomposer
        managerScope.launch(coroutineContext) {
            newRecomposer.runRecomposeAndApplyChanges()
        }

        // Step 7: Move lifecycle to STARTED state before setContent
        owner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // Step 8: Set compose content
        view.setContent {
            AmplyTheme {
                VolumeOverlay(
                    volumeBars = volumeBars.value,
                    selectedTarget = selectedVolumeTarget.value,
                    volumeLimitFeedback = volumeLimitFeedback.value,
                    visible = overlayVisible.value,
                    expanded = overlayExpanded.value,
                    iconType = iconType.value,
                    apps = if (showPerAppVolumeControl.value) currentApps.value else emptyList(),
                    shizukuConnectionState = shizukuConnectionState.value,
                    shizukuIcon = shizukuIcon.value,
                    showShizukuDisconnectedWarning = showShizukuDisconnectedWarning.value,
                    showStandDownButton = showStandDownButton.value,
                    showDndButton = showDndButton.value,
                    dndActive = dndActive.value,
                    overlaySide = currentOverlaySide.value,
                    availableWidthDp = availableOverlayWidthDp.floatValue,
                    onStreamVolumeChange = { streamType, newVolume ->
                        setStreamVolume(streamType, newVolume)
                    },
                    onStreamSelected = { target ->
                        selectedVolumeTarget.value = target
                        onVolumeTargetSelectedCallback?.invoke(target)
                    },
                    onAppVolumeChange = { app, volume ->
                        invokeAppVolumeCallback(app, volume)
                    },
                    onMuteToggle = { streamType ->
                        toggleMute(streamType)
                    },
                    onInteraction = {
                        if (overlayVisible.value) {
                            scheduleHide(currentAutoHideDelayMs()) // Reset timer on interaction
                        }
                    },
                    onTouchStart = {
                        if (overlayVisible.value) cancelAutoHide() // Stop timer while user is touching
                    },
                    onTouchEnd = {
                        if (overlayVisible.value) {
                            scheduleHide(currentAutoHideDelayMs()) // Start timer when touch ends
                        }
                    },
                    onExpandedChange = { expanded ->
                        // A click can finish dispatching after hide() has already started. Ignore
                        // that stale event so it cannot arm expansion for the next appearance.
                        if (!overlayVisible.value) {
                            overlayExpanded.value = false
                        } else {
                            overlayExpanded.value = expanded
                            scheduleHide(currentAutoHideDelayMs())
                        }
                    },
                    onPauseAmply = {
                        onPauseAmplyCallback?.invoke()
                        hide()
                    },
                    onDndToggle = {
                        onDndToggleCallback?.invoke()
                    },
                    onDismissRequest = {
                        hide()
                    }
                )
            }
        }

        overlayContainerRef = WeakReference(container)
        composeView = view

        // Step 9: Configure window params
        // The overlay needs pointer input, but never keyboard focus. A non-focusable
        // window still receives touch events and does not dismiss the app's IME.
        val params = WindowManager.LayoutParams(
            windowWidthForCurrentOrientation(context),
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            OverlayWindowPolicy.FLAGS,
            PixelFormat.TRANSLUCENT
        ).applyPosition(context)

        // Step 10: Add CONTAINER to window manager
        try {
            windowManager?.addView(container, params)
            currentWindowType = windowType
            // On API 29 the final system/cutout insets are only reliable after the
            // overlay is attached. Re-apply once to avoid an oversized first frame.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                container.post { updateOverlayPosition(context) }
            }
            return OverlayAttachResult.ATTACHED
        } catch (e: Exception) {
            Log.e("OverlayManager", "Accessibility overlay attachment failed", e)
            // Cleanup on failure
            newRecomposer.cancel()
            recomposer = null
            lifecycleOwner?.destroy()
            lifecycleOwner = null
            overlayContainerRef = null
            composeView = null
            currentWindowType = null
            return OverlayAttachResult.FAILED
        }
    }

    /**
     * Hide and remove the overlay
     */
    fun hide() {
        hideJob?.cancel()
        val wasVisible = overlayVisible.value
        overlayVisible.value = false
        overlayExpanded.value = false
        volumeObservationJob?.cancel()
        volumeObservationJob = null
        if (wasVisible) {
            onOverlayHiddenCallback?.invoke()
        }
        if (overlayContainer == null) {
            overlayExpanded.value = false
            presentationMode = OverlayPresentationMode.NORMAL
            removeJob = null
            return
        }
        removeJob?.cancel()
        removeJob = managerScope.launch {
            // Leave one frame of headroom beyond the 200 ms slide-out so the retained
            // transition reaches its exact hidden state before the window is parked.
            delay(EXIT_ANIMATION_SETTLE_MS.milliseconds)
            parkOverlay()
        }
    }

    /** Keep the expensive Compose tree warm while guaranteeing the window cannot intercept input. */
    private fun parkOverlay() {
        val container = overlayContainer ?: return
        val params = container.layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = OverlayWindowPolicy.PARKED_FLAGS
        runCatching { windowManager?.updateViewLayout(container, params) }
            .onFailure { Log.w("OverlayManager", "Failed to park overlay input window", it) }
        container.visibility = View.INVISIBLE
        overlayExpanded.value = false
        removeJob = null
    }

    private fun unparkOverlay() {
        val container = overlayContainer ?: return
        val params = container.layoutParams as? WindowManager.LayoutParams ?: return
        container.visibility = View.VISIBLE
        if (params.flags == OverlayWindowPolicy.FLAGS) return
        params.flags = OverlayWindowPolicy.FLAGS
        runCatching { windowManager?.updateViewLayout(container, params) }
            .onFailure { Log.w("OverlayManager", "Failed to restore overlay input window", it) }
    }

    private fun removeOverlay() {

        overlayContainer?.let { container ->
            try {
                windowManager?.removeView(container)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Cleanup recomposer
        recomposer?.cancel()
        recomposer = null

        // Cleanup lifecycle
        lifecycleOwner?.destroy()
        lifecycleOwner = null
        overlayContainerRef = null
        composeView = null
        currentWindowType = null
        overlayVisible.value = false
        volumeObservationJob?.cancel()
        volumeObservationJob = null
        overlayExpanded.value = false
        presentationMode = OverlayPresentationMode.NORMAL
        volumeLimitFeedback.value = null
        removeJob = null
    }

    private fun refreshSystemStreamVolumes() {
        val manager = audioManager ?: return
        val state = dynamicStreamState.value
        val routeState = mediaRouteVolumeState.value
        val templates = ensureStreamBarTemplates(manager, state, routeState, volumeDotScaleConfig.value)
        val updated = readSystemStreamVolumes(manager, templates, state, routeState)
        if (updated != volumeBars.value) volumeBars.value = updated
    }

    private fun ensureStreamBarTemplates(
        manager: AudioManager,
        state: DynamicStreamState,
        routeState: MediaRouteVolumeState,
        dotConfig: VolumeDotScaleConfig
    ): List<StreamBarTemplate> {
        val targets = state.visibleTargets()
        val signature = StreamTemplateSignature(
            targets = targets,
            disabledTargets = state.disabledTargets,
            topology = state.topology,
            route = routeState.outputRoute,
            routeMax = routeState.maxVolume,
            routeVariable = routeState.variableVolume,
            dotConfig = dotConfig
        )
        if (signature == streamTemplateSignature) return streamBarTemplates

        val topology = state.topology
        val combinedRinger = topology.aliasesTogether(VolumeTarget.RING, VolumeTarget.NOTIFICATION)
        val deviceReferenceMax = VolumeTarget.entries.asSequence()
            .filter { it.userAdjustable }
            .mapNotNull { target -> runCatching { manager.getStreamMaxVolume(target.streamType) }.getOrNull() }
            .maxOrNull()
            ?.coerceAtLeast(1)
            ?: 16
        val resolvedDotCount = dotConfig.resolvedDotCount(deviceReferenceMax)
        streamBarTemplates = targets.map { target ->
            val aliases = VolumeTarget.entries
                .filter { topology.canonicalTarget(it) == target }
                .mapTo(linkedSetOf()) { it.streamType }
            val remoteMedia = routeState.takeIf {
                target == VolumeTarget.MEDIA && it.isRemote && it.maxVolume > 0
            }
            val min = if (remoteMedia != null) 0 else {
                runCatching { manager.getStreamMinVolume(target.streamType) }.getOrDefault(0)
            }
            val max = remoteMedia?.maxVolume
                ?: runCatching { manager.getStreamMaxVolume(target.streamType) }.getOrDefault(min)
            val ringerControl = VolumeTarget.NOTIFICATION.streamType in aliases ||
                VolumeTarget.RING.streamType in aliases
            StreamBarTemplate(
                target = target,
                aliases = aliases.ifEmpty { setOf(target.streamType) },
                label = if (combinedRinger && ringerControl) "Ring & notifications" else target.label,
                minVolume = min,
                maxVolume = max,
                enabled = target.userAdjustable && target !in state.disabledTargets &&
                    (remoteMedia == null || remoteMedia.variableVolume),
                referenceMaxVolume = remoteMedia?.maxVolume ?: deviceReferenceMax,
                dotCount = resolvedDotCount,
                combinedRinger = combinedRinger && ringerControl,
                ringerControl = ringerControl
            )
        }
        streamTemplateSignature = signature
        return streamBarTemplates
    }

    private fun readSystemStreamVolumes(
        manager: AudioManager,
        templates: List<StreamBarTemplate>,
        state: DynamicStreamState,
        routeState: MediaRouteVolumeState
    ): List<VolumeBarModel> {
        val ringerMode = if (templates.any { it.ringerControl }) manager.ringerMode else AudioManager.RINGER_MODE_NORMAL
        return templates.map { template ->
            val current = routeState.takeIf {
                template.target == VolumeTarget.MEDIA && it.isRemote && it.maxVolume > 0
            }?.currentVolume ?: runCatching {
                manager.getStreamVolume(template.target.streamType)
            }.getOrDefault(0)
            VolumeBarModel(
                target = template.target,
                aliases = template.aliases,
                label = template.label,
                currentVolume = current,
                minVolume = template.minVolume,
                maxVolume = template.maxVolume,
                active = template.target in state.activeTargets,
                enabled = template.enabled,
                referenceMaxVolume = template.referenceMaxVolume,
                dotCount = template.dotCount,
                combinedRinger = template.combinedRinger,
                notificationAlertMode = if (template.ringerControl) {
                    NotificationAlertMode.resolve(ringerMode)
                } else null
            )
        }
    }

    private fun startVolumeObservation() {
        if (volumeObservationJob?.isActive == true) return
        volumeObservationJob = managerScope.launch {
            while (isActive && overlayVisible.value) {
                val manager = audioManager ?: break
                val state = dynamicStreamState.value
                val routeState = mediaRouteVolumeState.value
                val templates = streamBarTemplates
                val updated = withContext(Dispatchers.Default) {
                    readSystemStreamVolumes(manager, templates, state, routeState)
                }
                if (updated != volumeBars.value) volumeBars.value = updated
                delay(100L.milliseconds)
            }
        }
    }

    /**
     * Update a system stream from overlay interaction.
     */
    private fun setStreamVolume(
        streamType: Int,
        newVolume: Int,
        changedThroughMuteToggle: Boolean = false
    ) {
        val manager = audioManager ?: return
        if (!changedThroughMuteToggle) {
            streamMuteToggleController.onVolumeChangedOutsideToggle(streamType)
        }
        val remoteMedia = mediaRouteVolumeState.value.takeIf {
            streamType == AudioManager.STREAM_MUSIC && it.isControllableRemote
        }
        if (remoteMedia != null) {
            val clamped = newVolume.coerceIn(0, remoteMedia.maxVolume)
            if (onRemoteMediaVolumeChangeCallback?.invoke(remoteMedia.generation, clamped) == true) {
                mediaRouteVolumeState.value = remoteMedia.copy(currentVolume = clamped)
                refreshSystemStreamVolume(streamType)
            }
            return
        }
        val clampedVolume = newVolume.coerceIn(
            manager.getStreamMinVolume(streamType),
            manager.getStreamMaxVolume(streamType)
        )
        val target = VolumeTarget.findByStreamType(streamType) ?: return
        onSystemStreamVolumeChangeCallback?.invoke(target, clampedVolume)
            ?: runCatching { manager.setStreamVolume(streamType, clampedVolume, 0) }
        refreshSystemStreamVolume(streamType)
    }

    /** Refresh only the model affected by a direct slider/icon interaction. */
    private fun refreshSystemStreamVolume(streamType: Int) {
        val manager = audioManager ?: return
        val bars = volumeBars.value
        val index = bars.indexOfFirst { streamType in it.aliases }
        if (index < 0) return

        val bar = bars[index]
        val canonicalStream = bar.target.streamType
        val current = mediaRouteVolumeState.value.takeIf {
            canonicalStream == AudioManager.STREAM_MUSIC && it.isControllableRemote
        }?.currentVolume ?: runCatching { manager.getStreamVolume(canonicalStream) }
            .getOrDefault(bar.currentVolume)
        val ringerControl = VolumeTarget.NOTIFICATION.streamType in bar.aliases ||
            VolumeTarget.RING.streamType in bar.aliases
        val updated = bar.copy(
            currentVolume = current,
            notificationAlertMode = if (ringerControl) {
                NotificationAlertMode.resolve(manager.ringerMode)
            } else {
                null
            }
        )
        if (updated == bar) return

        volumeBars.value = bars.toMutableList().apply { this[index] = updated }
    }

    /** Screen-off cannot visibly finish a Compose exit animation; remove the window now. */
    fun dismissImmediatelyForScreenOff() {
        hideJob?.cancel()
        hideJob = null
        removeJob?.cancel()
        removeJob = null
        volumeObservationJob?.cancel()
        volumeObservationJob = null
        val wasVisible = overlayVisible.value
        overlayVisible.value = false
        if (wasVisible) onOverlayHiddenCallback?.invoke()
        removeOverlay()
    }

    /**
     * Restore the exact level muted through an icon. If the stream reached minimum
     * through keys or a slider, restore only the first tick above minimum instead.
     */
    private fun toggleMute(streamType: Int) {
        val isRingerControl = volumeBars.value.any {
            it.target.streamType == streamType &&
                (it.target == VolumeTarget.NOTIFICATION ||
                    it.target == VolumeTarget.RING || it.combinedRinger)
        }
        if (streamType != AudioManager.STREAM_MUSIC && !isRingerControl) {
            return
        }
        if (isRingerControl) {
            onNotificationModeToggleCallback?.invoke()
            return
        }
        val manager = audioManager ?: return
        val remoteMedia = mediaRouteVolumeState.value.takeIf {
            streamType == AudioManager.STREAM_MUSIC && it.isControllableRemote
        }
        val nextVolume = streamMuteToggleController.nextVolume(
            streamType = streamType,
            currentVolume = remoteMedia?.currentVolume ?: manager.getStreamVolume(streamType),
            minVolume = if (remoteMedia != null) 0 else manager.getStreamMinVolume(streamType),
            maxVolume = remoteMedia?.maxVolume ?: manager.getStreamMaxVolume(streamType)
        )
        setStreamVolume(streamType, nextVolume, changedThroughMuteToggle = true)
    }

    /**
     * Schedule auto-hide after delay
     * @param delayMs Delay in milliseconds before hiding
     */
    private fun scheduleHide(delayMs: Long = COLLAPSED_AUTO_HIDE_DELAY_MS) {
        hideJob?.cancel()
        hideJob = managerScope.launch {
            delay(delayMs.milliseconds)
            hide()
        }
    }

    /**
     * Cancel auto-hide timer (when user is actively touching)
     */
    private fun cancelAutoHide() {
        hideJob?.cancel()
        hideJob = null
    }

    private fun currentAutoHideDelayMs(): Long =
        if (overlayExpanded.value) EXPANDED_AUTO_HIDE_DELAY_MS else COLLAPSED_AUTO_HIDE_DELAY_MS

    /**
     * Check if overlay is showing
     */
    fun isShowing(): Boolean = overlayVisible.value

    /**
     * Cleanup resources
     */
    fun cleanup() {
        hideJob?.cancel()
        removeJob?.cancel()
        volumeObservationJob?.cancel()
        if (overlayVisible.value) {
            onOverlayHiddenCallback?.invoke()
        }
        removeOverlay()
        managerScope.cancel()
        windowManager = null
        audioManager = null
    }

    private fun ensureManagerScope() {
        if (!managerScope.isActive) {
            managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
    }

    private fun updateOverlayPosition(context: Context) {
        val container = overlayContainer ?: return
        val params = container.layoutParams as? WindowManager.LayoutParams ?: return
        updateAvailableOverlayWidth(context)
        val previousWidth = params.width
        val previousGravity = params.gravity
        val previousX = params.x
        val previousY = params.y
        params.applyPosition(context)
        if (params.width == previousWidth &&
            params.gravity == previousGravity &&
            params.x == previousX &&
            params.y == previousY
        ) {
            return
        }
        try {
            windowManager?.updateViewLayout(container, params)
        } catch (e: Exception) {
            Log.w("OverlayManager", "Failed to update overlay position", e)
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun WindowManager.LayoutParams.applyPosition(context: Context): WindowManager.LayoutParams {
        val margin = (16 * context.resources.displayMetrics.density).toInt()
        val baseOverlayHeight = (BASE_OVERLAY_HEIGHT_DP * context.resources.displayMetrics.density).toInt()
        val pauseControlSlotHeight = if (showStandDownButton.value || showDndButton.value) {
            (PAUSE_CONTROL_SLOT_HEIGHT_DP * context.resources.displayMetrics.density).toInt()
        } else {
            0
        }
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val maxPillY = (screenHeight - baseOverlayHeight).coerceAtLeast(0)
        val pillY = (maxPillY * currentOverlayVerticalFraction.floatValue).toInt()
        val windowY = pillY - pauseControlSlotHeight

        val isLandscape = screenWidth > screenHeight
        width = windowWidthForCurrentOrientation(context)
        gravity = when {
            isLandscape -> Gravity.LEFT or Gravity.TOP
            currentOverlaySide.value == OverlaySide.LEFT -> Gravity.LEFT or Gravity.TOP
            else -> Gravity.RIGHT or Gravity.TOP
        }
        x = margin
        y = windowY.coerceIn(-pauseControlSlotHeight, maxPillY - pauseControlSlotHeight)
        return this
    }

    private fun updateAvailableOverlayWidth(context: Context) {
        val density = context.resources.displayMetrics.density
        val metrics = context.resources.displayMetrics
        val isLandscape = metrics.widthPixels > metrics.heightPixels
        val availableWidthPx = if (isLandscape) {
            OverlayWindowGeometry.landscapeWidthPx(
                displayWidthPx = currentWindowWidthPx(context),
                edgeMarginPx = (16f * density).toInt(),
                occlusions = landscapeHorizontalOcclusionInsets(context)
            )
        } else {
            (metrics.widthPixels - (32f * density).toInt()).coerceAtLeast(1)
        }
        availableOverlayWidthDp.floatValue = (availableWidthPx / density).coerceAtLeast(1f)
    }

    private fun windowWidthForCurrentOrientation(context: Context): Int {
        val metrics = context.resources.displayMetrics
        val margin = (16 * metrics.density).toInt()
        val isLandscape = metrics.widthPixels > metrics.heightPixels
        return if (isLandscape) {
            OverlayWindowGeometry.landscapeWidthPx(
                displayWidthPx = currentWindowWidthPx(context),
                edgeMarginPx = margin,
                occlusions = landscapeHorizontalOcclusionInsets(context)
            )
        } else {
            WindowManager.LayoutParams.WRAP_CONTENT
        }
    }

    /**
     * WindowManager lays landscape overlays inside a frame that may already exclude
     * a side-mounted status bar, camera cutout, or navigation bar. The resource display
     * width still includes those pixels, so both sides must be removed before requesting
     * the wide overlay window. On the Nothing Phone this is commonly a left-only inset
     * in rotation 90, even when the pill itself is on the right.
     */
    private fun landscapeHorizontalOcclusionInsets(context: Context): HorizontalOcclusionInsets {
        val metrics = context.resources.displayMetrics
        if (metrics.widthPixels <= metrics.heightPixels) return HorizontalOcclusionInsets()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = windowManager
                ?: context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val windowInsets = manager.currentWindowMetrics.windowInsets
            val systemBars = windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            val cutout = windowInsets.displayCutout
            val waterfall = cutout?.waterfallInsets
            return HorizontalOcclusionInsets(
                left = maxOf(
                    systemBars.left,
                    cutout?.safeInsetLeft ?: 0,
                    waterfall?.left ?: 0
                ),
                right = maxOf(
                    systemBars.right,
                    cutout?.safeInsetRight ?: 0,
                    waterfall?.right ?: 0
                )
            )
        }

        val windowInsets = overlayContainer?.rootWindowInsets ?: return HorizontalOcclusionInsets()
        @Suppress("DEPRECATION")
        val stableLeft = windowInsets.stableInsetLeft
        @Suppress("DEPRECATION")
        val stableRight = windowInsets.stableInsetRight
        return HorizontalOcclusionInsets(
            left = maxOf(stableLeft, windowInsets.displayCutout?.safeInsetLeft ?: 0),
            right = maxOf(stableRight, windowInsets.displayCutout?.safeInsetRight ?: 0)
        )
    }

    private fun currentWindowWidthPx(context: Context): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = windowManager
                ?: context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            manager.currentWindowMetrics.bounds.width()
        } else {
            context.resources.displayMetrics.widthPixels
        }
}
