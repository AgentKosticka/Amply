package com.agentkosticka.amply.profiles

import com.agentkosticka.amply.settings.model.AppSettingsStoreHealth
import org.junit.Assert.*
import org.junit.Test

class ProfileStorageValidationTest {
    @Test fun acceptsLimitAndRejectsAnAdditionalProfile() {
        val valid = store(64)
        assertEquals(valid, ProfileCodec.decode(ProfileCodec.validatedEncoding(valid)))
        assertThrows(IllegalArgumentException::class.java) { ProfileCodec.validatedEncoding(store(65)) }
    }

    @Test fun rejectsMergedStoresThatIndividuallyFit() {
        val local = store(33)
        val imported = store(32, start = 33)
        ProfileCodec.validatedEncoding(local)
        ProfileCodec.validatedEncoding(imported)
        assertThrows(IllegalArgumentException::class.java) {
            ProfileCodec.validatedEncoding(local.copy(profiles = local.profiles + imported.profiles))
        }
    }

    @Test fun recoveryDistinguishesBackupFromUnrecoverableData() {
        val good = ProfileCodec.validatedEncoding(store(1))
        assertEquals(AppSettingsStoreHealth.HEALTHY, ProfileCodec.resolve(good, null).health)
        val backup = ProfileCodec.resolve("broken", good)
        assertEquals(AppSettingsStoreHealth.RECOVERED_FROM_BACKUP, backup.health)
        assertEquals(store(1), backup.store)
        val missingPrimary = ProfileCodec.resolve(null, good)
        assertEquals(AppSettingsStoreHealth.RECOVERED_FROM_BACKUP, missingPrimary.health)
        assertEquals(store(1), missingPrimary.store)
        assertEquals(AppSettingsStoreHealth.HEALTHY, ProfileCodec.resolve(null, null).health)
        assertEquals(AppSettingsStoreHealth.CORRUPT, ProfileCodec.resolve("broken", null).health)
        assertEquals(AppSettingsStoreHealth.CORRUPT, ProfileCodec.resolve("broken", "broken").health)
    }

    @Test fun rejectsInvalidOutputAssignmentsAndAppIdentities() {
        val base = store(1)
        val device = OutputDeviceDescriptor("device", OutputKind.BLUETOOTH, "Headphones", OutputIdentityQuality.STABLE)
        assertThrows(IllegalArgumentException::class.java) {
            ProfileCodec.validatedEncoding(base.copy(devices = mapOf("device" to KnownOutputDevice(device, "missing"))))
        }
        val profile = base.profiles.values.single()
        val malformed = profile.copy(snapshot = AudioProfileSnapshot(appVolumes = mapOf(
            com.agentkosticka.amply.settings.model.AppIdentity(-1, "invalid package") to 0.5f
        )))
        assertThrows(IllegalArgumentException::class.java) {
            ProfileCodec.validatedEncoding(base.copy(profiles = mapOf(malformed.id to malformed)))
        }
    }

    private fun store(count: Int, start: Int = 0) = ProfileStore(profiles = (start until start + count).associate { n ->
        "p$n" to AudioProfile("p$n", "Profile $n", ProfileSaveMode.EXPLICIT, AudioProfileSnapshot(), 1, 1)
    })
}
