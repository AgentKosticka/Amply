package com.agentkosticka.amply.audio

import android.media.AudioManager

enum class NotificationAlertMode {
    LOUD,
    VIBRATIONS,
    MUTED;

    companion object {
        fun resolve(ringerMode: Int, currentVolume: Int, minVolume: Int): NotificationAlertMode =
            when (ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> MUTED
                AudioManager.RINGER_MODE_VIBRATE -> VIBRATIONS
                else -> LOUD
            }
    }
}
