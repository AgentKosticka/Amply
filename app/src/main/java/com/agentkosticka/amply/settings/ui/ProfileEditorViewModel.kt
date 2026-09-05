package com.agentkosticka.amply.settings.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.agentkosticka.amply.profiles.AudioProfile

internal class ProfileEditorViewModel : ViewModel() {
    private val drafts = mutableMapOf<String, ProfileEditorDraft>()

    fun open(profile: AudioProfile, assigned: Set<String>): ProfileEditorDraft =
        drafts.getOrPut(profile.id) { ProfileEditorDraft(profile, assigned) }

    fun close(id: String) { drafts.remove(id) }
}

internal class ProfileEditorDraft(val original: AudioProfile, val originalAssigned: Set<String>) {
    var name by mutableStateOf(original.name)
    var snapshot by mutableStateOf(original.snapshot)
    var assigned by mutableStateOf(originalAssigned)
}
