package com.agentkosticka.amply.audio.ringer

import org.junit.Assert.assertEquals
import org.junit.Test

class RingerKeyStepPolicyTest {
    @Test fun downWalksThroughVibrateMutedAndDnd() {
        assertEquals(
            RingerKeyStepAction.TO_VIBRATIONS,
            RingerKeyStepPolicy.action(NotificationAlertMode.LOUD, false, true, false, true)
        )
        assertEquals(
            RingerKeyStepAction.TO_MUTED,
            RingerKeyStepPolicy.action(NotificationAlertMode.VIBRATIONS, false, true, false, true)
        )
        assertEquals(
            RingerKeyStepAction.ENABLE_DND,
            RingerKeyStepPolicy.action(NotificationAlertMode.MUTED, false, true, false, true)
        )
        assertEquals(
            RingerKeyStepAction.LIMIT,
            RingerKeyStepPolicy.action(NotificationAlertMode.MUTED, false, true, true, true)
        )
    }

    @Test fun upReversesEveryStateOneStepAtATime() {
        assertEquals(
            RingerKeyStepAction.DISABLE_DND,
            RingerKeyStepPolicy.action(NotificationAlertMode.MUTED, true, true, true, true)
        )
        assertEquals(
            RingerKeyStepAction.TO_VIBRATIONS,
            RingerKeyStepPolicy.action(NotificationAlertMode.MUTED, true, true, false, true)
        )
        assertEquals(
            RingerKeyStepAction.TO_LOUD,
            RingerKeyStepPolicy.action(NotificationAlertMode.VIBRATIONS, true, true, false, true)
        )
    }

    @Test fun hiddenDndFeatureEndsAtMuted() {
        assertEquals(
            RingerKeyStepAction.LIMIT,
            RingerKeyStepPolicy.action(NotificationAlertMode.MUTED, false, true, false, false)
        )
    }
}
