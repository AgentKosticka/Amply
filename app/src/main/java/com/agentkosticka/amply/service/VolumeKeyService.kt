package com.agentkosticka.amply.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import java.util.Locale

/**
 * AccessibilityService that detects volume key presses
 * with proper hold-to-repeat behavior and accurate volume mapping
 * PLUS: Dynamic icon detection based on audio device connections
 */
class VolumeKeyService : AccessibilityService() {

    companion object {
        private const val TAG = "VolumeKeyService"
        private val VOLUME_KEY_CAMERA_PACKAGES = setOf(
            "com.android.camera",
            "com.android.camera2",
            "com.google.android.GoogleCamera",
            "com.google.android.apps.camera",
            "com.nothing.camera",
            "com.sec.android.app.camera",
            "com.samsung.android.camera",
            "com.oneplus.camera",
            "com.oplus.camera",
            "com.coloros.camera",
            "com.motorola.camera",
            "com.motorola.camera3",
            "com.huawei.camera",
            "com.hihonor.camera",
            "com.miui.camera",
            "org.lineageos.aperture",
            "net.sourceforge.opencamera",
            "com.motioncam"
        )
        private val VIDEO_CALL_PACKAGE_HINTS = setOf(
            "meet",
            "duo",
            "zoom",
            "teams",
            "skype",
            "whatsapp",
            "messenger",
            "telegram",
            "discord",
            "signal",
            "facetime",
            "videocall",
            "videochat"
        )
    }

    private var audioManager: AudioManager? = null
    private var cameraManager: CameraManager? = null
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
    private val activeCameraIds = mutableSetOf<String>()
    private val volumeKeyCameraAppCache = mutableMapOf<String, Boolean>()

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

    private val cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            activeCameraIds.remove(cameraId)
            Log.d(TAG, "Camera available: $cameraId, active=${activeCameraIds.isNotEmpty()}")
        }

        override fun onCameraUnavailable(cameraId: String) {
            activeCameraIds.add(cameraId)
            Log.d(TAG, "Camera unavailable/in use: $cameraId, active=${activeCameraIds.isNotEmpty()}")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        // Get real max volume from system
        maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 30

        // Register audio device callback
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
        cameraManager?.registerAvailabilityCallback(mainExecutor, cameraAvailabilityCallback)

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
            }
        }
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (shouldBypassForCamera()) {
                    handleVolumeKeyUp()
                    Log.d(TAG, "Bypassing volume up for foreground camera app: $foregroundPackage")
                    return false
                }

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
                if (shouldBypassForCamera()) {
                    handleVolumeKeyUp()
                    Log.d(TAG, "Bypassing volume down for foreground camera app: $foregroundPackage")
                    return false
                }

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

    private fun shouldBypassForCamera(): Boolean {
        val packageName = foregroundPackage ?: return false
        return activeCameraIds.isNotEmpty() && isVolumeKeyCameraApp(packageName)
    }

    private fun isVolumeKeyCameraApp(packageName: String): Boolean {
        return volumeKeyCameraAppCache.getOrPut(packageName) {
            if (packageName in VOLUME_KEY_CAMERA_PACKAGES) return@getOrPut true

            val normalizedPackage = packageName.lowercase(Locale.US)
            if (VIDEO_CALL_PACKAGE_HINTS.any { it in normalizedPackage }) return@getOrPut false

            try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                val appName = packageManager.getApplicationLabel(appInfo).toString().lowercase(Locale.US)
                "camera" in appName && VIDEO_CALL_PACKAGE_HINTS.none { it in appName }
            } catch (_: Exception) {
                false
            }
        }
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
        cameraManager?.unregisterAvailabilityCallback(cameraAvailabilityCallback)
        stopService(Intent(this, OverlayService::class.java))
        Log.d(TAG, "VolumeKeyService destroyed")
    }
}
