package com.agentkosticka.amply.audio

import android.media.AudioManager

enum class NotificationAlertMode {
    SOUND,
    VIBRATE,
    MUTED;

    companion object {
        fun resolve(ringerMode: Int, currentVolume: Int, minVolume: Int): NotificationAlertMode =
            when {
                currentVolume <= minVolume -> MUTED
                ringerMode == AudioManager.RINGER_MODE_SILENT -> MUTED
                ringerMode == AudioManager.RINGER_MODE_VIBRATE -> VIBRATE
                else -> SOUND
            }
    }
}
