package com.agentkosticka.amply.audio.ringer

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import com.agentkosticka.amply.service.VolumeKeyService
import com.agentkosticka.amply.shizuku.client.ShizukuRepository
import com.agentkosticka.amply.shizuku.client.ShizukuVolumeManager
import com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

enum class RingerExperimentMethod(
    val number: Int,
    val title: String,
    val description: String,
    val requiresShizuku: Boolean = false
) {
    PUBLIC_RINGER_MODE(1, "Android mode API", "Uses AudioManager.setRingerMode, Android's supported public ringer-mode API."),
    PUBLIC_NOTIFICATION_INDEX(2, "Notification stream index", "Sets the notification stream index with Android's allow-ringer-modes flag."),
    PUBLIC_RING_INDEX(3, "Ring stream index", "Sets the ring stream index with Android's allow-ringer-modes flag."),
    PUBLIC_NOTIFICATION_ADJUST(4, "Notification stream steps", "Repeatedly raises or lowers the notification stream and observes mode changes."),
    PUBLIC_RING_ADJUST(5, "Ring stream steps", "Repeatedly raises or lowers the ring stream using the normal ringer transition policy."),
    SHIZUKU_EXTERNAL_MODE(6, "Privileged external mode", "Calls IAudioService.setRingerModeExternal from Shizuku's shell process.", true),
    SHIZUKU_INTERNAL_MODE(7, "Privileged internal mode", "Calls IAudioService.setRingerModeInternal from Shizuku's shell process.", true),
    SHIZUKU_NOTIFICATION_ADJUST(8, "Privileged notification steps", "Adjusts the notification stream through IAudioService as the Shizuku shell user.", true),
    SHIZUKU_RING_ADJUST(9, "Privileged ring steps", "Adjusts the ring stream through IAudioService as the Shizuku shell user.", true),
    SHIZUKU_KEY_INJECTION(10, "Injected volume keys", "Injects system volume-key events while temporarily passing Amply's interception through.", true)
}

data class RingerExperimentSnapshot(
    val mode: NotificationAlertMode,
    val rawRingerMode: Int,
    val notificationVolume: Int,
    val ringVolume: Int,
    val notificationMuted: Boolean,
    val ringMuted: Boolean,
    val policyAccess: Boolean,
    val interruptionFilter: Int
) {
    companion object {
        val EMPTY = RingerExperimentSnapshot(NotificationAlertMode.LOUD, 2, 0, 0,
            notificationMuted = false,
            ringMuted = false,
            policyAccess = false,
            interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL
        )
    }
}

data class RingerExperimentResult(
    val method: RingerExperimentMethod,
    val requested: NotificationAlertMode,
    val before: RingerExperimentSnapshot,
    val after: RingerExperimentSnapshot,
    val success: Boolean,
    val detail: String,
    val elapsedMs: Long
)

data class RingerTransitionResult(
    val from: NotificationAlertMode,
    val to: NotificationAlertMode,
    val passed: Boolean,
    val enabledDnd: Boolean,
    val detail: String
)

data class RingerMethodTestResult(
    val method: RingerExperimentMethod,
    val transitions: List<RingerTransitionResult>,
    val detail: String = ""
)

internal val ALL_RINGER_MODE_TRANSITIONS = listOf(
    NotificationAlertMode.LOUD to NotificationAlertMode.VIBRATIONS,
    NotificationAlertMode.VIBRATIONS to NotificationAlertMode.MUTED,
    NotificationAlertMode.MUTED to NotificationAlertMode.VIBRATIONS,
    NotificationAlertMode.VIBRATIONS to NotificationAlertMode.LOUD,
    NotificationAlertMode.LOUD to NotificationAlertMode.MUTED,
    NotificationAlertMode.MUTED to NotificationAlertMode.LOUD
)

object NotificationModePolicy {
    fun iconTarget(current: NotificationAlertMode): NotificationAlertMode =
        if (current == NotificationAlertMode.MUTED) NotificationAlertMode.LOUD
        else NotificationAlertMode.MUTED

