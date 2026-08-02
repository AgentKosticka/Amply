package com.agentkosticka.amply.audio

import android.media.AudioAttributes
import kotlin.math.roundToInt

/** Legacy Android volume streams Amply can present or route volume keys to. */
enum class VolumeTarget(
    val streamType: Int,
    val label: String,
    val icon: StreamIcon,
    val permanentlyVisible: Boolean = false,
    val userAdjustable: Boolean = true
) {
    CALL(0, "Call", StreamIcon.CALL, permanentlyVisible = true),
    SYSTEM(1, "System", StreamIcon.SYSTEM),
    RING(2, "Ring", StreamIcon.RING),
    MEDIA(3, "Media", StreamIcon.MEDIA, permanentlyVisible = true),
    ALARM(4, "Alarm", StreamIcon.ALARM, permanentlyVisible = true),
    NOTIFICATION(5, "Notifications", StreamIcon.NOTIFICATION, permanentlyVisible = true),
    BLUETOOTH_SCO(6, "Bluetooth call", StreamIcon.BLUETOOTH),
    ENFORCED_AUDIBLE(7, "Enforced sound", StreamIcon.LOCKED_SOUND, userAdjustable = false),
    DTMF(8, "Dial tones", StreamIcon.DIAL_PAD),
    TTS(9, "Spoken audio", StreamIcon.SPOKEN_TEXT),
    ACCESSIBILITY(10, "Accessibility", StreamIcon.ACCESSIBILITY),
    ASSISTANT(11, "Assistant", StreamIcon.ASSISTANT);

    companion object {
        fun fromStreamType(streamType: Int): VolumeTarget =
            entries.firstOrNull { it.streamType == streamType } ?: MEDIA

        fun findByStreamType(streamType: Int): VolumeTarget? =
            entries.firstOrNull { it.streamType == streamType }
    }
}

enum class StreamIcon {
    MEDIA,
    ALARM,
    NOTIFICATION,
    CALL,
    SYSTEM,
    RING,
    BLUETOOTH,
    LOCKED_SOUND,
    DIAL_PAD,
    SPOKEN_TEXT,
    ACCESSIBILITY,
    ASSISTANT
}

data class SystemStreamDescriptor(
    val target: VolumeTarget,
    val optional: Boolean = !target.permanentlyVisible
)

object SystemStreamCatalog {
    val descriptors: List<SystemStreamDescriptor> = VolumeTarget.entries.map(::SystemStreamDescriptor)
    val coreOrder = listOf(
        VolumeTarget.MEDIA,
        VolumeTarget.ALARM,
        VolumeTarget.NOTIFICATION,
        VolumeTarget.CALL
    )
    val optionalOrder = listOf(
        VolumeTarget.BLUETOOTH_SCO,
        VolumeTarget.ACCESSIBILITY,
        VolumeTarget.RING,
        VolumeTarget.DTMF,
        VolumeTarget.ASSISTANT,
        VolumeTarget.TTS,
        VolumeTarget.SYSTEM,
        VolumeTarget.ENFORCED_AUDIBLE
    )
}

/** Canonical stream aliases reported by Android's audio service. */
data class StreamTopology(
    val aliasKnown: Boolean = false,
    val aliases: Map<Int, Int> = emptyMap()
) {
    fun canonicalStream(streamType: Int): Int {
        var current = streamType
        val visited = mutableSetOf<Int>()
        while (visited.add(current)) {
            val next = aliases[current] ?: return current
            if (next == current) return current
            current = next
        }
        return streamType
    }

    fun canonicalTarget(target: VolumeTarget): VolumeTarget =
        VolumeTarget.findByStreamType(canonicalStream(target.streamType)) ?: target

    fun aliasesTogether(first: VolumeTarget, second: VolumeTarget): Boolean =
        aliasKnown && canonicalStream(first.streamType) == canonicalStream(second.streamType)

    companion object {
        val UNKNOWN = StreamTopology()
    }
}

