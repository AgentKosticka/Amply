package com.agentkosticka.amply.audio.routing

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
