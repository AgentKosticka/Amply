package com.agentkosticka.amply.profiles

import com.agentkosticka.amply.audio.ringer.NotificationAlertMode
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.settings.model.AppIdentity
import com.agentkosticka.amply.settings.model.AppSettingsStoreHealth
import com.agentkosticka.amply.settings.model.SettingsOperationResult

enum class OutputKind { SPEAKER, WIRED, BLUETOOTH, CAST }

enum class OutputIdentityQuality { STABLE, BEST_EFFORT, CATEGORY }

data class OutputDeviceDescriptor(
    val key: String,
    val kind: OutputKind,
    val displayName: String,
    val identityQuality: OutputIdentityQuality
)

enum class ProfileSaveMode { AUTO_DEVICE, EXPLICIT }

data class AudioProfileSnapshot(
    val appVolumes: Map<AppIdentity, Float> = emptyMap(),
    val systemVolumes: Map<VolumeTarget, Float> = emptyMap(),
    val ringerMode: NotificationAlertMode = NotificationAlertMode.LOUD,
    val dndEnabled: Boolean? = null
)

data class AudioProfile(
    val id: String,
    val name: String,
    val saveMode: ProfileSaveMode,
    val snapshot: AudioProfileSnapshot,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

data class KnownOutputDevice(
    val descriptor: OutputDeviceDescriptor,
    val assignedProfileId: String?,
    val explicitlyUnassigned: Boolean = false
)

data class ProfileStore(
    val profiles: Map<String, AudioProfile> = emptyMap(),
    val devices: Map<String, KnownOutputDevice> = emptyMap(),
    val activeProfileId: String? = null,
    val activeDraft: AudioProfileSnapshot? = null
) {
    val activeProfile: AudioProfile? get() = activeProfileId?.let(profiles::get)
    val activeSnapshot: AudioProfileSnapshot? get() = activeDraft ?: activeProfile?.snapshot
    val dirty: Boolean get() {
        val saved = activeProfile?.snapshot ?: return false
        val draft = activeDraft ?: return false
        return !draft.equivalentTo(saved)
    }
}

data class ProfileRuntimeState(
    val store: ProfileStore = ProfileStore(),
    val currentOutput: OutputDeviceDescriptor? = null,
    val applying: Boolean = false,
    val lastApplyWarnings: List<String> = emptyList(),
    val storeHealth: AppSettingsStoreHealth = AppSettingsStoreHealth.HEALTHY,
    val operationError: String? = null
) {
    val activeProfile: AudioProfile? get() = store.activeProfile
    val activeSnapshot: AudioProfileSnapshot? get() = store.activeSnapshot
    val dirty: Boolean get() = store.dirty
}

sealed interface ProfileOperationResult<out T> {
    data class Success<T>(val value: T) : ProfileOperationResult<T>
    data class Failure(val error: SettingsOperationResult) : ProfileOperationResult<Nothing>
}

data class ProfileVolumeContext(val profileId: String?, val outputGeneration: Long, val revision: Long)

data class ProfileStoreResolution(val store: ProfileStore, val health: AppSettingsStoreHealth)

internal val PROFILE_SYSTEM_TARGETS = listOf(
    VolumeTarget.MEDIA,
    VolumeTarget.RING,
    VolumeTarget.NOTIFICATION,
    VolumeTarget.ALARM,
    VolumeTarget.CALL
)

private fun AudioProfileSnapshot.equivalentTo(other: AudioProfileSnapshot): Boolean {
    if (ringerMode != other.ringerMode || dndEnabled != other.dndEnabled) return false
    if (appVolumes.keys != other.appVolumes.keys || appVolumes.any { (key, value) ->
            kotlin.math.abs(value - other.appVolumes.getValue(key)) > 0.001f
        }
    ) return false
    val targets = systemVolumes.keys + other.systemVolumes.keys
    return targets.all { target ->
        kotlin.math.abs((systemVolumes[target] ?: 1f) - (other.systemVolumes[target] ?: 1f)) <= 0.04f
    }
}
