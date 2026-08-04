package com.agentkosticka.amply.audio.ringer

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationAlertModeTest {
    @Test
    fun `normal ringer with volume is sound`() {
        assertEquals(
            NotificationAlertMode.LOUD,
            NotificationAlertMode.resolve(AudioManager.RINGER_MODE_NORMAL)
        )
    }

    @Test
    fun `vibrate ringer with volume is vibrate`() {
        assertEquals(
            NotificationAlertMode.VIBRATIONS,
            NotificationAlertMode.resolve(AudioManager.RINGER_MODE_VIBRATE)
        )
    }

    @Test
    fun `silent ringer is muted even when stream retains volume`() {
        assertEquals(
            NotificationAlertMode.MUTED,
            NotificationAlertMode.resolve(AudioManager.RINGER_MODE_SILENT)
        )
    }

    @Test
    fun `ringer mode remains authoritative when stream index is zero`() {
        assertEquals(
            NotificationAlertMode.LOUD,
            NotificationAlertMode.resolve(AudioManager.RINGER_MODE_NORMAL)
        )
    }
}
