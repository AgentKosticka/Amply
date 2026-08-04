package com.agentkosticka.amply.audio.ringer

import org.junit.Assert.assertEquals
import org.junit.Test

class RingerKeyStepPolicyTest {
    @Test fun downWalksThroughVibrateMutedAndDnd() {
        assertEquals(
            RingerKeyStepAction.TO_VIBRATIONS,
            RingerKeyStepPolicy.action(NotificationAlertMode.LOUD,
                isUp = false,
                atMinimum = true,
                dndActive = false,
                dndAvailable = true
            )
        )
        assertEquals(
            RingerKeyStepAction.TO_MUTED,
            RingerKeyStepPolicy.action(NotificationAlertMode.VIBRATIONS,
                isUp = false,
                atMinimum = true,
                dndActive = false,
                dndAvailable = true
            )
        )
        assertEquals(
            RingerKeyStepAction.ENABLE_DND,
            RingerKeyStepPolicy.action(NotificationAlertMode.MUTED,
                isUp = false,
                atMinimum = true,
                dndActive = false,
                dndAvailable = true
            )
        )
        assertEquals(
            RingerKeyStepAction.LIMIT,
            RingerKeyStepPolicy.action(NotificationAlertMode.MUTED,
                isUp = false,
                atMinimum = true,
                dndActive = true,
                dndAvailable = true
            )
        )
    }

    @Test fun upReversesEveryStateOneStepAtATime() {
        assertEquals(
            RingerKeyStepAction.DISABLE_DND,
            RingerKeyStepPolicy.action(NotificationAlertMode.MUTED,
                isUp = true,
                atMinimum = true,
                dndActive = true,
                dndAvailable = true
            )
        )
        assertEquals(
            RingerKeyStepAction.TO_VIBRATIONS,
            RingerKeyStepPolicy.action(NotificationAlertMode.MUTED,
                isUp = true,
                atMinimum = true,
                dndActive = false,
                dndAvailable = true
            )
        )
        assertEquals(
            RingerKeyStepAction.TO_LOUD,
            RingerKeyStepPolicy.action(NotificationAlertMode.VIBRATIONS,
                isUp = true,
                atMinimum = true,
                dndActive = false,
                dndAvailable = true
            )
        )
    }

    @Test fun hiddenDndFeatureEndsAtMuted() {
        assertEquals(
            RingerKeyStepAction.LIMIT,
            RingerKeyStepPolicy.action(NotificationAlertMode.MUTED,
                isUp = false,
                atMinimum = true,
                dndActive = false,
                dndAvailable = false
            )
        )
    }
}
