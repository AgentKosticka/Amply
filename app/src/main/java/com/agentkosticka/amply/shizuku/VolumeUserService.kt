package com.agentkosticka.amply.shizuku

import android.media.AudioPlaybackConfiguration
import android.os.IBinder
import android.os.Process
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

/**
 * Shizuku UserService that runs in the privileged shell process (UID 2000).
 *
 * Because this runs with shell permissions, the AudioPlaybackConfiguration
 * objects returned by getActivePlaybackConfigurations() will contain
 * the full data (uid, pid, playerProxy) that is normally sanitized
 * when running in an app's process.
 */
class VolumeUserService : IVolumeService.Stub() {

    companion object {
        private const val TAG = "VolumeUserService"
    }

    // Direct access to IAudioService for privileged operations
    private var audioService: Any? = null
    private var getActivePlaybackConfigsMethod: Method? = null

    // Cached reflection methods for AudioPlaybackConfiguration
    private var getPlayerProxyMethod: Method? = null
    private var setVolumeMethod: Method? = null
    private var getClientUidMethod: Method? = null
    private var getClientPidMethod: Method? = null
    private var getPlayerInterfaceIdMethod: Method? = null
    private var reflectionInitialized = false

    // Store active configs for volume control
    private var lastConfigs: List<AudioPlaybackConfiguration> = emptyList()

    init {
        Log.d(TAG, "VolumeUserService created in process ${Process.myPid()}, uid ${Process.myUid()}")

        // Initialize HiddenApiBypass in the service process too
        try {
            HiddenApiBypass.addHiddenApiExemptions("")
            Log.d(TAG, "HiddenApiBypass initialized in service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init HiddenApiBypass in service", e)
        }

        // Get IAudioService using system-level access
        initializeAudioService()
    }

    /**
     * Initialize IAudioService using direct system-level access.
     * Since we're running in Shizuku's shell process (UID 2000), we can access system services.
     */
    private fun initializeAudioService() {
        try {
            // Get audio service binder via ServiceManager
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val audioBinder = getServiceMethod.invoke(null, "audio") as? IBinder

            if (audioBinder == null) {
                Log.e(TAG, "Failed to get audio service binder")
                return
            }

            Log.d(TAG, "Got audio binder: $audioBinder")

            // Get IAudioService.Stub.asInterface
            val audioServiceStubClass = Class.forName("android.media.IAudioService\$Stub")
            val asInterfaceMethod = audioServiceStubClass.getMethod("asInterface", IBinder::class.java)
            audioService = asInterfaceMethod.invoke(null, audioBinder)

            if (audioService == null) {
                Log.e(TAG, "Failed to get IAudioService")
                return
            }

            Log.d(TAG, "Got IAudioService: ${audioService!!.javaClass.name}")

            // Find getActivePlaybackConfigurations method
            getActivePlaybackConfigsMethod = audioService!!.javaClass.getMethod("getActivePlaybackConfigurations")
            Log.d(TAG, "Found getActivePlaybackConfigurations method on IAudioService")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioService", e)
            e.printStackTrace()
        }
    }

