package com.agentkosticka.amply.settings.ui

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import com.agentkosticka.amply.MainActivity
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.profiles.AudioProfile
import com.agentkosticka.amply.profiles.AudioProfileSnapshot
import com.agentkosticka.amply.profiles.ProfileSaveMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileEditorRecreationTest {
    @Test fun activityRecreationRetainsEditedVolumesAndAssignments() {
        val profile = AudioProfile("test-draft", "Original", ProfileSaveMode.EXPLICIT, AudioProfileSnapshot(), 1, 1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val draft = ViewModelProvider(activity)[ProfileEditorViewModel::class.java].open(profile, setOf("speaker"))
                draft.name = "Edited"
                draft.snapshot = draft.snapshot.copy(systemVolumes = mapOf(VolumeTarget.MEDIA to 0.3f), dndEnabled = true)
                draft.assigned = setOf("headphones")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[ProfileEditorViewModel::class.java]
                val draft = model.open(profile, setOf("speaker"))
                assertEquals("Edited", draft.name)
                assertEquals(0.3f, draft.snapshot.systemVolumes.getValue(VolumeTarget.MEDIA))
                assertEquals(true, draft.snapshot.dndEnabled)
                assertEquals(setOf("headphones"), draft.assigned)
                model.close(profile.id)
            }
        }
    }
}
