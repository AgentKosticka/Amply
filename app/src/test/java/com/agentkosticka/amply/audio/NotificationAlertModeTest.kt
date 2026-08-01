package com.agentkosticka.amply.audio

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationAlertModeTest {
    @Test
    fun `normal ringer with volume is sound`() {
        assertEquals(
            NotificationAlertMode.SOUND,
            NotificationAlertMode.resolve(AudioManager.RINGER_MODE_NORMAL, 4, 0)
        )
    }

    @Test
    fun `vibrate ringer with volume is vibrate`() {
        assertEquals(
            NotificationAlertMode.VIBRATE,
            NotificationAlertMode.resolve(AudioManager.RINGER_MODE_VIBRATE, 4, 0)
        )
    }

    @Test
    fun `silent ringer is muted even when stream retains volume`() {
        assertEquals(
            NotificationAlertMode.MUTED,
            NotificationAlertMode.resolve(AudioManager.RINGER_MODE_SILENT, 4, 0)
        )
    }

    @Test
    fun `minimum stream volume is muted in every ringer mode`() {
        assertEquals(
            NotificationAlertMode.MUTED,
            NotificationAlertMode.resolve(AudioManager.RINGER_MODE_NORMAL, 0, 0)
        )
    }
}
