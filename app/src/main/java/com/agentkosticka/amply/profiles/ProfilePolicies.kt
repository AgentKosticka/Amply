package com.agentkosticka.amply.profiles

import com.agentkosticka.amply.audio.ringer.NotificationAlertMode
import com.agentkosticka.amply.audio.routing.VolumeTarget

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

internal fun ProfileStore.withDeviceAssignment(
    deviceKey: String,
    profileId: String?,
    currentOutputKey: String?
): ProfileStore {
    val known = devices[deviceKey] ?: return this
    if (profileId != null && profileId !in profiles) return this
    val clearingCurrentOutput = deviceKey == currentOutputKey && profileId == null
    return copy(
        devices = devices + (deviceKey to known.copy(
            assignedProfileId = profileId,
            explicitlyUnassigned = profileId == null
        )),
        activeProfileId = if (clearingCurrentOutput) null else activeProfileId,
        activeDraft = if (clearingCurrentOutput) null else activeDraft
    )
}

internal data class ProfileRingerApplyPlan(
    val applyBeforeVolumes: Boolean,
    val reapplyAfterVolumes: Boolean
)

internal fun profileRingerApplyPlan(mode: NotificationAlertMode): ProfileRingerApplyPlan =
    ProfileRingerApplyPlan(
        applyBeforeVolumes = true,
        // Loud restores a remembered alert level, so applying it after the two
        // saved streams would overwrite one of them. Silent/Vibrate do not.
        reapplyAfterVolumes = mode != NotificationAlertMode.LOUD
    )

internal fun profileVolumeSettled(
    target: VolumeTarget,
    expectedRingerMode: NotificationAlertMode,
    ringerModeMatches: Boolean,
    observedVolume: Int,
    expectedVolume: Int
): Boolean {
    val alertStream = target == VolumeTarget.RING || target == VolumeTarget.NOTIFICATION
    return (alertStream && expectedRingerMode != NotificationAlertMode.LOUD && ringerModeMatches) ||
        observedVolume == expectedVolume
}

internal fun normalizedVolume(current: Int, min: Int, max: Int): Float =
    if (max <= min) 0f else ((current.coerceIn(min, max) - min).toFloat() / (max - min)).coerceIn(0f, 1f)

internal fun volumeIndex(fraction: Float, min: Int, max: Int): Int {
    if (max <= min) return min
    return (min + fraction.coerceIn(0f, 1f) * (max - min)).toInt().coerceIn(min, max)
}
