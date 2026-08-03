package com.agentkosticka.amply.overlay.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import com.agentkosticka.amply.audio.session.AppVolumeControlState
import com.agentkosticka.amply.audio.session.AppVolumeTarget
import com.agentkosticka.amply.settings.model.AppIdentity

/** Immutable, Compose-facing representation of an app-volume row. */
@Immutable
data class OverlayAppPresentation(
    val target: AppVolumeTarget,
    val icon: ImageBitmap?,
    val volume: Float,
    val isPlaying: Boolean,
    val controlState: AppVolumeControlState
) {
    val identity: AppIdentity get() = target.identity
}
