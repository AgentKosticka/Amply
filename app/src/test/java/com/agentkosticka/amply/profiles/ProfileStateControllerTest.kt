package com.agentkosticka.amply.profiles

import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.settings.model.AppIdentity
import com.agentkosticka.amply.settings.model.AppSettings
import com.agentkosticka.amply.settings.model.AppSettingsStoreHealth
import com.agentkosticka.amply.settings.model.SettingsOperationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileStateControllerTest {
    @Test fun startupRestoresBeforeObservingAndIdleDoesNotWrite() = runTest {
        val h = Harness(backgroundScope)
        h.audio = snapshot(0.9f)
        h.controller.start()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(snapshot(0.2f), h.audio)
        assertEquals(snapshot(0.2f), h.saved("speaker"))
        val writes = h.writes
        repeat(4) {
            h.controller.audioChanged()
            advanceTimeBy(1_000); runCurrent()
        }
        assertEquals(writes, h.writes)
    }

    @Test fun routeSwitchFlushesCachedDepartingVolumeInsteadOfNewDeviceVolume() = runTest {
        val h = Harness(backgroundScope)
        h.controller.start()
        advanceTimeBy(1_000); runCurrent()
        h.audio = snapshot(0.3f)
        h.controller.audioChanged()
        runCurrent()
        h.audio = snapshot(0.95f)
        h.route("headphones", OutputKind.BLUETOOTH)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(snapshot(0.3f), h.saved("speaker"))
        assertEquals(snapshot(0.7f), h.audio)
        h.audio = snapshot(0.8f)
        h.controller.audioChanged()
        runCurrent()
        h.audio = snapshot(0.99f)
        h.route("cast", OutputKind.CAST)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(snapshot(0.8f), h.saved("headphones"))
        assertEquals(snapshot(0.4f), h.audio)
    }

    @Test fun cancellationClearsApplyingAndNewRouteCanSettle() = runTest {
        val h = Harness(backgroundScope)
        h.controller.start()
        advanceTimeBy(1_000); runCurrent()
        h.applyDelay = 2_000
        h.route("headphones", OutputKind.BLUETOOTH)
        advanceTimeBy(800); runCurrent()
        assertTrue(h.controller.state.value.applying)
        h.route("cast", OutputKind.CAST)
        runCurrent()
        assertFalse(h.controller.state.value.applying)
        h.applyDelay = 0
        advanceTimeBy(1_000); runCurrent()
        assertEquals("cast", h.controller.state.value.store.activeProfileId)
        assertFalse(h.controller.state.value.applying)
        h.audio = snapshot(0.5f)
        h.controller.audioChanged()
        advanceTimeBy(600); runCurrent()
        assertEquals(snapshot(0.5f), h.saved("cast"))
    }

    @Test fun concurrentAppChangesMergeAndStaleEditsAreDropped() = runTest {
        val h = Harness(backgroundScope)
        h.controller.start()
        advanceTimeBy(1_000); runCurrent()
        val origin = h.controller.volumeContext()
        val a = AppIdentity(0, "com.example.a")
        val b = AppIdentity(0, "com.example.b")
        val first = launch { h.controller.recordAppVolume(a, 0.3f, origin) }
        val second = launch { h.controller.recordAppVolume(b, 0.6f, origin) }
        first.join(); second.join()
        assertEquals(mapOf(a to 0.3f, b to 0.6f), h.saved("speaker").appVolumes)
        h.controller.activateProfile("headphones")
        h.controller.recordAppVolume(a, 0.9f, origin)
        assertTrue(h.saved("headphones").appVolumes.isEmpty())
        assertEquals(0.3f, h.saved("speaker").appVolumes.getValue(a))
    }

    @Test fun storageFailureDoesNotApplyAudioOrClaimSuccess() = runTest {
        val h = Harness(backgroundScope)
        h.controller.start()
        advanceTimeBy(1_000); runCurrent()
        h.failure = SettingsOperationResult.IoFailed("Disk unavailable")
        val before = h.audio
        assertTrue(h.controller.activateProfile("headphones") is ProfileOperationResult.Failure)
        assertEquals(before, h.audio)
        assertEquals("speaker", h.controller.state.value.store.activeProfileId)
        assertEquals("Disk unavailable", h.controller.state.value.operationError)
        assertFalse(h.controller.state.value.applying)
    }

    @Test fun manualSavingKeepsDraftWithoutChangingSavedProfile() = runTest {
        val h = Harness(backgroundScope)
        h.automaticSaving.value = false
        h.controller.start()
        advanceTimeBy(1_000); runCurrent()
        h.audio = snapshot(0.6f)
        h.controller.audioChanged()
        advanceTimeBy(600); runCurrent()
        assertEquals(snapshot(0.2f), h.saved("speaker"))
        assertEquals(snapshot(0.6f), h.profiles.value.store.activeDraft)
        assertTrue(h.controller.saveCurrentProfile() is ProfileOperationResult.Success)
        assertEquals(snapshot(0.6f), h.saved("speaker"))
    }

    private class Harness(scope: CoroutineScope) : ProfilePersistence {
        override val profiles = MutableStateFlow(ProfileStoreResolution(initialStore(), AppSettingsStoreHealth.HEALTHY))
        override val apps = profiles.map { emptyMap<AppIdentity, AppSettings>() }
        override val automaticSaving = MutableStateFlow(true)
        val output = MutableStateFlow(OutputRouteState(descriptor("speaker", OutputKind.SPEAKER), generation = 1))
        var audio = snapshot(0.9f)
        var writes = 0
        var applyDelay = 0L
        var failure: SettingsOperationResult? = null
        val controller = ProfileStateController(this, output, scope, { audio }, {
            audio = it
            delay(applyDelay)
            emptyList()
        }, { writes.toLong() + 10 })

        override suspend fun update(transform: (ProfileStore) -> ProfileStore): SettingsOperationResult {
            failure?.let { return it }
            val next = transform(profiles.value.store)
            ProfileCodec.validatedEncoding(next)
            if (next != profiles.value.store) {
                writes++
                profiles.value = profiles.value.copy(store = next)
            }
            return SettingsOperationResult.Success
        }

        fun saved(id: String) = profiles.value.store.profiles.getValue(id).snapshot
        fun route(key: String, kind: OutputKind) {
            output.value = OutputRouteState(descriptor(key, kind), output.value.generation + 1)
        }
    }

    companion object {
        private fun snapshot(value: Float) = AudioProfileSnapshot(systemVolumes = mapOf(VolumeTarget.MEDIA to value))
        private fun descriptor(key: String, kind: OutputKind) = OutputDeviceDescriptor(key, kind, key, OutputIdentityQuality.STABLE)
        private fun initialStore(): ProfileStore {
            val entries = listOf("speaker" to 0.2f, "headphones" to 0.7f, "cast" to 0.4f)
            val profiles = entries.associate { (id, value) -> id to AudioProfile(id, id, ProfileSaveMode.AUTO_DEVICE, snapshot(value), 1, 1) }
            return ProfileStore(profiles, entries.associate { (id, _) ->
                id to KnownOutputDevice(descriptor(id, OutputKind.SPEAKER), id)
            }, "speaker", profiles.getValue("speaker").snapshot)
        }
    }
}
