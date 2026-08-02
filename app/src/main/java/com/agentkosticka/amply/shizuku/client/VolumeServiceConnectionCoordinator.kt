package com.agentkosticka.amply.shizuku.client

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration.Companion.milliseconds

enum class VolumeServiceConnectionState {
    WAITING_FOR_PERMISSION,
    DISCONNECTED,
    BINDING,
    CONNECTED
}

internal interface VolumeServiceConnector {
    val connectionState: StateFlow<VolumeServiceConnectionState>

    fun onPermissionAvailable()
    fun onPermissionUnavailable()
    fun ensureBound()
    fun invalidateConnection(cause: String)
}

internal class ConnectionGenerationTracker {
    var current: Int = 0
        private set

    fun next(): Int = ++current

    fun invalidate(): Int = ++current

    fun isCurrent(generation: Int): Boolean = generation == current
}

internal class VolumeServiceConnectionCoordinator(
    private val scope: CoroutineScope,
    private val permissionState: StateFlow<ShizukuPermissionState>,
    private val connector: VolumeServiceConnector,
    private val permissionRefresher: () -> Unit = {},
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val retryDelaysMs: List<Long> = listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 10_000L),
    private val bindTimeoutMs: Long = 5_000L,
    private val logger: (String) -> Unit = { message -> Log.d(TAG, message) }
) {
    companion object {
        private const val TAG = "VolumeConnection"
        private const val IDLE_WAIT_MS = 24L * 60L * 60L * 1_000L
    }

    private var job: Job? = null
    private var retryIndex = 0
    private var nextRetryAt = Long.MAX_VALUE
    private var bindStartedAt = 0L
    private var lastState: VolumeServiceConnectionState? = null
    private val wakeSignals = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch {
                permissionState.drop(1).collect {
                    wakeSignals.trySend(Unit)
                }
            }
            launch {
                connector.connectionState.drop(1).collect {
                    wakeSignals.trySend(Unit)
                }
            }

            while (isActive) {
                val now = clock()
                try {
                    step(now)
                } catch (e: RuntimeException) {
                    logger("Connection loop recovered from ${e.javaClass.simpleName}")
                    connector.invalidateConnection("coordinator failure: ${e.javaClass.simpleName}")
                    lastState = VolumeServiceConnectionState.DISCONNECTED
                    scheduleRetry(now)
                }
                val waitMs = nextWakeDelayMs(clock())
                if (waitMs > 0L) {
                    withTimeoutOrNull(waitMs.milliseconds) {
                        wakeSignals.receive()
                    }
                }
            }
        }
    }

    fun retryNow() {
        if (permissionState.value != ShizukuPermissionState.GRANTED) return
        retryIndex = 0
        nextRetryAt = 0L
        if (connector.connectionState.value == VolumeServiceConnectionState.BINDING) {
            connector.invalidateConnection("manual retry")
            lastState = VolumeServiceConnectionState.DISCONNECTED
        }
        wakeSignals.trySend(Unit)
        logger("Manual reconnect requested")
    }

    internal fun step(now: Long) {
        if (permissionState.value != ShizukuPermissionState.GRANTED) {
            if (connector.connectionState.value != VolumeServiceConnectionState.WAITING_FOR_PERMISSION) {
                logger("Waiting for Shizuku permission: ${permissionState.value}")
                connector.onPermissionUnavailable()
            }
            resetRetryState()
            lastState = VolumeServiceConnectionState.WAITING_FOR_PERMISSION
            return
        }

        if (connector.connectionState.value == VolumeServiceConnectionState.WAITING_FOR_PERMISSION) {
            connector.onPermissionAvailable()
            retryIndex = 0
            nextRetryAt = now + retryDelaysMs.first()
            lastState = VolumeServiceConnectionState.DISCONNECTED
            logger("Shizuku permission available; first bind in ${retryDelaysMs.first()}ms")
            return
        }

        val state = connector.connectionState.value
        if (state != lastState) {
            when (state) {
                VolumeServiceConnectionState.CONNECTED -> {
                    resetRetryState()
                    logger("Connection recovered")
                }
                VolumeServiceConnectionState.BINDING -> bindStartedAt = now
                VolumeServiceConnectionState.DISCONNECTED -> scheduleRetry(now)
                VolumeServiceConnectionState.WAITING_FOR_PERMISSION -> Unit
            }
            lastState = state
        }

        when (state) {
            VolumeServiceConnectionState.CONNECTED,
            VolumeServiceConnectionState.WAITING_FOR_PERMISSION -> Unit

            VolumeServiceConnectionState.BINDING -> {
                if (now - bindStartedAt >= bindTimeoutMs) {
                    logger("UserService bind timed out")
                    connector.invalidateConnection("bind timeout")
                    lastState = VolumeServiceConnectionState.DISCONNECTED
                    scheduleRetry(now)
                }
            }

            VolumeServiceConnectionState.DISCONNECTED -> {
                if (now >= nextRetryAt) {
                    permissionRefresher()
                    if (permissionState.value != ShizukuPermissionState.GRANTED) {
                        connector.onPermissionUnavailable()
                        resetRetryState()
                        lastState = VolumeServiceConnectionState.WAITING_FOR_PERMISSION
                        return
                    }
                    logger("Starting bind attempt ${retryIndex + 1}")
                    connector.ensureBound()
                    retryIndex = (retryIndex + 1).coerceAtMost(retryDelaysMs.lastIndex)
                    if (connector.connectionState.value == VolumeServiceConnectionState.BINDING) {
                        bindStartedAt = now
                        lastState = VolumeServiceConnectionState.BINDING
                    } else {
                        scheduleRetry(now)
                    }
                }
            }
        }
    }

    private fun scheduleRetry(now: Long) {
        val delayMs = retryDelaysMs[retryIndex.coerceAtMost(retryDelaysMs.lastIndex)]
        nextRetryAt = now + delayMs
        logger("Next bind attempt in ${delayMs}ms")
    }

    private fun resetRetryState() {
        retryIndex = 0
        nextRetryAt = Long.MAX_VALUE
        bindStartedAt = 0L
    }

    internal fun nextWakeDelayMs(now: Long): Long {
        if (permissionState.value != ShizukuPermissionState.GRANTED) {
            return IDLE_WAIT_MS
        }

        return when (connector.connectionState.value) {
            VolumeServiceConnectionState.WAITING_FOR_PERMISSION -> 0L
            VolumeServiceConnectionState.DISCONNECTED -> (nextRetryAt - now).coerceAtLeast(0L)
            VolumeServiceConnectionState.BINDING ->
                (bindTimeoutMs - (now - bindStartedAt)).coerceAtLeast(0L)
            VolumeServiceConnectionState.CONNECTED -> IDLE_WAIT_MS
        }
    }
}
