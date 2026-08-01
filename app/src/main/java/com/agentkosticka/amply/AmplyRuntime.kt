package com.agentkosticka.amply

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.agentkosticka.amply.audio.AudioSessionManager
import com.agentkosticka.amply.audio.ForegroundVisitTracker
import com.agentkosticka.amply.audio.RingerExperimentExecutor
import com.agentkosticka.amply.audio.SystemStreamSessionController
import com.agentkosticka.amply.audio.VolumeTarget
import com.agentkosticka.amply.audio.VolumeTargetSessionController
import com.agentkosticka.amply.audio.VolumeTargetPolicy
import com.agentkosticka.amply.data.PreferencesManager
import com.agentkosticka.amply.shizuku.ShizukuRepository
import com.agentkosticka.amply.shizuku.ShizukuVolumeManager
import com.agentkosticka.amply.shizuku.VolumeServiceConnectionCoordinator
import com.agentkosticka.amply.shizuku.VolumeServiceConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AmplyRuntime(context: Context) {
    companion object {
        private const val TAG = "AmplyRuntime"
    }

    private val appContext = context.applicationContext
    private val runtimeScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var notificationExpiryJob: Job? = null
    private var lastObservedAudioMode: Int? = null

    val preferencesManager = PreferencesManager(appContext)
    val shizukuRepository = ShizukuRepository(appContext)
    val shizukuVolumeManager = ShizukuVolumeManager(appContext.packageName)
    val ringerExperimentExecutor = RingerExperimentExecutor(
        appContext, shizukuVolumeManager, shizukuRepository
    )
    val audioSessionManager = AudioSessionManager(
        context = appContext,
        preferencesManager = preferencesManager,
        shizukuVolumeManager = shizukuVolumeManager
    )
    val foregroundVisitTracker = ForegroundVisitTracker()
    val foregroundVisitState = foregroundVisitTracker.state
    val volumeTargetSessionController = VolumeTargetSessionController()
    val selectedVolumeTarget = volumeTargetSessionController.selectedTarget
    val systemStreamSessionController = SystemStreamSessionController()
    val dynamicStreamState = systemStreamSessionController.state

    private val connectionCoordinator = VolumeServiceConnectionCoordinator(
        scope = runtimeScope,
        permissionState = shizukuRepository.permissionState,
        connector = shizukuVolumeManager,
        permissionRefresher = { shizukuRepository.checkPermissionState() }
    )

    val sessionState = audioSessionManager.sessionState
    val permissionState = shizukuRepository.permissionState
    val connectionState: StateFlow<VolumeServiceConnectionState> = shizukuVolumeManager.connectionState

    init {
        Log.i(TAG, "Creating process-owned Amply runtime")
        runtimeScope.launch {
            sessionState.collect { state ->
                foregroundVisitTracker.onSessionsChanged(state.sessions)
            }
        }
        runtimeScope.launch {
            audioSessionManager.activePlaybackUsages.collect { usages ->
                ringerExperimentExecutor.onPlaybackUsagesChanged(usages)
                volumeTargetSessionController.onPlaybackUsagesChanged(usages)
                systemStreamSessionController.onCallUsageChanged(
                    AudioAttributes.USAGE_VOICE_COMMUNICATION in usages
                )
                notificationExpiryJob?.cancel()
                notificationExpiryJob = runtimeScope.launch {
                    delay(VolumeTargetPolicy.NOTIFICATION_GRACE_MS + 1L)
                    volumeTargetSessionController.onTimeAdvanced()
                }
            }
        }
        runtimeScope.launch {
            audioSessionManager.activeSystemStreams.collect { streams ->
                systemStreamSessionController.onStreamsChanged(streams)
                volumeTargetSessionController.onStreamsChanged(
                    streams,
                    systemStreamSessionController.state.value.disabledTargets
                )
            }
        }
        onAudioModeObserved(audioManager.mode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.addOnModeChangedListener(appContext.mainExecutor) { mode ->
                onAudioModeObserved(mode)
            }
        }
        connectionCoordinator.start()
        audioSessionManager.startPolling()
    }

    fun onForegroundPackageChanged(packageName: String?) {
        foregroundVisitTracker.onForegroundChanged(packageName)
    }

    fun retryVolumeServiceConnection() {
        connectionCoordinator.retryNow()
    }

    fun onAudioModeObserved(mode: Int) {
        if (lastObservedAudioMode != mode) {
            lastObservedAudioMode = mode
            shizukuVolumeManager.invalidateStreamTopologyCache()
            audioSessionManager.requestRefresh()
        }
        volumeTargetSessionController.onAudioModeChanged(mode)
        systemStreamSessionController.onCallModeChanged(
            VolumeTargetPolicy.isActiveCallMode(mode)
        )
    }

    fun onOverlayShown() {
        volumeTargetSessionController.onOverlayShown()
        systemStreamSessionController.onOverlayShown()
    }

    fun onOverlayHidden() {
        volumeTargetSessionController.onOverlayHidden()
        systemStreamSessionController.onOverlayHidden()
    }

    fun disableSystemStream(target: VolumeTarget) {
        systemStreamSessionController.disable(target)
        volumeTargetSessionController.onTargetUnavailable(target)
        volumeTargetSessionController.onStreamsChanged(
            audioSessionManager.activeSystemStreams.value,
            systemStreamSessionController.state.value.disabledTargets
        )
    }

    fun setSystemStreamVolume(target: VolumeTarget, index: Int): Boolean {
        val canonical = dynamicStreamState.value.topology.canonicalTarget(target)
        if (!canonical.userAdjustable) {
            disableSystemStream(canonical)
            return false
        }
        val min = runCatching { audioManager.getStreamMinVolume(canonical.streamType) }.getOrDefault(0)
        val max = runCatching { audioManager.getStreamMaxVolume(canonical.streamType) }.getOrDefault(min)
        val clamped = index.coerceIn(min, max)
        val success = when {
            canonical == VolumeTarget.NOTIFICATION -> runCatching {
                ringerExperimentExecutor.setNotificationVolumeFromControl(clamped)
            }.isSuccess
            canonical.permanentlyVisible || canonical == VolumeTarget.RING -> runCatching {
                audioManager.setStreamVolume(canonical.streamType, clamped, 0)
            }.isSuccess
            else -> shizukuVolumeManager.setSystemStreamVolume(canonical.streamType, clamped)
        }
        if (!success) disableSystemStream(canonical)
        return success
    }
}
