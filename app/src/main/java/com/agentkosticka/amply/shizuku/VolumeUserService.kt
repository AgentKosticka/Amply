package com.agentkosticka.amply.shizuku

import android.media.AudioPlaybackConfiguration
import android.media.AudioManager
import android.os.IBinder
import android.os.Process
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import com.agentkosticka.amply.audio.LegacyStreamResolver
import com.agentkosticka.amply.audio.VolumeTarget
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
        private const val STATUS_OK = 1
        private const val STATUS_FAILED = -1
        private const val STATUS_DENIED = -2
        private const val STATUS_UNSUPPORTED = -3
        private const val STATUS_UNAVAILABLE = -4
    }

    // Direct access to IAudioService for privileged operations
    private var audioService: Any? = null
    private var audioServiceBinder: IBinder? = null
    private var audioServiceDeathRecipient: IBinder.DeathRecipient? = null
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
    @Synchronized
    private fun initializeAudioService(): Boolean {
        clearAudioService()
        try {
            // Get audio service binder via ServiceManager
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val audioBinder = getServiceMethod.invoke(null, "audio") as? IBinder

            if (audioBinder == null) {
                Log.e(TAG, "Failed to get audio service binder")
                return false
            }

            Log.d(TAG, "Got audio binder: $audioBinder")

            // Get IAudioService.Stub.asInterface
            val audioServiceStubClass = Class.forName("android.media.IAudioService\$Stub")
            val asInterfaceMethod = audioServiceStubClass.getMethod("asInterface", IBinder::class.java)
            audioService = asInterfaceMethod.invoke(null, audioBinder)

            if (audioService == null) {
                Log.e(TAG, "Failed to get IAudioService")
                return false
            }

            Log.d(TAG, "Got IAudioService: ${audioService!!.javaClass.name}")

            // Find getActivePlaybackConfigurations method
            getActivePlaybackConfigsMethod = audioService!!.javaClass.getMethod("getActivePlaybackConfigurations")
            val deathRecipient = IBinder.DeathRecipient {
                Log.w(TAG, "Android audio service binder died; it will be reacquired")
                clearAudioService()
            }
            audioBinder.linkToDeath(deathRecipient, 0)
            audioServiceBinder = audioBinder
            audioServiceDeathRecipient = deathRecipient
            Log.d(TAG, "Found getActivePlaybackConfigurations method on IAudioService")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioService", e)
            clearAudioService()
            return false
        }
    }

    @Synchronized
    private fun clearAudioService() {
        val binder = audioServiceBinder
        val recipient = audioServiceDeathRecipient
        if (binder != null && recipient != null) {
            try {
                binder.unlinkToDeath(recipient, 0)
            } catch (_: Exception) {
                // The binder may already be dead.
            }
        }
        audioService = null
        audioServiceBinder = null
        audioServiceDeathRecipient = null
        getActivePlaybackConfigsMethod = null
        lastConfigs = emptyList()
    }

    /**
     * Get active playback configurations directly from IAudioService
     */
    private fun getActivePlaybackConfigurations(): List<AudioPlaybackConfiguration>? {
        repeat(2) { attempt ->
            if (audioService == null && !initializeAudioService()) {
                return@repeat
            }

            val service = audioService ?: return@repeat
            val method = getActivePlaybackConfigsMethod ?: return@repeat
            try {
                val result = method.invoke(service)
                return (result as? List<*>)?.filterIsInstance<AudioPlaybackConfiguration>()
                    ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Playback query failed on attempt ${attempt + 1}", e)
                clearAudioService()
            }
        }
        return null
    }

    override fun getActivePlaybacks(): IntArray {
        if (!reflectionInitialized) {
            initializeReflection()
        }

        try {
            // A one-element response is the valid wire representation of an idle
            // service. Never return a zero-length array: the client reserves that for
            // a query/protocol failure and temporarily uses its local fallback.
            val configs = getActivePlaybackConfigurations() ?: return intArrayOf(0)
            lastConfigs = configs // Store for setPlayerVolume

            // [count, piid, uid, pid, state, legacyStreamType, ...]
            val result = mutableListOf<Int>()
            result.add(configs.size)

            for (config in configs) {
                val piid = getPlayerInterfaceId(config)
                val uid = getClientUid(config)
                val pid = getClientPid(config)
                val state = getPlayerState(config)
                val attributes = config.audioAttributes
                val allFlags = runCatching {
                    val method = attributes.javaClass.getDeclaredMethod("getAllFlags")
                    method.isAccessible = true
                    method.invoke(attributes) as Int
                }.getOrElse { attributes.flags }
                val streamType = LegacyStreamResolver.resolve(attributes.usage, allFlags).streamType

                result.add(piid)
                result.add(uid)
                result.add(pid)
                result.add(state)
                result.add(streamType)
            }

            return result.toIntArray()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting playback configurations", e)
            return intArrayOf(0)
        }
    }

    override fun setPlayerVolume(piid: Int, volume: Float): Boolean {
        if (!reflectionInitialized) {
            initializeReflection()
        }

        // Find the config with matching piid
        val config = lastConfigs.find { getPlayerInterfaceId(it) == piid }
        if (config == null) {
            // Refresh configs and try again
            lastConfigs = getActivePlaybackConfigurations() ?: return false
            val refreshedConfig = lastConfigs.find { getPlayerInterfaceId(it) == piid }
            if (refreshedConfig == null) {
                Log.w(TAG, "Player with piid=$piid not found")
                return false
            }
            return setVolumeForConfig(refreshedConfig, volume)
        }

        return setVolumeForConfig(config, volume)
    }

    override fun getStreamTopology(): IntArray {
        val service: Any = audioService ?: run {
            if (initializeAudioService()) audioService else null
        } ?: return intArrayOf(0, 12, *IntArray(12) { it })

        val identity = IntArray(12) { it }
        val method = service.javaClass.methods.firstOrNull {
            it.name == "getStreamTypeAlias" && it.parameterTypes.size == 1
        } ?: return intArrayOf(0, 12, *identity)
        return try {
            val aliases = IntArray(12) { stream -> method.invoke(service, stream) as Int }
            intArrayOf(1, aliases.size, *aliases)
        } catch (e: Exception) {
            Log.w(TAG, "Authoritative stream aliases unavailable", e)
            intArrayOf(0, 12, *identity)
        }
    }

    override fun setSystemStreamVolume(streamType: Int, index: Int): Int {
        if (streamType !in 0..11 || streamType == VolumeTarget.ENFORCED_AUDIBLE.streamType) {
            return STATUS_DENIED
        }
        val service: Any = audioService ?: run {
            if (initializeAudioService()) audioService else null
        } ?: return STATUS_UNAVAILABLE
        return try {
            invokeCompatible(
                service,
                listOf("setStreamVolumeWithAttribution", "setStreamVolume"),
                streamType,
                index,
                0,
                "com.android.shell",
                null
            )
            val applied = invokeAudioService(service, "getStreamVolume", streamType) as? Int
            if (applied == index) STATUS_OK else STATUS_FAILED
        } catch (e: SecurityException) {
            Log.w(TAG, "System stream update denied stream=$streamType", e)
            STATUS_DENIED
        } catch (e: NoSuchMethodException) {
            STATUS_UNSUPPORTED
        } catch (e: Exception) {
            Log.e(TAG, "System stream update failed stream=$streamType", e)
            STATUS_FAILED
        }
    }

    override fun applyRingerExperiment(method: Int, target: Int, restoreVolume: Int): Int {
        val service: Any = audioService ?: run {
            if (initializeAudioService()) audioService else null
        } ?: return STATUS_UNAVAILABLE
        val targetMode = when (target) {
            0 -> AudioManager.RINGER_MODE_NORMAL
            1 -> AudioManager.RINGER_MODE_VIBRATE
            2 -> AudioManager.RINGER_MODE_SILENT
            else -> return STATUS_UNSUPPORTED
        }
        return try {
            when (method) {
                6 -> invokeAudioService(service, "setRingerModeExternal", targetMode, "com.android.shell")
                7 -> invokeAudioService(service, "setRingerModeInternal", targetMode, "com.android.shell")
                8 -> adjustPrivilegedStream(service, AudioManager.STREAM_NOTIFICATION, targetMode)
                9 -> adjustPrivilegedStream(service, AudioManager.STREAM_RING, targetMode)
                else -> return STATUS_UNSUPPORTED
            }
            if (targetMode == AudioManager.RINGER_MODE_NORMAL && restoreVolume > 0) {
                runCatching {
                    invokeCompatible(
                        service,
                        listOf("setStreamVolumeWithAttribution", "setStreamVolume"),
                        AudioManager.STREAM_NOTIFICATION,
                        restoreVolume,
                        0,
                        "com.android.shell",
                        null
                    )
                }
            }
            STATUS_OK
        } catch (e: SecurityException) {
            Log.w(TAG, "Ringer experiment denied method=$method", e)
            STATUS_DENIED
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "Ringer experiment unsupported method=$method", e)
            STATUS_UNSUPPORTED
        } catch (e: Exception) {
            Log.e(TAG, "Ringer experiment failed method=$method", e)
            STATUS_FAILED
        }
    }

    private fun adjustPrivilegedStream(service: Any, stream: Int, targetMode: Int) {
        repeat(20) {
            val currentMode = invokeAudioService(service, "getRingerModeExternal") as Int
            if (currentMode == targetMode) return
            val direction = if (targetMode == AudioManager.RINGER_MODE_NORMAL) {
                AudioManager.ADJUST_RAISE
            } else {
                AudioManager.ADJUST_LOWER
            }
            invokeCompatible(
                service,
                listOf("adjustStreamVolumeWithAttribution", "adjustStreamVolume"),
                stream,
                direction,
                AudioManager.FLAG_ALLOW_RINGER_MODES,
                "com.android.shell",
                null
            )
        }
    }

    private fun invokeAudioService(service: Any, name: String, vararg args: Any?): Any? {
        val method = service.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == args.size
        } ?: throw NoSuchMethodException(name)
        return method.invoke(service, *args)
    }

    private fun invokeCompatible(
        service: Any,
        names: List<String>,
        stream: Int,
        value: Int,
        flags: Int,
        packageName: String,
        attributionTag: String?
    ): Any? {
        names.forEach { name ->
            val candidates = service.javaClass.methods.filter { it.name == name }
            candidates.forEach { method ->
                val args: Array<Any?> = when (method.parameterTypes.size) {
                    4 -> arrayOf(stream, value, flags, packageName)
                    5 -> arrayOf(stream, value, flags, packageName, attributionTag)
                    else -> return@forEach
                }
                return method.invoke(service, *args)
            }
        }
        throw NoSuchMethodException(names.joinToString())
    }

    private fun setVolumeForConfig(config: AudioPlaybackConfiguration, volume: Float): Boolean {
        try {
            val uid = getClientUid(config)
            val piid = getPlayerInterfaceId(config)
            
            if (getPlayerProxyMethod == null) {
                Log.e(TAG, "getPlayerProxyMethod is NULL - reflection failed!")
                return false
            }
            
            val playerProxy = getPlayerProxyMethod?.invoke(config)
            if (playerProxy == null) {
                Log.w(TAG, "PlayerProxy is null for config piid=$piid uid=$uid")
                return false
            }

            if (setVolumeMethod == null) {
                Log.e(TAG, "setVolumeMethod is NULL - cannot set volume!")
                return false
            }

            // Try to set volume
            setVolumeMethod!!.invoke(playerProxy, volume)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume: ${e.message}", e)
            return false
        }
    }

    override fun destroy() {
        Log.d(TAG, "destroy called")
        clearAudioService()
        System.exit(0)
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
