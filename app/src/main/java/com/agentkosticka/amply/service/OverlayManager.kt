package com.agentkosticka.amply.service

import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.agentkosticka.amply.data.AudioSession
import com.agentkosticka.amply.data.OverlaySide
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

    // Auto-hide timer
    private var managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var hideJob: Job? = null
    private var removeJob: Job? = null

    // State - persists across hide/show cycles
    private val currentVolume = mutableIntStateOf(0)
    private val maxVolume = mutableIntStateOf(30)
    private val alarmVolume = mutableIntStateOf(0)
    private val maxAlarmVolume = mutableIntStateOf(7)
    private val notificationVolume = mutableIntStateOf(0)
    private val maxNotificationVolume = mutableIntStateOf(7)
    private val callVolume = mutableIntStateOf(0)
    private val maxCallVolume = mutableIntStateOf(5)
    private val isMuted = mutableStateOf(false)
    private val iconType = mutableStateOf("MUSIC")
    private val currentSessions = mutableStateOf<List<AudioSession>>(emptyList())
    private val currentOverlaySide = mutableStateOf(OverlaySide.LEFT)
    private val currentOverlayVerticalFraction = mutableFloatStateOf(0.5f)
    private val availableOverlayWidthDp = mutableFloatStateOf(0f)
    
    // Phase 3.5: Smart Focus - the foreground app if detected
    private val focusedApp = mutableStateOf<AudioSession?>(null)

    // Callback for per-app volume changes (wired to the foreground runtime backend)
    private var onSessionVolumeChangeCallback: ((Int, String?, Float) -> Unit)? = null
    private var onPauseAmplyCallback: (() -> Unit)? = null
    private val overlayVisible = mutableStateOf(false)
    private var isOverlayExpanded = false

    private val overlayContainer: FrameLayout?
        get() = overlayContainerRef?.get()

    /**
     * Set the callback for per-app volume changes
     * This should be called by OverlayService to wire up to AudioSessionManager
     */
    fun setSessionVolumeCallback(callback: (Int, String?, Float) -> Unit) {
        Log.d("OverlayManager", "setSessionVolumeCallback: callback set")
        onSessionVolumeChangeCallback = callback
    }

    fun clearSessionVolumeCallback() {
        onSessionVolumeChangeCallback = null
    }

    fun setPauseAmplyCallback(callback: () -> Unit) {
        onPauseAmplyCallback = callback
    }

    fun clearPauseAmplyCallback() {
        onPauseAmplyCallback = null
    }

    fun updateSessions(sessions: List<AudioSession>, focusedAppSession: AudioSession?) {
        currentSessions.value = sessions
        focusedApp.value = focusedAppSession
    }

    /**
     * Invoke the session volume callback
     * This method ensures the callback is always read fresh from the property
     */
    private fun invokeSessionVolumeCallback(sessionId: Int, packageName: String?, volume: Float) {
        Log.d("OverlayManager", "invokeSessionVolumeCallback: sessionId=$sessionId, package=$packageName, volume=$volume")
        val callback = onSessionVolumeChangeCallback
        if (callback != null) {
            Log.d("OverlayManager", "Callback exists, invoking...")
            callback.invoke(sessionId, packageName, volume)
        } else {
            Log.e("OverlayManager", "ERROR: onSessionVolumeChangeCallback is NULL!")
        }
    }

    /**
     * Show or update the overlay
     * CRITICAL: Context is passed in, not stored
     * Phase 3.5: Added focusedAppSession for Smart Focus feature
     * Phase 3: Added volumeReceiver for robust IPC volume control
     *
     * @param context Service context
     * @param volume Current global volume
     * @param newIconType Device icon type (MUSIC, BLUETOOTH, HEADPHONE)
     * @param sessions List of active audio sessions (Phase 3)
     * @param focusedAppSession The currently focused/foreground app (Phase 3.5 Smart Focus)
     */
    fun show(
        context: Context,
        volume: Int,
        newIconType: String = "MUSIC",
        sessions: List<AudioSession> = emptyList(),
        focusedAppSession: AudioSession? = null,
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
        Log.d("OverlayManager", "show() called: volume=$volume, sessions=${sessions.size}, focused=${focusedAppSession?.appName}")

        // Update state
        updateAvailableOverlayWidth(context)
        refreshSystemStreamVolumes(mediaVolumeOverride = volume)
        isMuted.value = (currentVolume.intValue == 0)
        iconType.value = newIconType
        currentSessions.value = sessions
        focusedApp.value = focusedAppSession
        currentOverlaySide.value = overlaySide
        currentOverlayVerticalFraction.floatValue = overlayVerticalFraction.coerceIn(0f, 1f)
        
        // DEBUG: Verify state was set
        Log.d("OverlayManager", "State updated: currentSessions.value has ${currentSessions.value.size} items")

        if (overlayContainer != null && currentWindowType != windowType) {
            removeOverlay()
        }

        if (overlayContainer == null) {
            createOverlay(context, windowType)
        } else {
            updateOverlayPosition(context)
        }
        overlayVisible.value = true

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
                    currentVolume = currentVolume.intValue,
                    maxVolume = maxVolume.intValue,
                    alarmVolume = alarmVolume.intValue,
                    maxAlarmVolume = maxAlarmVolume.intValue,
                    notificationVolume = notificationVolume.intValue,
                    maxNotificationVolume = maxNotificationVolume.intValue,
                    callVolume = callVolume.intValue,
                    maxCallVolume = maxCallVolume.intValue,
                    visible = overlayVisible.value,
                    iconType = iconType.value,
                    sessions = currentSessions.value,
                    focusedApp = focusedApp.value, // Phase 3.5: Smart Focus
                    overlaySide = currentOverlaySide.value,
                    availableWidthDp = availableOverlayWidthDp.floatValue,
                    onVolumeChange = { newVolume ->
                        setStreamVolume(AudioManager.STREAM_MUSIC, newVolume)
                    },
                    onStreamVolumeChange = { streamType, newVolume ->
                        setStreamVolume(streamType, newVolume)
                    },
                    onSessionVolumeChange = { session, volume ->
                        Log.d(
                            "OverlayManager",
                            "Sending volume change via runtime callback: id=${session.sessionId} pkg=${session.packageName} vol=$volume"
                        )
                        invokeSessionVolumeCallback(session.sessionId, session.packageName, volume)
                    },
                    onMuteToggle = {
                        toggleMute()
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

        overlayVisible.value = false
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

    private fun refreshSystemStreamVolumes(mediaVolumeOverride: Int? = null) {
        val manager = audioManager ?: return
        currentVolume.intValue = mediaVolumeOverride ?: manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        maxVolume.intValue = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        alarmVolume.intValue = manager.getStreamVolume(AudioManager.STREAM_ALARM)
        maxAlarmVolume.intValue = manager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        notificationVolume.intValue = manager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        maxNotificationVolume.intValue = manager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
        callVolume.intValue = manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        maxCallVolume.intValue = manager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
    }

    /**
     * Update a system stream from overlay interaction.
     */
    private fun setStreamVolume(streamType: Int, newVolume: Int) {
        val manager = audioManager ?: return
        val clampedVolume = newVolume.coerceIn(0, manager.getStreamMaxVolume(streamType))
        when (streamType) {
            AudioManager.STREAM_MUSIC -> currentVolume.intValue = clampedVolume
            AudioManager.STREAM_ALARM -> alarmVolume.intValue = clampedVolume
            AudioManager.STREAM_NOTIFICATION -> notificationVolume.intValue = clampedVolume
            AudioManager.STREAM_VOICE_CALL -> callVolume.intValue = clampedVolume
        }
        manager.setStreamVolume(
            streamType,
            clampedVolume,
            0 // No system UI
        )
        isMuted.value = currentVolume.intValue == 0
    }

    /**
     * Smart mute toggle: 0 → 70%, any → 0
     */
    private fun toggleMute() {
        if (currentVolume.intValue == 0) {
            // Unmute: Restore to 70%
            val restoreVolume = (maxVolume.intValue * 0.7f).toInt()
            setStreamVolume(AudioManager.STREAM_MUSIC, restoreVolume)
        } else {
            // Mute: Set to 0
            setStreamVolume(AudioManager.STREAM_MUSIC, 0)
        }
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
        availableOverlayWidthDp.floatValue =
            (windowWidthForCurrentOrientation(context) / density).coerceAtLeast(216f)
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
