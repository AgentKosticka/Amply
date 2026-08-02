package com.agentkosticka.amply.service

import android.content.Context
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.overlay.service.OverlayForegroundService

/**
 * Stable Android foreground-service component name retained across app upgrades.
 * The implementation belongs to the overlay feature package.
 */
class OverlayService : OverlayForegroundService() {
    companion object {
        fun startRuntime(context: Context) {
            OverlayForegroundService.startRuntime(context)
        }

        fun showFromAccessibilityHost(
            host: Context,
            target: VolumeTarget,
            iconType: String,
            foregroundPackage: String?
        ) {
            OverlayForegroundService.showFromAccessibilityHost(
                host = host,
                target = target,
                iconType = iconType,
                foregroundPackage = foregroundPackage
            )
        }
    }
}
