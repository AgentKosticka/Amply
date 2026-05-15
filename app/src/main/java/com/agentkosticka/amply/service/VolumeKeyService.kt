package com.agentkosticka.amply.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.agentkosticka.amply.audio.AudioSessionManager
import com.agentkosticka.amply.data.toParcelable
import com.agentkosticka.amply.shizuku.ShizukuRepository
import kotlinx.coroutines.*

/**
 * AccessibilityService that detects volume key presses
 * with proper hold-to-repeat behavior and accurate volume mapping
 * PLUS: Dynamic icon detection based on audio device connections
 */
class VolumeKeyService : AccessibilityService() {

    companion object {
        private const val TAG = "VolumeKeyService"
    }

    private var audioManager: AudioManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Hold-to-repeat job
    private var repeatJob: Job? = null

    // Volume step configuration
    private var maxVolume = 30
    private val volumeStep = 1 // Change by 1 step per press

    // Current icon type
    private var currentIconType = "MUSIC"

    // Phase 3.5: Smart Focus - track foreground app package
    private var foregroundPackage: String? = null

    // Phase 3: Audio session management
    private var shizukuRepository: ShizukuRepository? = null
    private var audioSessionManager: AudioSessionManager? = null

    // Audio device callback for dynamic icon updates
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            super.onAudioDevicesAdded(addedDevices)

