package com.agentkosticka.amply.audio.ringer

enum class RingerKeyStepAction {
    ADJUST_VOLUME,
    TO_LOUD,
    TO_VIBRATIONS,
    TO_MUTED,
    ENABLE_DND,
    DISABLE_DND,
    LIMIT
}

enum class RingerKeyAdjustmentResult {
    NOT_HANDLED,
    APPLIED,
    LIMIT,
    FAILED
}

object RingerKeyStepPolicy {
    fun action(
        mode: NotificationAlertMode,
        isUp: Boolean,
        atMinimum: Boolean,
        dndActive: Boolean,
        dndAvailable: Boolean
    ): RingerKeyStepAction {
        if (dndActive) {
            return if (isUp) RingerKeyStepAction.DISABLE_DND else RingerKeyStepAction.LIMIT
        }
        return when (mode) {
            NotificationAlertMode.LOUD -> if (!isUp && atMinimum) {
                RingerKeyStepAction.TO_VIBRATIONS
            } else {
                RingerKeyStepAction.ADJUST_VOLUME
            }
            NotificationAlertMode.VIBRATIONS -> if (isUp) {
                RingerKeyStepAction.TO_LOUD
            } else {
                RingerKeyStepAction.TO_MUTED
            }
            NotificationAlertMode.MUTED -> if (isUp) {
                RingerKeyStepAction.TO_VIBRATIONS
            } else if (dndAvailable) {
                RingerKeyStepAction.ENABLE_DND
            } else {
                RingerKeyStepAction.LIMIT
            }
        }
    }
}
