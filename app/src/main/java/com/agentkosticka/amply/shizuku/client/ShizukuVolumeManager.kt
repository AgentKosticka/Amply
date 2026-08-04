package com.agentkosticka.amply.shizuku.client

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.util.Log
import com.agentkosticka.amply.audio.routing.StreamTopology
import com.agentkosticka.amply.shizuku.protocol.IVolumeService
import com.agentkosticka.amply.shizuku.protocol.VOLUME_PROTOCOL_CAPABILITIES
import com.agentkosticka.amply.shizuku.protocol.VOLUME_PROTOCOL_VERSION
import com.agentkosticka.amply.shizuku.VolumeUserService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

internal val STABLE_VOLUME_USER_SERVICE_CLASS_NAME: String = VolumeUserService::class.java.name
internal const val VOLUME_USER_SERVICE_VERSION = 5

/** Manages the single process-wide connection to the privileged volume service. */
class ShizukuVolumeManager(
    context: Context
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
    private var cachedStreamTopology: StreamTopology? = null

    private val _connectionState = MutableStateFlow(VolumeServiceConnectionState.WAITING_FOR_PERMISSION)
    override val connectionState: StateFlow<VolumeServiceConnectionState> =
        _connectionState.asStateFlow()

    val isConnected: StateFlow<Boolean>
        field = MutableStateFlow(false)

    private val appContext = context.applicationContext
    private val userServiceDebuggable =
        appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, STABLE_VOLUME_USER_SERVICE_CLASS_NAME)
    )
        .tag(USER_SERVICE_TAG)
        .daemon(false)
        .processNameSuffix("volume_service")
        .debuggable(userServiceDebuggable)
        .version(VOLUME_USER_SERVICE_VERSION)

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
            isConnected.value = false
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

                val service = IVolumeService.Stub.asInterface(binder)
                if (service == null) {
                    handleDisconnect(generation, "failed to create service proxy")
                    return
                }

                val compatible = runCatching {
                    service.protocolVersion == VOLUME_PROTOCOL_VERSION &&
                        service.capabilities and VOLUME_PROTOCOL_CAPABILITIES ==
                        VOLUME_PROTOCOL_CAPABILITIES
                }.getOrDefault(false)
                if (!compatible) {
                    handleDisconnect(
                        generation,
                        "incompatible protocol",
                        VolumeServiceConnectionState.PROTOCOL_MISMATCH
                    )
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
                    isConnected.value = true
                }
                Log.i(TAG, "UserService connected generation=$generation name=$name")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                handleDisconnect(generation, "service disconnected: $name")
            }
        }

    private fun handleDisconnect(
        generation: Int,
        cause: String,
        nextState: VolumeServiceConnectionState = VolumeServiceConnectionState.DISCONNECTED
    ) {
        val connection: ServiceConnection?
        synchronized(lock) {
            if (!generations.isCurrent(generation)) {
                Log.d(TAG, "Ignoring stale disconnect generation=$generation cause=$cause")
                return
            }
            generations.invalidate()
            connection = activeConnection
            clearServiceLocked()
            activeConnection = null
            _connectionState.value = nextState
            isConnected.value = false
        }
        connection?.let(::unbindConnection)
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
            isConnected.value = false
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
        cachedStreamTopology = null
    }

    data class PrivilegedPlayback(
        val piid: Int,
        val uid: Int,
        val pid: Int,
        val state: Int,
        val streamType: Int,
        val userId: Int = (uid / 100_000).coerceAtLeast(0),
        val usage: Int = 0,
        val contentType: Int = 0,
        val muted: Boolean = false,
        val volume: Float = 1f
    )

    /** Returns null for an unavailable/failed service and an empty list for valid idle audio. */
    fun getActivePlaybacks(): List<PrivilegedPlayback>? {
        val service = synchronized(lock) { volumeService } ?: return null

        return try {
            service.activePlaybacks.orEmpty().mapNotNull { playback ->
                if (!playback.isValid()) return@mapNotNull null
                PrivilegedPlayback(
                    piid = playback.playerInterfaceId,
                    uid = playback.uid,
                    pid = playback.pid,
                    state = playback.playerState,
                    streamType = playback.streamType,
                    userId = playback.userId,
                    usage = playback.usage,
                    contentType = playback.contentType,
                    muted = playback.muted,
                    volume = playback.volume
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Playback query failed", e)
            invalidateConnection("playback RPC failed: ${e.javaClass.simpleName}")
            null
        }
    }

    fun setPlayerVolume(piid: Int, volume: Float): Boolean {
        if (piid <= 0 || !volume.isFinite() || volume !in 0f..1f) return false
        val service = synchronized(lock) { volumeService } ?: return false

        return try {
            service.setPlayerVolume(piid, volume).succeeded
        } catch (e: Exception) {
            Log.e(TAG, "Volume RPC failed piid=$piid", e)
            invalidateConnection("volume RPC failed: ${e.javaClass.simpleName}")
            false
        }
    }

    fun getStreamTopology(): StreamTopology? {
        synchronized(lock) { cachedStreamTopology }?.let { return it }
        val service = synchronized(lock) { volumeService } ?: return null
        return try {
            val data = service.getStreamTopology()
            if (data.size < 2) error("empty topology response")
            val count = data[1]
            if (count !in 1..12 || data.size != 2 + count) error("malformed topology response")
            val topology = StreamTopology(
                aliasKnown = data[0] == 1,
                aliases = (0 until count).associateWith { data[2 + it] }
            )
            synchronized(lock) { cachedStreamTopology = topology }
            topology
        } catch (e: Exception) {
            Log.e(TAG, "Stream-topology query failed", e)
            StreamTopology.UNKNOWN.also { unknown ->
                synchronized(lock) { cachedStreamTopology = unknown }
            }
        }
    }

    fun invalidateStreamTopologyCache() {
        synchronized(lock) { cachedStreamTopology = null }
    }

    fun setSystemStreamVolume(streamType: Int, index: Int): Boolean {
        if (streamType !in 0..11 || index < 0) return false
        val service = synchronized(lock) { volumeService } ?: return false
        return try {
            service.setSystemStreamVolume(streamType, index).succeeded
        } catch (e: Exception) {
            Log.e(TAG, "System stream update failed stream=$streamType", e)
            invalidateConnection("system-stream RPC failed: ${e.javaClass.simpleName}")
            false
        }
    }

    fun applyRingerExperiment(method: Int, target: Int, restoreVolume: Int): Int? {
        val service = synchronized(lock) { volumeService } ?: return null
        return try {
            service.applyRingerExperiment(method, target, restoreVolume)
        } catch (e: Exception) {
            Log.e(TAG, "Ringer experiment RPC failed method=$method", e)
            invalidateConnection("ringer experiment RPC failed: ${e.javaClass.simpleName}")
            null
        }
    }
}
