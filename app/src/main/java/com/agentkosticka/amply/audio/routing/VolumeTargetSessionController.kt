package com.agentkosticka.amply.audio.routing

import android.media.AudioAttributes
import android.media.AudioManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CallPhase {
    NONE,
    INCOMING_RINGING,
    OUTGOING_OR_ACTIVE,
    UNKNOWN
}

data class SystemVolumeContext(
    val audioMode: Int = AudioManager.MODE_NORMAL,
    val callPhase: CallPhase = CallPhase.UNKNOWN,
    val activeUsages: Set<Int> = emptySet(),
    val activeStreamTargets: Set<VolumeTarget> = emptySet(),
    val topology: StreamTopology = StreamTopology.UNKNOWN,
    val disabledTargets: Set<VolumeTarget> = emptySet(),
    val lastNotificationElapsedMs: Long = Long.MIN_VALUE
)

sealed interface VolumeKeyStreamAction {
    data class Adjust(val target: VolumeTarget) : VolumeKeyStreamAction
    data class AdjustRemoteMedia(val routeGeneration: Long) : VolumeKeyStreamAction
    data object SilenceIncomingRinger : VolumeKeyStreamAction
    data object PassThrough : VolumeKeyStreamAction
}

sealed interface VolumeAdjustmentResult {
    data class Applied(val target: VolumeTarget) : VolumeAdjustmentResult
    data object Unavailable : VolumeAdjustmentResult
    data class Failed(val target: VolumeTarget?) : VolumeAdjustmentResult
}

internal object VolumeTargetPolicy {
    const val NOTIFICATION_GRACE_MS = 1_000L

    fun automaticTarget(context: SystemVolumeContext, nowElapsedMs: Long): VolumeTarget {
        fun active(target: VolumeTarget): Boolean {
            val canonical = context.topology.canonicalTarget(target)
            return canonical in context.activeStreamTargets && canonical !in context.disabledTargets
        }
        fun available(target: VolumeTarget): VolumeTarget? {
            val canonical = context.topology.canonicalTarget(target)
            return canonical.takeIf { it.userAdjustable && it !in context.disabledTargets }
        }

        if (context.callPhase == CallPhase.OUTGOING_OR_ACTIVE ||
            isActiveCallMode(context.audioMode) || context.activeUsages.any(::isCallUsage) ||
            active(VolumeTarget.BLUETOOTH_SCO) || active(VolumeTarget.CALL)
        ) {
            if (active(VolumeTarget.BLUETOOTH_SCO)) available(VolumeTarget.BLUETOOTH_SCO)?.let { return it }
            available(VolumeTarget.CALL)?.let { return it }
        }
        if (AudioAttributes.USAGE_ALARM in context.activeUsages || active(VolumeTarget.ALARM)) {
            available(VolumeTarget.ALARM)?.let { return it }
        }
        if (active(VolumeTarget.ACCESSIBILITY)) {
            available(VolumeTarget.ACCESSIBILITY)?.let { return it }
        }
        if (active(VolumeTarget.RING)) {
            available(VolumeTarget.RING)?.let { return it }
        }
        if (context.activeUsages.any(::isNotificationUsage) || active(VolumeTarget.NOTIFICATION) ||
            elapsedSince(context.lastNotificationElapsedMs, nowElapsedMs) <= NOTIFICATION_GRACE_MS) {
            available(VolumeTarget.NOTIFICATION)?.let { return it }
        }
        listOf(
            VolumeTarget.DTMF,
            VolumeTarget.ASSISTANT,
            VolumeTarget.TTS,
            VolumeTarget.SYSTEM
        ).firstOrNull(::active)?.let { target ->
            available(target)?.let { return it }
        }
        return available(VolumeTarget.MEDIA) ?: VolumeTarget.MEDIA
    }

    fun isIncomingRinging(audioMode: Int, callPhase: CallPhase): Boolean =
        callPhase == CallPhase.INCOMING_RINGING ||
            (callPhase == CallPhase.UNKNOWN && audioMode == AudioManager.MODE_RINGTONE)

    fun isActiveCallMode(mode: Int): Boolean = when (mode) {
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
        AudioManager.MODE_CALL_SCREENING,
        AudioManager.MODE_CALL_REDIRECT,
        AudioManager.MODE_COMMUNICATION_REDIRECT -> true
        else -> false
    }

