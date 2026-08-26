package com.agentkosticka.amply.overlay.window

import android.view.WindowManager

/**
 * Self-contained Lifecycle Owner for ComposeView in Service context
 * Provides the "Heartbeat" Compose needs to run outside an Activity
 */

internal object OverlayWindowPolicy {
    const val FLAGS: Int = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

    const val PARKED_FLAGS: Int = FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

    fun flags(parked: Boolean, secureCaptureFallback: Boolean): Int {
        val base = if (parked) PARKED_FLAGS else FLAGS
        return if (secureCaptureFallback) {
            base or WindowManager.LayoutParams.FLAG_SECURE
        } else {
            base
        }
    }
}

enum class OverlayPresentationMode {
    NORMAL,
    LOCK_SCREEN_SYSTEM_ONLY
}

/**
 * Manages the floating volume overlay using WindowManager
 * CRITICAL FIX: Self-contained lifecycle and proper window token management
 */
