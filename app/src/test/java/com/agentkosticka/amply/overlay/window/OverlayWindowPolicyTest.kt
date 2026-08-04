package com.agentkosticka.amply.overlay.window

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayWindowPolicyTest {
    @Test
    fun overlayAcceptsTouchesWithoutTakingKeyboardFocus() {
        assertNotEquals(
            0,
            OverlayWindowPolicy.FLAGS and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        assertEquals(
            0,
            OverlayWindowPolicy.FLAGS and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
    }

    @Test
    fun parkedOverlayCannotReceiveTouches() {
        assertTrue(
            OverlayWindowPolicy.PARKED_FLAGS and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
        )
        assertTrue(
            OverlayWindowPolicy.PARKED_FLAGS and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0
        )
    }
}
