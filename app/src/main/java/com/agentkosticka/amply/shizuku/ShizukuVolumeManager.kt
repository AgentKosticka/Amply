package com.agentkosticka.amply.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Manages connection to the VolumeUserService running in Shizuku's privileged process.
 */
class ShizukuVolumeManager(
    packageName: String
) {

    companion object {
        private const val TAG = "ShizukuVolumeManager"
    }

    private var volumeService: IVolumeService? = null
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(packageName, VolumeUserService::class.java.name)
    )
        .daemon(false)  // Don't run as daemon - stop when unbind
        .processNameSuffix("volume_service")
        .debuggable(true)
        .version(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "UserService connected: $name")
            volumeService = IVolumeService.asInterface(binder)
            _isConnected.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "UserService disconnected: $name")
            volumeService = null
            _isConnected.value = false
        }
    }

    /**
     * Binds to the VolumeUserService.
     * Requires Shizuku permission to be granted.
     */
    fun bindService() {
        try {
            Log.d(TAG, "Binding to VolumeUserService...")
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind UserService", e)
        }
    }

    /**
     * Unbinds from the VolumeUserService.
     */
    fun unbindService() {
        try {
            Log.d(TAG, "Unbinding from VolumeUserService...")
            volumeService?.destroy()
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            volumeService = null
            _isConnected.value = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unbind UserService", e)
        }
    }

    /**
     * Data class representing an active player from the privileged service
     */
    data class PrivilegedPlayback(
        val piid: Int,
        val uid: Int,
        val pid: Int,
        val state: Int
    )

    /**
     * Gets active playback configurations from the privileged service.
     * Returns data that would be sanitized if accessed from app process.
     */
    fun getActivePlaybacks(): List<PrivilegedPlayback> {
        val service = volumeService
        if (service == null) {
            Log.w(TAG, "getActivePlaybacks: Service not connected")
            return emptyList()
        }

        return try {
            val data = service.getActivePlaybacks()
            if (data.isEmpty()) {
                Log.d(TAG, "getActivePlaybacks: Empty result")
                return emptyList()
            }

            val count = data[0]
            Log.d(TAG, "getActivePlaybacks: Got $count playbacks")

            val result = mutableListOf<PrivilegedPlayback>()
            var index = 1
            for (i in 0 until count) {
                if (index + 3 >= data.size) break

                val playback = PrivilegedPlayback(
                    piid = data[index],
                    uid = data[index + 1],
                    pid = data[index + 2],
                    state = data[index + 3]
                )
                result.add(playback)
                index += 4

                Log.d(TAG, "  Playback: piid=${playback.piid}, uid=${playback.uid}, pid=${playback.pid}, state=${playback.state}")
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Error getting active playbacks", e)
            emptyList()
        }
    }

    /**
     * Sets volume for a player by piid via the privileged service.
     */
    fun setPlayerVolume(piid: Int, volume: Float): Boolean {
        val service = volumeService
        if (service == null) {
            Log.w(TAG, "setPlayerVolume: Service not connected")
            return false
        }

        return try {
            val result = service.setPlayerVolume(piid, volume)
            Log.d(TAG, "setPlayerVolume: piid=$piid, volume=$volume, result=$result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error setting player volume", e)
            false
        }
    }
}
