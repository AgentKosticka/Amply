package com.agentkosticka.amply.service

internal enum class VolumeKeyRoute { INTERCEPT, PASS_THROUGH }

internal data class VolumeKeyRoutingState(
    val preferencesLoaded: Boolean,
    val foregroundPackage: String?,
    val passThroughPackages: Set<String>,
    val pausedUntilEpochMs: Long,
    val cameraBypassActive: Boolean,
    val nowEpochMs: Long
)

internal object VolumeKeyRoutingPolicy {
    fun route(state: VolumeKeyRoutingState): VolumeKeyRoute {
        if (!state.preferencesLoaded) {
            return VolumeKeyRoute.PASS_THROUGH
        }
        if (state.cameraBypassActive || state.pausedUntilEpochMs > state.nowEpochMs) {
            return VolumeKeyRoute.PASS_THROUGH
        }
        return if (state.foregroundPackage != null && state.foregroundPackage in state.passThroughPackages) {
            VolumeKeyRoute.PASS_THROUGH
        } else {
            VolumeKeyRoute.INTERCEPT
        }
    }
}

internal class VolumeKeySequenceRouter {
    private val routes = mutableMapOf<Int, VolumeKeyRoute>()

    fun onDown(keyCode: Int, repeatCount: Int, decide: () -> VolumeKeyRoute): VolumeKeyRoute {
        if (repeatCount == 0 || keyCode !in routes) routes[keyCode] = decide()
        return routes.getValue(keyCode)
    }

    fun onUp(keyCode: Int): VolumeKeyRoute? = routes.remove(keyCode)

    fun clear() = routes.clear()
}
