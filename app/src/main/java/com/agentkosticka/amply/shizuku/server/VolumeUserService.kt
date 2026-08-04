package com.agentkosticka.amply.shizuku.server

import android.annotation.SuppressLint
import android.media.AudioPlaybackConfiguration
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import com.agentkosticka.amply.audio.routing.LegacyStreamResolver
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.shizuku.protocol.IVolumeService
import com.agentkosticka.amply.shizuku.protocol.OperationResultParcel
import com.agentkosticka.amply.shizuku.protocol.PlaybackSessionParcel
import com.agentkosticka.amply.shizuku.protocol.VOLUME_PROTOCOL_CAPABILITIES
import com.agentkosticka.amply.shizuku.protocol.VOLUME_PROTOCOL_VERSION
import com.agentkosticka.amply.shizuku.protocol.VolumeOperationStatus
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * Shizuku UserService that runs in the privileged shell process (UID 2000).
 *
 * Because this runs with shell permissions, the AudioPlaybackConfiguration
 * objects returned by getActivePlaybackConfigurations() will contain
 * the full data (uid, pid, playerProxy) that is normally sanitized
 * when running in an app's process.
 */
@SuppressLint("PrivateApi")
open class VolumeUserService : IVolumeService.Stub() {

    companion object {
        private const val TAG = "VolumeUserService"
        private const val STATUS_OK = VolumeOperationStatus.OK
        private const val STATUS_FAILED = VolumeOperationStatus.FAILED
        private const val STATUS_DENIED = VolumeOperationStatus.DENIED
        private const val STATUS_UNSUPPORTED = VolumeOperationStatus.UNSUPPORTED
        private const val STATUS_UNAVAILABLE = VolumeOperationStatus.UNAVAILABLE
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
    private val compatibleMethodCache = ConcurrentHashMap<String, Method>()

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
            val audioServiceStubClass = Class.forName($$"android.media.IAudioService$Stub")
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

    override fun getProtocolVersion(): Int = VOLUME_PROTOCOL_VERSION

    override fun getCapabilities(): Long = VOLUME_PROTOCOL_CAPABILITIES

    @Synchronized
    override fun getActivePlaybacks(): MutableList<PlaybackSessionParcel> {
        if (!reflectionInitialized) {
            initializeReflection()
        }

        try {
            val configs = getActivePlaybackConfigurations() ?: return mutableListOf()
            lastConfigs = configs // Store for setPlayerVolume

            return configs.mapNotNullTo(mutableListOf()) { config ->
                val piid = getPlayerInterfaceId(config)
                val uid = getClientUid(config)
                val pid = getClientPid(config)
                if (piid <= 0 || uid < 0 || pid < 0) return@mapNotNullTo null
                val state = getPlayerState(config)
                val attributes = config.audioAttributes
                val allFlags = runCatching {
                    val method = attributes.javaClass.getDeclaredMethod("getAllFlags")
                    method.isAccessible = true
                    method.invoke(attributes) as Int
                }.getOrElse { attributes.flags }
                val streamType = LegacyStreamResolver.resolve(attributes.usage, allFlags).streamType

                PlaybackSessionParcel(
                    userId = (uid / 100_000).coerceAtLeast(0),
                    uid = uid,
                    pid = pid,
                    playerInterfaceId = piid,
                    playerState = state,
                    streamType = streamType,
                    usage = attributes.usage,
                    contentType = attributes.contentType,
                    muted = readMuted(config),
                    volume = 1f
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting playback configurations", e)
            return mutableListOf()
        }
    }

    @Synchronized
    override fun setPlayerVolume(piid: Int, volume: Float): OperationResultParcel {
        if (piid <= 0 || !volume.isFinite() || volume !in 0f..1f) {
            return OperationResultParcel.failure(
                VolumeOperationStatus.INVALID_ARGUMENT,
                "invalid_player_volume"
            )
        }
        if (!reflectionInitialized) {
            initializeReflection()
        }

        // Find the config with matching piid
        val config = lastConfigs.find { getPlayerInterfaceId(it) == piid }
        if (config == null) {
            // Refresh configs and try again
            lastConfigs = getActivePlaybackConfigurations()
                ?: return OperationResultParcel.failure(STATUS_UNAVAILABLE, "playback_query_unavailable")
            val refreshedConfig = lastConfigs.find { getPlayerInterfaceId(it) == piid }
            if (refreshedConfig == null) {
                return OperationResultParcel.failure(VolumeOperationStatus.NOT_FOUND, "player_not_found")
            }
            return if (setVolumeForConfig(refreshedConfig, volume)) {
                OperationResultParcel.success(verified = false)
            } else {
                OperationResultParcel.failure(STATUS_FAILED, "player_volume_failed")
            }
        }

        return if (setVolumeForConfig(config, volume)) {
            OperationResultParcel.success(verified = false)
        } else {
            OperationResultParcel.failure(STATUS_FAILED, "player_volume_failed")
        }
    }

    @Synchronized
    override fun getStreamTopology(): IntArray {
        val service: Any = audioService ?: run {
            if (initializeAudioService()) audioService else null
        } ?: return intArrayOf(0, 12, *IntArray(12) { it })

        val identity = IntArray(12) { it }
        return try {
            val aliases = IntArray(12) { stream ->
                invokeAudioService(service, "getStreamTypeAlias", stream) as Int
            }
            intArrayOf(1, aliases.size, *aliases)
        } catch (e: Exception) {
            Log.w(TAG, "Authoritative stream aliases unavailable", e)
            intArrayOf(0, 12, *identity)
        }
    }

    @Synchronized
    override fun setSystemStreamVolume(streamType: Int, index: Int): OperationResultParcel {
        if (streamType !in 0..11 || index < 0) {
            return OperationResultParcel.failure(
                VolumeOperationStatus.INVALID_ARGUMENT,
                "invalid_stream_volume"
            )
        }
        if (streamType == VolumeTarget.ENFORCED_AUDIBLE.streamType) {
            return OperationResultParcel.failure(STATUS_DENIED, "protected_stream")
        }
        val service: Any = audioService ?: run {
            if (initializeAudioService()) audioService else null
        } ?: return OperationResultParcel.failure(STATUS_UNAVAILABLE, "audio_service_unavailable")
        return try {
            val minimum = runCatching {
                invokeAudioService(service, "getStreamMinVolume", streamType) as Int
            }.getOrDefault(0)
            val maximum = invokeAudioService(service, "getStreamMaxVolume", streamType) as Int
            if (index !in minimum..maximum) {
                return OperationResultParcel.failure(
                    VolumeOperationStatus.INVALID_ARGUMENT,
                    "stream_index_out_of_range"
                )
            }
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
            if (applied == index) {
                OperationResultParcel.success(verified = true)
            } else {
                OperationResultParcel.failure(STATUS_FAILED, "stream_volume_not_applied")
            }
        } catch (_: SecurityException) {
            OperationResultParcel.failure(STATUS_DENIED, "stream_volume_denied")
        } catch (_: NoSuchMethodException) {
            OperationResultParcel.failure(STATUS_UNSUPPORTED, "stream_volume_unsupported")
        } catch (e: Exception) {
            Log.e(TAG, "System stream update failed", e)
            OperationResultParcel.failure(STATUS_FAILED, "stream_volume_failed")
        }
    }

    @Synchronized
    override fun applyRingerExperiment(method: Int, target: Int, restoreVolume: Int): Int {
        if (method !in 1..10 || target !in 0..2 || restoreVolume !in 0..100) {
            return VolumeOperationStatus.INVALID_ARGUMENT
        }
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
        val cacheKey = methodCacheKey(service, name, args)
        compatibleMethodCache[cacheKey]?.let { cached ->
            try {
                return cached.invoke(service, *args)
            } catch (_: IllegalArgumentException) {
                compatibleMethodCache.remove(cacheKey, cached)
            }
        }
        val candidates = service.javaClass.methods.filter {
            it.name == name && parametersAccept(it.parameterTypes, args)
        }
        var lastFailure: IllegalArgumentException? = null
        candidates.forEach { method ->
            try {
                return method.invoke(service, *args).also {
                    compatibleMethodCache[cacheKey] = method
                }
            } catch (error: IllegalArgumentException) {
                lastFailure = error
            }
        }
        throw lastFailure ?: NoSuchMethodException(name)
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
            val candidateArgs = listOf(
                arrayOf<Any?>(stream, value, flags, packageName),
                arrayOf<Any?>(stream, value, flags, packageName, attributionTag)
            )
            candidateArgs.forEach { args ->
                val cacheKey = methodCacheKey(service, name, args)
                compatibleMethodCache[cacheKey]?.let { cached ->
                    try {
                        return cached.invoke(service, *args)
                    } catch (_: IllegalArgumentException) {
                        compatibleMethodCache.remove(cacheKey, cached)
                    }
                }
            }
            val candidates = service.javaClass.methods.filter { it.name == name }
            candidates.forEach { method ->
                val args: Array<Any?> = when (method.parameterTypes.size) {
                    4 -> arrayOf(stream, value, flags, packageName)
                    5 -> arrayOf(stream, value, flags, packageName, attributionTag)
                    else -> return@forEach
                }
                if (!parametersAccept(method.parameterTypes, args)) return@forEach
                try {
                    return method.invoke(service, *args).also {
                        compatibleMethodCache[methodCacheKey(service, name, args)] = method
                    }
                } catch (_: IllegalArgumentException) {
                    // Continue to the next compatible overload.
                }
            }
        }
        throw NoSuchMethodException(names.joinToString())
    }

    private fun methodCacheKey(
        service: Any,
        name: String,
        args: Array<out Any?>
    ): String = buildString {
        append(Build.VERSION.SDK_INT)
        append(':')
        append(service.javaClass.name)
        append(':')
        append(name)
        args.forEach { argument ->
            append(':')
            append(argument?.javaClass?.name ?: "null")
        }
    }

    private fun parametersAccept(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (types.size != args.size) return false
        return types.indices.all { index ->
            val type = types[index]
            val argument = args[index]
            when {
                argument == null -> !type.isPrimitive
                type.isInstance(argument) -> true
                type == Integer.TYPE -> argument is Int
                type == java.lang.Long.TYPE -> argument is Long
                type == java.lang.Float.TYPE -> argument is Float
                type == java.lang.Double.TYPE -> argument is Double
                type == java.lang.Boolean.TYPE -> argument is Boolean
                type == java.lang.Byte.TYPE -> argument is Byte
                type == java.lang.Short.TYPE -> argument is Short
                type == Character.TYPE -> argument is Char
                else -> false
            }
        }
    }

    private fun setVolumeForConfig(config: AudioPlaybackConfiguration, volume: Float): Boolean {
        try {
            if (getPlayerProxyMethod == null) {
                Log.e(TAG, "getPlayerProxyMethod is NULL - reflection failed!")
                return false
            }
            
            val playerProxy = getPlayerProxyMethod?.invoke(config)
            if (playerProxy == null) {
                Log.w(TAG, "PlayerProxy is unavailable")
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
        exitProcess(0)
    }


    @Synchronized
    private fun initializeReflection() {
        if (reflectionInitialized) return
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
            getPlayerInterfaceIdMethod?.invoke(config) as? Int ?: -1
        } catch (_: Exception) {
            -1
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

    private fun readMuted(config: AudioPlaybackConfiguration): Boolean = runCatching {
        val method = AudioPlaybackConfiguration::class.java.getDeclaredMethod("isMuted")
        method.isAccessible = true
        method.invoke(config) as? Boolean ?: false
    }.getOrDefault(false)
}
