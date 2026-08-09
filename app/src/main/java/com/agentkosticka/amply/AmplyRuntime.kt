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
import com.agentkosticka.amply.settings.data.PreferencesManager
import com.agentkosticka.amply.runtime.RuntimeError
import com.agentkosticka.amply.runtime.RuntimeErrorCode
import com.agentkosticka.amply.runtime.RuntimeHealth
import com.agentkosticka.amply.runtime.RuntimeOperationState
import com.agentkosticka.amply.shizuku.client.ShizukuRepository
import com.agentkosticka.amply.shizuku.client.ShizukuVolumeManager
import com.agentkosticka.amply.shizuku.client.FractionalVolumeOperationResult
import com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionCoordinator
import com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState
import com.agentkosticka.amply.tutorial.TutorialCoordinator
import com.agentkosticka.amply.update.AppUpdateChecker
import com.agentkosticka.amply.dnd.AmplyDndController
import com.agentkosticka.amply.dnd.DndOperationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
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
    private var lastObservedAudioMode: Int? = null
    private data class FractionalVolumeWrite(val target: VolumeTarget, val value: Float)
    private data class AcknowledgedFractionalVolume(val value: Float, val nativeIndex: Int)
    private val fractionalVolumeWrites = Channel<FractionalVolumeWrite>(Channel.CONFLATED)
    private val acknowledgedFractionalVolumes =
        ConcurrentHashMap<Int, AcknowledgedFractionalVolume>()
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
    val connectionState: StateFlow<VolumeServiceConnectionState> = shizukuVolumeManager.connectionState
    /** Native OEM float-stream support, used for non-media streams when available. */
    val fractionalStreamVolumeSupported: StateFlow<Boolean> =
        shizukuVolumeManager.fractionalStreamVolumeSupported
    /** Media also supports the safe composed backend on ordinary integer-only firmware. */
    val mediaFractionalStreamVolumeSupported: StateFlow<Boolean> = combine(
        fractionalStreamVolumeSupported,
        connectionState
    ) { nativeSupported, connection ->
        nativeSupported || connection == VolumeServiceConnectionState.CONNECTED
    }.stateIn(runtimeScope, SharingStarted.Eagerly, false)

    init {
        Log.i(TAG, "Creating process-owned Amply runtime")
        runtimeScope.launch(Dispatchers.IO) {
            runCatching { preferencesManager.pruneStaleApps(automatic = true) }
                .onFailure { Log.w(TAG, "Automatic stale-app cleanup failed", it) }
        }
        runtimeScope.launch {
            preferencesManager.showDndButton.collect(dndController::setFeatureEnabled)
        }
        runtimeScope.launch(Dispatchers.IO) {
            for (write in fractionalVolumeWrites) {
                val result = shizukuVolumeManager.setSystemStreamVolumeFloat(
                    write.target.streamType,
                    write.value
                )
                when (result) {
                    FractionalVolumeOperationResult.Applied -> {
                        if (write.target == VolumeTarget.MEDIA) {
                            audioSessionManager.resetMediaStreamGainFactor()
                        }
                        val state = shizukuVolumeManager.getSystemStreamVolumeFloatState(
                            write.target.streamType
                        )
                        if (state != null) {
                            acknowledgedFractionalVolumes[write.target.streamType] =
                                AcknowledgedFractionalVolume(state.value, state.nativeIndex)
                            reportVolumeOperation(true)
                        } else {
                            acknowledgedFractionalVolumes.remove(write.target.streamType)
                            reportVolumeOperation(false)
                        }
                    }
                    FractionalVolumeOperationResult.Unsupported -> {
                        if (write.target == VolumeTarget.MEDIA) {
                            applyComposedMediaVolume(write)
                        } else {
                            acknowledgedFractionalVolumes.remove(write.target.streamType)
                        }
                    }
                    FractionalVolumeOperationResult.Failed -> {
                        acknowledgedFractionalVolumes.remove(write.target.streamType)
                        reportVolumeOperation(false)
                    }
                }
                delay(16L)
            }
        }
        runtimeScope.launch {
            sessionState.collect { state ->
                foregroundVisitTracker.onSessionsChanged(state.sessions)
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
                if (connection != VolumeServiceConnectionState.CONNECTED) {
                    acknowledgedFractionalVolumes.clear()
                    audioSessionManager.resetMediaStreamGainFactor()
                }
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
        if (lastObservedAudioMode != mode) {
            lastObservedAudioMode = mode
            shizukuVolumeManager.invalidateStreamTopologyCache()
            invalidateFractionalVolumes()
            audioSessionManager.requestRefresh()
        }
        volumeTargetSessionController.onAudioModeChanged(mode)
        systemStreamSessionController.onCallModeChanged(
            VolumeTargetPolicy.isActiveCallMode(mode)
        )
    }

    fun onOverlayShown(): Boolean {
        volumeTargetSessionController.onOverlayShown()
        systemStreamSessionController.onOverlayShown()
        return tutorialCoordinator.onOverlayAttached()
    }

    fun onTutorialOverlayPreviewFinished() {
        tutorialCoordinator.onOverlayPreviewFinished()
    }

    fun onOverlayHidden() {
        volumeTargetSessionController.onOverlayHidden()
        systemStreamSessionController.onOverlayHidden()
    }

    fun disableSystemStream(target: VolumeTarget) {
        val canonical = dynamicStreamState.value.topology.canonicalTarget(target)
        if (canonical.permanentlyVisible || canonical == VolumeTarget.RING || canonical == VolumeTarget.NOTIFICATION) {
            return
        }
        systemStreamSessionController.disable(canonical)
        volumeTargetSessionController.onTargetUnavailable(canonical)
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
        acknowledgedFractionalVolumes.remove(canonical.streamType)
        shizukuVolumeManager.invalidateSystemStreamVolumeFloat(canonical.streamType)
        if (canonical == VolumeTarget.MEDIA) {
            audioSessionManager.resetMediaStreamGainFactor()
        }
        if (canonical == VolumeTarget.NOTIFICATION || canonical == VolumeTarget.RING) {
            if (dndController.active.value) {
                dndController.setActive(false)
            }
        }
        val success = when {
            canonical == VolumeTarget.NOTIFICATION || canonical == VolumeTarget.RING -> runCatching {
                ringerExperimentExecutor.setAlertVolumeFromControl(canonical.streamType, clamped)
            }.getOrDefault(false)
            canonical.permanentlyVisible -> runCatching {
                audioManager.setStreamVolume(canonical.streamType, clamped, 0)
                audioManager.getStreamVolume(canonical.streamType) == clamped
            }.getOrDefault(false)
            else -> shizukuVolumeManager.setSystemStreamVolume(canonical.streamType, clamped)
        }
        reportVolumeOperation(success)
        if (!success) {
            reportRuntimeError(RuntimeErrorCode.VOLUME_CHANGE_FAILED)
            if (!canonical.permanentlyVisible && canonical != VolumeTarget.RING && canonical != VolumeTarget.NOTIFICATION) {
                disableSystemStream(canonical)
            }
        }
        return success
    }

    fun setSystemStreamVolumeFloat(target: VolumeTarget, gain: Float): Boolean {
        val canonical = dynamicStreamState.value.topology.canonicalTarget(target)
        val fractionalSupported = fractionalStreamVolumeSupported.value ||
            (canonical == VolumeTarget.MEDIA &&
                connectionState.value == VolumeServiceConnectionState.CONNECTED)
        if (!canonical.userAdjustable || !fractionalSupported) return false
        val min = runCatching { audioManager.getStreamMinVolume(canonical.streamType) }.getOrDefault(0).toFloat()
        val max = runCatching { audioManager.getStreamMaxVolume(canonical.streamType) }.getOrDefault(min.toInt()).toFloat()
        if (!gain.isFinite() || max < min) return false
        if ((canonical == VolumeTarget.NOTIFICATION || canonical == VolumeTarget.RING) &&
            (dndController.active.value || audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL)
        ) {
            return false
        }
        return fractionalVolumeWrites.trySend(
            FractionalVolumeWrite(canonical, gain.coerceIn(min, max))
        ).isSuccess
    }

    fun getSystemStreamVolumeFloat(streamType: Int): Float? {
        val acknowledged = acknowledgedFractionalVolumes[streamType] ?: return null
        val currentIndex = runCatching { audioManager.getStreamVolume(streamType) }.getOrNull()
        if (currentIndex != acknowledged.nativeIndex) {
            acknowledgedFractionalVolumes.remove(streamType)
            shizukuVolumeManager.invalidateSystemStreamVolumeFloat(streamType)
            if (streamType == AudioManager.STREAM_MUSIC) {
                audioSessionManager.resetMediaStreamGainFactor()
            }
            return null
        }
        return acknowledged.value
    }

    fun invalidateFractionalVolumes() {
        val streams = acknowledgedFractionalVolumes.keys.toList()
        acknowledgedFractionalVolumes.clear()
        streams.forEach(shizukuVolumeManager::invalidateSystemStreamVolumeFloat)
        audioSessionManager.resetMediaStreamGainFactor()
    }

    /**
     * Integer-only Android builds can still represent a fractional media index without
     * corrupting per-app settings: select the upper native index, then multiply every media
     * player's own Amply gain by the remaining fraction.
     */
    private fun applyComposedMediaVolume(write: FractionalVolumeWrite) {
        val min = runCatching {
            audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        }.getOrDefault(0)
        val max = runCatching {
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        }.getOrDefault(min)
        val nativeIndex = ceil(write.value.toDouble()).toInt().coerceIn(min, max)
        val streamFactor = if (nativeIndex > 0) {
            (write.value / nativeIndex.toFloat()).coerceIn(0f, 1f)
        } else {
            1f
        }
        val streamApplied = shizukuVolumeManager.setSystemStreamVolume(
            AudioManager.STREAM_MUSIC,
            nativeIndex
        )
        val playersApplied = streamApplied &&
            audioSessionManager.setMediaStreamGainFactor(streamFactor)
        if (playersApplied) {
            acknowledgedFractionalVolumes[AudioManager.STREAM_MUSIC] =
                AcknowledgedFractionalVolume(write.value, nativeIndex)
            reportVolumeOperation(true)
        } else {
            acknowledgedFractionalVolumes.remove(AudioManager.STREAM_MUSIC)
            audioSessionManager.resetMediaStreamGainFactor()
            reportVolumeOperation(false)
        }
    }

    fun adjustRingerKeyStep(
        target: VolumeTarget,
        isUp: Boolean,
        currentVolume: Int,
        minVolume: Int
    ): RingerKeyAdjustmentResult {
        if (target != VolumeTarget.RING && target != VolumeTarget.NOTIFICATION) {
            return RingerKeyAdjustmentResult.NOT_HANDLED
        }
        val mode = NotificationAlertMode.resolve(audioManager.ringerMode)
        return when (
            RingerKeyStepPolicy.action(
                mode = mode,
                isUp = isUp,
                atMinimum = currentVolume <= minVolume,
                dndActive = dndController.active.value,
                dndAvailable = dndController.canUseVolumeKeyStep()
            )
        ) {
            RingerKeyStepAction.ADJUST_VOLUME -> RingerKeyAdjustmentResult.NOT_HANDLED
            RingerKeyStepAction.LIMIT -> RingerKeyAdjustmentResult.LIMIT
            RingerKeyStepAction.ENABLE_DND -> dndController.setActive(true).toRingerResult()
            RingerKeyStepAction.DISABLE_DND -> dndController.setActive(false).toRingerResult()
            RingerKeyStepAction.TO_LOUD -> setAlertMode(target, NotificationAlertMode.LOUD)
            RingerKeyStepAction.TO_VIBRATIONS -> setAlertMode(target, NotificationAlertMode.VIBRATIONS)
            RingerKeyStepAction.TO_MUTED -> setAlertMode(target, NotificationAlertMode.MUTED)
        }
    }

    private fun setAlertMode(target: VolumeTarget, mode: NotificationAlertMode): RingerKeyAdjustmentResult =
        if (ringerExperimentExecutor.setProductionAlertMode(mode, target.streamType)) {
            RingerKeyAdjustmentResult.APPLIED
        } else {
            reportRuntimeError(RuntimeErrorCode.VOLUME_CHANGE_FAILED)
            RingerKeyAdjustmentResult.FAILED
        }

    private fun DndOperationResult.toRingerResult(): RingerKeyAdjustmentResult = when (this) {
        DndOperationResult.APPLIED -> RingerKeyAdjustmentResult.APPLIED
        DndOperationResult.ACCESS_REQUIRED,
        DndOperationResult.FEATURE_DISABLED,
        DndOperationResult.FAILED -> RingerKeyAdjustmentResult.FAILED
    }
}
