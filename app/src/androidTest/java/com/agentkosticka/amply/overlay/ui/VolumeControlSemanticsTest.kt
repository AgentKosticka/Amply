package com.agentkosticka.amply.overlay.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.agentkosticka.amply.ui.theme.AmplyTheme
import com.agentkosticka.amply.audio.routing.VolumeBarModel
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState
import com.agentkosticka.amply.profiles.AudioProfile
import com.agentkosticka.amply.profiles.AudioProfileSnapshot
import com.agentkosticka.amply.profiles.ProfileSaveMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VolumeControlSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sliderExposesProgressActionAndTouchTarget() {
        var changedTo = -1
        compose.setContent {
            AmplyTheme {
                DraggableDotSlider(
                    currentVolume = 2,
                    maxVolume = 10,
                    onVolumeChange = { changedTo = it },
                    accessibilityLabel = "Test volume"
                )
            }
        }

        compose.onNodeWithContentDescription("Test volume")
            .assertHeightIsAtLeast(48.dp)
            .performSemanticsAction(SemanticsActions.SetProgress) { action -> action(8f) }
        compose.runOnIdle { assertEquals(8, changedTo) }
    }

    @Test fun disabledSliderCannotBeAdjusted() {
        compose.setContent {
            AmplyTheme {
                DraggableDotSlider(
                    currentVolume = 2,
                    maxVolume = 10,
                    onVolumeChange = {},
                    enabled = false,
                    accessibilityLabel = "Disabled volume"
                )
            }
        }
        compose.onNodeWithContentDescription("Disabled volume").assertIsNotEnabled()
    }

    @Test fun hiddenStandDownButtonIsRemovedFromTheOverlay() {
        compose.setContent {
            AmplyTheme {
                VolumeOverlay(
                    volumeBars = listOf(
                        VolumeBarModel(
                            target = VolumeTarget.MEDIA,
                            aliases = setOf(VolumeTarget.MEDIA.streamType),
                            label = "Media",
                            currentVolume = 8,
                            minVolume = 0,
                            maxVolume = 16,
                            active = true,
                            enabled = true
                        )
                    ),
                    selectedTarget = VolumeTarget.MEDIA,
                    expanded = true,
                    showStandDownButton = false,
                    showShizukuDisconnectedWarning = false,
                    shizukuConnectionState = VolumeServiceConnectionState.CONNECTED
                )
            }
        }

        compose.onAllNodesWithContentDescription("Pause Amply temporarily").assertCountEquals(0)
    }

    @Test fun expandedOverlayOpensProfileSelectorFromItsOwnActionButton() {
        val profile = AudioProfile(
            id = "night",
            name = "Night",
            saveMode = ProfileSaveMode.EXPLICIT,
            snapshot = AudioProfileSnapshot(),
            createdAtEpochMs = 0L,
            updatedAtEpochMs = 0L
        )
        compose.setContent {
            AmplyTheme {
                VolumeOverlay(
                    expanded = true,
                    profiles = listOf(profile),
                    activeProfileId = profile.id,
                    showStandDownButton = false,
                    showShizukuDisconnectedWarning = false,
                    shizukuConnectionState = VolumeServiceConnectionState.CONNECTED
                )
            }
        }

        compose.onNodeWithContentDescription("Switch profile").performClick()
        compose.onNodeWithText("Night").assertIsDisplayed()
    }

    @Test fun collapsedRingerPillKeepsDndButtonVisible() {
        compose.setContent {
            AmplyTheme {
                VolumeOverlay(
                    volumeBars = listOf(
                        VolumeBarModel(
                            target = VolumeTarget.RING,
                            aliases = setOf(VolumeTarget.RING.streamType),
                            label = "Ring",
                            currentVolume = 0,
                            minVolume = 0,
                            maxVolume = 7,
                            active = true,
                            enabled = true
                        )
                    ),
                    selectedTarget = VolumeTarget.RING,
                    expanded = false,
                    showDndButton = true,
                    dndActive = false,
                    showShizukuDisconnectedWarning = false,
                    shizukuConnectionState = VolumeServiceConnectionState.CONNECTED
                )
            }
        }

        compose.onAllNodesWithContentDescription("Turn Do Not Disturb on").assertCountEquals(1)
    }
}
