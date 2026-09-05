package com.agentkosticka.amply.volumekeys

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.app.KeyguardManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter
import android.os.Build
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.agentkosticka.amply.AmplyApplication
import com.agentkosticka.amply.audio.routing.MediaOutputRoute
import com.agentkosticka.amply.audio.routing.MediaOutputRoutePolicy
import com.agentkosticka.amply.audio.routing.MediaRouteDeviceKind
import com.agentkosticka.amply.audio.routing.MediaRouteSnapshot
import com.agentkosticka.amply.audio.routing.MediaRouteVolumeState
import com.agentkosticka.amply.audio.routing.MediaVolumeActionPolicy
import com.agentkosticka.amply.audio.routing.CallPhase
import com.agentkosticka.amply.audio.routing.VolumeKeyStreamAction
import com.agentkosticka.amply.audio.routing.VolumeAdjustmentResult
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.audio.ringer.RingerKeyAdjustmentResult
import com.agentkosticka.amply.overlay.window.OverlayManager
import com.agentkosticka.amply.service.OverlayService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * AccessibilityService that detects volume key presses
 * with proper hold-to-repeat behavior and accurate volume mapping
 * PLUS: Dynamic icon detection based on audio device connections
 */
open class VolumeKeyAccessibilityService : AccessibilityService() {

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
    private var mediaRouter: MediaRouter? = null
    private var cameraManager: CameraManager? = null
    private var keyguardManager: KeyguardManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Hold-to-repeat job
    private var repeatJob: Job? = null

    private val volumeStep = 1 // Change by 1 step per press

    // Current media output presentation for the large icon above the media bar.
    private var currentIconType = MediaOutputRoute.LOCAL.wireName
    private var mediaRouteGeneration = 0L
    private var currentMediaRouteInfo: MediaRouter.RouteInfo? = null
    private var currentMediaRouteSignature: String? = null
    private var currentMediaRouteVolumeState = MediaRouteVolumeState()
    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .build()

    // Foreground identity is used for routing and never persisted in logs.
    private var foregroundPackage: String? = null
    private val activeCameraIds = mutableSetOf<String>()
    private val volumeKeyCameraAppCache = mutableMapOf<String, Boolean>()
    private val sequenceRouter = VolumeKeySequenceRouter()
    private val streamActionRouter = VolumeKeyStreamActionRouter()
    private var routingPreferencesLoaded = false
    private var passThroughPackages: Set<String> = emptySet()
    private var pausedUntilEpochMs: Long = 0L
    private var routingPreferencesJob: Job? = null
    private lateinit var foregroundAppResolver: ForegroundAppResolver

    // Audio device callback for dynamic icon updates
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            super.onAudioDevicesAdded(addedDevices)

            serviceScope.launch {
                delay(300.milliseconds)
                updateIconType()
                if (OverlayManager.isShowing()) OverlayManager.refreshStreamVolumes()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            super.onAudioDevicesRemoved(removedDevices)

            // Update icon to reflect device disconnection
            serviceScope.launch {
                delay(300.milliseconds) // Consistency delay
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
        mediaRouter = getSystemService(MEDIA_ROUTER_SERVICE) as MediaRouter
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        keyguardManager = getSystemService(KeyguardManager::class.java)
        foregroundAppResolver = ForegroundAppResolver.fromPackageManager(packageManager, packageName)
        (application as AmplyApplication).runtime.setAccessibilityConnected(true)

        // Register audio device callback
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
        mediaRouter?.addCallback(
            MediaRouter.ROUTE_TYPE_LIVE_AUDIO,
            mediaRouteCallback,
            MediaRouter.CALLBACK_FLAG_UNFILTERED_EVENTS
        )
        cameraManager?.registerAvailabilityCallback(mainExecutor, cameraAvailabilityCallback)

        // Initial icon type detection
        updateIconType()
        OverlayManager.setRemoteMediaVolumeCallback(::setRemoteRouteVolume)

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
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            refreshForegroundPackage(event)
        }
    }

