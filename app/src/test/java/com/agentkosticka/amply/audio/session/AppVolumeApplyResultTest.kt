package com.agentkosticka.amply.audio.session

import com.agentkosticka.amply.settings.model.AppIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class AppVolumeApplyResultTest {
    private val identity = AppIdentity(0, "com.example")

    @Test fun statesDescribeSavedFullPartialAndFailedUpdates() {
        assertEquals(AppVolumeControlState.SAVED_ONLY, AppVolumeApplyResult(identity, 0, 0).state)
        assertEquals(AppVolumeControlState.ACTIVE, AppVolumeApplyResult(identity, 2, 2).state)
        assertEquals(AppVolumeControlState.PARTIAL, AppVolumeApplyResult(identity, 2, 1).state)
        assertEquals(AppVolumeControlState.UNAVAILABLE, AppVolumeApplyResult(identity, 2, 0).state)
    }
}
