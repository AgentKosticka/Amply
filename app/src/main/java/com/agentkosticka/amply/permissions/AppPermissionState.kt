package com.agentkosticka.amply.permissions

data class AppPermissionState(
    val volumeKeysGranted: Boolean = false,
    val notificationPolicyGranted: Boolean = false,
    val phoneStateGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val nearbyDevicesGranted: Boolean = false
)
