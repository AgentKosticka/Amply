package com.agentkosticka.amply.tutorial

internal enum class TutorialStage {
    NOT_STARTED,
    WAITING_FOR_VOLUME_KEY,
    OVERLAY_LEVEL,
    OVERLAY_MUTE,
    OVERLAY_EXPAND,
    OVERLAY_STREAMS,
    OVERLAY_APPS,
    OVERLAY_PAUSE,
    APP_ACCESS,
    APP_APPS,
    APP_PILL,
    APP_STAND_DOWN,
    COMPLETED;

    val isOverlayDemo: Boolean
        get() = this in OVERLAY_LEVEL..OVERLAY_PAUSE

    val isAppTour: Boolean
        get() = this in APP_ACCESS..APP_STAND_DOWN

    companion object {
        val overlayStages = listOf(
            OVERLAY_LEVEL,
            OVERLAY_MUTE,
            OVERLAY_EXPAND,
            OVERLAY_STREAMS,
            OVERLAY_APPS,
            OVERLAY_PAUSE
        )

        val appStages = listOf(APP_ACCESS, APP_APPS, APP_PILL, APP_STAND_DOWN)

        fun fromStored(value: String?, introductionSeen: Boolean): TutorialStage =
            value?.let { stored -> entries.firstOrNull { it.name == stored } }
                ?: if (introductionSeen) COMPLETED else NOT_STARTED
    }
}
