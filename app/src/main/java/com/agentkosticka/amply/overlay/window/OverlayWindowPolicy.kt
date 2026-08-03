package com.agentkosticka.amply.overlay.window

import android.view.WindowManager

/**
 * Self-contained Lifecycle Owner for ComposeView in Service context
 * Provides the "Heartbeat" Compose needs to run outside an Activity
 */

internal object OverlayWindowPolicy {
    val flags: Int = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

    val parkedFlags: Int = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
}

enum class OverlayPresentationMode {
    NORMAL,
    LOCK_SCREEN_SYSTEM_ONLY
}

/**
 * Manages the floating volume overlay using WindowManager
 * CRITICAL FIX: Self-contained lifecycle and proper window token management
 */
