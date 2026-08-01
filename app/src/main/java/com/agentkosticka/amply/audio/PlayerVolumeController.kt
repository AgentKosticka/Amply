package com.agentkosticka.amply.audio

import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.util.Log
import java.lang.reflect.Method

/**
 * Controls per-app volume using AudioPlaybackConfiguration API.
 *
 * Uses local reflection with HiddenApiBypass to access hidden APIs.
 * The volume multiplier (0.0 to 1.0) is applied on top of the stream volume,
 * allowing per-app volume control without affecting other apps.
 *
 * Note: Volume control may not work on devices with strict hidden API enforcement.
 */
class PlayerVolumeController(
    context: Context
) {

    companion object {
        private const val TAG = "PlayerVolumeController"
        private const val PLAYER_STATE_STARTED = 2
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    // Cached reflection methods
    private var getPlayerProxyMethod: Method? = null
    private var setVolumeMethod: Method? = null
    private var setVolumeStereoMethod: Method? = null  // Alternative signature
    private var getClientUidMethod: Method? = null
    private var getClientPidMethod: Method? = null  // VolumeManager uses PID!
    private var getPlayerInterfaceIdMethod: Method? = null
    private var reflectionInitialized = false

    // PID to UID/Package mapping cache
    private val pidToUidMap = mutableMapOf<Int, Int>()

    /**
     * Data class representing an active player with volume control capability
     */
    data class ActivePlayer(
        val piid: Int,                  // Player Interface ID (unique identifier)
        val uid: Int,                   // App UID
        val clientUid: Int,             // Client UID (usually same as uid)
        val sessionId: Int,             // Audio session ID
        val playerState: Int,           // Player state (started, paused, etc.)
        val playerProxy: Any?,          // The PlayerProxy object for volume control (null when using service)
        val config: AudioPlaybackConfiguration?  // Original config for reference (null when using service)
    )

    /**
     * Gets all active audio playback configurations and extracts player info.
     * Uses local reflection with HiddenApiBypass.
     *
     * @return List of ActivePlayer objects with volume control capability
     */
    fun getActivePlayers(): List<ActivePlayer> {
        val players = mutableListOf<ActivePlayer>()

        try {
            val configs = audioManager.activePlaybackConfigurations
            Log.d(TAG, "Found ${configs.size} active playback configurations (local)")

            // Initialize reflection methods if needed
            if (!reflectionInitialized) {
                initializeReflection()
            }

            for (config in configs) {
                try {
                    // Extract basic info using public APIs
                    val audioAttributes = config.audioAttributes
                    val sessionId = getSessionId(config)

                    // Get piid (player interface ID) via reflection
                    val piid = getPlayerInterfaceId(config)

                    // Get client UID via reflection
                    var clientUid = getClientUid(config)

                    // If UID is still -1, try alternative methods
                    if (clientUid <= 0) {
                        Log.d(TAG, "UID lookup failed for piid=$piid, trying alternatives...")

                        // Try to get UID from the config object itself
                        try {
                            val allFields = config.javaClass.declaredFields
                            for (field in allFields) {
                                if (field.name.lowercase().contains("uid") ||
                                    field.name.lowercase().contains("client")) {
                                    field.isAccessible = true
                                    val value = field.get(config)
                                    if (value is Int && value >= 10000) {
                                        clientUid = value
                                        Log.d(TAG, "Found UID via field ${field.name}: $clientUid")
                                        break
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Field-based UID lookup failed: ${e.message}")
                        }
                    }

                    // Get PlayerProxy via reflection for volume control
                    val playerProxy = getPlayerProxy(config)

                    // Get player state
                    val playerState = getPlayerState(config)

                    // Log full config info for debugging
                    Log.d(TAG, "Config (local): piid=$piid, uid=$clientUid, session=$sessionId, " +
                            "state=$playerState, hasProxy=${playerProxy != null}, " +
                            "usage=${audioAttributes.usage}, contentType=${audioAttributes.contentType}")

                    // Only include players that are actively rendering audio.
                    if (playerState == PLAYER_STATE_STARTED) {
                        val player = ActivePlayer(
                            piid = piid,
                            uid = clientUid,
                            clientUid = clientUid,
                            sessionId = sessionId,
                            playerState = playerState,
                            playerProxy = playerProxy,
                            config = config
                        )
                        players.add(player)

                        Log.d(TAG, "Added active player (local): piid=$piid, uid=$clientUid")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing config", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting active playback configurations", e)
        }

        return players
    }

    /**
     * Initialize reflection methods for PlayerProxy access
     * Tries multiple method signatures for compatibility across Android versions
     */
    private fun initializeReflection() {
        try {
            val apcClass = AudioPlaybackConfiguration::class.java

            // Cache getPlayerProxy method
            try {
                getPlayerProxyMethod = apcClass.getDeclaredMethod("getPlayerProxy")
                getPlayerProxyMethod?.isAccessible = true
                Log.d(TAG, "Found getPlayerProxy method")
            } catch (_: NoSuchMethodException) {
                Log.w(TAG, "getPlayerProxy method not found")
            }

            // Cache getClientUid method
            try {
                getClientUidMethod = apcClass.getDeclaredMethod("getClientUid")
                getClientUidMethod?.isAccessible = true
                Log.d(TAG, "Found getClientUid method")
            } catch (_: NoSuchMethodException) {
                Log.w(TAG, "getClientUid method not found")
            }

            // Cache getClientPid method (VolumeManager approach - more reliable!)
            try {
                getClientPidMethod = apcClass.getDeclaredMethod("getClientPid")
                getClientPidMethod?.isAccessible = true
                Log.d(TAG, "Found getClientPid method")
            } catch (_: NoSuchMethodException) {
                Log.w(TAG, "getClientPid method not found")
            }

            // Cache getPlayerInterfaceId method
            try {
                getPlayerInterfaceIdMethod = apcClass.getDeclaredMethod("getPlayerInterfaceId")
                getPlayerInterfaceIdMethod?.isAccessible = true
                Log.d(TAG, "Found getPlayerInterfaceId method")
            } catch (_: NoSuchMethodException) {
                Log.w(TAG, "getPlayerInterfaceId method not found")
            }

            // Get PlayerProxy class and its setVolume methods
            try {
                val playerProxyClass = Class.forName("android.media.PlayerProxy")
                Log.d(TAG, "Found PlayerProxy class")

                // List all methods for debugging
                val methods = playerProxyClass.declaredMethods
                Log.d(TAG, "PlayerProxy methods: ${methods.map { "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})" }}")

                // Try single float signature first (most common on newer Android)
                try {
                    setVolumeMethod = playerProxyClass.getDeclaredMethod("setVolume", Float::class.javaPrimitiveType)
                    setVolumeMethod?.isAccessible = true
                    Log.d(TAG, "Found setVolume(float) method")
                } catch (_: NoSuchMethodException) {
                    // Try Float wrapper class
                    try {
                        setVolumeMethod = playerProxyClass.getDeclaredMethod("setVolume", Float::class.java)
                        setVolumeMethod?.isAccessible = true
                        Log.d(TAG, "Found setVolume(Float) method")
                    } catch (_: NoSuchMethodException) {
                        Log.w(TAG, "setVolume(float) not found")
                    }
                }

                // Try stereo signature (left, right floats)
                try {
                    setVolumeStereoMethod = playerProxyClass.getDeclaredMethod(
                        "setVolume",
                        Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType
                    )
                    setVolumeStereoMethod?.isAccessible = true
                    Log.d(TAG, "Found setVolume(float, float) method")
                } catch (_: NoSuchMethodException) {
                    Log.w(TAG, "setVolume(float, float) not found")
                }

            } catch (e: ClassNotFoundException) {
                Log.e(TAG, "PlayerProxy class not found", e)
            }

            reflectionInitialized = true
            Log.d(TAG, "Reflection initialization complete. " +
                    "setVolume=${setVolumeMethod != null}, " +
                    "setVolumeStereo=${setVolumeStereoMethod != null}, " +
                    "getPlayerProxy=${getPlayerProxyMethod != null}")

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing reflection", e)
        }
    }

    /**
     * Get PlayerProxy from AudioPlaybackConfiguration via reflection
     */
    private fun getPlayerProxy(config: AudioPlaybackConfiguration): Any? {
        return try {
            val proxy = getPlayerProxyMethod?.invoke(config)
            Log.d(TAG, "getPlayerProxy result: $proxy (type: ${proxy?.javaClass?.name})")
            proxy
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get PlayerProxy: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Get session ID from AudioPlaybackConfiguration
     */
    private fun getSessionId(config: AudioPlaybackConfiguration): Int {
        return try {
            // Try getAudioSessionId method (available in some versions)
            val method = AudioPlaybackConfiguration::class.java
                .getDeclaredMethod("getAudioSessionId")
            method.isAccessible = true
            method.invoke(config) as? Int ?: 0
        } catch (_: NoSuchMethodException) {
            config.hashCode()
        } catch (_: Exception) {
            config.hashCode()
        }
    }

    /**
     * Get player interface ID (piid) from AudioPlaybackConfiguration
     */
    private fun getPlayerInterfaceId(config: AudioPlaybackConfiguration): Int {
        // Try cached method first
        if (getPlayerInterfaceIdMethod != null) {
            try {
                val result = getPlayerInterfaceIdMethod?.invoke(config) as? Int
                if (result != null) {
                    return result
                }
            } catch (e: Exception) {
                Log.w(TAG, "getPlayerInterfaceId method failed: ${e.message}")
            }
        }

        return config.hashCode()
    }

    /**
     * Get client UID from AudioPlaybackConfiguration
     * Uses VolumeManager approach: Try PID first, then map to UID via ActivityManager
     */
    private fun getClientUid(config: AudioPlaybackConfiguration): Int {
        // ================================================
        // APPROACH 1: Try getClientUid directly
        // ================================================
        if (getClientUidMethod != null) {
            try {
                val rawResult = getClientUidMethod?.invoke(config)
                Log.d(TAG, "getClientUid raw result: $rawResult (type: ${rawResult?.javaClass?.name})")
                val result = rawResult as? Int
                if (result != null && result > 0) {
                    Log.d(TAG, "Got UID via getClientUid: $result")
                    return result
                } else {
                    Log.d(TAG, "getClientUid returned invalid: $result")
                }
            } catch (e: Exception) {
                Log.w(TAG, "getClientUid method failed: ${e.message}")
                e.printStackTrace()
            }
        } else {
            Log.w(TAG, "getClientUidMethod is null")
        }

        // ================================================
        // APPROACH 2: VolumeManager style - Get PID and map to UID
        // ================================================
        if (getClientPidMethod != null) {
            try {
                val rawPid = getClientPidMethod?.invoke(config)
                Log.d(TAG, "getClientPid raw result: $rawPid (type: ${rawPid?.javaClass?.name})")
                val pid = rawPid as? Int
                if (pid != null && pid > 0) {
                    Log.d(TAG, "Got PID via getClientPid: $pid")

                    // Check cache first
                    pidToUidMap[pid]?.let {
                        Log.d(TAG, "Found UID in cache for PID $pid: $it")
                        return it
                    }

                    // Map PID to UID using ActivityManager
                    val uid = mapPidToUid(pid)
                    if (uid > 0) {
                        pidToUidMap[pid] = uid
                        Log.d(TAG, "Mapped PID $pid to UID $uid")
                        return uid
                    } else {
                        Log.w(TAG, "mapPidToUid failed for PID $pid")
                    }
                } else {
                    Log.d(TAG, "getClientPid returned invalid: $pid")
                }
            } catch (e: Exception) {
                Log.w(TAG, "getClientPid method failed: ${e.message}")
                e.printStackTrace()
            }
        } else {
            Log.w(TAG, "getClientPidMethod is null")
        }

        return -1 // Return -1 to indicate failure
    }

    /**
     * Map a PID to UID using ActivityManager.getRunningAppProcesses()
     * This is the VolumeManager approach
     */
    private fun mapPidToUid(pid: Int): Int {
        try {
            val processes = activityManager.runningAppProcesses ?: return -1
            for (process in processes) {
                if (process.pid == pid) {
                    Log.d(TAG, "Found process for PID $pid: ${process.processName}, UID=${process.uid}")
                    return process.uid
                }
            }
            Log.w(TAG, "No process found for PID $pid")
        } catch (e: Exception) {
            Log.e(TAG, "Error mapping PID to UID", e)
        }
        return -1
    }

    /**
     * Get player state from AudioPlaybackConfiguration (API 28+)
     * States: 0=idle, 1=configured, 2=started, 3=paused, 4=stopped, 5=released
     */
    private fun getPlayerState(config: AudioPlaybackConfiguration): Int {
        return try {
            val method = AudioPlaybackConfiguration::class.java
                .getDeclaredMethod("getPlayerState")
            method.isAccessible = true
            method.invoke(config) as? Int ?: 0
        } catch (_: Exception) {
            // Assume started if we can't get state
            2
        }
    }

    /**
     * Set volume for a specific player by piid.
     * Uses local reflection via PlayerProxy.
     *
     * @param piid Player interface ID
     * @param volume Volume level from 0.0 (mute) to 1.0 (full)
     * @return true if volume was set successfully
     */
    fun setPlayerVolumeByPiid(piid: Int, volume: Float): Boolean {
        val clampedVolume = volume.coerceIn(0f, 1f)
        return setVolumeByPiid(piid, clampedVolume)
    }

    /**
     * Set volume for a specific player using PlayerProxy (local reflection).
     * This is the core volume control method for local fallback.
     *
     * @param playerProxy The PlayerProxy object obtained from getActivePlayers()
     * @param volume Volume level from 0.0 (mute) to 1.0 (full)
     * @return true if volume was set successfully
     */
    fun setPlayerVolume(playerProxy: Any?, volume: Float): Boolean {
        if (playerProxy == null) {
            Log.w(TAG, "Cannot set volume: playerProxy is null")
            return false
        }

        if (!reflectionInitialized) {
            initializeReflection()
        }

        // Clamp volume to valid range
        val clampedVolume = volume.coerceIn(0f, 1f)

        // Try mono signature first: setVolume(float)
        if (setVolumeMethod != null) {
            try {
                setVolumeMethod?.invoke(playerProxy, clampedVolume)
                Log.d(TAG, "Set volume to $clampedVolume via setVolume(float)")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "setVolume(float) failed: ${e.message}")
            }
        }

        // Try stereo signature: setVolume(float, float)
        if (setVolumeStereoMethod != null) {
            try {
                setVolumeStereoMethod?.invoke(playerProxy, clampedVolume, clampedVolume)
                Log.d(TAG, "Set volume to $clampedVolume via setVolume(float, float)")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "setVolume(float, float) failed: ${e.message}")
            }
        }

        // Try direct method call on the object as last resort
        try {
            val proxyClass = playerProxy.javaClass
            Log.d(TAG, "Trying direct method lookup on ${proxyClass.name}")

            // Try to find any setVolume method
            val methods = proxyClass.methods.filter { it.name == "setVolume" }
            Log.d(TAG, "Found ${methods.size} setVolume methods: ${methods.map { it.parameterTypes.map { p -> p.simpleName } }}")

            for (method in methods) {
                try {
                    when (method.parameterCount) {
                        1 -> {
                            method.invoke(playerProxy, clampedVolume)
                            Log.d(TAG, "Set volume via direct 1-param method")
                            return true
                        }
                        2 -> {
                            method.invoke(playerProxy, clampedVolume, clampedVolume)
                            Log.d(TAG, "Set volume via direct 2-param method")
                            return true
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Direct method ${method.parameterCount}-param failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Direct method lookup failed", e)
        }

        Log.e(TAG, "All volume set attempts failed")
        return false
    }

    /**
     * Set volume for a player by piid (player interface ID).
     * Searches current active players and sets volume if found.
     *
     * @param piid The player interface ID
     * @param volume Volume level from 0.0 to 1.0
     * @return true if player was found and volume was set
     */
    fun setVolumeByPiid(piid: Int, volume: Float): Boolean {
        val players = getActivePlayers()
        val player = players.find { it.piid == piid }

        return if (player != null) {
            setPlayerVolume(player.playerProxy, volume)
        } else {
            Log.w(TAG, "Player with piid=$piid not found")
            false
        }
    }

    /**
     * Set volume for all players belonging to a specific UID (app).
     * This affects all audio output from the app.
     *
     * @param uid The app's UID
     * @param volume Volume level from 0.0 to 1.0
     * @return Number of players that were successfully updated
     */
    fun setVolumeByUid(uid: Int, volume: Float): Int {
        val players = getActivePlayers()
        val appPlayers = players.filter { it.uid == uid || it.clientUid == uid }

        var successCount = 0
        for (player in appPlayers) {
            if (setPlayerVolume(player.playerProxy, volume)) {
                successCount++
            }
        }

        Log.d(TAG, "Set volume for $successCount/${appPlayers.size} players with uid=$uid")
        return successCount
    }

    /**
     * Get unique UIDs of all currently playing apps
     */
    fun getActiveAppUids(): Set<Int> {
        return getActivePlayers()
            .map { it.uid }
            .filter { it >= 10000 } // Filter out system UIDs
            .toSet()
    }

    /**
     * Register callback for playback configuration changes.
     * This provides real-time updates when apps start/stop playing.
     *
     * @param callback Callback to invoke when configurations change
     */
    fun registerPlaybackCallback(callback: AudioManager.AudioPlaybackCallback) {
        audioManager.registerAudioPlaybackCallback(callback, null)
        Log.d(TAG, "Registered playback callback")
    }

    /**
     * Unregister playback callback
     */
    fun unregisterPlaybackCallback(callback: AudioManager.AudioPlaybackCallback) {
        audioManager.unregisterAudioPlaybackCallback(callback)
        Log.d(TAG, "Unregistered playback callback")
    }

    /** Returns usages for players that are actively rendering in a playback callback snapshot. */
    fun getActivePlaybackUsages(configs: List<AudioPlaybackConfiguration>): Set<Int> =
        configs.asSequence()
            .filter { getPlayerState(it) == PLAYER_STATE_STARTED }
            .map { it.audioAttributes.usage }
            .toSet()
}
