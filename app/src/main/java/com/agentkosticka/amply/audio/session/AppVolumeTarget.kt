package com.agentkosticka.amply.audio.session

import androidx.compose.runtime.Immutable
import com.agentkosticka.amply.settings.model.AppIdentity

/** Stable command data required to change or persist one app's volume. */
@Immutable
data class AppVolumeTarget(
    val packageName: String,
    val uid: Int,
    val appName: String
) {
    val identity: AppIdentity get() = AppIdentity.fromUid(packageName, uid)
}

internal fun OverlayAppEntry.toVolumeTarget() = AppVolumeTarget(
    packageName = packageName,
    uid = uid,
    appName = appName
)