    fun targetForVolume(volume: Int, minVolume: Int): NotificationAlertMode =
        if (volume <= minVolume) NotificationAlertMode.VIBRATIONS else NotificationAlertMode.LOUD
}

class RingerExperimentExecutor(
    context: Context,
    private val shizukuVolumeManager: ShizukuVolumeManager,
    private val shizukuRepository: ShizukuRepository
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private var lastAudibleNotificationVolume = 1
    @Volatile private var activePlaybackUsages: Set<Int> = emptySet()

    private val _selectedMethod = MutableStateFlow(RingerExperimentMethod.SHIZUKU_INTERNAL_MODE)
    val selectedMethod = _selectedMethod.asStateFlow()
    private val _snapshot = MutableStateFlow(RingerExperimentSnapshot.EMPTY)
    private val _results = MutableStateFlow<Map<RingerExperimentMethod, RingerExperimentResult>>(emptyMap())
    val results = _results.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _methodTestResults = MutableStateFlow<Map<RingerExperimentMethod, RingerMethodTestResult>>(emptyMap())
    val methodTestResults = _methodTestResults.asStateFlow()
    private val _methodTestProgress = MutableStateFlow<Map<RingerExperimentMethod, Float>>(emptyMap())
    val methodTestProgress = _methodTestProgress.asStateFlow()
    private val _runningMethod = MutableStateFlow<RingerExperimentMethod?>(null)
    val runningMethod = _runningMethod.asStateFlow()
    private val experimentMutex = Mutex()

    init { refresh() }

    fun select(method: RingerExperimentMethod) {
        _selectedMethod.value = method
    }

    fun onPlaybackUsagesChanged(usages: Set<Int>) {
        activePlaybackUsages = usages
    }

    fun setAlertVolumeFromControl(streamType: Int, volume: Int): Boolean {
        require(streamType == AudioManager.STREAM_NOTIFICATION || streamType == AudioManager.STREAM_RING)
        val min = audioManager.getStreamMinVolume(streamType)
        val max = audioManager.getStreamMaxVolume(streamType)
        val clamped = volume.coerceIn(min, max)
        audioManager.setStreamVolume(streamType, clamped, 0)
        val target = NotificationModePolicy.targetForVolume(clamped, min)
        if (target == NotificationAlertMode.LOUD) {
            lastAudibleNotificationVolume = clamped
        }
        if (!setProductionAlertMode(target, streamType)) return false
        audioManager.setStreamVolume(streamType, clamped, 0)
        refresh()
        return audioManager.getStreamVolume(streamType) == clamped &&
            NotificationAlertMode.resolve(audioManager.ringerMode) == target
    }

    /** Fixed production controller used by the overlay and hardware-key state ladder. */
    fun setProductionAlertMode(target: NotificationAlertMode, streamType: Int): Boolean {
        val restoreVolume = audibleRestore()
        val privilegedApplied = if (
            shizukuVolumeManager.connectionState.value == VolumeServiceConnectionState.CONNECTED
        ) {
            shizukuVolumeManager.applyRingerExperiment(
                RingerExperimentMethod.SHIZUKU_INTERNAL_MODE.number,
                target.ordinal,
                0
            )?.let { it > 0 } == true
        } else {
            false
        }
        if (!privilegedApplied) {
            runCatching { audioManager.ringerMode = target.toRingerMode() }
                .getOrElse { return false }
        }
        if (target == NotificationAlertMode.LOUD) {
            val min = audioManager.getStreamMinVolume(streamType)
            val max = audioManager.getStreamMaxVolume(streamType)
            val restored = restoreVolume.coerceIn((min + 1).coerceAtMost(max), max)
            runCatching { audioManager.setStreamVolume(streamType, restored, 0) }
                .getOrElse { return false }
        }
        val observed = refresh().mode
        return observed == target
    }

    fun toggleProductionAlertMode(): Boolean = setProductionAlertMode(
        target = NotificationModePolicy.iconTarget(refresh().mode),
        streamType = AudioManager.STREAM_NOTIFICATION
    )

    fun refresh(): RingerExperimentSnapshot {
        val current = snapshotNow()
        if (current.mode == NotificationAlertMode.LOUD && current.notificationVolume > 0) {
            lastAudibleNotificationVolume = current.notificationVolume
        }
        _snapshot.value = current
        return current
    }

    suspend fun testAllTransitions(method: RingerExperimentMethod) {
        experimentMutex.withLock {
            _busy.value = true
            _runningMethod.value = method
            _methodTestProgress.value += (method to 0f)
            val results = mutableListOf<RingerTransitionResult>()
            var overallDetail = ""
            val original = refresh()
            try {
                checkExperimentSafety(method)
                if (notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                    error("Turn off Do Not Disturb before running compatibility checks")
                }
                ALL_RINGER_MODE_TRANSITIONS.forEachIndexed { index, (from, to) ->
                var sourceError: String? = null
                var targetError: String? = null
                try {
                    withContext(Dispatchers.IO) { apply(method, from) }
                } catch (e: Exception) {
                    sourceError = e.message ?: e.javaClass.simpleName
                }
                delay(220L.milliseconds)
                val source = refresh()
                var enabledDnd = source.interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                var dndCleanupSucceeded = true
                if (enabledDnd) dndCleanupSucceeded = disableDnd()

                try {
                    withContext(Dispatchers.IO) { apply(method, to) }
                } catch (e: Exception) {
                    targetError = e.message ?: e.javaClass.simpleName
                }
                delay(260L.milliseconds)
                val after = refresh()
                if (after.interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                    enabledDnd = true
                    dndCleanupSucceeded = disableDnd() && dndCleanupSucceeded
                }

                val passed = sourceError == null && targetError == null &&
                    source.mode == from && after.mode == to && !enabledDnd
                val detail = when {
                    enabledDnd && dndCleanupSucceeded -> "FAILED — enabled DND; DND was disabled automatically"
                    enabledDnd -> "FAILED — enabled DND; automatic cleanup was denied"
                    sourceError != null -> "FAILED — could not enter ${from.name}: $sourceError"
                    source.mode != from -> "FAILED — requested ${from.name}, observed ${source.mode.name}"
                    targetError != null -> "FAILED — $targetError"
                    after.mode != to -> "FAILED — observed ${after.mode.name}"
                    else -> "PASSED — DND remained off"
                }
                results += RingerTransitionResult(from, to, passed, enabledDnd, detail)
                _methodTestProgress.value += (method to ((index + 1f) / ALL_RINGER_MODE_TRANSITIONS.size))
                }
            } catch (e: Exception) {
                overallDetail = e.message ?: e.javaClass.simpleName
            } finally {
                val restoreError = withContext(NonCancellable) {
                    restoreSnapshot(original)
                }
                if (restoreError != null) {
                    overallDetail = listOf(overallDetail, "Restore failed: $restoreError")
                        .filter(String::isNotBlank)
                        .joinToString("; ")
                }
                _methodTestResults.value += (
                    method to RingerMethodTestResult(method, results, overallDetail)
                )
                _methodTestProgress.value += (method to 1f)
                _runningMethod.value = null
                _busy.value = false
                refresh()
            }
        }
    }

    suspend fun execute(
        method: RingerExperimentMethod,
        target: NotificationAlertMode,
        onApplied: () -> Unit = {},
        settleForDiagnostics: Boolean = true
    ): RingerExperimentResult {
        return experimentMutex.withLock {
            _busy.value = true
            val before = refresh()
            val started = SystemClock.elapsedRealtime()
            var detail = "Completed"
            try {
                checkExperimentSafety(method)
                withContext(Dispatchers.IO) { apply(method, target) }
                refresh()
                onApplied()
                if (settleForDiagnostics) {
                    delay(150L.milliseconds)
                    refresh()
                    delay(450L.milliseconds)
                }
                val after = refresh()
                val success = after.mode == target
                if (!success) detail = "Requested ${target.name}, observed ${after.mode.name}"
                RingerExperimentResult(
                    method, target, before, after, success, detail,
                    SystemClock.elapsedRealtime() - started
                ).also { result -> _results.value += (method to result) }
            } catch (e: Exception) {
                val after = refresh()
                RingerExperimentResult(
                    method = method,
                    requested = target,
                    before = before,
                    after = after,
                    success = false,
                    detail = "${e.javaClass.simpleName}: ${e.message ?: "failed"}",
                    elapsedMs = SystemClock.elapsedRealtime() - started
                ).also { result -> _results.value += (method to result) }
            } finally {
                _busy.value = false
            }
        }
    }

    fun report(): String = buildString {
        appendLine("Amply ringer experiments")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Selected: E${selectedMethod.value.number} ${selectedMethod.value.title}")
        results.value.toSortedMap(compareBy { it.number }).values.forEach { result ->
            appendLine("E${result.method.number} ${result.method.title}: ${result.requested} -> ${result.after.mode}; success=${result.success}; ${result.detail}; ${result.elapsedMs}ms")
            appendLine("  before=${result.before}; after=${result.after}")
        }
    }

    private fun snapshotNow(): RingerExperimentSnapshot {
        val mode = audioManager.ringerMode
        return RingerExperimentSnapshot(
            mode = NotificationAlertMode.resolve(mode),
            rawRingerMode = mode,
            notificationVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
            ringVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING),
            notificationMuted = audioManager.isStreamMute(AudioManager.STREAM_NOTIFICATION),
            ringMuted = audioManager.isStreamMute(AudioManager.STREAM_RING),
            policyAccess = notificationManager.isNotificationPolicyAccessGranted,
            interruptionFilter = notificationManager.currentInterruptionFilter
        )
    }

    private fun checkExperimentSafety(method: RingerExperimentMethod) {
        if (audioManager.mode != AudioManager.MODE_NORMAL ||
            AudioAttributes.USAGE_ALARM in activePlaybackUsages
        ) error("Checks are blocked during calls, ringing, or active alarms")
        if (method.requiresShizuku &&
            shizukuVolumeManager.connectionState.value != VolumeServiceConnectionState.CONNECTED
        ) error("Shizuku disconnected")
    }

    private suspend fun disableDnd(): Boolean {
        runCatching {
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
        delay(120L.milliseconds)
        return refresh().interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
    }

    private suspend fun apply(method: RingerExperimentMethod, target: NotificationAlertMode) {
        when (method) {
            RingerExperimentMethod.PUBLIC_RINGER_MODE -> setPublicMode(target)
            RingerExperimentMethod.PUBLIC_NOTIFICATION_INDEX -> setIndex(AudioManager.STREAM_NOTIFICATION, target)
            RingerExperimentMethod.PUBLIC_RING_INDEX -> setIndex(AudioManager.STREAM_RING, target)
            RingerExperimentMethod.PUBLIC_NOTIFICATION_ADJUST -> adjustLocal(AudioManager.STREAM_NOTIFICATION, target)
            RingerExperimentMethod.PUBLIC_RING_ADJUST -> adjustLocal(AudioManager.STREAM_RING, target)
            RingerExperimentMethod.SHIZUKU_EXTERNAL_MODE,
            RingerExperimentMethod.SHIZUKU_INTERNAL_MODE,
            RingerExperimentMethod.SHIZUKU_NOTIFICATION_ADJUST,
            RingerExperimentMethod.SHIZUKU_RING_ADJUST -> {
                val status = shizukuVolumeManager.applyRingerExperiment(
                    method.number, target.ordinal, lastAudibleNotificationVolume
                ) ?: error("Privileged RPC unavailable")
                if (status <= 0) error("Privileged method returned status $status")
            }
            RingerExperimentMethod.SHIZUKU_KEY_INJECTION -> injectKeys(target)
        }
    }

    private fun setPublicMode(target: NotificationAlertMode) {
        audioManager.ringerMode = target.toRingerMode()
        if (target == NotificationAlertMode.LOUD) {
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, audibleRestore(), 0)
        }
    }

    private fun setIndex(stream: Int, target: NotificationAlertMode) {
        val value = if (target == NotificationAlertMode.LOUD) audibleRestore() else 0
        audioManager.setStreamVolume(stream, value, AudioManager.FLAG_ALLOW_RINGER_MODES)
        if (target == NotificationAlertMode.MUTED) {
            audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_ALLOW_RINGER_MODES)
        }
    }

    private fun adjustLocal(stream: Int, target: NotificationAlertMode) {
        repeat(audioManager.getStreamMaxVolume(stream) + 3) {
            val mode = audioManager.ringerMode
            if (NotificationAlertMode.resolve(mode) == target) return
            val direction = if (target == NotificationAlertMode.LOUD) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audioManager.adjustStreamVolume(stream, direction, AudioManager.FLAG_ALLOW_RINGER_MODES)
        }
    }

    private suspend fun injectKeys(target: NotificationAlertMode) {
        VolumeKeyService.suppressInjectedKeysFor(4_000L)
        repeat(audioManager.getStreamMaxVolume(AudioManager.STREAM_RING) + 3) {
            if (NotificationAlertMode.resolve(audioManager.ringerMode) == target) return
            val keyCode = if (target == NotificationAlertMode.LOUD) 24 else 25
            if (!shizukuRepository.injectVolumeKey(keyCode)) error("Key injection failed")
            delay(80L.milliseconds)
        }
    }

    private suspend fun restoreSnapshot(snapshot: RingerExperimentSnapshot): String? =
        runCatching {
            withContext(Dispatchers.IO) {
                val notificationMin = audioManager.getStreamMinVolume(AudioManager.STREAM_NOTIFICATION)
                val notificationMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
                val ringMin = audioManager.getStreamMinVolume(AudioManager.STREAM_RING)
                val ringMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
                audioManager.ringerMode = snapshot.rawRingerMode
                audioManager.setStreamVolume(
                    AudioManager.STREAM_NOTIFICATION,
                    snapshot.notificationVolume.coerceIn(notificationMin, notificationMax),
                    0
                )
                audioManager.setStreamVolume(
                    AudioManager.STREAM_RING,
                    snapshot.ringVolume.coerceIn(ringMin, ringMax),
                    0
                )
                if (snapshot.policyAccess &&
                    notificationManager.currentInterruptionFilter != snapshot.interruptionFilter
                ) {
                    notificationManager.setInterruptionFilter(snapshot.interruptionFilter)
                }
            }
            delay(180L.milliseconds)
            val restored = refresh()
            check(restored.rawRingerMode == snapshot.rawRingerMode) { "ringer mode did not restore" }
            check(restored.notificationVolume == snapshot.notificationVolume) {
                "notification volume did not restore"
            }
            check(restored.ringVolume == snapshot.ringVolume) { "ring volume did not restore" }
        }.exceptionOrNull()?.message

    private fun audibleRestore(): Int = lastAudibleNotificationVolume.coerceIn(
        (audioManager.getStreamMinVolume(AudioManager.STREAM_NOTIFICATION) + 1)
            .coerceAtMost(audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)),
        audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
    )

    private fun NotificationAlertMode.toRingerMode(): Int = when (this) {
        NotificationAlertMode.MUTED -> AudioManager.RINGER_MODE_SILENT
        NotificationAlertMode.VIBRATIONS -> AudioManager.RINGER_MODE_VIBRATE
        NotificationAlertMode.LOUD -> AudioManager.RINGER_MODE_NORMAL
    }
}

internal fun overlayToggleMethod(selectedMethod: RingerExperimentMethod): RingerExperimentMethod =
    selectedMethod
