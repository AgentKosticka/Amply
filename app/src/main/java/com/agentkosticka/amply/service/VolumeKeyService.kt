package com.agentkosticka.amply.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.agentkosticka.amply.AmplyApplication
import com.agentkosticka.amply.audio.VolumeKeyStreamAction
import com.agentkosticka.amply.audio.VolumeTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
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
        @Volatile private var suppressInjectedKeysUntilElapsedMs: Long = 0L

        fun suppressInjectedKeysFor(durationMs: Long) {
            suppressInjectedKeysUntilElapsedMs = SystemClock.elapsedRealtime() + durationMs
        }
    }

    private var audioManager: AudioManager? = null
    private var cameraManager: CameraManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Hold-to-repeat job
    private var repeatJob: Job? = null

    private val volumeStep = 1 // Change by 1 step per press

    // Current icon type
    private var currentIconType = "MUSIC"

    // Phase 3.5: Smart Focus - track foreground app package
    private var foregroundPackage: String? = null
    private val activeCameraIds = mutableSetOf<String>()
    private val volumeKeyCameraAppCache = mutableMapOf<String, Boolean>()
    private val sequenceRouter = VolumeKeySequenceRouter()
    private val streamActionRouter = VolumeKeyStreamActionRouter()
    private var routingPreferencesLoaded = false
    private var passThroughPackages: Set<String> = emptySet()
    private var pausedUntilEpochMs: Long = 0L
    private var routingPreferencesJob: Job? = null

    // Audio device callback for dynamic icon updates
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            super.onAudioDevicesAdded(addedDevices)

            serviceScope.launch {
                delay(300)
                updateIconType()
                showOverlay((application as AmplyApplication).runtime.selectedVolumeTarget.value)
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

        // Register audio device callback
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
        cameraManager?.registerAvailabilityCallback(mainExecutor, cameraAvailabilityCallback)

        // Initial icon type detection
        updateIconType()

        val preferences = (application as AmplyApplication).runtime.preferencesManager
        routingPreferencesJob = serviceScope.launch {
            combine(
                preferences.volumeKeyPassThroughPackages,
                preferences.amplyPausedUntilEpochMs
            ) { packages, pausedUntil -> packages to pausedUntil }
                .collect { (packages, pausedUntil) ->
                    passThroughPackages = packages
                    pausedUntilEpochMs = pausedUntil
                    routingPreferencesLoaded = true
                }
        }

        OverlayService.startRuntime(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Phase 3.5: Track foreground app for Smart Focus
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            if (!packageName.isNullOrBlank() && 
                !packageName.startsWith("com.android.systemui") &&
                !packageName.startsWith("com.nothing.systemui")) {
                if (foregroundPackage != packageName) {
                    foregroundPackage = packageName
                    val runtime = (application as AmplyApplication).runtime
                    runtime.onForegroundPackageChanged(packageName)
                    runtime.shizukuRepository.checkPermissionStateThrottled()
                }
            }
        }
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (SystemClock.elapsedRealtime() < suppressInjectedKeysUntilElapsedMs) return false
        val isUp = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true
            KeyEvent.KEYCODE_VOLUME_DOWN -> false
            else -> return false
        }

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val route = sequenceRouter.onDown(event.keyCode, event.repeatCount, ::currentRoute)
                if (route == VolumeKeyRoute.PASS_THROUGH) {
                    handleVolumeKeyUp()
                    Log.d(TAG, "Passing volume key through to $foregroundPackage")
                    false
                } else {
                    val action = streamActionRouter.onDown(
                        event.keyCode,
                        event.repeatCount,
                        ::resolveStreamAction
                    )
                    when (action) {
                        VolumeKeyStreamAction.SilenceIncomingRinger -> {
                            handleVolumeKeyUp()
                            false
                        }
                        is VolumeKeyStreamAction.Adjust -> {
                            if (event.repeatCount == 0) handleVolumeKeyDown(isUp, action.target)
                            true
                        }
                    }
                }
            }
            KeyEvent.ACTION_UP -> {
                val route = sequenceRouter.onUp(event.keyCode) ?: currentRoute()
                val streamAction = streamActionRouter.onUp(event.keyCode)
                handleVolumeKeyUp()
                route == VolumeKeyRoute.INTERCEPT &&
                    streamAction != VolumeKeyStreamAction.SilenceIncomingRinger
            }
            else -> false
        }
    }

    private fun currentRoute(): VolumeKeyRoute = VolumeKeyRoutingPolicy.route(
        VolumeKeyRoutingState(
            preferencesLoaded = routingPreferencesLoaded,
            foregroundPackage = foregroundPackage,
            passThroughPackages = passThroughPackages,
            pausedUntilEpochMs = pausedUntilEpochMs,
            cameraBypassActive = shouldBypassForCamera(),
            nowEpochMs = System.currentTimeMillis()
        )
    )

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
    private fun handleVolumeKeyDown(isUp: Boolean, target: VolumeTarget) {
        // Cancel any existing repeat job
        repeatJob?.cancel()

        // Change volume once immediately
        changeVolume(isUp, target)

        // Start repeat after initial debounce
        repeatJob = serviceScope.launch {
            delay(400) // Initial debounce: 400ms

            // Repeat every 150ms while held
            while (isActive) {
                changeVolume(isUp, target)
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
    private fun resolveStreamAction(): VolumeKeyStreamAction {
        val manager = audioManager ?: return VolumeKeyStreamAction.Adjust(VolumeTarget.MEDIA)
        return (application as AmplyApplication).runtime.volumeTargetSessionController
            .resolveForInitialKeyDown(manager.mode)
    }

    private fun changeVolume(isUp: Boolean, target: VolumeTarget) {
        val manager = audioManager ?: return
        val streamType = target.streamType
        val currentVolume = manager.getStreamVolume(streamType)
        val minVolume = manager.getStreamMinVolume(streamType)
        val maxVolume = manager.getStreamMaxVolume(streamType)

        val newVolume = VolumeStepPolicy.next(
            current = currentVolume,
            min = minVolume,
            max = maxVolume,
            isUp = isUp,
            step = volumeStep
        )

        if (target == VolumeTarget.NOTIFICATION) {
            (application as AmplyApplication).runtime.ringerExperimentExecutor
                .setNotificationVolumeFromControl(newVolume)
        } else {
            manager.setStreamVolume(
                streamType,
                newVolume,
                0 // No flags = no system UI
            )
        }

        showOverlay(target)
    }

    /**
     * Show or update the overlay with current sessions
     * Phase 3.5: Now includes focused app detection for Smart Focus
     */
    private fun showOverlay(target: VolumeTarget) {
        OverlayService.showFromAccessibilityHost(
            host = this,
            target = target,
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
        routingPreferencesJob?.cancel()
        sequenceRouter.clear()
        streamActionRouter.clear()
        serviceScope.cancel()

        // Unregister audio device callback
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        cameraManager?.unregisterAvailabilityCallback(cameraAvailabilityCallback)
        stopService(Intent(this, OverlayService::class.java))
        Log.d(TAG, "VolumeKeyService destroyed")
    }
}
