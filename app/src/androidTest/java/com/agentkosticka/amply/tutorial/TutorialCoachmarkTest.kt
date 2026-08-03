package com.agentkosticka.amply.tutorial

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.agentkosticka.amply.ui.theme.AmplyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TutorialCoachmarkTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun coachmarkAlwaysOffersNextAndRestoresWithBack() {
        var action = ""
        compose.setContent {
            AmplyTheme {
                TutorialCoachmarkCard(
                    step = 2,
                    total = 6,
                    title = "Demo title",
                    body = "Demo explanation",
                    canGoBack = true,
                    onBack = { action = "back" },
                    onNext = { action = "next" },
                    onSkip = { action = "skip" }
                )
            }
        }

        compose.onNodeWithText("Next").performClick()
        compose.runOnIdle { assertEquals("next", action) }
        compose.onNodeWithText("Back").performClick()
        compose.runOnIdle { assertEquals("back", action) }
        compose.onNodeWithText("Skip tutorial").performClick()
        compose.runOnIdle { assertEquals("skip", action) }
    }
}
