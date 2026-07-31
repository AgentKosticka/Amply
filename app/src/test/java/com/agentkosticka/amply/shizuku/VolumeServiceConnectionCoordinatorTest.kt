package com.agentkosticka.amply.shizuku

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeServiceConnectionCoordinatorTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @Test
    fun latePermissionGrantBindsOnceAfterInitialDelay() {
        val permission = MutableStateFlow(ShizukuPermissionState.NOT_GRANTED)
        val connector = FakeConnector()
        val coordinator = coordinator(permission, connector)

        coordinator.step(0L)
        assertEquals(VolumeServiceConnectionState.WAITING_FOR_PERMISSION, connector.connectionState.value)

        permission.value = ShizukuPermissionState.GRANTED
        coordinator.step(100L)
        coordinator.step(599L)
        assertEquals(0, connector.bindCalls)

        coordinator.step(600L)
        coordinator.step(700L)
        assertEquals(1, connector.bindCalls)
        assertEquals(VolumeServiceConnectionState.BINDING, connector.connectionState.value)
    }

    @Test
    fun bindTimeoutUsesIncreasingRetryDelay() {
        val permission = MutableStateFlow(ShizukuPermissionState.GRANTED)
        val connector = FakeConnector()
        val coordinator = coordinator(permission, connector)

        coordinator.step(0L)
        coordinator.step(500L)
        assertEquals(1, connector.bindCalls)

        coordinator.step(5_500L)
        assertEquals(1, connector.invalidations)
        coordinator.step(6_499L)
        assertEquals(1, connector.bindCalls)

        coordinator.step(6_500L)
        assertEquals(2, connector.bindCalls)
    }

    @Test
    fun disconnectAfterSuccessAutomaticallyBindsAgain() {
        val permission = MutableStateFlow(ShizukuPermissionState.GRANTED)
        val connector = FakeConnector()
        val coordinator = coordinator(permission, connector)

        coordinator.step(0L)
        coordinator.step(500L)
        connector.connect()
        coordinator.step(600L)

        connector.disconnect()
        coordinator.step(700L)
        coordinator.step(1_199L)
        assertEquals(1, connector.bindCalls)

        coordinator.step(1_200L)
        assertEquals(2, connector.bindCalls)
    }

    @Test
    fun permissionLossStopsBindingAndMovesToWaiting() {
        val permission = MutableStateFlow(ShizukuPermissionState.GRANTED)
        val connector = FakeConnector()
        val coordinator = coordinator(permission, connector)

        coordinator.step(0L)
        coordinator.step(500L)
        connector.connect()
        coordinator.step(600L)

        permission.value = ShizukuPermissionState.SHIZUKU_NOT_RUNNING
        coordinator.step(700L)

        assertEquals(VolumeServiceConnectionState.WAITING_FOR_PERMISSION, connector.connectionState.value)
        assertEquals(1, connector.permissionUnavailableCalls)
    }

    @Test
    fun generationTrackerRejectsStaleCallbacks() {
        val tracker = ConnectionGenerationTracker()
        val first = tracker.next()
        val second = tracker.next()

        assertFalse(tracker.isCurrent(first))
        assertTrue(tracker.isCurrent(second))

        tracker.invalidate()
        assertFalse(tracker.isCurrent(second))
    }

    private fun coordinator(
        permission: StateFlow<ShizukuPermissionState>,
        connector: FakeConnector
    ) = VolumeServiceConnectionCoordinator(
        scope = scope,
        permissionState = permission,
        connector = connector,
        clock = { 0L },
        tickIntervalMs = Long.MAX_VALUE,
        logger = {}
    )

    private class FakeConnector : VolumeServiceConnector {
        private val mutableState = MutableStateFlow(VolumeServiceConnectionState.WAITING_FOR_PERMISSION)
        override val connectionState: StateFlow<VolumeServiceConnectionState> = mutableState

        var bindCalls = 0
        var invalidations = 0
        var permissionUnavailableCalls = 0

        override fun onPermissionAvailable() {
            mutableState.value = VolumeServiceConnectionState.DISCONNECTED
        }

        override fun onPermissionUnavailable() {
            permissionUnavailableCalls++
            mutableState.value = VolumeServiceConnectionState.WAITING_FOR_PERMISSION
        }

        override fun ensureBound() {
            bindCalls++
            mutableState.value = VolumeServiceConnectionState.BINDING
        }

        override fun invalidateConnection(cause: String) {
            invalidations++
            mutableState.value = VolumeServiceConnectionState.DISCONNECTED
        }

        fun connect() {
            mutableState.value = VolumeServiceConnectionState.CONNECTED
        }

        fun disconnect() {
            mutableState.value = VolumeServiceConnectionState.DISCONNECTED
        }
    }
}
