package com.agentkosticka.amply.service

import com.agentkosticka.amply.volumekeys.VolumeKeyAccessibilityService

/**
 * Stable Android accessibility component name retained across app upgrades.
 * The implementation belongs to the volume-key feature package.
 */
class VolumeKeyService : VolumeKeyAccessibilityService() {
    companion object {
        fun suppressInjectedKeysFor(durationMs: Long) {
            VolumeKeyAccessibilityService.suppressInjectedKeysFor(durationMs)
        }
    }
}
