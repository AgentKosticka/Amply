package com.agentkosticka.amply.service

import com.agentkosticka.amply.audio.VolumeKeyStreamAction

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

internal class VolumeKeyStreamActionRouter {
    private val actions = mutableMapOf<Int, VolumeKeyStreamAction>()

    fun onDown(
        keyCode: Int,
        repeatCount: Int,
        decide: () -> VolumeKeyStreamAction
    ): VolumeKeyStreamAction {
        if (repeatCount == 0 || keyCode !in actions) actions[keyCode] = decide()
        return actions.getValue(keyCode)
    }

    fun onUp(keyCode: Int): VolumeKeyStreamAction? = actions.remove(keyCode)

    fun replace(keyCode: Int, action: VolumeKeyStreamAction) {
        actions[keyCode] = action
    }

    fun clear() = actions.clear()
}

internal object VolumeStepPolicy {
    fun next(current: Int, min: Int, max: Int, isUp: Boolean, step: Int = 1): Int =
        if (isUp) {
            (current + step).coerceAtMost(max)
        } else {
            (current - step).coerceAtLeast(min)
        }
}
