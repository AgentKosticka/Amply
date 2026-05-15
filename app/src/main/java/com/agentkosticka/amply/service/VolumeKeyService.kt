package com.agentkosticka.amply.service

import android.accessibilityservice.AccessibilityService
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
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

    // Audio device callback for dynamic icon updates
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            super.onAudioDevicesAdded(addedDevices)

            serviceScope.launch {
                delay(300)
                updateIconType()
                val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                showOverlay(currentVol)
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
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)

        // Initial icon type detection
        updateIconType()

        OverlayService.startRuntime(this)
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
        Log.d(TAG, "showOverlay: volume=$volume, foreground=$foregroundPackage")
        OverlayService.showFromAccessibilityHost(
            host = this,
            volume = volume,
            iconType = currentIconType,
            foregroundPackage = foregroundPackage
        )
    }

    /**
     * Detect connected audio devices and update icon type
     */
    private fun updateIconType() {
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
    }

    override fun onDestroy() {
        super.onDestroy()
        repeatJob?.cancel()
        serviceScope.cancel()

        // Unregister audio device callback
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        OverlayManager.cleanup()
        Log.d(TAG, "VolumeKeyService destroyed")
    }
}
