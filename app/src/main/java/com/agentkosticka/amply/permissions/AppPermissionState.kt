package com.agentkosticka.amply.permissions

data class AppPermissionState(
    val overlayGranted: Boolean = false,
    val volumeKeysGranted: Boolean = false,
    val notificationPolicyGranted: Boolean = false,
    val phoneStateGranted: Boolean = false,
    val notificationsGranted: Boolean = false
)