            // AGGRESSIVE Z-ORDER STRATEGY: "Double Tap" to force our overlay on top
            serviceScope.launch {
                // Step 1: Wait for System UI to appear
                delay(300) // 300ms delay

                // Step 2: Update icon type and show overlay (first tap)
                updateIconType()
                val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                showOverlay(currentVol)

                // Step 3: Wait a bit
                delay(150) // 150ms delay

                // Step 4: FORCE REFRESH - hide & immediately re-show (second tap)
                // This forces WindowManager to re-evaluate the stack
                forceRefreshOverlay(currentVol)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            super.onAudioDevicesRemoved(removedDevices)

            // Update icon to reflect device disconnection
            serviceScope.launch {
                delay(300) // Consistency delay
                updateIconType()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // Get real max volume from system
        maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 30

        // Register audio device callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
        }

        // Initial icon type detection
        updateIconType()

        // Phase 3: Initialize audio session management
        try {
            shizukuRepository = ShizukuRepository(this)
            audioSessionManager = AudioSessionManager(this, shizukuRepository!!)
            audioSessionManager?.startPolling()
            Log.d(TAG, "AudioSessionManager initialized and polling started")
            
            // Set up per-app volume callback
            ensureCallbackRegistered()
            Log.d(TAG, "Per-app volume callback registered in onServiceConnected")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioSessionManager", e)
            // Continue without per-app volume (graceful fallback)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Phase 3.5: Track foreground app for Smart Focus
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            if (!packageName.isNullOrBlank() && 
                !packageName.startsWith("com.android.systemui") &&
                !packageName.startsWith("com.nothing.systemui")) {
                foregroundPackage = packageName
                Log.d(TAG, "Foreground app: $foregroundPackage")
            }
        }
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                return when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        handleVolumeKeyDown(isUp = true)
                        true // Consume event
                    }
                    KeyEvent.ACTION_UP -> {
                        handleVolumeKeyUp()
                        true
                    }
                    else -> false
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                return when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        handleVolumeKeyDown(isUp = false)
                        true // Consume event
                    }
                    KeyEvent.ACTION_UP -> {
                        handleVolumeKeyUp()
                        true
                    }
                    else -> false
                }
            }
        }

        return false // Let other keys pass through
    }

    /**
     * Handle volume key down with hold-to-repeat logic
     */
    private fun handleVolumeKeyDown(isUp: Boolean) {
        // Cancel any existing repeat job
        repeatJob?.cancel()

        // Change volume once immediately
        changeVolume(isUp)

        // Start repeat after initial debounce
        repeatJob = serviceScope.launch {
            delay(400) // Initial debounce: 400ms

            // Repeat every 150ms while held
            while (isActive) {
                changeVolume(isUp)
                delay(150)
            }
        }
    }

    /**
     * Handle volume key up - stop repeating
     */
    private fun handleVolumeKeyUp() {
        repeatJob?.cancel()
        repeatJob = null
    }

    /**
     * Change volume by one step
     * Uses real max volume and proper mapping
     */
    private fun changeVolume(isUp: Boolean) {
        val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0

        val newVolume = if (isUp) {
            (currentVolume + volumeStep).coerceAtMost(maxVolume)
        } else {
            (currentVolume - volumeStep).coerceAtLeast(0)
        }

        // CRITICAL: Set volume using real system values
        audioManager?.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            newVolume,
            0 // No flags = no system UI
        )

        // Show/update custom overlay
        showOverlay(newVolume)
    }

    /**
     * Show or update the overlay with current sessions
     * Phase 3.5: Now includes focused app detection for Smart Focus
     */
    private fun showOverlay(volume: Int) {
        // IMPORTANT: Ensure callback is always set (safety net for app updates)
        // This is needed because onServiceConnected() may not run after an APK update
        ensureCallbackRegistered()
        
        // Get current sessions from AudioSessionManager
        val sessions = audioSessionManager?.getCurrentSessions() ?: emptyList()
        
        // DEBUG: Log session count
        Log.d(TAG, "showOverlay: volume=$volume, sessions=${sessions.size}, foreground=$foregroundPackage")
        sessions.forEachIndexed { index, session ->
            Log.d(TAG, "  Session[$index]: ${session.appName} (uid=${session.uid}, pkg=${session.packageName})")
        }
        
        // Phase 3.5: Get focused app based on foreground package
        val focusedApp = audioSessionManager?.getFocusedApp(foregroundPackage)
            ?: audioSessionManager?.getMostRecentSession() // Fallback to most recent
        Log.d(TAG, "  Focused app: ${focusedApp?.appName ?: "none"}")

        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW_OVERLAY
            putExtra(OverlayService.EXTRA_VOLUME, volume)
            putExtra(OverlayService.EXTRA_MAX_VOLUME, maxVolume)
            putExtra(OverlayService.EXTRA_ICON_TYPE, currentIconType)

            // Phase 3: Pass sessions as parcelable list
            if (sessions.isNotEmpty()) {
                val parcelableSessions = ArrayList(sessions.map { it.toParcelable() })
                putParcelableArrayListExtra(OverlayService.EXTRA_SESSIONS, parcelableSessions)
                Log.d(TAG, "  Passing ${parcelableSessions.size} parcelable sessions to overlay")
            } else {
                Log.w(TAG, "  WARNING: No sessions to pass to overlay!")
            }
            
            // Phase 3.5: Pass focused app for Smart Focus
            focusedApp?.let {
                putExtra(OverlayService.EXTRA_FOCUSED_APP, it.toParcelable())
            }

            // Phase 3: Create ResultReceiver for robust per-app volume control
            val volumeReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    val sessionId = resultCode // We use resultCode as sessionId
                    val newVolume = resultData?.getFloat("volume") ?: return
                    Log.d(TAG, "VolumeReceiver: sessionId=$sessionId, volume=$newVolume")
                    
                    serviceScope.launch {
                        audioSessionManager?.setSessionVolume(sessionId, newVolume)
                    }
                }
            }
            putExtra(OverlayService.EXTRA_VOLUME_RECEIVER, volumeReceiver)
        }
        
        // Start the overlay service
        startService(intent)
    }

    /**
     * Force refresh overlay by hiding and immediately re-showing
     * This forces WindowManager to re-evaluate Z-order stack
     */
    private fun forceRefreshOverlay(volume: Int) {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_FORCE_REFRESH
            putExtra(OverlayService.EXTRA_VOLUME, volume)
            putExtra(OverlayService.EXTRA_MAX_VOLUME, maxVolume)
            putExtra(OverlayService.EXTRA_ICON_TYPE, currentIconType)
        }
        startService(intent)
    }

    /**
     * Detect connected audio devices and update icon type
     */
    private fun updateIconType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return

            // Priority: Bluetooth > Wired > Default
            currentIconType = when {
                devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                             it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                             it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } -> "BLUETOOTH"

                devices.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                             it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                             it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                             it.type == AudioDeviceInfo.TYPE_USB_DEVICE } -> "HEADPHONE"

                else -> "MUSIC"
            }
        } else {
            // Fallback for older Android versions
            currentIconType = "MUSIC"
        }
    }

    /**
     * Ensure the per-app volume callback is registered
     * Called from both onServiceConnected() and showOverlay() for redundancy
     */
    private fun ensureCallbackRegistered() {
        OverlayManager.setSessionVolumeCallback { sessionId, newVolume ->
            Log.d(TAG, "Per-app volume callback invoked: sessionId=$sessionId, volume=$newVolume")
            serviceScope.launch {
                audioSessionManager?.setSessionVolume(sessionId, newVolume)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        repeatJob?.cancel()
        serviceScope.cancel()

        // Unregister audio device callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        }

        // Phase 3: Cleanup audio session management
        audioSessionManager?.cleanup()
        shizukuRepository?.cleanup()
        Log.d(TAG, "VolumeKeyService destroyed, cleaned up audio session management")
    }
}
