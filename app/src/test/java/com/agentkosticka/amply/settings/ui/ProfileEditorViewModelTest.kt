package com.agentkosticka.amply.settings.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.profiles.AudioProfile
import com.agentkosticka.amply.profiles.AudioProfileSnapshot
import com.agentkosticka.amply.profiles.ProfileSaveMode
import org.junit.Assert.*
import org.junit.Test

class ProfileEditorViewModelTest {
    @Test fun recreatedOwnerRetainsEveryDraftFieldUntilExplicitClose() {
        val store = ViewModelStore()
        fun owner() = object : ViewModelStoreOwner { override val viewModelStore = store }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = ProfileEditorViewModel() as T
        }
        val profile = AudioProfile("p", "Original", ProfileSaveMode.EXPLICIT, AudioProfileSnapshot(), 1, 1)
        val first = ViewModelProvider(owner(), factory)[ProfileEditorViewModel::class.java]
        val draft = first.open(profile, setOf("speaker"))
        draft.name = "Edited"
        draft.snapshot = draft.snapshot.copy(systemVolumes = mapOf(VolumeTarget.MEDIA to 0.3f), dndEnabled = true)
        draft.assigned = setOf("headphones")
        val recreated = ViewModelProvider(owner(), factory)[ProfileEditorViewModel::class.java]
        val restored = recreated.open(profile, setOf("speaker"))
        assertSame(draft, restored)
        assertEquals("Edited", restored.name)
        assertEquals(0.3f, restored.snapshot.systemVolumes.getValue(VolumeTarget.MEDIA))
        assertEquals(true, restored.snapshot.dndEnabled)
        assertEquals(setOf("headphones"), restored.assigned)
        recreated.close("p")
        assertEquals(profile.snapshot, recreated.open(profile, emptySet()).snapshot)
    }
}
