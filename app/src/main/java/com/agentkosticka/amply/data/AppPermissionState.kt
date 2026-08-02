package com.agentkosticka.amply.data

data class AppPermissionState(
    val overlayGranted: Boolean = false,
    val volumeKeysGranted: Boolean = false,
    val notificationPolicyGranted: Boolean = false,
    val notificationsGranted: Boolean = false
)
