package com.agentkosticka.amply.audio.routing

/** Legacy Android volume streams Amply can present or route volume keys to. */

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
