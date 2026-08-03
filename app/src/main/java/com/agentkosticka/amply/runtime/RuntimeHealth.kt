package com.agentkosticka.amply.runtime

import com.agentkosticka.amply.shizuku.client.ShizukuPermissionState
import com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState

enum class RuntimeErrorCode {
    OVERLAY_ATTACH_FAILED,
    OVERLAY_HOST_UNAVAILABLE,
    VOLUME_CHANGE_FAILED,
    SHIZUKU_PROTOCOL_MISMATCH,
    SHIZUKU_CONNECTION_FAILED
}

data class RuntimeError(
    val code: RuntimeErrorCode,
    val occurredAtEpochMs: Long = System.currentTimeMillis()
)

enum class RuntimeOperationState {
    IDLE,
    APPLIED,
    FAILED
}

data class RuntimeHealth(
    val accessibilityConnected: Boolean = false,
    val foregroundServiceRunning: Boolean = false,
    val shizukuPermission: ShizukuPermissionState = ShizukuPermissionState.UNKNOWN,
    val volumeServiceConnection: VolumeServiceConnectionState =
        VolumeServiceConnectionState.WAITING_FOR_PERMISSION,
    val pausedUntilEpochMs: Long = 0L,
    val lastOperation: RuntimeOperationState = RuntimeOperationState.IDLE,
    val recoverableError: RuntimeError? = null
) {
    val isPaused: Boolean
        get() = pausedUntilEpochMs > System.currentTimeMillis()

    val isOperational: Boolean
        get() = accessibilityConnected &&
            foregroundServiceRunning &&
            shizukuPermission == ShizukuPermissionState.GRANTED &&
            volumeServiceConnection == VolumeServiceConnectionState.CONNECTED &&
            !isPaused &&
            recoverableError == null
}
