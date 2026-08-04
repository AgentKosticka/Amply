package com.agentkosticka.amply.shizuku.client

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuPermissionStateTest {
    @Test fun unavailableInstallAndBinderMapToStableStates() {
        assertEquals(
            ShizukuPermissionState.SHIZUKU_NOT_INSTALLED,
            resolveShizukuPermissionState(
                installed = false,
                running = false,
                checkPermission = { PackageManager.PERMISSION_DENIED },
                shouldShowRationale = { false })
        )
        assertEquals(
            ShizukuPermissionState.SHIZUKU_NOT_RUNNING,
            resolveShizukuPermissionState(
                installed = true,
                running = false,
                checkPermission = { PackageManager.PERMISSION_DENIED },
                shouldShowRationale = { false })
        )
    }

    @Test fun permissionQueryFailureBecomesNotRunning() {
        assertEquals(
            ShizukuPermissionState.SHIZUKU_NOT_RUNNING,
            resolveShizukuPermissionState(
                installed = true,
                running = true,
                checkPermission = { error("stale binder") },
                shouldShowRationale = { false })
        )
    }

    @Test fun grantedAndDeniedQueriesMapCorrectly() {
        assertEquals(
            ShizukuPermissionState.GRANTED,
            resolveShizukuPermissionState(
                installed = true,
                running = true,
                checkPermission = { PackageManager.PERMISSION_GRANTED },
                shouldShowRationale = { false })
        )
        assertEquals(
            ShizukuPermissionState.NOT_GRANTED,
            resolveShizukuPermissionState(
                installed = true,
                running = true,
                checkPermission = { PackageManager.PERMISSION_DENIED },
                shouldShowRationale = { false })
        )
    }
}