    private fun isCallUsage(usage: Int): Boolean =
        usage == AudioAttributes.USAGE_VOICE_COMMUNICATION

    @Suppress("DEPRECATION")
    fun isNotificationUsage(usage: Int): Boolean = when (usage) {
        AudioAttributes.USAGE_NOTIFICATION,
        AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
        AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
        AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
        AudioAttributes.USAGE_NOTIFICATION_EVENT -> true
        else -> false
    }

    private fun elapsedSince(then: Long, now: Long): Long {
        if (then == Long.MIN_VALUE || now < then) return Long.MAX_VALUE
        return now - then
    }
}

class VolumeTargetSessionController(
    private val clock: () -> Long = SystemClock::elapsedRealtime
) {
    private var context = SystemVolumeContext()
    private var overlayVisible = false
    private var manualTarget: VolumeTarget? = null
    private var latchedTarget: VolumeTarget? = null
    private val _selectedTarget = MutableStateFlow(VolumeTarget.MEDIA)
    val selectedTarget: StateFlow<VolumeTarget> = _selectedTarget.asStateFlow()

    @Synchronized
    fun onAudioModeChanged(audioMode: Int) {
        context = context.copy(audioMode = audioMode)
        publishAutomaticIfAllowed()
    }

    @Synchronized
    fun onPlaybackUsagesChanged(activeUsages: Set<Int>) {
        val now = clock()
        val notificationWasActive = context.activeUsages.any(VolumeTargetPolicy::isNotificationUsage)
        val notificationIsActive = activeUsages.any(VolumeTargetPolicy::isNotificationUsage)
        val lastNotification = if (notificationWasActive || notificationIsActive) {
            now
        } else {
            context.lastNotificationElapsedMs
        }
        context = context.copy(
            activeUsages = activeUsages,
            lastNotificationElapsedMs = lastNotification
        )
        publishAutomaticIfAllowed()
    }

    @Synchronized
    fun onStreamsChanged(streams: ActiveSystemStreams, disabledTargets: Set<VolumeTarget> = emptySet()) {
        if (!overlayVisible && !streams.shizukuConnected && manualTarget?.permanentlyVisible == false) {
            manualTarget = null
        }
        context = context.copy(
            activeStreamTargets = streams.canonicalTargets,
            topology = streams.topology,
            disabledTargets = disabledTargets
        )
        publishAutomaticIfAllowed()
    }

    @Synchronized
    fun onTimeAdvanced() {
        publishAutomaticIfAllowed()
    }

    @Synchronized
    fun resolveForInitialKeyDown(
        audioMode: Int,
        callPhase: CallPhase = CallPhase.UNKNOWN
    ): VolumeKeyStreamAction {
        context = context.copy(audioMode = audioMode, callPhase = callPhase)
        if (VolumeTargetPolicy.isIncomingRinging(audioMode, callPhase)) {
            return VolumeKeyStreamAction.SilenceIncomingRinger
        }
        val target = manualTarget ?: latchedTarget ?: VolumeTargetPolicy.automaticTarget(context, clock())
        _selectedTarget.value = target
        return VolumeKeyStreamAction.Adjust(target)
    }

    @Synchronized
    fun onOverlayShown() {
        if (!overlayVisible) {
            overlayVisible = true
            manualTarget = null
            latchedTarget = _selectedTarget.value
        }
    }

    @Synchronized
    fun onUserSelected(target: VolumeTarget) {
        if (!overlayVisible) overlayVisible = true
        manualTarget = target
        latchedTarget = target
        _selectedTarget.value = target
    }

    @Synchronized
    fun onOverlayHidden() {
        overlayVisible = false
        manualTarget = null
        latchedTarget = null
        _selectedTarget.value = VolumeTargetPolicy.automaticTarget(context, clock())
    }

    @Synchronized
    fun hasManualTarget(): Boolean = manualTarget != null

    @Synchronized
    fun onTargetUnavailable(target: VolumeTarget) {
        if (overlayVisible) return
        if (manualTarget?.let(context.topology::canonicalTarget) == context.topology.canonicalTarget(target)) {
            manualTarget = null
        }
        publishAutomaticIfAllowed()
    }

    private fun publishAutomaticIfAllowed() {
        if (!overlayVisible && manualTarget == null) {
            _selectedTarget.value = VolumeTargetPolicy.automaticTarget(context, clock())
        }
    }
}