data class ActiveSystemStreams(
    val rawStreamTypes: Set<Int> = emptySet(),
    val topology: StreamTopology = StreamTopology.UNKNOWN,
    val shizukuConnected: Boolean = false
) {
    val canonicalTargets: Set<VolumeTarget>
        get() = if (!shizukuConnected) emptySet() else rawStreamTypes.mapNotNullTo(linkedSetOf()) {
            VolumeTarget.findByStreamType(topology.canonicalStream(it))
        }
}

data class DynamicStreamState(
    val activeTargets: Set<VolumeTarget> = emptySet(),
    val visibleOptionalTargets: Set<VolumeTarget> = emptySet(),
    val disabledTargets: Set<VolumeTarget> = emptySet(),
    val topology: StreamTopology = StreamTopology.UNKNOWN,
    val shizukuConnected: Boolean = false,
    val callActive: Boolean = false
) {
    val hasIndependentRinger: Boolean
        get() = topology.aliasKnown &&
            !topology.aliasesTogether(VolumeTarget.RING, VolumeTarget.NOTIFICATION)

    fun coreTargets(): List<VolumeTarget> {
        val fourth = if (hasIndependentRinger && !callActive) {
            VolumeTarget.RING
        } else {
            VolumeTarget.CALL
        }
        return listOf(
            VolumeTarget.MEDIA,
            VolumeTarget.ALARM,
            VolumeTarget.NOTIFICATION,
            fourth
        ).map(topology::canonicalTarget).distinct()
    }

    fun visibleTargets(): List<VolumeTarget> {
        val core = coreTargets()
        val optional = SystemStreamCatalog.optionalOrder
            .map(topology::canonicalTarget)
            .filter {
                it in visibleOptionalTargets && it !in core &&
                    !(hasIndependentRinger && it == topology.canonicalTarget(VolumeTarget.RING))
            }
            .distinct()
        return core + optional
    }
}

/** Latches optional bars for one overlay appearance without affecting live routing. */
class SystemStreamSessionController {
    private var overlayVisible = false
    private var latchedOptionalTargets = linkedSetOf<VolumeTarget>()
    private var disabledTargets = linkedSetOf<VolumeTarget>()
    private var latest = ActiveSystemStreams()
    private var callModeActive = false
    private var callUsageActive = false

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(DynamicStreamState())
    val state: kotlinx.coroutines.flow.StateFlow<DynamicStreamState> = _state

    @Synchronized
    fun onStreamsChanged(streams: ActiveSystemStreams) {
        latest = streams
        if (!streams.shizukuConnected) {
            latchedOptionalTargets.clear()
            disabledTargets.clear()
        } else if (overlayVisible) {
            latchedOptionalTargets += optionalCanonicalTargets(streams)
        }
        publish()
    }

    @Synchronized
    fun onCallModeChanged(active: Boolean) {
        callModeActive = active
        publish()
    }

    @Synchronized
    fun onCallUsageChanged(active: Boolean) {
        callUsageActive = active
        publish()
    }

    @Synchronized
    fun onOverlayShown() {
        overlayVisible = true
        latchedOptionalTargets += optionalCanonicalTargets(latest)
        publish()
    }

    @Synchronized
    fun onOverlayHidden() {
        overlayVisible = false
        latchedOptionalTargets.clear()
        disabledTargets.clear()
        publish()
    }

    @Synchronized
    fun disable(target: VolumeTarget) {
        disabledTargets += latest.topology.canonicalTarget(target)
        publish()
    }

    private fun optionalCanonicalTargets(streams: ActiveSystemStreams): Set<VolumeTarget> {
        val state = currentState()
        val core = state.coreTargets().toSet()
        val independentRing = state.hasIndependentRinger
        val canonicalRing = streams.topology.canonicalTarget(VolumeTarget.RING)
        return streams.canonicalTargets.filterTo(linkedSetOf()) {
            it !in core && !(independentRing && it == canonicalRing)
        }
    }

    private fun currentState() = DynamicStreamState(
            activeTargets = latest.canonicalTargets,
            visibleOptionalTargets = latchedOptionalTargets.toSet(),
            disabledTargets = disabledTargets.toSet(),
            topology = latest.topology,
            shizukuConnected = latest.shizukuConnected,
            callActive = callModeActive || callUsageActive ||
                VolumeTarget.CALL in latest.canonicalTargets ||
                VolumeTarget.BLUETOOTH_SCO in latest.canonicalTargets
        )

