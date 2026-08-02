package com.agentkosticka.amply.shizuku.client

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuPermissionStateTest {
    @Test fun unavailableInstallAndBinderMapToStableStates() {
        assertEquals(
            ShizukuPermissionState.SHIZUKU_NOT_INSTALLED,
            resolveShizukuPermissionState(false, false, { PackageManager.PERMISSION_DENIED }, { false })
        )
        assertEquals(
            ShizukuPermissionState.SHIZUKU_NOT_RUNNING,
            resolveShizukuPermissionState(true, false, { PackageManager.PERMISSION_DENIED }, { false })
        )
    }

    @Test fun permissionQueryFailureBecomesNotRunning() {
        assertEquals(
            ShizukuPermissionState.SHIZUKU_NOT_RUNNING,
            resolveShizukuPermissionState(true, true, { error("stale binder") }, { false })
        )
    }

    @Test fun grantedAndDeniedQueriesMapCorrectly() {
        assertEquals(
            ShizukuPermissionState.GRANTED,
            resolveShizukuPermissionState(true, true, { PackageManager.PERMISSION_GRANTED }, { false })
        )
        assertEquals(
            ShizukuPermissionState.NOT_GRANTED,
            resolveShizukuPermissionState(true, true, { PackageManager.PERMISSION_DENIED }, { false })
        )
    }
}
