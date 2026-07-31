package com.agentkosticka.amply.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/** Manages the single process-wide connection to the privileged volume service. */
class ShizukuVolumeManager(
    packageName: String
) : VolumeServiceConnector {

    companion object {
        private const val TAG = "ShizukuVolumeManager"
        private const val USER_SERVICE_TAG = "amply-volume-service"
    }

    private val lock = Any()
    private val generations = ConnectionGenerationTracker()
    private var volumeService: IVolumeService? = null
    private var serviceBinder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private var activeConnection: ServiceConnection? = null

    private val _connectionState = MutableStateFlow(VolumeServiceConnectionState.WAITING_FOR_PERMISSION)
    override val connectionState: StateFlow<VolumeServiceConnectionState> =
        _connectionState.asStateFlow()

    val isConnected: StateFlow<Boolean>
        get() = _isConnected

    private val _isConnected = MutableStateFlow(false)

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(packageName, VolumeUserService::class.java.name)
    )
        .tag(USER_SERVICE_TAG)
        .daemon(false)
        .processNameSuffix("volume_service")
        .debuggable(true)
        .version(1)

    override fun onPermissionAvailable() {
        synchronized(lock) {
            if (_connectionState.value == VolumeServiceConnectionState.WAITING_FOR_PERMISSION) {
                _connectionState.value = VolumeServiceConnectionState.DISCONNECTED
            }
        }
    }

    override fun onPermissionUnavailable() {
        disconnectCurrentConnection(
            nextState = VolumeServiceConnectionState.WAITING_FOR_PERMISSION,
            cause = "permission unavailable"
        )
    }

    override fun ensureBound() {
        val generation: Int
        val connection: ServiceConnection
        synchronized(lock) {
            if (_connectionState.value == VolumeServiceConnectionState.CONNECTED ||
                _connectionState.value == VolumeServiceConnectionState.BINDING
            ) {
                return
            }

            generation = generations.next()
            connection = createServiceConnection(generation)
            activeConnection = connection
            _connectionState.value = VolumeServiceConnectionState.BINDING
            _isConnected.value = false
        }

        try {
            Log.d(TAG, "Binding UserService generation=$generation")
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (e: Exception) {
            Log.e(TAG, "Bind failed generation=$generation", e)
            handleDisconnect(generation, "bind failed: ${e.javaClass.simpleName}")
        }
    }

    override fun invalidateConnection(cause: String) {
        disconnectCurrentConnection(
            nextState = VolumeServiceConnectionState.DISCONNECTED,
            cause = cause
        )
    }

    private fun createServiceConnection(generation: Int): ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    handleDisconnect(generation, "connected with null binder")
                    return
                }

                val service = IVolumeService.asInterface(binder)
                if (service == null) {
                    handleDisconnect(generation, "failed to create service proxy")
                    return
                }

                val recipient = IBinder.DeathRecipient {
                    handleDisconnect(generation, "service binder died")
                }

                synchronized(lock) {
                    if (!generations.isCurrent(generation)) {
                        Log.w(TAG, "Ignoring stale connection generation=$generation current=${generations.current}")
                        unbindConnection(this)
                        return
                    }

                    try {
                        binder.linkToDeath(recipient, 0)
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not watch binder generation=$generation", e)
                        handleDisconnect(generation, "linkToDeath failed")
                        return
                    }

                    clearServiceLocked()
                    volumeService = service
                    serviceBinder = binder
                    deathRecipient = recipient
                    _connectionState.value = VolumeServiceConnectionState.CONNECTED
                    _isConnected.value = true
                }
                Log.i(TAG, "UserService connected generation=$generation name=$name")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                handleDisconnect(generation, "service disconnected: $name")
            }
        }

    private fun handleDisconnect(generation: Int, cause: String) {
        synchronized(lock) {
            if (!generations.isCurrent(generation)) {
                Log.d(TAG, "Ignoring stale disconnect generation=$generation cause=$cause")
                return
            }
            generations.invalidate()
            clearServiceLocked()
            activeConnection = null
            _connectionState.value = VolumeServiceConnectionState.DISCONNECTED
            _isConnected.value = false
        }
        Log.w(TAG, "UserService disconnected generation=$generation cause=$cause")
    }

    private fun disconnectCurrentConnection(
        nextState: VolumeServiceConnectionState,
        cause: String
    ) {
        val connection: ServiceConnection?
        synchronized(lock) {
            generations.invalidate()
            connection = activeConnection
            activeConnection = null
            clearServiceLocked()
            _connectionState.value = nextState
            _isConnected.value = false
        }
        connection?.let(::unbindConnection)
        Log.d(TAG, "Connection cleared cause=$cause nextState=$nextState")
    }

    private fun unbindConnection(connection: ServiceConnection) {
        try {
            Shizuku.unbindUserService(userServiceArgs, connection, false)
        } catch (e: Exception) {
            Log.w(TAG, "Non-destructive unbind failed", e)
        }
    }

    private fun clearServiceLocked() {
        val binder = serviceBinder
        val recipient = deathRecipient
        if (binder != null && recipient != null) {
            try {
                binder.unlinkToDeath(recipient, 0)
            } catch (_: Exception) {
                // The binder may already be dead.
            }
        }
        volumeService = null
        serviceBinder = null
        deathRecipient = null
    }

    data class PrivilegedPlayback(
        val piid: Int,
        val uid: Int,
        val pid: Int,
        val state: Int
    )

    /** Returns null for an unavailable/failed service and an empty list for valid idle audio. */
    fun getActivePlaybacks(): List<PrivilegedPlayback>? {
        val service = synchronized(lock) { volumeService } ?: return null

        return try {
            val data = service.getActivePlaybacks()
            if (data.isEmpty()) {
                invalidateConnection("playback query failed")
                return null
            }

            val count = data[0]
            if (count < 0 || data.size < 1 + count * 4) {
                invalidateConnection("malformed playback response")
                return null
            }

            buildList(count) {
                var index = 1
                repeat(count) {
                    add(
                        PrivilegedPlayback(
                            piid = data[index],
                            uid = data[index + 1],
                            pid = data[index + 2],
                            state = data[index + 3]
                        )
                    )
                    index += 4
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Playback query failed", e)
            invalidateConnection("playback RPC failed: ${e.javaClass.simpleName}")
            null
        }
    }

    fun setPlayerVolume(piid: Int, volume: Float): Boolean {
        val service = synchronized(lock) { volumeService } ?: return false

        return try {
            service.setPlayerVolume(piid, volume)
        } catch (e: Exception) {
            Log.e(TAG, "Volume RPC failed piid=$piid", e)
            invalidateConnection("volume RPC failed: ${e.javaClass.simpleName}")
            false
        }
    }
}
