package com.agentkosticka.amply

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.agentkosticka.amply.audio.session.AudioSessionManager
import com.agentkosticka.amply.audio.session.ForegroundVisitTracker
import com.agentkosticka.amply.audio.ringer.RingerExperimentExecutor
import com.agentkosticka.amply.audio.ringer.NotificationAlertMode
import com.agentkosticka.amply.audio.ringer.RingerKeyAdjustmentResult
import com.agentkosticka.amply.audio.ringer.RingerKeyStepAction
import com.agentkosticka.amply.audio.ringer.RingerKeyStepPolicy
import com.agentkosticka.amply.audio.routing.SystemStreamSessionController
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.audio.routing.VolumeTargetSessionController
import com.agentkosticka.amply.audio.routing.VolumeTargetPolicy
import com.agentkosticka.amply.overlay.window.OverlayManager
import com.agentkosticka.amply.settings.data.PreferencesManager
import com.agentkosticka.amply.runtime.RuntimeError
import com.agentkosticka.amply.runtime.RuntimeErrorCode
import com.agentkosticka.amply.runtime.RuntimeHealth
import com.agentkosticka.amply.runtime.RuntimeOperationState
import com.agentkosticka.amply.shizuku.client.ShizukuPermissionState
import com.agentkosticka.amply.shizuku.client.ShizukuRepository
import com.agentkosticka.amply.shizuku.client.ShizukuVolumeManager
import com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionCoordinator
import com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState
import com.agentkosticka.amply.tutorial.TutorialCoordinator
import com.agentkosticka.amply.update.AppUpdateChecker
import com.agentkosticka.amply.dnd.AmplyDndController
import com.agentkosticka.amply.dnd.DndOperationResult
import com.agentkosticka.amply.profiles.OutputRouteMonitor
import com.agentkosticka.amply.profiles.ProfileCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class AmplyRuntime(context: Context) {
    companion object {
        private const val TAG = "AmplyRuntime"
    }

    private val appContext = context.applicationContext
    private val runtimeScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var notificationExpiryJob: Job? = null
    private var pauseHealthExpiryJob: Job? = null
    private var screenshotMonitorJob: Job? = null
    private var lastObservedAudioMode: Int? = null
    private val _runtimeHealth = MutableStateFlow(RuntimeHealth())
    val runtimeHealth: StateFlow<RuntimeHealth> = _runtimeHealth.asStateFlow()

    val preferencesManager = PreferencesManager(appContext)
    val dndController = AmplyDndController(appContext)
    internal val tutorialCoordinator = TutorialCoordinator(preferencesManager, runtimeScope)
    internal val updateChecker = AppUpdateChecker(appContext, preferencesManager)
    val shizukuRepository = ShizukuRepository(appContext)
    val shizukuVolumeManager = ShizukuVolumeManager(appContext)
    val ringerExperimentExecutor = RingerExperimentExecutor(
        appContext,
        shizukuVolumeManager,
        shizukuRepository
    )
    val outputRouteMonitor = OutputRouteMonitor(appContext)
    val profileCoordinator = ProfileCoordinator(
        context = appContext,
        preferences = preferencesManager,
        outputMonitor = outputRouteMonitor,
        dndController = dndController,
        ringerExecutor = ringerExperimentExecutor,
        shizukuVolumeManager = shizukuVolumeManager,
        scope = runtimeScope
    )
    val audioSessionManager = AudioSessionManager(
        context = appContext,
        preferencesManager = preferencesManager,
        shizukuVolumeManager = shizukuVolumeManager,
        profileCoordinator = profileCoordinator
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
    val connectionState: StateFlow<VolumeServiceConnectionState> = shizukuVolumeManager.connectionState

    init {
        Log.i(TAG, "Creating process-owned Amply runtime")
        profileCoordinator.start()
        runtimeScope.launch(Dispatchers.IO) {
            runCatching { preferencesManager.pruneStaleApps(automatic = true) }
                .onFailure { Log.w(TAG, "Automatic stale-app cleanup failed", it) }
        }
        runtimeScope.launch {
            preferencesManager.showDndButton.collect(dndController::setFeatureEnabled)
        }
        runtimeScope.launch {
            sessionState.collect { state ->
                foregroundVisitTracker.onSessionsChanged(state.sessions)
            }
        }
        runtimeScope.launch {
            shizukuRepository.permissionState.collect { permission ->
                screenshotMonitorJob?.cancel()
                screenshotMonitorJob = null
                if (permission == ShizukuPermissionState.GRANTED) {
                    screenshotMonitorJob = runtimeScope.launch(Dispatchers.IO) {
                        shizukuRepository.monitorHardwareKeys { likelyScreenshotChord ->
                            runtimeScope.launch {
                                if (OverlayManager.isShowing()) {
                                    Log.d(
                                        TAG,
                                        if (likelyScreenshotChord) {
                                            "Hiding overlay for likely screenshot chord"
                                        } else {
                                            "Hiding overlay preemptively on Power key"
                                        }
                                    )
                                    OverlayManager.hide()
                                }
                            }
                        }
                    }
                }
            }
        }
        runtimeScope.launch {
            combine(
                shizukuRepository.permissionState,
                connectionState,
                preferencesManager.amplyPausedUntilEpochMs
            ) { permission, connection, pausedUntil ->
                Triple(permission, connection, pausedUntil)
            }.collect { (permission, connection, pausedUntil) ->
                pauseHealthExpiryJob?.cancel()
                val now = System.currentTimeMillis()
                val effectivePausedUntil = if (pausedUntil > now) pausedUntil else 0L
                _runtimeHealth.update {
                    it.copy(
                        shizukuPermission = permission,
                        volumeServiceConnection = connection,
                        pausedUntilEpochMs = effectivePausedUntil,
                        recoverableError = when (connection) {
                            VolumeServiceConnectionState.PROTOCOL_MISMATCH ->
                                RuntimeError(RuntimeErrorCode.SHIZUKU_PROTOCOL_MISMATCH)

                            VolumeServiceConnectionState.CONNECTED if it.recoverableError?.code in setOf(
                                RuntimeErrorCode.SHIZUKU_CONNECTION_FAILED,
                                RuntimeErrorCode.SHIZUKU_PROTOCOL_MISMATCH
                            ) -> null

                            else -> it.recoverableError
                        }
                    )
                }
                if (effectivePausedUntil in (now + 1)..<Long.MAX_VALUE) {
                    pauseHealthExpiryJob = runtimeScope.launch {
                        delay((effectivePausedUntil - System.currentTimeMillis()).coerceAtLeast(1L).milliseconds)
                        _runtimeHealth.update { health ->
                            if (health.pausedUntilEpochMs == effectivePausedUntil) {
                                health.copy(pausedUntilEpochMs = 0L)
                            } else health
                        }
                    }
                }
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
                    delay((VolumeTargetPolicy.NOTIFICATION_GRACE_MS + 1L).milliseconds)
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
        clearRuntimeError(RuntimeErrorCode.SHIZUKU_CONNECTION_FAILED)
        connectionCoordinator.retryNow()
    }

    fun setAccessibilityConnected(connected: Boolean) {
        _runtimeHealth.update { it.copy(accessibilityConnected = connected) }
    }

    fun setForegroundServiceRunning(running: Boolean) {
        _runtimeHealth.update { it.copy(foregroundServiceRunning = running) }
    }

    fun reportRuntimeError(code: RuntimeErrorCode) {
        _runtimeHealth.update {
            it.copy(
                lastOperation = RuntimeOperationState.FAILED,
                recoverableError = RuntimeError(code)
            )
        }
    }

    fun clearRuntimeError(code: RuntimeErrorCode? = null) {
        _runtimeHealth.update {
            if (code == null || it.recoverableError?.code == code) {
                it.copy(recoverableError = null)
            } else {
                it
            }
        }
    }

    fun reportVolumeOperation(applied: Boolean) {
        _runtimeHealth.update {
            it.copy(
                lastOperation = if (applied) {
                    RuntimeOperationState.APPLIED
                } else {
                    RuntimeOperationState.FAILED
                },
                recoverableError = if (applied &&
                    it.recoverableError?.code == RuntimeErrorCode.VOLUME_CHANGE_FAILED
                ) null else it.recoverableError
            )
        }
    }

    fun onAudioModeObserved(mode: Int) {
        val wasCallActive = lastObservedAudioMode?.let(VolumeTargetPolicy::isActiveCallMode) ?: false
        if (lastObservedAudioMode != mode) {
            lastObservedAudioMode = mode
            shizukuVolumeManager.invalidateStreamTopologyCache()
            audioSessionManager.requestRefresh()
        }
        volumeTargetSessionController.onAudioModeChanged(mode)
        systemStreamSessionController.onCallModeChanged(
            VolumeTargetPolicy.isActiveCallMode(mode)
        )
        if (!wasCallActive && VolumeTargetPolicy.isActiveCallMode(mode)) {
            profileCoordinator.onCallBecameActive()
        }
    }
}
