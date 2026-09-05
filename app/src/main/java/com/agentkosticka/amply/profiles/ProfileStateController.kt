package com.agentkosticka.amply.profiles

import com.agentkosticka.amply.settings.model.AppIdentity
import com.agentkosticka.amply.settings.model.AppSettings
import com.agentkosticka.amply.settings.model.SettingsOperationResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface ProfilePersistence {
    val profiles: Flow<ProfileStoreResolution>
    val apps: Flow<Map<AppIdentity, AppSettings>>
    val automaticSaving: Flow<Boolean>
    suspend fun update(transform: (ProfileStore) -> ProfileStore): SettingsOperationResult
}

internal class ProfileStateController(
    private val persistence: ProfilePersistence,
    private val output: StateFlow<OutputRouteState>,
    private val scope: CoroutineScope,
    private val captureAudio: suspend () -> AudioProfileSnapshot,
    private val applyAudio: suspend (AudioProfileSnapshot) -> List<String>,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ProfileRuntimeState())
    val state = mutableState.asStateFlow()
    private val volumes = MutableStateFlow<Map<AppIdentity, Float>>(emptyMap())
    val effectiveAppVolumes = volumes.asStateFlow()
    @Volatile private var context = ProfileVolumeContext(null, -1, 0)
    private var settledGeneration = -1L
    private var observed: Pair<ProfileVolumeContext, AudioProfileSnapshot>? = null
    private var ignoredOutput: String? = null
    private var routeJob: Job? = null
    private var saveJob: Job? = null
    private var started = false

    fun volumeContext(): ProfileVolumeContext = context.copy(outputGeneration = output.value.generation)

    fun start() {
        if (started) return
        started = true
        scope.launch {
            persistence.profiles.collect { resolution ->
                mutex.withLock { publish(resolution) }
            }
        }
        scope.launch {
            persistence.apps.map { apps -> apps.mapValues { it.value.defaultVolume } }
                .distinctUntilChanged().collect { audioChanged() }
        }
        scope.launch {
            output.collect { route ->
                if (route.generation != context.outputGeneration) {
                    context = context.copy(outputGeneration = route.generation, revision = context.revision + 1)
                    routeJob?.cancel()
                    saveJob?.cancel()
                    routeJob = scope.launch {
                        delay(750)
                        operation {
                            if (route.generation != output.value.generation) return@operation Unit
                            flushObserved()
                            observed = null
                            if (ignoredOutput != route.descriptor.key) ignoredOutput = null
                            val store = load()
                            when (val action = OutputProfilePolicy.resolve(store, route.descriptor.key, ignoredOutput)) {
                                OutputProfileAction.Ignore -> Unit
                                OutputProfileAction.Clear -> commit { it.copy(activeProfileId = null, activeDraft = null) }
                                OutputProfileAction.Create -> {
                                    val snapshot = capture(store)
                                    val profile = newProfile(uniqueName(route.descriptor.displayName, store), snapshot, ProfileSaveMode.AUTO_DEVICE)
                                    commit {
                                        it.copy(
                                            profiles = it.profiles + (profile.id to profile),
                                            devices = it.devices + (route.descriptor.key to KnownOutputDevice(route.descriptor, profile.id)),
                                            activeProfileId = profile.id, activeDraft = snapshot
                                        )
                                    }
                                }
                                is OutputProfileAction.Activate -> activate(action.profileId)
                            }
                            if (route.generation == output.value.generation) {
                                settledGeneration = route.generation
                                rememberObservation()
                            }
                        }
                    }
                } else audioChanged()
                mutableState.update { it.copy(currentOutput = route.descriptor) }
            }
        }
    }

    fun audioChanged() {
        val requested = volumeContext()
        if (mutableState.value.applying || settledGeneration != requested.outputGeneration) return
        scope.launch {
            mutex.withLock {
                if (!isCurrent(requested) || mutableState.value.applying) return@withLock
                val snapshot = capture(load())
                if (!isCurrent(requested)) return@withLock
                observed = requested to snapshot
                saveJob?.cancel()
                saveJob = scope.launch {
                    delay(500)
                    operation { if (isCurrent(requested)) flushObserved() }
                }
            }
        }
    }

    suspend fun activateProfile(id: String) = operation {
        flushObserved()
        context = context.copy(revision = context.revision + 1)
        observed = null
        activate(id)
        settledGeneration = output.value.generation
        rememberObservation()
    }

    private suspend fun activate(id: String) {
        val profile = load().profiles[id] ?: invalid("Profile not found")
        val generation = output.value.generation
        commit { it.copy(activeProfileId = id, activeDraft = profile.snapshot) }
        if (generation != output.value.generation) throw CancellationException("Output changed")
        mutableState.update { it.copy(applying = true, lastApplyWarnings = emptyList()) }
        try {
            val warnings = applyAudio(profile.snapshot)
            if (generation != output.value.generation) throw CancellationException("Output changed")
            mutableState.update { it.copy(lastApplyWarnings = warnings) }
        } finally {
            mutableState.update { it.copy(applying = false) }
        }
    }

    suspend fun createNamedProfile(name: String) = operation {
        val store = load()
        val normalized = checkedName(name, store)
        val profile = newProfile(normalized, capture(store), ProfileSaveMode.EXPLICIT)
        commit { it.copy(profiles = it.profiles + (profile.id to profile)) }
        profile
    }

    suspend fun onCallBecameActive(apply: (Float) -> Boolean) = operation {
        val fraction = load().activeSnapshot?.systemVolumes
            ?.get(com.agentkosticka.amply.audio.routing.VolumeTarget.CALL) ?: return@operation
        if (!apply(fraction)) mutableState.update {
            it.copy(lastApplyWarnings = it.lastApplyWarnings + "Call volume could not be changed")
        }
    }

    suspend fun saveCurrentProfile() = operation {
        if (settledGeneration != output.value.generation) invalid("Wait for the output to finish changing")
        rememberObservation()
        flushObserved(force = true)
    }

    suspend fun updateProfile(id: String, name: String, snapshot: AudioProfileSnapshot) = operation {
        val store = load()
        val normalized = checkedName(name, store, id)
        if (id !in store.profiles) invalid("Profile not found")
        commit {
            val profile = it.profiles[id] ?: invalid("Profile not found")
            it.copy(
                profiles = it.profiles + (id to profile.copy(name = normalized, snapshot = snapshot, updatedAtEpochMs = now())),
                activeDraft = if (it.activeProfileId == id) snapshot else it.activeDraft
            )
        }
        if (store.activeProfileId == id) {
            context = context.copy(revision = context.revision + 1)
            observed = null
            activate(id)
            rememberObservation()
        }
    }

    suspend fun assignDevice(key: String, id: String?) = operation {
        flushObserved()
        commit {
            require(key in it.devices) { "Output device not found" }
            require(id == null || id in it.profiles) { "Profile not found" }
            it.withDeviceAssignment(key, id, output.value.descriptor.key)
        }
        if (key == output.value.descriptor.key) {
            observed = null
            context = context.copy(revision = context.revision + 1)
            if (id != null) activate(id)
            rememberObservation()
        }
    }

    suspend fun deleteProfile(id: String) = operation {
        val active = load().activeProfileId == id
        commit { store ->
            store.copy(
                profiles = store.profiles - id,
                devices = store.devices.mapValues { (_, device) ->
                    if (device.assignedProfileId == id) device.copy(assignedProfileId = null) else device
                },
                activeProfileId = if (store.activeProfileId == id) null else store.activeProfileId,
                activeDraft = if (store.activeProfileId == id) null else store.activeDraft
            )
        }
        if (active) {
            ignoredOutput = output.value.descriptor.key
            observed = null
        }
    }

    suspend fun forgetDevice(key: String) = operation {
        commit { store ->
            val device = store.devices[key] ?: return@commit store
            val profile = device.assignedProfileId?.let(store.profiles::get)
            val removeId = profile?.id?.takeIf {
                profile.saveMode == ProfileSaveMode.AUTO_DEVICE &&
                    store.devices.values.count { it.assignedProfileId == profile.id } == 1
            }
            store.copy(
                devices = store.devices - key,
                profiles = removeId?.let(store.profiles::minus) ?: store.profiles,
                activeProfileId = if (store.activeProfileId == removeId) null else store.activeProfileId,
                activeDraft = if (store.activeProfileId == removeId) null else store.activeDraft
            )
        }
        if (key == output.value.descriptor.key) {
            ignoredOutput = key
            observed = null
        }
    }

    // False means a current, deliberately unprofiled edit may use global preferences.
    suspend fun recordAppVolume(identity: AppIdentity, volume: Float, origin: ProfileVolumeContext) = operation {
        load()
        if (!isCurrent(origin)) return@operation true
        val id = origin.profileId ?: return@operation false
        require(volume.isFinite() && volume in 0f..1f) { "Invalid app volume" }
        val automatic = persistence.automaticSaving.first()
        commit { store ->
            if (!isCurrent(origin) || store.activeProfileId != id) return@commit store
            val profile = store.profiles[id] ?: return@commit store
            val snapshot = (store.activeSnapshot ?: profile.snapshot).let {
                it.copy(appVolumes = it.appVolumes + (identity to volume))
            }
            store.copy(
                profiles = if (automatic && profile.snapshot != snapshot) {
                    store.profiles + (id to profile.copy(snapshot = snapshot, updatedAtEpochMs = now()))
                } else store.profiles,
                activeDraft = snapshot
            )
        }
        true
    }

    private fun isCurrent(origin: ProfileVolumeContext) = origin == volumeContext()

    private suspend fun capture(store: ProfileStore): AudioProfileSnapshot {
        val apps = persistence.apps.first().mapValues { it.value.defaultVolume } + store.activeSnapshot?.appVolumes.orEmpty()
        return captureAudio().copy(appVolumes = apps)
    }

    private suspend fun rememberObservation() {
        val origin = volumeContext()
        val snapshot = capture(load())
        if (isCurrent(origin)) observed = origin to snapshot
    }

    private suspend fun flushObserved(force: Boolean = false) {
        val (origin, audio) = observed ?: return
        val id = origin.profileId ?: return
        val automatic = force || persistence.automaticSaving.first()
        commit { store ->
            val profile = store.profiles[id] ?: return@commit store
            if (store.activeProfileId != id) return@commit store
            val snapshot = audio.copy(appVolumes = audio.appVolumes + store.activeSnapshot?.appVolumes.orEmpty())
            store.copy(
                profiles = if (automatic && profile.snapshot != snapshot) {
                    store.profiles + (id to profile.copy(snapshot = snapshot, updatedAtEpochMs = now()))
                } else store.profiles,
                activeDraft = snapshot
            )
        }
    }

    private suspend fun load(): ProfileStore = persistence.profiles.first().also(::publish).store

    private fun publish(resolution: ProfileStoreResolution) {
        val id = resolution.store.activeProfileId
        if (id != context.profileId) context = context.copy(profileId = id, revision = context.revision + 1)
        mutableState.update { it.copy(store = resolution.store, storeHealth = resolution.health) }
        volumes.value = resolution.store.activeSnapshot?.appVolumes.orEmpty()
    }

    private suspend fun commit(transform: (ProfileStore) -> ProfileStore) {
        val result = persistence.update(transform)
        if (result != SettingsOperationResult.Success) throw ProfileWriteException(result)
        load()
    }

    private suspend fun <T> operation(block: suspend () -> T): ProfileOperationResult<T> = mutex.withLock {
        try {
            val value = block()
            mutableState.update { it.copy(operationError = null) }
            ProfileOperationResult.Success(value)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val result = when (error) {
                is ProfileWriteException -> error.result
                is IllegalArgumentException -> SettingsOperationResult.ValidationFailed(error.message ?: "Invalid profile")
                else -> SettingsOperationResult.IoFailed("Profile operation failed. Please retry.")
            }
            val message = when (result) {
                is SettingsOperationResult.ValidationFailed -> result.reason
                is SettingsOperationResult.IoFailed -> result.reason
                else -> "Profile data needs recovery. Export your settings before replacing or resetting them."
            }
            mutableState.update { it.copy(operationError = message) }
            ProfileOperationResult.Failure(result)
        }
    }

    private fun checkedName(name: String, store: ProfileStore, id: String? = null): String = name.trim().also { clean ->
        require(clean.isNotEmpty() && clean.length <= 40) { "Use a profile name of 1 to 40 characters" }
        require(store.profiles.values.none { it.id != id && it.name.equals(clean, true) }) { "A profile with that name already exists" }
    }

    private fun uniqueName(name: String, store: ProfileStore): String {
        val base = name.trim().ifBlank { "Audio profile" }.take(30)
        var candidate = base
        var suffix = 2
        while (store.profiles.values.any { it.name.equals(candidate, true) }) candidate = "$base (${suffix++})"
        return candidate
    }

    private fun newProfile(name: String, snapshot: AudioProfileSnapshot, mode: ProfileSaveMode) =
        AudioProfile(UUID.randomUUID().toString(), name, mode, snapshot, now(), now())

    private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)
    private class ProfileWriteException(val result: SettingsOperationResult) : Exception()
}