    /**
     * Get active playback configurations directly from IAudioService
     */
    private fun getActivePlaybackConfigurations(): List<AudioPlaybackConfiguration> {
        val service = audioService
        val method = getActivePlaybackConfigsMethod

        if (service == null || method == null) {
            Log.e(TAG, "AudioService not initialized: service=$service, method=$method")
            return emptyList()
        }

        return try {
            val result = method.invoke(service)
            Log.d(TAG, "getActivePlaybackConfigurations returned: ${result?.javaClass?.name}")
            (result as? List<*>)?.filterIsInstance<AudioPlaybackConfiguration>() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get playback configurations: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    override fun getActivePlaybacks(): IntArray {
        if (audioService == null) {
            Log.e(TAG, "getActivePlaybacks: AudioService is null")
            return IntArray(0)
        }

        if (!reflectionInitialized) {
            initializeReflection()
        }

        try {
            val configs = getActivePlaybackConfigurations()
            lastConfigs = configs // Store for setPlayerVolume

            Log.d(TAG, "Found ${configs.size} active playback configurations (privileged)")

            // Build result array: [count, piid1, uid1, pid1, state1, piid2, uid2, pid2, state2, ...]
            val result = mutableListOf<Int>()
            result.add(configs.size)

            for (config in configs) {
                val piid = getPlayerInterfaceId(config)
                val uid = getClientUid(config)
                val pid = getClientPid(config)
                val state = getPlayerState(config)

                Log.d(TAG, "Config: piid=$piid, uid=$uid, pid=$pid, state=$state")

                result.add(piid)
                result.add(uid)
                result.add(pid)
                result.add(state)
            }

            return result.toIntArray()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting playback configurations", e)
            return IntArray(0)
        }
    }

    override fun setPlayerVolume(piid: Int, volume: Float): Boolean {
        Log.d(TAG, "setPlayerVolume: piid=$piid, volume=$volume")

        if (!reflectionInitialized) {
            initializeReflection()
        }

        // Find the config with matching piid
        val config = lastConfigs.find { getPlayerInterfaceId(it) == piid }
        if (config == null) {
            // Refresh configs and try again
            lastConfigs = getActivePlaybackConfigurations()
            val refreshedConfig = lastConfigs.find { getPlayerInterfaceId(it) == piid }
            if (refreshedConfig == null) {
                Log.w(TAG, "Player with piid=$piid not found")
                return false
            }
            return setVolumeForConfig(refreshedConfig, volume)
        }

        return setVolumeForConfig(config, volume)
    }

    private fun setVolumeForConfig(config: AudioPlaybackConfiguration, volume: Float): Boolean {
        try {
            val uid = getClientUid(config)
            val piid = getPlayerInterfaceId(config)
            
            Log.d(TAG, "setVolumeForConfig: piid=$piid, uid=$uid, volume=$volume")
            Log.d(TAG, "  setVolumeMethod=${setVolumeMethod != null}, getPlayerProxyMethod=${getPlayerProxyMethod != null}")
            
            if (getPlayerProxyMethod == null) {
                Log.e(TAG, "getPlayerProxyMethod is NULL - reflection failed!")
                return false
            }
            
            val playerProxy = getPlayerProxyMethod?.invoke(config)
            if (playerProxy == null) {
                Log.w(TAG, "PlayerProxy is null for config piid=$piid uid=$uid")
                return false
            }

            Log.d(TAG, "Got PlayerProxy: ${playerProxy.javaClass.name}")
            
            if (setVolumeMethod == null) {
                Log.e(TAG, "setVolumeMethod is NULL - cannot set volume!")
                return false
            }

            // Try to set volume
            setVolumeMethod!!.invoke(playerProxy, volume)
            Log.d(TAG, "Volume set successfully: piid=$piid, uid=$uid, volume=$volume")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume: ${e.message}", e)
            return false
        }
    }

    override fun destroy() {
        Log.d(TAG, "destroy called")
        audioService = null
        getActivePlaybackConfigsMethod = null
        lastConfigs = emptyList()
    }

    private fun initializeReflection() {
        try {
            val apcClass = AudioPlaybackConfiguration::class.java

            // getPlayerProxy
            try {
                getPlayerProxyMethod = apcClass.getDeclaredMethod("getPlayerProxy")
                getPlayerProxyMethod?.isAccessible = true
                Log.d(TAG, "Found getPlayerProxy method")
            } catch (_: NoSuchMethodException) {
                Log.w(TAG, "getPlayerProxy method not found")
            }

            // getClientUid
            try {
                getClientUidMethod = apcClass.getDeclaredMethod("getClientUid")
                getClientUidMethod?.isAccessible = true
                Log.d(TAG, "Found getClientUid method")
            } catch (_: NoSuchMethodException) {
                Log.w(TAG, "getClientUid method not found")
            }

            // getClientPid
            try {
                getClientPidMethod = apcClass.getDeclaredMethod("getClientPid")
                getClientPidMethod?.isAccessible = true
                Log.d(TAG, "Found getClientPid method")
            } catch (_: NoSuchMethodException) {
                Log.w(TAG, "getClientPid method not found")
            }

            // getPlayerInterfaceId
            try {
                getPlayerInterfaceIdMethod = apcClass.getDeclaredMethod("getPlayerInterfaceId")
                getPlayerInterfaceIdMethod?.isAccessible = true
                Log.d(TAG, "Found getPlayerInterfaceId method")
            } catch (_: NoSuchMethodException) {
                Log.w(TAG, "getPlayerInterfaceId method not found")
            }

            // Get PlayerProxy class and setVolume method
            try {
                val playerProxyClass = Class.forName("android.media.PlayerProxy")
                Log.d(TAG, "Found PlayerProxy class")

                // List methods for debugging
                val methods = playerProxyClass.declaredMethods
                Log.d(TAG, "PlayerProxy methods: ${methods.map { it.name }}")

                // Try single float signature
                try {
                    setVolumeMethod = playerProxyClass.getDeclaredMethod("setVolume", Float::class.javaPrimitiveType)
                    setVolumeMethod?.isAccessible = true
                    Log.d(TAG, "Found setVolume(float) method")
                } catch (_: NoSuchMethodException) {
                    Log.w(TAG, "setVolume(float) not found")
                }
            } catch (e: ClassNotFoundException) {
                Log.e(TAG, "PlayerProxy class not found", e)
            }

            reflectionInitialized = true
            Log.d(TAG, "Reflection initialization complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing reflection", e)
        }
    }

    private fun getPlayerInterfaceId(config: AudioPlaybackConfiguration): Int {
        return try {
            getPlayerInterfaceIdMethod?.invoke(config) as? Int ?: config.hashCode()
        } catch (_: Exception) {
            config.hashCode()
        }
    }

    private fun getClientUid(config: AudioPlaybackConfiguration): Int {
        return try {
            getClientUidMethod?.invoke(config) as? Int ?: -1
        } catch (_: Exception) {
            -1
        }
    }

    private fun getClientPid(config: AudioPlaybackConfiguration): Int {
        return try {
            getClientPidMethod?.invoke(config) as? Int ?: -1
        } catch (_: Exception) {
            -1
        }
    }

    private fun getPlayerState(config: AudioPlaybackConfiguration): Int {
        return try {
            val method = AudioPlaybackConfiguration::class.java.getDeclaredMethod("getPlayerState")
            method.isAccessible = true
            method.invoke(config) as? Int ?: 0
        } catch (_: Exception) {
            0
        }
    }
}
