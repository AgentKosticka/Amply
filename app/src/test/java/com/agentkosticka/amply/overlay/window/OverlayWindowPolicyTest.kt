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

    @Test
    fun secureCaptureFallbackCanBeAppliedToActiveAndParkedWindows() {
        assertTrue(
            OverlayWindowPolicy.flags(parked = false, secureCaptureFallback = true) and
                WindowManager.LayoutParams.FLAG_SECURE != 0
        )
        assertTrue(
            OverlayWindowPolicy.flags(parked = true, secureCaptureFallback = true) and
                WindowManager.LayoutParams.FLAG_SECURE != 0
        )
        assertEquals(
            0,
            OverlayWindowPolicy.flags(parked = false, secureCaptureFallback = false) and
                WindowManager.LayoutParams.FLAG_SECURE
        )
    }
}