    private fun publish() {
        _state.value = currentState()
    }
}

/** Pure mapping used by the privileged playback reader and unit tests. */
object LegacyStreamResolver {
    const val FLAG_AUDIBILITY_ENFORCED = 0x1
    const val FLAG_SCO = 0x4
    const val FLAG_BEACON = 0x8

    fun resolve(usage: Int, allFlags: Int): VolumeTarget {
        if (allFlags and FLAG_AUDIBILITY_ENFORCED != 0) return VolumeTarget.ENFORCED_AUDIBLE
        if (allFlags and FLAG_SCO != 0) return VolumeTarget.BLUETOOTH_SCO
        if (allFlags and FLAG_BEACON != 0) return VolumeTarget.TTS
        return when (usage) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> VolumeTarget.CALL
            AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> VolumeTarget.DTMF
            AudioAttributes.USAGE_ALARM -> VolumeTarget.ALARM
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> VolumeTarget.RING
            AudioAttributes.USAGE_NOTIFICATION,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
            AudioAttributes.USAGE_NOTIFICATION_EVENT -> VolumeTarget.NOTIFICATION
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> VolumeTarget.ACCESSIBILITY
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> VolumeTarget.SYSTEM
            AudioAttributes.USAGE_ASSISTANT -> VolumeTarget.ASSISTANT
            else -> VolumeTarget.MEDIA
        }
    }
}

data class VolumeBarModel(
    val target: VolumeTarget,
    val aliases: Set<Int>,
    val label: String,
    val currentVolume: Int,
    val minVolume: Int,
    val maxVolume: Int,
    val active: Boolean,
    val enabled: Boolean,
    val referenceMaxVolume: Int = maxVolume.coerceAtLeast(1),
    val dotCount: Int = 16,
    val combinedRinger: Boolean = false,
    val notificationAlertMode: NotificationAlertMode? = null
)

/**
 * Maps native stream indices onto the shared Nothing-style dot rail. Native
 * values are never quantized by this model; only their visual representation is.
 */
object VolumeDotScale {
    fun displayLevel(current: Int, referenceMax: Int, dotCount: Int): Int =
        ((current.coerceAtLeast(0).toFloat() / referenceMax.coerceAtLeast(1)) * dotCount)
            .roundToInt()
            .coerceIn(0, dotCount)

    fun levelForFraction(
        fraction: Float,
        min: Int,
        max: Int,
        referenceMax: Int
    ): Int {
        val requested = (fraction.coerceIn(0f, 1f) * referenceMax.coerceAtLeast(1)).roundToInt()
        val lower = min.coerceAtLeast(0)
        val upper = max.coerceAtLeast(lower)
        return requested.coerceIn(lower, upper)
    }

    fun projectedLevel(nativeLevel: Int, referenceMax: Int, dotCount: Int): Int =
        ((nativeLevel.coerceAtLeast(0).toFloat() / referenceMax.coerceAtLeast(1)) * dotCount)
            .roundToInt()
            .coerceIn(0, dotCount)

    fun isLevelAvailable(
        visualLevel: Int,
        min: Int,
        max: Int,
        referenceMax: Int,
        dotCount: Int
    ): Boolean {
        val projectedMin = projectedLevel(min, referenceMax, dotCount).coerceAtLeast(1)
        val projectedMax = projectedLevel(max, referenceMax, dotCount)
        return visualLevel in projectedMin..projectedMax
    }
}

data class VolumeLimitFeedback(
    val target: VolumeTarget,
    val dotLevel: Int,
    val eventId: Long
)

object VolumeLimitFeedbackPolicy {
    fun rejectedDotLevel(
        isUp: Boolean,
        min: Int,
        max: Int,
        referenceMax: Int = 16,
        dotCount: Int = 16
    ): Int =
        if (isUp) {
            (VolumeDotScale.projectedLevel(max, referenceMax, dotCount) + 1)
                .coerceIn(1, dotCount)
        } else {
            VolumeDotScale.projectedLevel(min, referenceMax, dotCount).coerceIn(1, dotCount)
        }
}
