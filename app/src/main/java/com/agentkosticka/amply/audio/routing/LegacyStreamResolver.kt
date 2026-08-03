package com.agentkosticka.amply.audio.routing

import android.media.AudioAttributes

/** Legacy Android volume streams Amply can present or route volume keys to. */

@Suppress("DEPRECATION")
object LegacyStreamResolver {
    const val FLAG_AUDIBILITY_ENFORCED = 0x1
    const val FLAG_SCO = 0x4
    const val FLAG_BEACON = 0x8

    fun resolve(usage: Int, allFlags: Int): VolumeTarget {
        if (allFlags and FLAG_AUDIBILITY_ENFORCED != 0) return VolumeTarget.ENFORCED_AUDIBLE
        if (allFlags and FLAG_SCO != 0) return VolumeTarget.BLUETOOTH_SCO
        if (allFlags and FLAG_BEACON != 0) return VolumeTarget.TTS
        return when (usage) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> VolumeTarget.CALL
            AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> VolumeTarget.DTMF
            AudioAttributes.USAGE_ALARM -> VolumeTarget.ALARM
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> VolumeTarget.RING
            AudioAttributes.USAGE_NOTIFICATION,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
            AudioAttributes.USAGE_NOTIFICATION_EVENT -> VolumeTarget.NOTIFICATION
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> VolumeTarget.ACCESSIBILITY
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> VolumeTarget.SYSTEM
            AudioAttributes.USAGE_ASSISTANT -> VolumeTarget.ASSISTANT
            else -> VolumeTarget.MEDIA
        }
    }
}

