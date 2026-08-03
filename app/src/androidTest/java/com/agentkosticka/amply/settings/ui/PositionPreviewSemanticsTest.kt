package com.agentkosticka.amply.settings.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import com.agentkosticka.amply.settings.model.OverlaySide
import com.agentkosticka.amply.ui.theme.AmplyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PositionPreviewSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun positionRailSupportsSetProgress() {
        var changedTo = -1f
        compose.setContent {
            AmplyTheme {
                PositionPreview(
                    side = OverlaySide.RIGHT,
                    verticalFraction = 0.5f,
                    onFractionChange = { changedTo = it }
                )
            }
        }
        compose.onNodeWithContentDescription("Overlay vertical position")
            .performSemanticsAction(SemanticsActions.SetProgress) { action -> action(0.9f) }
        compose.runOnIdle { assertEquals(0.9f, changedTo, 0.001f) }
    }
}
