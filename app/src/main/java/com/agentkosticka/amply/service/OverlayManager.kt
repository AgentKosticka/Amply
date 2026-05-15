package com.agentkosticka.amply.service

import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Bundle
import android.os.ResultReceiver
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
    private var windowManager: WindowManager? = null
    private var overlayContainerRef: WeakReference<FrameLayout>? = null
    private var composeView: ComposeView? = null
    private var lifecycleOwner: ComposeLifecycleOwner? = null
    private var recomposer: Recomposer? = null
    private var audioManager: AudioManager? = null

    // Auto-hide timer
    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var hideJob: Job? = null
    private var removeJob: Job? = null

    // State - persists across hide/show cycles
    private val currentVolume = mutableIntStateOf(0)
    private val maxVolume = mutableIntStateOf(30)
    private val isMuted = mutableStateOf(false)
    private val iconType = mutableStateOf("MUSIC")
    private val currentSessions = mutableStateOf<List<AudioSession>>(emptyList())
    private val currentOverlaySide = mutableStateOf(OverlaySide.LEFT)
    private val currentOverlayVerticalFraction = mutableFloatStateOf(0.5f)
    
    // Phase 3.5: Smart Focus - the foreground app if detected
    private val focusedApp = mutableStateOf<AudioSession?>(null)

    // Callback for per-app volume changes (wired to Shizuku backend)
    private var onSessionVolumeChangeCallback: ((Int, Float) -> Unit)? = null
    
    // Phase 3: ResultReceiver for robust IPC volume control
    private var volumeReceiver: ResultReceiver? = null
    private val overlayVisible = mutableStateOf(false)

    private val overlayContainer: FrameLayout?
        get() = overlayContainerRef?.get()

    /**
     * Set the callback for per-app volume changes
     * This should be called by VolumeKeyService to wire up to AudioSessionManager
     */
    fun setSessionVolumeCallback(callback: (Int, Float) -> Unit) {
        Log.d("OverlayManager", "setSessionVolumeCallback: callback set")
        onSessionVolumeChangeCallback = callback
    }

    /**
     * Invoke the session volume callback
     * This method ensures the callback is always read fresh from the property
     */
    private fun invokeSessionVolumeCallback(sessionId: Int, volume: Float) {
        Log.d("OverlayManager", "invokeSessionVolumeCallback: sessionId=$sessionId, volume=$volume")
        val callback = onSessionVolumeChangeCallback
        if (callback != null) {
            Log.d("OverlayManager", "Callback exists, invoking...")
            callback.invoke(sessionId, volume)
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
     * @param volumeReceiver ResultReceiver for per-app volume changes (Phase 3)
     */
    fun show(
        context: Context,
        volume: Int,
        newIconType: String = "MUSIC",
        sessions: List<AudioSession> = emptyList(),
        focusedAppSession: AudioSession? = null,
        volumeReceiver: ResultReceiver? = null,
        overlaySide: OverlaySide = OverlaySide.LEFT,
        overlayVerticalFraction: Float = 0.5f
    ) {
        removeJob?.cancel()
        removeJob = null

        // Initialize managers if needed
        if (windowManager == null) {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        if (audioManager == null) {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            maxVolume.intValue = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 30
        }

        // DEBUG: Log incoming state
        Log.d("OverlayManager", "show() called: volume=$volume, sessions=${sessions.size}, focused=${focusedAppSession?.appName}")

        // Update state
        currentVolume.intValue = volume
        isMuted.value = (volume == 0)
        iconType.value = newIconType
        currentSessions.value = sessions
        focusedApp.value = focusedAppSession
        this.volumeReceiver = volumeReceiver // Update the receiver property
        currentOverlaySide.value = overlaySide
        currentOverlayVerticalFraction.floatValue = overlayVerticalFraction.coerceIn(0f, 1f)
        
        // DEBUG: Verify state was set
        Log.d("OverlayManager", "State updated: currentSessions.value has ${currentSessions.value.size} items")

        if (overlayContainer == null) {
            createOverlay(context)
        } else {
            updateOverlayPosition(context)
        }
        overlayVisible.value = true

        // Reset auto-hide timer
        scheduleHide()
    }

    /**
     * Create the overlay view with proper lifecycle
     * CRITICAL: Uses wrapper FrameLayout for proper view tree lifecycle propagation
     */
    private fun createOverlay(context: Context) {
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
                    visible = overlayVisible.value,
                    iconType = iconType.value,
                    sessions = currentSessions.value,
                    focusedApp = focusedApp.value, // Phase 3.5: Smart Focus
                    overlaySide = currentOverlaySide.value,
                    onVolumeChange = { newVolume ->
                        setVolume(newVolume)
                    },
                    onSessionVolumeChange = { session, volume ->
                        // Phase 3: Use ResultReceiver to send data back to Service
                        Log.d(
                            "OverlayManager",
                            "Sending volume change via ResultReceiver: id=${session.sessionId} pkg=${session.packageName} vol=$volume"
                        )
                        val bundle = Bundle().apply {
                            putFloat("volume", volume)
                            putString("package_name", session.packageName)
                        }
                        volumeReceiver?.send(session.sessionId, bundle)
                            ?: Log.w("OverlayManager", "ResultReceiver is null!")
                    },
                    onMuteToggle = {
                        toggleMute()
                    },
                    onInteraction = {
                        scheduleHide() // Reset timer on interaction
                    },
                    onTouchStart = {
                        cancelAutoHide() // Stop timer while user is touching
                    },
                    onTouchEnd = {
                        scheduleHide(2500L) // Start timer when touch ends
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
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).applyPosition(context)

        // Step 10: Add CONTAINER to window manager
        try {
            windowManager?.addView(container, params)
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
        overlayVisible.value = false
        removeJob = null
    }

    /**
     * Update volume from overlay interaction
     */
    private fun setVolume(newVolume: Int) {
        currentVolume.intValue = newVolume
        audioManager?.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            newVolume,
            0 // No system UI
        )
    }

    /**
     * Smart mute toggle: 0 → 70%, any → 0
     */
    private fun toggleMute() {
        if (currentVolume.intValue == 0) {
            // Unmute: Restore to 70%
            val restoreVolume = (maxVolume.intValue * 0.7f).toInt()
            setVolume(restoreVolume)
        } else {
            // Mute: Set to 0
            setVolume(0)
        }
    }

    /**
     * Schedule auto-hide after delay
     * @param delayMs Delay in milliseconds before hiding (default 2500ms)
     */
    private fun scheduleHide(delayMs: Long = 2500L) {
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

    private fun updateOverlayPosition(context: Context) {
        val container = overlayContainer ?: return
        val params = container.layoutParams as? WindowManager.LayoutParams ?: return
        params.applyPosition(context)
        try {
            windowManager?.updateViewLayout(container, params)
        } catch (e: Exception) {
            Log.w("OverlayManager", "Failed to update overlay position", e)
        }
    }

    private fun WindowManager.LayoutParams.applyPosition(context: Context): WindowManager.LayoutParams {
        val margin = (16 * context.resources.displayMetrics.density).toInt()
        val approximateOverlayHeight = (220 * context.resources.displayMetrics.density).toInt()
        val screenHeight = context.resources.displayMetrics.heightPixels
        val maxY = (screenHeight - approximateOverlayHeight).coerceAtLeast(0)

        gravity = when (currentOverlaySide.value) {
            OverlaySide.LEFT -> Gravity.START or Gravity.TOP
            OverlaySide.RIGHT -> Gravity.END or Gravity.TOP
        }
        x = margin
        y = (maxY * currentOverlayVerticalFraction.floatValue).toInt().coerceIn(0, maxY)
        return this
    }
}
