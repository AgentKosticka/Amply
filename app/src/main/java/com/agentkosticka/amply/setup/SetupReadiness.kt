package com.agentkosticka.amply.setup

import com.agentkosticka.amply.shizuku.client.ShizukuPermissionState
import com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState

internal data class SetupReadiness(
    val accessibilityEnabled: Boolean,
    val shizukuPermission: ShizukuPermissionState,
    val volumeServiceConnection: VolumeServiceConnectionState
) {

    val requiredServicesReady: Boolean
        get() = accessibilityEnabled

    fun canShowDashboard(introductionSeen: Boolean): Boolean =
        introductionSeen && requiredServicesReady
}
