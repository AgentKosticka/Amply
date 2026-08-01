package com.agentkosticka.amply.service

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.agentkosticka.amply.data.OverlayAppEntry
import com.agentkosticka.amply.data.OverlaySide
import com.agentkosticka.amply.audio.NotificationAlertMode
import com.agentkosticka.amply.audio.DynamicStreamState
import com.agentkosticka.amply.audio.StreamMuteToggleController
import com.agentkosticka.amply.audio.VolumeBarModel
import com.agentkosticka.amply.audio.VolumeTarget
import com.agentkosticka.amply.shizuku.VolumeServiceConnectionState
import com.agentkosticka.amply.ui.overlay.VolumeOverlay
import com.agentkosticka.amply.ui.theme.AmplyTheme
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

/**
 * Self-contained Lifecycle Owner for ComposeView in Service context
 * Provides the "Heartbeat" Compose needs to run outside an Activity
 */
private class ComposeLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)
    private val store: ViewModelStore = ViewModelStore()
    private val savedStateRegistryController: SavedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    fun performRestore(savedState: Bundle?) {
        savedStateRegistryController.performRestore(savedState)
    }

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }

    fun destroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

private class OverlayFrameLayout(context: Context) : FrameLayout(context) {
    var onOutsideTouch: (() -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            performClick()
            onOutsideTouch?.invoke()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/**
 * Manages the floating volume overlay using WindowManager
 * CRITICAL FIX: Self-contained lifecycle and proper window token management
 */
object OverlayManager {
    private const val COLLAPSED_AUTO_HIDE_DELAY_MS = 2500L
    private const val EXPANDED_AUTO_HIDE_DELAY_MS = 6000L
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

    // Auto-hide timer
    private var managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var hideJob: Job? = null
    private var removeJob: Job? = null

    // State - persists across hide/show cycles
    private val volumeBars = mutableStateOf<List<VolumeBarModel>>(emptyList())
    private val dynamicStreamState = mutableStateOf(DynamicStreamState())
    private val selectedVolumeTarget = mutableStateOf(VolumeTarget.MEDIA)
    private val iconType = mutableStateOf("MUSIC")
    private val currentApps = mutableStateOf<List<OverlayAppEntry>>(emptyList())
    private val appIconBitmapCache = LruCache<String, Bitmap>(32)
    private val currentOverlaySide = mutableStateOf(OverlaySide.LEFT)
    private val currentOverlayVerticalFraction = mutableFloatStateOf(0.5f)
    private val availableOverlayWidthDp = mutableFloatStateOf(0f)
    
    private val shizukuConnectionState = mutableStateOf(VolumeServiceConnectionState.WAITING_FOR_PERMISSION)
    private val shizukuIcon = mutableStateOf<Bitmap?>(null)

    // Callback for per-app volume changes (wired to the foreground runtime backend)
    private var onAppVolumeChangeCallback: ((OverlayAppEntry, Float) -> Unit)? = null
    private var onVolumeTargetSelectedCallback: ((VolumeTarget) -> Unit)? = null
    private var onOverlayShownCallback: (() -> Unit)? = null
    private var onOverlayHiddenCallback: (() -> Unit)? = null
    private var onPauseAmplyCallback: (() -> Unit)? = null
    private var onNotificationModeToggleCallback: (() -> Unit)? = null
    private var onSystemStreamVolumeChangeCallback: ((VolumeTarget, Int) -> Boolean)? = null
    private val overlayVisible = mutableStateOf(false)
    private var isOverlayExpanded = false

    private val overlayContainer: FrameLayout?
        get() = overlayContainerRef?.get()

    /**
     * Set the callback for per-app volume changes
     * This should be called by OverlayService to wire up to AudioSessionManager
     */
    fun setAppVolumeCallback(callback: (OverlayAppEntry, Float) -> Unit) {
        onAppVolumeChangeCallback = callback
    }

    fun clearAppVolumeCallback() {
        onAppVolumeChangeCallback = null
    }

    fun setVolumeTargetCallbacks(
        onSelected: (VolumeTarget) -> Unit,
        onShown: () -> Unit,
        onHidden: () -> Unit
    ) {
        onVolumeTargetSelectedCallback = onSelected
        onOverlayShownCallback = onShown
        onOverlayHiddenCallback = onHidden
    }

    fun clearVolumeTargetCallbacks() {
        onVolumeTargetSelectedCallback = null
        onOverlayShownCallback = null
        onOverlayHiddenCallback = null
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

    fun setPauseAmplyCallback(callback: () -> Unit) {
        onPauseAmplyCallback = callback
    }

    fun clearPauseAmplyCallback() {
        onPauseAmplyCallback = null
    }

    fun setNotificationModeToggleCallback(callback: () -> Unit) {
        onNotificationModeToggleCallback = callback
    }

    fun clearNotificationModeToggleCallback() {
        onNotificationModeToggleCallback = null
    }

    fun refreshStreamVolumes() {
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
        apps: List<OverlayAppEntry>,
        connectionState: VolumeServiceConnectionState
    ) {
        currentApps.value = prepareAppIcons(apps)
        shizukuConnectionState.value = connectionState
    }

    private fun prepareAppIcons(apps: List<OverlayAppEntry>): List<OverlayAppEntry> =
        apps.map { app ->
            val bitmap = app.appIconBitmap
                ?: appIconBitmapCache.get(app.packageName)
                ?: runCatching { app.appIcon?.toBitmap(52, 52) }
                    .getOrNull()
                    ?.also { appIconBitmapCache.put(app.packageName, it) }
            if (app.appIconBitmap === bitmap) app else app.copy(appIconBitmap = bitmap)
        }

    /**
     * Invoke the session volume callback
     * This method ensures the callback is always read fresh from the property
     */
    private fun invokeAppVolumeCallback(app: OverlayAppEntry, volume: Float) {
        onAppVolumeChangeCallback?.invoke(app, volume)
    }

    /** Show or update the overlay using the latest package-level app entries. */
    fun show(
        context: Context,
        selectedTarget: VolumeTarget,
        newIconType: String = "MUSIC",
        apps: List<OverlayAppEntry> = emptyList(),
        connectionState: VolumeServiceConnectionState = VolumeServiceConnectionState.WAITING_FOR_PERMISSION,
        overlaySide: OverlaySide = OverlaySide.LEFT,
        overlayVerticalFraction: Float = 0.5f,
        windowType: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    ) {
        ensureManagerScope()
        removeJob?.cancel()
        removeJob = null

        // Initialize managers if needed
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (audioManager == null) {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }

        // DEBUG: Log incoming state
        Log.d("OverlayManager", "show() called: target=$selectedTarget, apps=${apps.size}, connection=$connectionState")

        // Update state
        updateAvailableOverlayWidth(context)
        refreshSystemStreamVolumes()
        streamMuteToggleController.onVolumeChangedOutsideToggle(selectedTarget.streamType)
        selectedVolumeTarget.value = selectedTarget
        iconType.value = newIconType
        currentApps.value = prepareAppIcons(apps)
        shizukuConnectionState.value = connectionState
        if (shizukuIcon.value == null) {
            shizukuIcon.value = runCatching {
                context.packageManager
                    .getApplicationIcon("moe.shizuku.privileged.api")
                    .toBitmap(68, 68)
            }.getOrNull()
        }
        currentOverlaySide.value = overlaySide
        currentOverlayVerticalFraction.floatValue = overlayVerticalFraction.coerceIn(0f, 1f)
        
        // DEBUG: Verify state was set
        Log.d("OverlayManager", "State updated: currentApps.value has ${currentApps.value.size} items")

        if (overlayContainer != null && currentWindowType != windowType) {
            removeOverlay()
        }

        if (overlayContainer == null) {
            createOverlay(context, windowType)
        } else {
            updateOverlayPosition(context)
        }
        overlayVisible.value = true
        onOverlayShownCallback?.invoke()

        // Reset auto-hide timer
        scheduleHide(currentAutoHideDelayMs())
    }

    /**
     * Create the overlay view with proper lifecycle
     * CRITICAL: Uses wrapper FrameLayout for proper view tree lifecycle propagation
     */
    private fun createOverlay(context: Context, windowType: Int) {
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
                    visible = overlayVisible.value,
                    iconType = iconType.value,
                    apps = currentApps.value,
                    shizukuConnectionState = shizukuConnectionState.value,
                    shizukuIcon = shizukuIcon.value,
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
                        scheduleHide(currentAutoHideDelayMs()) // Reset timer on interaction
                    },
                    onTouchStart = {
                        cancelAutoHide() // Stop timer while user is touching
                    },
                    onTouchEnd = {
                        scheduleHide(currentAutoHideDelayMs()) // Start timer when touch ends
                    },
                    onExpandedChange = { expanded ->
                        isOverlayExpanded = expanded
                        scheduleHide(currentAutoHideDelayMs())
                    },
                    onPauseAmply = {
                        onPauseAmplyCallback?.invoke()
                        hide()
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
        // Note: Removed FLAG_NOT_FOCUSABLE to allow touch events on sliders
        val params = WindowManager.LayoutParams(
            windowWidthForCurrentOrientation(context),
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).applyPosition(context)

        // Step 10: Add CONTAINER to window manager
        try {
            windowManager?.addView(container, params)
            currentWindowType = windowType
        } catch (e: Exception) {
            e.printStackTrace()
            // Cleanup on failure
            newRecomposer.cancel()
            recomposer = null
            lifecycleOwner?.destroy()
            lifecycleOwner = null
            overlayContainerRef = null
            composeView = null
        }
    }

    /**
     * Hide and remove the overlay
     */
    fun hide() {
        hideJob?.cancel()

        if (overlayContainer == null) {
            return
        }

        val wasVisible = overlayVisible.value
        overlayVisible.value = false
        if (wasVisible) {
            onOverlayHiddenCallback?.invoke()
        }
        removeJob?.cancel()
        removeJob = managerScope.launch {
            delay(200L)
            removeOverlay()
        }
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
        isOverlayExpanded = false
        removeJob = null
    }

    private fun refreshSystemStreamVolumes() {
        val manager = audioManager ?: return
        val state = dynamicStreamState.value
        val topology = state.topology
        val combinedRinger = topology.aliasesTogether(VolumeTarget.RING, VolumeTarget.NOTIFICATION)
        volumeBars.value = state.visibleTargets().map { target ->
            val aliases = VolumeTarget.entries
                .filter { topology.canonicalTarget(it) == target }
                .mapTo(linkedSetOf()) { it.streamType }
            val current = runCatching { manager.getStreamVolume(target.streamType) }.getOrDefault(0)
            val min = runCatching { manager.getStreamMinVolume(target.streamType) }.getOrDefault(0)
            val max = runCatching { manager.getStreamMaxVolume(target.streamType) }.getOrDefault(min)
            val ringerControl = VolumeTarget.NOTIFICATION.streamType in aliases ||
                VolumeTarget.RING.streamType in aliases
            VolumeBarModel(
                target = target,
                aliases = aliases.ifEmpty { setOf(target.streamType) },
                label = if (combinedRinger && ringerControl) "Ring & notifications" else target.label,
                currentVolume = current,
                minVolume = min,
                maxVolume = max,
                active = target in state.activeTargets,
                enabled = target.userAdjustable && target !in state.disabledTargets,
                combinedRinger = combinedRinger && ringerControl,
                notificationAlertMode = if (ringerControl) {
                    NotificationAlertMode.resolve(manager.ringerMode, current, min)
                } else null
            )
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
        val clampedVolume = newVolume.coerceIn(
            manager.getStreamMinVolume(streamType),
            manager.getStreamMaxVolume(streamType)
        )
        if (!changedThroughMuteToggle) {
            streamMuteToggleController.onVolumeChangedOutsideToggle(streamType)
        }
        val target = VolumeTarget.findByStreamType(streamType) ?: return
        onSystemStreamVolumeChangeCallback?.invoke(target, clampedVolume)
            ?: runCatching { manager.setStreamVolume(streamType, clampedVolume, 0) }
        refreshSystemStreamVolumes()
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
        val nextVolume = streamMuteToggleController.nextVolume(
            streamType = streamType,
            currentVolume = manager.getStreamVolume(streamType),
            minVolume = manager.getStreamMinVolume(streamType),
            maxVolume = manager.getStreamMaxVolume(streamType)
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
            delay(delayMs)
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
        if (isOverlayExpanded) EXPANDED_AUTO_HIDE_DELAY_MS else COLLAPSED_AUTO_HIDE_DELAY_MS

    /**
     * Check if overlay is showing
     */
    fun isShowing(): Boolean = overlayContainer != null

    /**
     * Cleanup resources
     */
    fun cleanup() {
        hideJob?.cancel()
        removeJob?.cancel()
        if (overlayVisible.value) {
            onOverlayHiddenCallback?.invoke()
        }
        removeOverlay()
        managerScope.cancel()
        appIconBitmapCache.evictAll()
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
        params.applyPosition(context)
        try {
            windowManager?.updateViewLayout(container, params)
        } catch (e: Exception) {
            Log.w("OverlayManager", "Failed to update overlay position", e)
        }
    }

    private fun WindowManager.LayoutParams.applyPosition(context: Context): WindowManager.LayoutParams {
        val margin = (16 * context.resources.displayMetrics.density).toInt()
        val baseOverlayHeight = (BASE_OVERLAY_HEIGHT_DP * context.resources.displayMetrics.density).toInt()
        val pauseControlSlotHeight =
            (PAUSE_CONTROL_SLOT_HEIGHT_DP * context.resources.displayMetrics.density).toInt()
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val maxPillY = (screenHeight - baseOverlayHeight).coerceAtLeast(0)
        val pillY = (maxPillY * currentOverlayVerticalFraction.floatValue).toInt()
        val windowY = pillY - pauseControlSlotHeight

        val isLandscape = screenWidth > screenHeight
        width = windowWidthForCurrentOrientation(context)
        gravity = when {
            isLandscape -> Gravity.START or Gravity.TOP
            currentOverlaySide.value == OverlaySide.LEFT -> Gravity.START or Gravity.TOP
            else -> Gravity.END or Gravity.TOP
        }
        x = margin
        y = windowY.coerceIn(-pauseControlSlotHeight, maxPillY - pauseControlSlotHeight)
        return this
    }

    private fun updateAvailableOverlayWidth(context: Context) {
        val density = context.resources.displayMetrics.density
        val horizontalMargins = 32f * density
        availableOverlayWidthDp.floatValue =
            ((context.resources.displayMetrics.widthPixels - horizontalMargins) / density)
                .coerceAtLeast(216f)
    }

    private fun windowWidthForCurrentOrientation(context: Context): Int {
        val metrics = context.resources.displayMetrics
        val margin = (16 * metrics.density).toInt()
        val isLandscape = metrics.widthPixels > metrics.heightPixels
        return if (isLandscape) {
            (metrics.widthPixels - margin * 2).coerceAtLeast((216 * metrics.density).toInt())
        } else {
            WindowManager.LayoutParams.WRAP_CONTENT
        }
    }
}
