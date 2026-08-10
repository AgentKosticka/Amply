package com.agentkosticka.amply.profiles

import com.agentkosticka.amply.audio.ringer.NotificationAlertMode
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.settings.model.AppIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCodecTest {
    @Test
    fun roundTripPreservesProfileAwareAppVolumesAndAssignments() {
        val personal = AppIdentity(0, "com.example.chat")
        val work = AppIdentity(10, "com.example.chat")
        val snapshot = AudioProfileSnapshot(
            appVolumes = mapOf(personal to 0.25f, work to 0.75f),
            systemVolumes = mapOf(VolumeTarget.MEDIA to 0.4f, VolumeTarget.RING to 0.2f),
            ringerMode = NotificationAlertMode.VIBRATIONS,
            dndEnabled = true
        )
        val profile = AudioProfile("profile-1", "Work", ProfileSaveMode.EXPLICIT, snapshot, 1L, 2L)
        val device = OutputDeviceDescriptor(
            "bluetooth:hashed",
            OutputKind.BLUETOOTH,
            "Headphones",
            OutputIdentityQuality.STABLE
        )
        val store = ProfileStore(
            profiles = mapOf(profile.id to profile),
            devices = mapOf(device.key to KnownOutputDevice(device, profile.id)),
            activeProfileId = profile.id,
            activeDraft = snapshot.copy(systemVolumes = snapshot.systemVolumes + (VolumeTarget.ALARM to 0.8f))
        )

        assertEquals(store, ProfileCodec.decode(ProfileCodec.encode(store)))
    }

    @Test
    fun roundTripPreservesExplicitNoAutomaticProfileAssignment() {
        val output = OutputDeviceDescriptor(
            "speaker",
            OutputKind.SPEAKER,
            "Phone speaker",
            OutputIdentityQuality.CATEGORY
        )
        val store = ProfileStore(
            devices = mapOf(
                output.key to KnownOutputDevice(
                    descriptor = output,
                    assignedProfileId = null,
                    explicitlyUnassigned = true
                )
            )
        )

        val decoded = ProfileCodec.decode(ProfileCodec.encode(store))

        assertNull(decoded.devices.getValue(output.key).assignedProfileId)
        assertTrue(decoded.devices.getValue(output.key).explicitlyUnassigned)
    }

    @Test
    fun exportFormOmitsTransientActiveProfileAndDraft() {
        val profile = AudioProfile(
            "p", "Night", ProfileSaveMode.EXPLICIT, AudioProfileSnapshot(), 1L, 1L
        )
        val decoded = ProfileCodec.decode(
            ProfileCodec.encode(
                ProfileStore(mapOf(profile.id to profile), activeProfileId = profile.id, activeDraft = profile.snapshot),
                includeTransient = false
            )
        )

        assertNull(decoded.activeProfileId)
        assertNull(decoded.activeDraft)
        assertEquals(profile, decoded.profiles[profile.id])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangeAppVolume() {
        ProfileCodec.decode(
            """{"schemaVersion":1,"profiles":[{"id":"p","name":"Bad","saveMode":"EXPLICIT","createdAt":0,"updatedAt":0,"snapshot":{"apps":{"0|com.example.app":1.5},"streams":{},"ringer":"LOUD","dnd":null}}],"devices":[]}"""
        )
    }
}

class OutputProfilePolicyTest {
    private val output = OutputDeviceDescriptor("speaker", OutputKind.SPEAKER, "Phone speaker", OutputIdentityQuality.CATEGORY)
    private val profile = AudioProfile("p", "Speaker", ProfileSaveMode.AUTO_DEVICE, AudioProfileSnapshot(), 0L, 0L)

    @Test
    fun createsForUnknownOutput() {
        assertEquals(OutputProfileAction.Create, OutputProfilePolicy.resolve(ProfileStore(), output.key, null))
    }

    @Test
    fun activatesAssignedProfile() {
        val store = ProfileStore(
            profiles = mapOf(profile.id to profile),
            devices = mapOf(output.key to KnownOutputDevice(output, profile.id))
        )
        assertEquals(OutputProfileAction.Activate(profile.id), OutputProfilePolicy.resolve(store, output.key, null))
    }

    @Test
    fun clearsForExplicitlyUnassignedOutput() {
        val store = ProfileStore(
            devices = mapOf(output.key to KnownOutputDevice(output, null, explicitlyUnassigned = true))
        )
        assertEquals(OutputProfileAction.Clear, OutputProfilePolicy.resolve(store, output.key, null))
    }

    @Test
    fun ignoresForgottenOutputUntilRouteLeaves() {
        assertEquals(OutputProfileAction.Ignore, OutputProfilePolicy.resolve(ProfileStore(), output.key, output.key))
    }

    @Test
    fun noAutomaticProfilePersistsAndClearsCurrentActivationImmediately() {
        val store = ProfileStore(
            profiles = mapOf(profile.id to profile),
            devices = mapOf(output.key to KnownOutputDevice(output, profile.id)),
            activeProfileId = profile.id,
            activeDraft = profile.snapshot
        )

        val updated = store.withDeviceAssignment(output.key, null, output.key)

        assertTrue(updated.devices.getValue(output.key).explicitlyUnassigned)
        assertEquals(null, updated.devices.getValue(output.key).assignedProfileId)
        assertEquals(null, updated.activeProfileId)
        assertEquals(null, updated.activeDraft)
    }
}

class ProfileVolumeMathTest {
    @Test
    fun normalizesAndRestoresAcrossDifferentRanges() {
        val fraction = normalizedVolume(current = 8, min = 2, max = 14)
        assertEquals(0.5f, fraction, 0.0001f)
        assertEquals(10, volumeIndex(fraction, min = 0, max = 20))
    }

    @Test
    fun clampsInvalidFractions() {
        assertEquals(0, volumeIndex(-1f, 0, 15))
        assertEquals(15, volumeIndex(2f, 0, 15))
        assertTrue(normalizedVolume(20, 0, 15) <= 1f)
        assertFalse(normalizedVolume(-5, 0, 15) < 0f)
    }
}

class ProfileRingerApplyPlanTest {
    @Test
    fun loudModeIsNeverAppliedAfterIndependentAlertVolumes() {
        val plan = profileRingerApplyPlan(NotificationAlertMode.LOUD)

        assertTrue(plan.applyBeforeVolumes)
        assertFalse(plan.reapplyAfterVolumes)
    }

    @Test
    fun silentAndVibrateAreRestoredAfterVolumeControls() {
        assertTrue(profileRingerApplyPlan(NotificationAlertMode.MUTED).reapplyAfterVolumes)
        assertTrue(profileRingerApplyPlan(NotificationAlertMode.VIBRATIONS).reapplyAfterVolumes)
    }
}

class ProfileDirtyStateTest {
    private val saved = AudioProfileSnapshot(
        systemVolumes = mapOf(VolumeTarget.MEDIA to 0.5f),
        appVolumes = mapOf(AppIdentity(0, "com.example.music") to 0.5f)
    )
    private val profile = AudioProfile("p", "Gaming", ProfileSaveMode.EXPLICIT, saved, 0L, 0L)

    @Test
    fun ignoresNativeStreamQuantizationButDetectsRealStep() {
        val quantized = saved.copy(systemVolumes = mapOf(VolumeTarget.MEDIA to 0.4667f))
        val changed = saved.copy(systemVolumes = mapOf(VolumeTarget.MEDIA to 0.40f))

        assertFalse(ProfileStore(mapOf("p" to profile), activeProfileId = "p", activeDraft = quantized).dirty)
        assertTrue(ProfileStore(mapOf("p" to profile), activeProfileId = "p", activeDraft = changed).dirty)
    }

    @Test
    fun profileAwareAppChangeIsAlwaysDirty() {
        val changed = saved.copy(appVolumes = saved.appVolumes + (AppIdentity(0, "com.example.music") to 0.6f))
        assertTrue(ProfileStore(mapOf("p" to profile), activeProfileId = "p", activeDraft = changed).dirty)
    }
}
