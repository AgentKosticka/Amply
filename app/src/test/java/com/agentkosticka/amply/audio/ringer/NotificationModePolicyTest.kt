package com.agentkosticka.amply.audio.ringer

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationModePolicyTest {
    @Test
    fun `icon sends loud and vibrations to muted`() {
        assertEquals(NotificationAlertMode.MUTED, NotificationModePolicy.iconTarget(NotificationAlertMode.LOUD))
        assertEquals(NotificationAlertMode.MUTED, NotificationModePolicy.iconTarget(NotificationAlertMode.VIBRATIONS))
    }

    @Test
    fun `icon sends muted to loud`() {
        assertEquals(NotificationAlertMode.LOUD, NotificationModePolicy.iconTarget(NotificationAlertMode.MUTED))
    }

    @Test
    fun `volume control uses vibrations at minimum and loud above it`() {
        assertEquals(NotificationAlertMode.VIBRATIONS, NotificationModePolicy.targetForVolume(0, 0))
        assertEquals(NotificationAlertMode.VIBRATIONS, NotificationModePolicy.targetForVolume(2, 2))
        assertEquals(NotificationAlertMode.LOUD, NotificationModePolicy.targetForVolume(3, 2))
    }

    @Test
    fun `compatibility check covers every directed mode transition`() {
        val expected = NotificationAlertMode.entries.flatMap { from ->
            NotificationAlertMode.entries.filter { it != from }.map { to -> from to to }
        }.toSet()

        assertEquals(6, ALL_RINGER_MODE_TRANSITIONS.size)
        assertEquals(expected, ALL_RINGER_MODE_TRANSITIONS.toSet())
    }

    @Test
    fun `overlay uses the selected E7 controller`() {
        assertEquals(
            RingerExperimentMethod.SHIZUKU_INTERNAL_MODE,
            overlayToggleMethod(RingerExperimentMethod.SHIZUKU_INTERNAL_MODE)
        )
    }
}
