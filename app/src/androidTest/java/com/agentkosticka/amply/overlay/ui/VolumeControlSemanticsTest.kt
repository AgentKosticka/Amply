package com.agentkosticka.amply.overlay.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.agentkosticka.amply.ui.theme.AmplyTheme
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
            .performSemanticsAction(SemanticsActions.SetProgress) { action -> action(0.8f) }
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
}
