package com.agentkosticka.amply.profiles

internal sealed interface OutputProfileAction {
    data object Ignore : OutputProfileAction
    data object Clear : OutputProfileAction
    data object Create : OutputProfileAction
    data class Activate(val profileId: String) : OutputProfileAction
}

internal object OutputProfilePolicy {
    fun resolve(store: ProfileStore, outputKey: String, ignoredOutputKey: String?): OutputProfileAction {
        if (outputKey == ignoredOutputKey) return OutputProfileAction.Ignore
        val known = store.devices[outputKey] ?: return OutputProfileAction.Create
        if (known.explicitlyUnassigned) return OutputProfileAction.Clear
        return known.assignedProfileId?.takeIf(store.profiles::containsKey)
            ?.let(OutputProfileAction::Activate)
            ?: OutputProfileAction.Create
    }
}

internal fun normalizedVolume(current: Int, min: Int, max: Int): Float =
    if (max <= min) 0f else ((current.coerceIn(min, max) - min).toFloat() / (max - min)).coerceIn(0f, 1f)

internal fun volumeIndex(fraction: Float, min: Int, max: Int): Int {
    if (max <= min) return min
    return (min + fraction.coerceIn(0f, 1f) * (max - min)).toInt().coerceIn(min, max)
}
