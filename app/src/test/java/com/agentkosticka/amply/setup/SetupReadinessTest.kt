package com.agentkosticka.amply.setup

import com.agentkosticka.amply.shizuku.client.ShizukuPermissionState
import com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupReadinessTest {
    private fun state(
        accessibility: Boolean = true,
        permission: ShizukuPermissionState = ShizukuPermissionState.GRANTED,
        connection: VolumeServiceConnectionState = VolumeServiceConnectionState.CONNECTED
    ) = SetupReadiness(accessibility, permission, connection)

    @Test fun introductionAloneNeverHidesRevokedAccessibility() {
        assertFalse(state(accessibility = false).canShowDashboard(introductionSeen = true))
    }

    @Test fun shizukuIsOptionalAfterIntroduction() {
        assertTrue(
            state(permission = ShizukuPermissionState.DENIED)
                .canShowDashboard(introductionSeen = true)
        )
        assertTrue(
            state(connection = VolumeServiceConnectionState.DISCONNECTED)
                .canShowDashboard(introductionSeen = true)
        )
        assertTrue(
            state(connection = VolumeServiceConnectionState.PROTOCOL_MISMATCH)
                .canShowDashboard(introductionSeen = true)
        )
    }

    @Test fun readyDependenciesStillRequireIntroduction() {
        assertFalse(state().canShowDashboard(introductionSeen = false))
        assertTrue(state().canShowDashboard(introductionSeen = true))
    }
}
