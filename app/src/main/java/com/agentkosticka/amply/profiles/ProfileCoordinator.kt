package com.agentkosticka.amply.profiles

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.agentkosticka.amply.audio.ringer.NotificationAlertMode
import com.agentkosticka.amply.audio.ringer.RingerExperimentExecutor
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.audio.routing.VolumeBarModel
import com.agentkosticka.amply.dnd.AmplyDndController
import com.agentkosticka.amply.dnd.DndOperationResult
import com.agentkosticka.amply.settings.data.PreferencesManager
import com.agentkosticka.amply.settings.model.AppIdentity
import com.agentkosticka.amply.settings.model.VolumeDotScaleConfig
import com.agentkosticka.amply.shizuku.client.ShizukuVolumeManager
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ProfileCoordinator(
    context: Context,
    private val preferences: PreferencesManager,
    val outputMonitor: OutputRouteMonitor,
    private val dndController: AmplyDndController,
    private val ringerExecutor: RingerExperimentExecutor,
    private val shizukuVolumeManager: ShizukuVolumeManager,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val _state = MutableStateFlow(ProfileRuntimeState())
    val state: StateFlow<ProfileRuntimeState> = _state.asStateFlow()
    private val _effectiveAppVolumes = MutableStateFlow<Map<AppIdentity, Float>>(emptyMap())
    val effectiveAppVolumes: StateFlow<Map<AppIdentity, Float>> = _effectiveAppVolumes.asStateFlow()
    private var routeJob: Job? = null
    private var autoSaveJob: Job? = null
    private var applying = false
    private var ignoredUntilRouteLeaves: String? = null
    private var lastRouteKey: String? = null

    private val systemObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = scheduleAutoSave()
    }
    private val ringerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = scheduleAutoSave()
    }

    fun start() {
        outputMonitor.start()
        appContext.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, systemObserver)
        ContextCompat.registerReceiver(
            appContext,
            ringerReceiver,
            IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        scope.launch {
            preferences.profileStore.collect { store ->
                _state.value = _state.value.copy(store = store)
                _effectiveAppVolumes.value = store.activeSnapshot?.appVolumes.orEmpty()
            }
        }
        scope.launch {
            preferences.appSettings.collect { scheduleAutoSave() }
        }
        scope.launch {
            dndController.active.collect { scheduleAutoSave() }
        }
        scope.launch {
            outputMonitor.state.collect { route ->
                val keyChanged = route.descriptor.key != lastRouteKey
                if (keyChanged) {
                    val previous = lastRouteKey
                    lastRouteKey = route.descriptor.key
                    if (ignoredUntilRouteLeaves != null && previous == ignoredUntilRouteLeaves) {
                        ignoredUntilRouteLeaves = null
                    }
                    routeJob?.cancel()
                    routeJob = scope.launch {
                        delay(750L)
                        onSettledOutput(route.descriptor)
                    }
                } else {
                    scheduleAutoSave()
                }
                _state.value = _state.value.copy(currentOutput = route.descriptor)
            }
        }
    }

    suspend fun activateProfile(profileId: String): ProfileActivationResult {
        flushAutoSave()
        val store = preferences.profileStore.first()
        val profile = store.profiles[profileId] ?: return ProfileActivationResult(false, listOf("Profile not found"))
        applying = true
        _state.value = _state.value.copy(applying = true, lastApplyWarnings = emptyList())
        preferences.updateProfileStore {
            it.copy(activeProfileId = profile.id, activeDraft = profile.snapshot)
        }
        val warnings = applySnapshot(profile.snapshot)
        applying = false
        outputMonitor.refresh()
        _state.value = _state.value.copy(applying = false, lastApplyWarnings = warnings)
        return ProfileActivationResult(true, warnings)
    }

    suspend fun createNamedProfile(name: String): AudioProfile? {
        val normalized = name.trim().take(40)
        if (normalized.isBlank()) return null
        val current = preferences.profileStore.first()
        if (current.profiles.values.any { it.name.equals(normalized, true) }) return null
        val now = System.currentTimeMillis()
        val profile = AudioProfile(
            id = UUID.randomUUID().toString(),
            name = normalized,
            saveMode = ProfileSaveMode.EXPLICIT,
            snapshot = captureSnapshot(),
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        preferences.updateProfileStore { it.copy(profiles = it.profiles + (profile.id to profile)) }
        return profile
    }

    suspend fun saveCurrentProfile(): Boolean {
        val store = preferences.profileStore.first()
        val profile = store.activeProfile ?: return false
        val snapshot = captureSnapshot()
        val now = System.currentTimeMillis()
        preferences.updateProfileStore {
            val latest = it.profiles[profile.id] ?: return@updateProfileStore it
            it.copy(
                profiles = it.profiles + (profile.id to latest.copy(snapshot = snapshot, updatedAtEpochMs = now)),
                activeDraft = snapshot
            )
        }
        return true
    }

    suspend fun updateProfile(
        profileId: String,
        name: String,
        snapshot: AudioProfileSnapshot
    ): Boolean {
        val normalized = name.trim().take(40)
        val store = preferences.profileStore.first()
        if (normalized.isBlank() || store.profiles.values.any {
                it.id != profileId && it.name.equals(normalized, true)
            }
        ) return false
        val current = store.profiles[profileId] ?: return false
        val updated = current.copy(name = normalized, snapshot = snapshot, updatedAtEpochMs = System.currentTimeMillis())
        preferences.updateProfileStore {
            it.copy(
                profiles = it.profiles + (profileId to updated),
                activeDraft = if (it.activeProfileId == profileId) snapshot else it.activeDraft
            )
        }
        if (store.activeProfileId == profileId) {
            applying = true
            val warnings = applySnapshot(snapshot)
            applying = false
            _state.value = _state.value.copy(lastApplyWarnings = warnings)
        }
        return true
    }

    suspend fun assignDevice(deviceKey: String, profileId: String?) {
        val currentOutputKey = state.value.currentOutput?.key
        preferences.updateProfileStore { store ->
            store.withDeviceAssignment(deviceKey, profileId, currentOutputKey)
        }
        if (deviceKey == currentOutputKey && profileId != null) {
            activateProfile(profileId)
        }
    }

    suspend fun deleteProfile(profileId: String) {
        val current = preferences.profileStore.first()
        val active = current.activeProfileId == profileId
        val currentKey = state.value.currentOutput?.key
        if (active && currentKey != null) ignoredUntilRouteLeaves = currentKey
        preferences.updateProfileStore { store ->
            store.copy(
                profiles = store.profiles - profileId,
                devices = store.devices.mapValues { (_, device) ->
                    if (device.assignedProfileId == profileId) device.copy(assignedProfileId = null) else device
                },
                activeProfileId = if (active) null else store.activeProfileId,
                activeDraft = if (active) null else store.activeDraft
            )
        }
    }

    suspend fun forgetDevice(deviceKey: String) {
        val store = preferences.profileStore.first()
        val device = store.devices[deviceKey] ?: return
        val profile = device.assignedProfileId?.let(store.profiles::get)
        if (state.value.currentOutput?.key == deviceKey) ignoredUntilRouteLeaves = deviceKey
        preferences.updateProfileStore {
            val removeProfileId = if (profile?.saveMode == ProfileSaveMode.AUTO_DEVICE &&
                it.devices.values.count { known -> known.assignedProfileId == profile.id } <= 1
            ) profile.id else null
            it.copy(
                devices = it.devices - deviceKey,
                profiles = removeProfileId?.let(it.profiles::minus) ?: it.profiles,
                activeProfileId = if (it.activeProfileId == removeProfileId) null else it.activeProfileId,
                activeDraft = if (it.activeProfileId == removeProfileId) null else it.activeDraft
            )
        }
    }

    suspend fun recordAppVolume(identity: AppIdentity, volume: Float): Boolean {
        val store = preferences.profileStore.first()
        val profile = store.activeProfile ?: return false
        val base = store.activeSnapshot ?: profile.snapshot
        val snapshot = base.copy(appVolumes = base.appVolumes + (identity to volume.coerceIn(0f, 1f)))
        val saveAutomatically = preferences.automaticallySaveProfileChanges.first()
        preferences.updateProfileStore {
            val latest = it.profiles[profile.id] ?: return@updateProfileStore it
            if (saveAutomatically) {
                val saved = latest.copy(snapshot = snapshot, updatedAtEpochMs = System.currentTimeMillis())
                it.copy(profiles = it.profiles + (profile.id to saved), activeDraft = snapshot)
            } else {
                it.copy(activeDraft = snapshot)
            }
        }
        return true
    }

    fun onCallBecameActive() {
        scope.launch {
            val fraction = preferences.profileStore.first().activeSnapshot
                ?.systemVolumes?.get(VolumeTarget.CALL) ?: return@launch
            if (!applyLocalStream(VolumeTarget.CALL, fraction)) {
                _state.value = _state.value.copy(
                    lastApplyWarnings = _state.value.lastApplyWarnings + "Call volume could not be changed"
                )
            }
        }
    }

    /** Live device limits used by the profile editor's shared Nothing-style rails. */
    fun systemVolumeBars(dotConfig: VolumeDotScaleConfig): Map<VolumeTarget, VolumeBarModel> {
        val route = outputMonitor.state.value
        val localReferenceMax = PROFILE_SYSTEM_TARGETS.maxOfOrNull { target ->
            runCatching { audioManager.getStreamMaxVolume(target.streamType) }.getOrDefault(1)
        }?.coerceAtLeast(1) ?: 16
        return PROFILE_SYSTEM_TARGETS.associateWith { target ->
            val remoteMedia = target == VolumeTarget.MEDIA &&
                route.descriptor.kind == OutputKind.CAST && route.remoteMaxVolume > 0
            val min = if (remoteMedia) 0 else {
                runCatching { audioManager.getStreamMinVolume(target.streamType) }.getOrDefault(0)
            }
            val max = if (remoteMedia) route.remoteMaxVolume else {
                runCatching { audioManager.getStreamMaxVolume(target.streamType) }.getOrDefault(min)
            }
            val reference = if (remoteMedia) max.coerceAtLeast(1) else localReferenceMax
            VolumeBarModel(
                target = target,
                aliases = setOf(target.streamType),
                label = target.label,
                currentVolume = if (remoteMedia) route.remoteVolume else {
                    runCatching { audioManager.getStreamVolume(target.streamType) }.getOrDefault(min)
                },
                minVolume = min,
                maxVolume = max,
                active = true,
                enabled = target.userAdjustable && (!remoteMedia || route.remoteVolumeVariable),
                referenceMaxVolume = reference,
                dotCount = dotConfig.resolvedDotCount(reference)
            )
        }
    }

    private suspend fun onSettledOutput(output: OutputDeviceDescriptor) {
        flushAutoSave()
        val store = preferences.profileStore.first()
        when (val action = OutputProfilePolicy.resolve(store, output.key, ignoredUntilRouteLeaves)) {
            OutputProfileAction.Ignore -> Unit
            OutputProfileAction.Clear -> preferences.updateProfileStore {
                it.copy(activeProfileId = null, activeDraft = null)
            }
            OutputProfileAction.Create -> createDeviceProfile(output)
            is OutputProfileAction.Activate -> activateProfile(action.profileId)
        }
    }

    private suspend fun createDeviceProfile(output: OutputDeviceDescriptor) {
        val store = preferences.profileStore.first()
        val snapshot = captureSnapshot()
        val now = System.currentTimeMillis()
        val name = uniqueName(output.displayName, store)
        val profile = AudioProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            saveMode = ProfileSaveMode.AUTO_DEVICE,
            snapshot = snapshot,
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        preferences.updateProfileStore {
            it.copy(
                profiles = it.profiles + (profile.id to profile),
                devices = it.devices + (output.key to KnownOutputDevice(output, profile.id)),
                activeProfileId = profile.id,
                activeDraft = snapshot
            )
        }
    }

    private fun uniqueName(base: String, store: ProfileStore): String {
        val clean = base.trim().ifBlank { "Audio profile" }.take(36)
        if (store.profiles.values.none { it.name.equals(clean, true) }) return clean
        var suffix = 2
        while (store.profiles.values.any { it.name.equals("$clean ($suffix)", true) }) suffix++
        return "$clean ($suffix)".take(40)
    }

    private suspend fun captureSnapshot(): AudioProfileSnapshot {
        val settings = preferences.getAppSettingsSnapshot()
        val draftApps = preferences.profileStore.first().activeSnapshot?.appVolumes.orEmpty()
        val appVolumes = draftApps + settings.mapValues { (identity, setting) ->
            draftApps[identity] ?: setting.defaultVolume
        }
        val route = outputMonitor.state.value
        val streams = PROFILE_SYSTEM_TARGETS.associateWith { target ->
            if (target == VolumeTarget.MEDIA && route.descriptor.kind == OutputKind.CAST && route.remoteMaxVolume > 0) {
                route.remoteVolume.toFloat() / route.remoteMaxVolume
            } else {
                val min = runCatching { audioManager.getStreamMinVolume(target.streamType) }.getOrDefault(0)
                val max = runCatching { audioManager.getStreamMaxVolume(target.streamType) }.getOrDefault(min)
                val current = runCatching { audioManager.getStreamVolume(target.streamType) }.getOrDefault(min)
                normalizedVolume(current, min, max)
            }.coerceIn(0f, 1f)
        }
        return AudioProfileSnapshot(
            appVolumes = appVolumes,
            systemVolumes = streams,
            ringerMode = NotificationAlertMode.resolve(audioManager.ringerMode),
            dndEnabled = dndController.active.value.takeIf { dndController.hasPolicyAccess() }
        )
    }

    private suspend fun applySnapshot(snapshot: AudioProfileSnapshot): List<String> {
        val warnings = mutableListOf<String>()
        val topology = shizukuVolumeManager.getStreamTopology()
        val seenCanonical = mutableSetOf<Int>()
        val ringerPlan = profileRingerApplyPlan(snapshot.ringerMode)
        val localVolumesNeedingVerification = mutableListOf<Pair<VolumeTarget, Float>>()

        // Setting Loud can restore a remembered alert volume. Do it before applying
        // the saved Ring/Notifications levels so independently routed streams are
        // not collapsed back onto that remembered value.
        if (ringerPlan.applyBeforeVolumes) {
            ringerExecutor.setProductionAlertMode(snapshot.ringerMode, VolumeTarget.RING.streamType)
        }
        snapshot.systemVolumes.forEach { (target, fraction) ->
            val canonical = topology?.canonicalStream(target.streamType) ?: target.streamType
            if (!seenCanonical.add(canonical)) return@forEach
            val route = outputMonitor.state.value
            val applied = if (target == VolumeTarget.MEDIA && route.descriptor.kind == OutputKind.CAST) {
                if (route.remoteVolumeVariable && route.remoteMaxVolume > 0) {
                    outputMonitor.setRemoteVolume((fraction * route.remoteMaxVolume).toInt())
                } else false
            } else applyLocalStream(target, fraction)
            if (!applied && !(target == VolumeTarget.MEDIA && route.descriptor.kind == OutputKind.CAST)) {
                localVolumesNeedingVerification += target to fraction
            } else if (!applied) {
                warnings += "${target.label} volume could not be changed"
            }
        }

        // Alert-volume controls can promote the phone to Loud. Reassert non-Loud
        // modes after the levels are in place; unlike Loud, these modes do not
        // restore and overwrite an independently saved stream volume.
        if (ringerPlan.reapplyAfterVolumes) {
            ringerExecutor.setProductionAlertMode(snapshot.ringerMode, VolumeTarget.RING.streamType)
        }
        snapshot.dndEnabled?.let { enabled ->
            when (dndController.setActive(enabled)) {
                DndOperationResult.APPLIED -> Unit
                DndOperationResult.ACCESS_REQUIRED -> warnings += "Do Not Disturb access is required"
                else -> warnings += "Do Not Disturb could not be changed"
            }
        }
        warnings += settledAudioWarnings(
            expectedRingerMode = snapshot.ringerMode,
            requestedVolumes = localVolumesNeedingVerification
        )
        return warnings
    }

    private suspend fun settledAudioWarnings(
        expectedRingerMode: NotificationAlertMode,
        requestedVolumes: List<Pair<VolumeTarget, Float>>
    ): List<String> {
        var modeMatches = false
        var unresolvedVolumes = requestedVolumes
        repeat(6) {
            delay(100L)
            modeMatches = NotificationAlertMode.resolve(audioManager.ringerMode) == expectedRingerMode
            unresolvedVolumes = requestedVolumes.filter { (target, fraction) ->
                val min = runCatching { audioManager.getStreamMinVolume(target.streamType) }
                    .getOrDefault(0)
                val max = runCatching { audioManager.getStreamMaxVolume(target.streamType) }
                    .getOrDefault(min)
                val expected = volumeIndex(fraction, min, max)
                val observed = runCatching { audioManager.getStreamVolume(target.streamType) }
                    .getOrDefault(Int.MIN_VALUE)
                !profileVolumeSettled(
                    target = target,
                    expectedRingerMode = expectedRingerMode,
                    ringerModeMatches = modeMatches,
                    observedVolume = observed,
                    expectedVolume = expected
                )
            }
            if (modeMatches && unresolvedVolumes.isEmpty()) {
                return emptyList()
            }
        }
        return buildList {
            if (!modeMatches) add("Ringer mode could not be changed")
            unresolvedVolumes.forEach { (target, _) ->
                add("${target.label} volume could not be changed")
            }
        }
    }

    private fun applyLocalStream(target: VolumeTarget, fraction: Float): Boolean {
        val min = runCatching { audioManager.getStreamMinVolume(target.streamType) }.getOrDefault(0)
        val max = runCatching { audioManager.getStreamMaxVolume(target.streamType) }.getOrDefault(min)
        val index = volumeIndex(fraction, min, max)
        return if (target == VolumeTarget.RING || target == VolumeTarget.NOTIFICATION) {
            runCatching { ringerExecutor.setAlertVolumeFromControl(target.streamType, index) }.getOrDefault(false)
        } else runCatching {
            audioManager.setStreamVolume(target.streamType, index, 0)
            audioManager.getStreamVolume(target.streamType) == index
        }.getOrDefault(false)
    }

    private fun scheduleAutoSave() {
        if (applying) return
        state.value.activeProfile ?: return
        autoSaveJob?.cancel()
        autoSaveJob = scope.launch {
            delay(500L)
            val store = preferences.profileStore.first()
            val profile = store.activeProfile ?: return@launch
            val snapshot = captureSnapshot()
            if (preferences.automaticallySaveProfileChanges.first()) {
                val updated = profile.copy(snapshot = snapshot, updatedAtEpochMs = System.currentTimeMillis())
                preferences.updateProfileStore {
                    it.copy(profiles = it.profiles + (profile.id to updated), activeDraft = snapshot)
                }
            } else {
                preferences.updateProfileStore { it.copy(activeDraft = snapshot) }
            }
        }
    }

    private suspend fun flushAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
        if (applying) return
        val profile = preferences.profileStore.first().activeProfile ?: return
        if (!preferences.automaticallySaveProfileChanges.first()) return
        saveCurrentProfile()
    }
}
