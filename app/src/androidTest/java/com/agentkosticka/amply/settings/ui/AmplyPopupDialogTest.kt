package com.agentkosticka.amply.settings.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agentkosticka.amply.ui.theme.AmplyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AmplyPopupDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun actionsUseCompleteLabelsAndEqualStackedRows() {
        composeRule.setContent {
            AmplyTheme {
                AmplyPopupDialog(
                    title = "Keep changes?",
                    body = "Save before leaving?",
                    onDismissRequest = {},
                    actions = listOf(
                        AmplyPopupAction("Keep changes", {}),
                        AmplyPopupAction(
                            "Discard changes",
                            {},
                            AmplyPopupActionTone.MUTED
                        )
                    )
                )
            }
        }

        composeRule.onNodeWithText("KEEP CHANGES").assertIsDisplayed()
        composeRule.onNodeWithText("DISCARD CHANGES").assertIsDisplayed()

        val keepBounds = composeRule
            .onNode(hasText("KEEP CHANGES") and hasClickAction())
            .fetchSemanticsNode()
            .boundsInRoot
        val discardBounds = composeRule
            .onNode(hasText("DISCARD CHANGES") and hasClickAction())
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue(keepBounds.top < discardBounds.top)
        assertEquals(keepBounds.left, discardBounds.left, 1f)
        assertEquals(keepBounds.right, discardBounds.right, 1f)
    }
}