    private fun refreshForegroundPackage(event: AccessibilityEvent) {
        if (!::foregroundAppResolver.isInitialized) return
        val eventWindow = ForegroundWindowCandidate(
            packageName = event.packageName?.toString(),
            className = event.className?.toString()
        )
        val resolvedPackage = foregroundAppResolver.resolve(eventWindow) ?: return
        if (foregroundPackage == resolvedPackage) return

        foregroundPackage = resolvedPackage
        val runtime = (application as AmplyApplication).runtime
        runtime.onForegroundPackageChanged(resolvedPackage)
        runtime.shizukuRepository.checkPermissionStateThrottled()
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
                    Log.d(TAG, "Passing volume key through")
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
                        VolumeKeyStreamAction.PassThrough -> {
                            handleVolumeKeyUp()
                            false
                        }
                        is VolumeKeyStreamAction.AdjustRemoteMedia -> {
                            if (event.repeatCount == 0) {
                                val applied = handleRemoteMediaKeyDown(isUp, action.routeGeneration)
                                if (!applied) {
                                    streamActionRouter.replace(event.keyCode, VolumeKeyStreamAction.PassThrough)
                                }
                                applied
                            } else {
                                true
                            }
                        }
                        is VolumeKeyStreamAction.Adjust -> {
                            if (event.repeatCount == 0) {
                                when (val result = handleVolumeKeyDown(event.keyCode, isUp, action.target)) {
                                    is VolumeAdjustmentResult.Applied -> {
                                        streamActionRouter.replace(
                                            event.keyCode,
                                            VolumeKeyStreamAction.Adjust(result.target)
                                        )
                                        true
                                    }
                                    VolumeAdjustmentResult.Unavailable,
                                    is VolumeAdjustmentResult.Failed -> {
                                        streamActionRouter.replace(
                                            event.keyCode,
                                            VolumeKeyStreamAction.PassThrough
                                        )
                                        false
                                    }
                                }
                            } else {
                                true
                            }
                        }
                    }
                }
            }
            KeyEvent.ACTION_UP -> {
                val route = sequenceRouter.onUp(event.keyCode) ?: currentRoute()
                val streamAction = streamActionRouter.onUp(event.keyCode)
                handleVolumeKeyUp()
                route == VolumeKeyRoute.INTERCEPT &&
                    streamAction != VolumeKeyStreamAction.SilenceIncomingRinger &&
                    streamAction != VolumeKeyStreamAction.PassThrough
            }
            else -> false
        }
    }

    private val mediaRouteCallback = object : MediaRouter.SimpleCallback() {
        override fun onRouteSelected(
            router: MediaRouter,
            type: Int,
            info: MediaRouter.RouteInfo
        ) {
            updateIconType()
        }

        override fun onRouteUnselected(
            router: MediaRouter,
            type: Int,
            info: MediaRouter.RouteInfo
        ) {
            updateIconType()
        }

        override fun onRouteChanged(router: MediaRouter, info: MediaRouter.RouteInfo) {
            updateIconType()
        }

        override fun onRouteVolumeChanged(router: MediaRouter, info: MediaRouter.RouteInfo) {
            updateIconType()
        }
    }

    private fun currentRoute(): VolumeKeyRoute = VolumeKeyRoutingPolicy.route(
        VolumeKeyRoutingState(
            preferencesLoaded = routingPreferencesLoaded,
            foregroundPackage = foregroundPackage,
            passThroughPackages = passThroughPackages,
            pausedUntilEpochMs = pausedUntilEpochMs,
            cameraBypassActive = shouldBypassForCamera(),
            keyguardLocked = keyguardManager?.isKeyguardLocked == true,
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
    private fun handleVolumeKeyDown(
        keyCode: Int,
        isUp: Boolean,
        target: VolumeTarget
    ): VolumeAdjustmentResult {
        // Cancel any existing repeat job
        repeatJob?.cancel()

        // Change volume once immediately
        val initial = changeVolumeWithFallback(isUp, target)
        if (initial !is VolumeAdjustmentResult.Applied) return initial
        var repeatTarget = initial.target

        // Start repeat after initial debounce
        repeatJob = serviceScope.launch {
            delay(400.milliseconds) // Initial debounce: 400ms

            // Repeat every 150ms while held
            while (isActive) {
                when (val result = changeVolumeWithFallback(isUp, repeatTarget)) {
                    is VolumeAdjustmentResult.Applied -> repeatTarget = result.target
                    VolumeAdjustmentResult.Unavailable,
                    is VolumeAdjustmentResult.Failed -> {
                        streamActionRouter.replace(keyCode, VolumeKeyStreamAction.PassThrough)
                        cancel()
                    }
                }
                delay(150.milliseconds)
            }
        }
        return initial
    }

    /**
     * Handle volume key up - stop repeating
     */
    private fun handleVolumeKeyUp() {
        repeatJob?.cancel()
        repeatJob = null
    }

    /**
     * Change volume by one-step
     * Uses real max volume and proper mapping
     */
    private fun resolveStreamAction(): VolumeKeyStreamAction {
        val manager = audioManager ?: return VolumeKeyStreamAction.Adjust(VolumeTarget.MEDIA)
        val runtime = (application as AmplyApplication).runtime
        runtime.onAudioModeObserved(manager.mode)
        val phoneStateGranted = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!phoneStateGranted &&
            (manager.mode == AudioManager.MODE_RINGTONE ||
                com.agentkosticka.amply.audio.routing.VolumeTargetPolicy.isActiveCallMode(manager.mode))
        ) {
            return VolumeKeyStreamAction.PassThrough
        }
        val automatic = runtime.volumeTargetSessionController.resolveForInitialKeyDown(
            audioMode = manager.mode,
            callPhase = currentCallPhase()
        )
        return MediaVolumeActionPolicy.resolve(automatic, currentMediaRouteVolumeState)
    }

    @Suppress("DEPRECATION")
    private fun currentCallPhase(): CallPhase {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return CallPhase.UNKNOWN
        }
        return when (runCatching {
            getSystemService(TelephonyManager::class.java).callState
        }.getOrNull()) {
            TelephonyManager.CALL_STATE_RINGING -> CallPhase.INCOMING_RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> CallPhase.OUTGOING_OR_ACTIVE
            TelephonyManager.CALL_STATE_IDLE -> CallPhase.NONE
            else -> CallPhase.UNKNOWN
        }
    }

    private fun handleRemoteMediaKeyDown(isUp: Boolean, generation: Long): Boolean {
        repeatJob?.cancel()
        if (!adjustRemoteMedia(isUp, generation)) return false
        showOverlay(VolumeTarget.MEDIA)
        repeatJob = serviceScope.launch {
            delay(400L.milliseconds)
            while (isActive && adjustRemoteMedia(isUp, generation)) {
                delay(150L.milliseconds)
            }
        }
        return true
    }

    private fun adjustRemoteMedia(isUp: Boolean, generation: Long): Boolean {
        val state = currentMediaRouteVolumeState
        val route = currentMediaRouteInfo
        if (state.generation != generation || !state.isControllableRemote || route == null) return false
        return runCatching {
            route.requestUpdateVolume(if (isUp) 1 else -1)
            val predicted = (state.currentVolume + if (isUp) 1 else -1)
                .coerceIn(0, state.maxVolume)
            currentMediaRouteVolumeState = state.copy(currentVolume = predicted)
            OverlayManager.updateMediaRouteVolumeState(currentMediaRouteVolumeState)
            true
        }.getOrDefault(false)
    }

    private fun setRemoteRouteVolume(generation: Long, volume: Int): Boolean {
        val state = currentMediaRouteVolumeState
        val route = currentMediaRouteInfo
        if (state.generation != generation || !state.isControllableRemote || route == null) return false
        return runCatching {
            val clamped = volume.coerceIn(0, state.maxVolume)
            route.requestSetVolume(clamped)
            currentMediaRouteVolumeState = state.copy(currentVolume = clamped)
            OverlayManager.updateMediaRouteVolumeState(currentMediaRouteVolumeState)
            true
        }.getOrDefault(false)
    }

    private fun changeVolumeWithFallback(
        isUp: Boolean,
        initialTarget: VolumeTarget
    ): VolumeAdjustmentResult {
        val runtime = (application as AmplyApplication).runtime
        var target = initialTarget
        var failedTarget: VolumeTarget? = null
        repeat(VolumeTarget.entries.size) {
            val result = changeVolume(isUp, target)
            when (result) {
                is VolumeAdjustmentResult.Applied -> return result
                is VolumeAdjustmentResult.Failed -> failedTarget = result.target
                VolumeAdjustmentResult.Unavailable -> Unit
            }
            // Once an overlay appearance is on screen, its selected stream is locked.
            // A failed adjustment must pass through rather than silently retargeting it.
            if (OverlayManager.isShowing()) return result
            runtime.disableSystemStream(target)
            target = when (val fallback = resolveStreamAction()) {
                is VolumeKeyStreamAction.Adjust -> fallback.target
                is VolumeKeyStreamAction.AdjustRemoteMedia,
                VolumeKeyStreamAction.SilenceIncomingRinger,
                VolumeKeyStreamAction.PassThrough -> return if (failedTarget != null) {
                    VolumeAdjustmentResult.Failed(failedTarget)
                } else {
                    VolumeAdjustmentResult.Unavailable
                }
            }
        }
        return if (failedTarget != null) {
            VolumeAdjustmentResult.Failed(failedTarget)
        } else {
            VolumeAdjustmentResult.Unavailable
        }
    }

    private fun changeVolume(isUp: Boolean, target: VolumeTarget): VolumeAdjustmentResult {
        val manager = audioManager ?: return VolumeAdjustmentResult.Unavailable
        val streamType = target.streamType
        val currentVolume = runCatching { manager.getStreamVolume(streamType) }
            .getOrElse { return VolumeAdjustmentResult.Failed(target) }
        val minVolume = runCatching { manager.getStreamMinVolume(streamType) }
            .getOrElse { return VolumeAdjustmentResult.Failed(target) }
        val maxVolume = runCatching { manager.getStreamMaxVolume(streamType) }
            .getOrElse { return VolumeAdjustmentResult.Failed(target) }

        val runtime = (application as AmplyApplication).runtime
        when (runtime.adjustRingerKeyStep(target, isUp, currentVolume, minVolume)) {
            RingerKeyAdjustmentResult.APPLIED -> {
                showOverlay(target)
                OverlayManager.refreshStreamVolumes()
                return VolumeAdjustmentResult.Applied(target)
            }
            RingerKeyAdjustmentResult.LIMIT -> {
                showOverlay(target)
                OverlayManager.signalVolumeLimit(target = target, isUp = isUp)
                return VolumeAdjustmentResult.Applied(target)
            }
            RingerKeyAdjustmentResult.FAILED -> return VolumeAdjustmentResult.Failed(target)
            RingerKeyAdjustmentResult.NOT_HANDLED -> Unit
        }

        val newVolume = VolumeStepPolicy.next(
            current = currentVolume,
            min = minVolume,
            max = maxVolume,
            isUp = isUp,
            step = volumeStep
        )

        if (newVolume == currentVolume) {
            showOverlay(target)
            OverlayManager.signalVolumeLimit(
                target = target,
                isUp = isUp
            )
            return VolumeAdjustmentResult.Applied(target)
        }

        val applied = runtime.setSystemStreamVolume(target, newVolume)
        if (!applied) return VolumeAdjustmentResult.Failed(target)
        val verified = runCatching { manager.getStreamVolume(streamType) == newVolume }
            .getOrDefault(false)
        if (!verified) return VolumeAdjustmentResult.Failed(target)
        showOverlay(target)
        return VolumeAdjustmentResult.Applied(target)
    }

    /** Show or update the overlay with the current routing target. */
    private fun showOverlay(target: VolumeTarget) {
        OverlayService.showFromAccessibilityHost(
            host = this,
            target = target,
            iconType = currentIconType,
            foregroundPackage = foregroundPackage
        )
    }

    /** Detect the destination Android would currently use for media playback. */
    private fun updateIconType() {
        val manager = audioManager ?: return
        val selectedRoute = runCatching {
            mediaRouter?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
        }.getOrNull()

        val routedDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                manager.getAudioDevicesForAttributes(mediaAttributes)
                    .mapTo(mutableSetOf()) { it.toMediaRouteDeviceKind() }
            }.getOrDefault(emptySet())
        } else {
            emptySet()
        }

        val selectedDevice = when (selectedRoute?.deviceType) {
            MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH -> MediaRouteDeviceKind.BLUETOOTH
            MediaRouter.RouteInfo.DEVICE_TYPE_TV -> MediaRouteDeviceKind.REMOTE
            else -> MediaRouteDeviceKind.LOCAL
        }
        val route = MediaOutputRoutePolicy.resolve(
            MediaRouteSnapshot(
                routedDevices = routedDevices,
                selectedRouteDevice = selectedDevice,
                selectedRouteIsRemote = selectedRoute?.playbackType ==
                    MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE
            )
        )
        val routeSignature = selectedRoute?.toString()
        if (routeSignature != currentMediaRouteSignature || route != currentMediaRouteVolumeState.outputRoute) {
            mediaRouteGeneration += 1L
        }
        currentMediaRouteInfo = selectedRoute
        currentMediaRouteSignature = routeSignature
        val isVariable = route == MediaOutputRoute.CAST &&
            selectedRoute?.playbackType == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE &&
            selectedRoute.volumeHandling == MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE
        currentMediaRouteVolumeState = MediaRouteVolumeState(
            outputRoute = route,
            generation = mediaRouteGeneration,
            currentVolume = selectedRoute?.volume ?: 0,
            maxVolume = selectedRoute?.volumeMax ?: 0,
            variableVolume = isVariable
        )
        OverlayManager.updateMediaRouteVolumeState(currentMediaRouteVolumeState)
        if (currentIconType != route.wireName) {
            currentIconType = route.wireName
            OverlayManager.updateMediaIconType(route.wireName)
            Log.d(TAG, "Media output route=${route.name}")
        }
    }

    private fun AudioDeviceInfo.toMediaRouteDeviceKind(): MediaRouteDeviceKind = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> MediaRouteDeviceKind.BLUETOOTH

        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> MediaRouteDeviceKind.REMOTE
        else -> MediaRouteDeviceKind.LOCAL
    }

    override fun onDestroy() {
        super.onDestroy()
        repeatJob?.cancel()
        routingPreferencesJob?.cancel()
        sequenceRouter.clear()
        streamActionRouter.clear()
        (application as AmplyApplication).runtime.setAccessibilityConnected(false)
        serviceScope.cancel()

        // Unregister audio device callback
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        mediaRouter?.removeCallback(mediaRouteCallback)
        OverlayManager.clearRemoteMediaVolumeCallback()
        cameraManager?.unregisterAvailabilityCallback(cameraAvailabilityCallback)
        stopService(Intent(this, OverlayService::class.java))
        Log.d(TAG, "VolumeKeyService destroyed")
    }
}
