package com.agentkosticka.amply.settings.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import com.agentkosticka.amply.profiles.AudioProfile
import com.agentkosticka.amply.profiles.AudioProfileSnapshot
import com.agentkosticka.amply.profiles.ProfileCodec
import com.agentkosticka.amply.profiles.ProfileSaveMode
import com.agentkosticka.amply.profiles.ProfileStore
import com.agentkosticka.amply.settings.model.AppSettingsStoreHealth
import com.agentkosticka.amply.settings.model.ImportMode
import com.agentkosticka.amply.settings.model.SettingsOperationResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProfilePreferencesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun rejectedMergeLeavesPrimaryBackupAndOtherSettingsIntact() = withStore { preferences, store ->
        assertEquals(SettingsOperationResult.Success, preferences.updateProfileStore { profiles(33) })
        val before = store.data.first()
        val imported = JSONObject(preferences.exportSettings())
            .put("profiles", JSONObject(ProfileCodec.encode(profiles(32, 33), false)))
            .put("automaticallySaveProfileChanges", false).toString()
        assertTrue(preferences.importSettings(imported, ImportMode.MERGE) is SettingsOperationResult.ValidationFailed)
        assertEquals(before, store.data.first())
        assertTrue(preferences.automaticallySaveProfileChanges.first())
    }

    @Test fun corruptProfilesRemainExportableAndBlockedUntilExplicitReplacement() = withStore { preferences, store ->
        val primary = stringPreferencesKey("audio_profiles_json")
        val backup = stringPreferencesKey("audio_profiles_json_backup")
        store.edit { it[primary] = "broken primary"; it[backup] = "broken backup" }
        val before = store.data.first()
        assertEquals(AppSettingsStoreHealth.CORRUPT, preferences.profileStoreResolution.first().health)
        assertEquals(SettingsOperationResult.StoreCorrupt, preferences.updateProfileStore { profiles(1) })
        assertEquals(before, store.data.first())
        val exported = JSONObject(preferences.exportSettings())
        assertEquals("broken primary", exported.getString("recoveryRawProfiles"))
        assertEquals("broken backup", exported.getString("recoveryRawProfilesBackup"))
        exported.put("profiles", JSONObject(ProfileCodec.encode(ProfileStore(), false)))
        assertEquals(SettingsOperationResult.Success, preferences.importSettings(exported.toString(), ImportMode.REPLACE))
        assertEquals(AppSettingsStoreHealth.HEALTHY, preferences.profileStoreResolution.first().health)
    }

    @Test fun resetRestoresAutomaticSavingDefault() = withStore { preferences, _ ->
        preferences.setAutomaticallySaveProfileChanges(false)
        assertFalse(preferences.automaticallySaveProfileChanges.first())
        assertEquals(SettingsOperationResult.Success, preferences.resetAllUserSettings())
        assertTrue(preferences.automaticallySaveProfileChanges.first())
    }

    private fun withStore(block: suspend (PreferencesManager, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit) = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val file = File(context.cacheDir, "profile-test-${UUID.randomUUID()}.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try { block(PreferencesManager(context, store), store) }
        finally {
            scope.cancel()
            job.join()
            file.delete()
        }
    }

    private fun profiles(count: Int, start: Int = 0) = ProfileStore((start until start + count).associate { n ->
        "p$n" to AudioProfile("p$n", "Profile $n", ProfileSaveMode.EXPLICIT, AudioProfileSnapshot(), 1, 1)
    })
}
