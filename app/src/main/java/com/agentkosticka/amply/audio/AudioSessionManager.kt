package com.agentkosticka.amply.audio

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.util.Log
import android.util.LruCache
import com.agentkosticka.amply.data.AudioSession
import com.agentkosticka.amply.data.AudioSessionState
import com.agentkosticka.amply.data.AppSettings
import com.agentkosticka.amply.data.DumpsysParseResult
import com.agentkosticka.amply.data.PreferencesManager
import com.agentkosticka.amply.data.RawAudioSession
import com.agentkosticka.amply.shizuku.ShizukuPermissionState
import com.agentkosticka.amply.shizuku.ShizukuRepository
import com.agentkosticka.amply.shizuku.ShizukuVolumeManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages audio session detection and app metadata enrichment.
 *
 * NEW APPROACH (Phase 4): Uses AudioPlaybackConfiguration API instead of dumpsys.
 * - Detection: AudioManager.getActivePlaybackConfigurations()
 * - Volume Control: PlayerProxy.setVolume() via reflection
 * - Real-time updates: AudioManager.registerAudioPlaybackCallback()
 *
 * Falls back to dumpsys-based detection if the new approach fails.
 */
class AudioSessionManager(
    private val context: Context,
    private val shizukuRepository: ShizukuRepository,
    private val preferencesManager: PreferencesManager = PreferencesManager(context)
) {
    companion object {
        private const val TAG = "AudioSessionManager"
        private const val POLL_INTERVAL_MS = 1500L // Optimized: Real-time callback handles immediate updates
        private const val CACHE_SIZE = 50
    }

    // NEW: ShizukuVolumeManager for privileged access via UserService
    private val shizukuVolumeManager = ShizukuVolumeManager(context.packageName)

    // Fallback: PlayerVolumeController for local reflection (usually returns -1 for uid)
    private val playerVolumeController = PlayerVolumeController(context, shizukuRepository)

    // Fallback detector (dumpsys-based)
    private val detector = AudioSessionDetector(context)
    private val packageManager: PackageManager = context.packageManager
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Session state observable
    private val _sessionState = MutableStateFlow(AudioSessionState.Companion.empty())
    val sessionState: StateFlow<AudioSessionState> = _sessionState.asStateFlow()

    // Polling job
    private var pollingJob: Job? = null
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // NEW: Playback callback for real-time updates
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    // NEW: Map of piid -> PlayerProxy for volume control
    private val playerProxyMap = mutableMapOf<Int, Any?>()

    // LRU cache for app metadata
    private val appMetadataCache = LruCache<Int, AppMetadata>(CACHE_SIZE)
    
    // NEW: Volume persistence cache (uid -> volume)
    private val uidVolumeCache = mutableMapOf<Int, Float>()
    private var appSettingsCache: Map<String, AppSettings> = emptyMap()
    private var appSettingsJob: Job? = null

    data class AppMetadata(
        val packageName: String,
        val appName: String,
        val appIcon: Drawable?
    )

    /**
     * Start polling for audio sessions
     */
    fun startPolling() {
        if (pollingJob?.isActive == true) {
            Log.d(TAG, "Polling already active")
            return
        }

        Log.d(TAG, "Starting audio session polling (Phase 5: Shizuku UserService)")

        appSettingsJob?.cancel()
        appSettingsJob = managerScope.launch {
            preferencesManager.appSettings.collect { settings ->
                appSettingsCache = settings
                uidVolumeCache.clear()
                settings.values.forEach { setting ->
                    if (setting.uid >= 0) {
                        uidVolumeCache[setting.uid] = setting.defaultVolume
                    }
                }
                applyPersistedVolumes(_sessionState.value.sessions)
            }
        }

        // Bind to ShizukuVolumeManager UserService if Shizuku is granted
        if (shizukuRepository.permissionState.value == ShizukuPermissionState.GRANTED) {
            Log.d(TAG, "Binding to ShizukuVolumeManager UserService...")
            shizukuVolumeManager.bindService()
        }

        // Register real-time playback callback (for local fallback)
        registerPlaybackCallback()

        pollingJob = managerScope.launch {
            // Wait a bit for UserService to connect
            delay(500)

            while (isActive) {
                try {
                    updateSessions()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during session update", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Register callback for real-time playback configuration updates
     * ENHANCED: Auto-applies saved volume to new players when they appear
     */
    private fun registerPlaybackCallback() {
        if (playbackCallback != null) return

        playbackCallback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                Log.d(TAG, "Playback config changed: ${configs?.size ?: 0} active players")
                
                // Auto-apply saved volume to new players
                managerScope.launch {
                    try {
                        applyVolumesToNewPlayers()
                        updateSessions()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during callback-triggered update", e)
                    }
                }
            }
        }

        playerVolumeController.registerPlaybackCallback(playbackCallback!!)
        Log.d(TAG, "Registered playback callback for real-time updates")
    }

    /**
     * Apply saved volume levels to any new players that appear
     * This ensures volume persists across song changes
     */
    private suspend fun applyVolumesToNewPlayers() {
        if (!shizukuVolumeManager.isConnected.value) {
            Log.d(TAG, "ShizukuVolumeManager not connected, skipping auto-apply")
            return
        }

        val playbacks = shizukuVolumeManager.getActivePlaybacks()
        val uidPackageMap = shizukuRepository.getUidPackageMap()
        
        for (playback in playbacks) {
            val packageName = uidPackageMap[playback.uid] ?: packageManager.getPackagesForUid(playback.uid)?.firstOrNull()
            val savedVolume = packageName?.let { appSettingsCache[it]?.defaultVolume } ?: uidVolumeCache[playback.uid]
            if (savedVolume != null && savedVolume < 1.0f) {
                // This UID has a saved volume that's not 100%
                val gain = toLogarithmicGain(savedVolume)
                Log.d(TAG, "Auto-applying volume $gain (linear=$savedVolume) to new player piid=${playback.piid}")
                shizukuVolumeManager.setPlayerVolume(playback.piid, gain)
            }
        }
    }

    /**
     * Converts a linear volume (0.0 to 1.0) to a logarithmic gain.
     * Human hearing is logarithmic; using a square curve makes volume perception more natural.
     * Slider at 0.5 -> Gain at 0.25 (perceived as half volume)
     */
    private fun toLogarithmicGain(linear: Float): Float {
        // Square curve is a good approximation for perceived loudness
        return (linear * linear).coerceIn(0f, 1f)
    }

    /**
     * Stop polling for audio sessions
     */
    fun stopPolling() {
        Log.d(TAG, "Stopping audio session polling")
        pollingJob?.cancel()
        pollingJob = null
        appSettingsJob?.cancel()
        appSettingsJob = null

        // Unregister playback callback
        playbackCallback?.let {
            playerVolumeController.unregisterPlaybackCallback(it)
            playbackCallback = null
        }
    }

    /**
     * Update session state using ShizukuVolumeManager (Phase 5)
     * Falls back to dumpsys if ShizukuVolumeManager is not connected
     */
    private suspend fun updateSessions() {
        // Get current global volume
        val globalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        // ==============================================
        // PRIMARY METHOD: ShizukuVolumeManager (Phase 5)
        // Uses Shizuku UserService for privileged access
        // ==============================================
        if (shizukuVolumeManager.isConnected.value) {
            val privilegedPlaybacks = shizukuVolumeManager.getActivePlaybacks()

            if (privilegedPlaybacks.isNotEmpty()) {
                // Build UID-to-package map
                val uidPackageMap = shizukuRepository.getUidPackageMap()

                // Convert PrivilegedPlaybacks to AudioSessions with app metadata
                val enrichedSessions = privilegedPlaybacks.mapNotNull { playback ->
                    enrichPrivilegedPlayback(playback, uidPackageMap)
                }
                applyPersistedVolumes(enrichedSessions)

                _sessionState.value = AudioSessionState(
                    sessions = enrichedSessions,
                    globalVolume = globalVolume,
                    maxVolume = maxVolume,
                    timestamp = System.currentTimeMillis()
                )
                return
            }
        }

        // ==============================================
        // FALLBACK 1: PlayerVolumeController (local reflection)
        // Usually returns -1 for uid due to sanitization
        // ==============================================
        val activePlayers = playerVolumeController.getActivePlayers()
        Log.d(TAG, "PlayerVolumeController found ${activePlayers.size} active players")

        if (activePlayers.isNotEmpty()) {
            // Store PlayerProxy references for volume control
            playerProxyMap.clear()
            activePlayers.forEach { player ->
                playerProxyMap[player.piid] = player.playerProxy
            }

            // Build UID-to-package map (use Shizuku if available, otherwise PackageManager)
            val uidPackageMap = if (shizukuRepository.permissionState.value == ShizukuPermissionState.GRANTED) {
                shizukuRepository.getUidPackageMap()
            } else {
                emptyMap()
            }

            // Convert ActivePlayers to AudioSessions with app metadata
            val enrichedSessions = activePlayers.mapNotNull { player ->
                enrichPlayerWithMetadata(player, uidPackageMap)
            }
            applyPersistedVolumes(enrichedSessions)

            Log.d(TAG, "Enriched ${enrichedSessions.size} sessions from PlayerVolumeController")

            if (enrichedSessions.isNotEmpty()) {
                _sessionState.value = AudioSessionState(
                    sessions = enrichedSessions,
                    globalVolume = globalVolume,
                    maxVolume = maxVolume,
                    timestamp = System.currentTimeMillis()
                )

                Log.d(TAG, "Active audio sessions (Fallback - PlayerVolumeController):")
                enrichedSessions.forEach { session ->
                    Log.d(TAG, "  - ${session.appName} (piid=${session.sessionId}, uid=${session.uid})")
                }
                return
            }
        }

        // ==============================================
        // FALLBACK: Dumpsys-based detection (legacy)
        // Used when PlayerVolumeController finds no players
        // ==============================================
        Log.d(TAG, "Falling back to dumpsys-based detection")

        // Check if Shizuku is available for dumpsys
        if (shizukuRepository.permissionState.value != ShizukuPermissionState.GRANTED) {
            // No Shizuku = no sessions (single slider mode)
            _sessionState.value = AudioSessionState(
                sessions = emptyList(),
                globalVolume = globalVolume,
                maxVolume = maxVolume,
                timestamp = System.currentTimeMillis()
            )
            return
        }

        // Try audio dumpsys
        var rawSessions: List<RawAudioSession> = emptyList()
        val audioOutput = shizukuRepository.dumpAudioFlinger()
        if (audioOutput != null) {
            val parseResult = detector.parseDumpsysOutput(audioOutput)
            if (parseResult is DumpsysParseResult.Success && parseResult.sessions.isNotEmpty()) {
                rawSessions = parseResult.sessions
                Log.d(TAG, "Fallback: Got ${rawSessions.size} sessions from dumpsys")
            }
        }

        // Try media_session as fallback
        if (rawSessions.isEmpty()) {
            val mediaOutput = shizukuRepository.dumpMediaSession()
            if (mediaOutput != null) {
                rawSessions = detector.parseMediaSessionOutput(mediaOutput)
            }
        }

        // Build UID-to-package map
        val uidPackageMap = shizukuRepository.getUidPackageMap()

        // Enrich with app metadata
        val enrichedSessions = rawSessions.mapNotNull { raw ->
            enrichSessionWithMetadata(raw, uidPackageMap)
        }
        applyPersistedVolumes(enrichedSessions)

        _sessionState.value = AudioSessionState(
            sessions = enrichedSessions,
            globalVolume = globalVolume,
            maxVolume = maxVolume,
            timestamp = System.currentTimeMillis()
        )

        if (enrichedSessions.isNotEmpty()) {
            Log.d(TAG, "Fallback detected ${enrichedSessions.size} sessions")
        }
    }

    /**
     * Enrich ActivePlayer with app metadata (name, icon)
     * Used by the Phase 4 PlayerVolumeController approach
     */
    private fun enrichPlayerWithMetadata(
        player: PlayerVolumeController.ActivePlayer,
        uidPackageMap: Map<Int, String>
    ): AudioSession? {
        return try {
            val uid = player.uid

            // Check cache first
            val cached = appMetadataCache.get(uid)
            if (cached != null) {
                val persistedVolume = getPersistedVolume(cached.packageName, uid)
                recordSeenApp(cached.packageName, cached.appName, uid, persistedVolume)
                return AudioSession(
                    sessionId = player.piid, // Use piid as sessionId for volume control
                    uid = uid,
                    packageName = cached.packageName,
                    appName = cached.appName,
                    appIcon = cached.appIcon,
                    streamType = AudioManager.STREAM_MUSIC,
                    volume = persistedVolume,
                    lastSeenTimestamp = System.currentTimeMillis()
                )
            }

            // Resolve package name from UID
            var packageName = uidPackageMap[uid]
            if (packageName == null) {
                val packages = packageManager.getPackagesForUid(uid)
                packageName = packages?.firstOrNull()
            }

            if (packageName == null) {
                Log.w(TAG, "No package found for UID $uid (player piid=${player.piid})")
                return null
            }

            // Get app info
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val appName = packageManager.getApplicationLabel(appInfo).toString()
            val appIcon = try {
                packageManager.getApplicationIcon(packageName)
            } catch (e: Exception) {
                null
            }

            // Cache metadata
            appMetadataCache.put(uid, AppMetadata(packageName, appName, appIcon))
            val persistedVolume = getPersistedVolume(packageName, uid)
            recordSeenApp(packageName, appName, uid, persistedVolume)

            Log.d(TAG, "Enriched player: $appName (uid=$uid, piid=${player.piid})")

            AudioSession(
                sessionId = player.piid, // Use piid for volume control reference
                uid = uid,
                packageName = packageName,
                appName = appName,
                appIcon = appIcon,
                streamType = AudioManager.STREAM_MUSIC,
                volume = persistedVolume,
                lastSeenTimestamp = System.currentTimeMillis()
            )
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Package not found for player uid=${player.uid}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error enriching player uid=${player.uid}", e)
            null
        }
    }

    /**
     * Enrich PrivilegedPlayback from ShizukuVolumeManager with app metadata
     * Used by Phase 5 approach
     */
    private fun enrichPrivilegedPlayback(
        playback: ShizukuVolumeManager.PrivilegedPlayback,
        uidPackageMap: Map<Int, String>
    ): AudioSession? {
        return try {
            val uid = playback.uid

            // Check cache first
            val cached = appMetadataCache.get(uid)
            if (cached != null) {
                val persistedVolume = getPersistedVolume(cached.packageName, uid)
                recordSeenApp(cached.packageName, cached.appName, uid, persistedVolume)
                return AudioSession(
                    sessionId = playback.piid,
                    uid = uid,
                    packageName = cached.packageName,
                    appName = cached.appName,
                    appIcon = cached.appIcon,
                    streamType = AudioManager.STREAM_MUSIC,
                    volume = persistedVolume,
                    lastSeenTimestamp = System.currentTimeMillis()
                )
            }

            // Resolve package name from UID
            var packageName = uidPackageMap[uid]
            if (packageName == null) {
                val packages = packageManager.getPackagesForUid(uid)
                packageName = packages?.firstOrNull()
            }

            if (packageName == null) {
                Log.w(TAG, "No package found for UID $uid (privileged playback piid=${playback.piid})")
                return null
            }

            // Get app info
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val appName = packageManager.getApplicationLabel(appInfo).toString()
            val appIcon = try {
                packageManager.getApplicationIcon(packageName)
            } catch (e: Exception) {
                null
            }

            // Cache metadata
            appMetadataCache.put(uid, AppMetadata(packageName, appName, appIcon))
            val persistedVolume = getPersistedVolume(packageName, uid)
            recordSeenApp(packageName, appName, uid, persistedVolume)

            Log.d(TAG, "Enriched privileged playback: $appName (uid=$uid, piid=${playback.piid})")

            AudioSession(
                sessionId = playback.piid,
                uid = uid,
                packageName = packageName,
                appName = appName,
                appIcon = appIcon,
                streamType = AudioManager.STREAM_MUSIC,
                volume = persistedVolume,
                lastSeenTimestamp = System.currentTimeMillis()
            )
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Package not found for privileged playback uid=${playback.uid}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error enriching privileged playback uid=${playback.uid}", e)
            null
        }
    }

    /**
     * Enrich raw session with app metadata (name, icon)
     * Uses uidPackageMap (from Shizuku) as primary lookup, falls back to PackageManager
     */
    private fun enrichSessionWithMetadata(
        raw: RawAudioSession,
        uidPackageMap: Map<Int, String>
    ): AudioSession? {
        return try {
            // Check cache first
            val cached = appMetadataCache.get(raw.uid)
            if (cached != null) {
                Log.d(TAG, "Cache hit for UID ${raw.uid}: ${cached.appName}")
                val persistedVolume = getPersistedVolume(cached.packageName, raw.uid)
                recordSeenApp(cached.packageName, cached.appName, raw.uid, persistedVolume)
                return AudioSession(
                    sessionId = raw.sessionId,
                    uid = raw.uid,
                    packageName = cached.packageName,
                    appName = cached.appName,
                    appIcon = cached.appIcon,
                    streamType = raw.streamType,
                    volume = persistedVolume,
                    lastSeenTimestamp = System.currentTimeMillis()
                )
            }

            // Primary: Use UID package map from Shizuku (more reliable)
            var packageName = uidPackageMap[raw.uid]
            
            // Fallback: Try PackageManager (may fail without QUERY_ALL_PACKAGES)
            if (packageName == null) {
                val packages = packageManager.getPackagesForUid(raw.uid)
                packageName = packages?.firstOrNull()
            }
            
            if (packageName == null) {
                Log.w(TAG, "No package found for UID ${raw.uid}, skipping")
                return null
            }

            // Get app info
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val appName = packageManager.getApplicationLabel(appInfo).toString()
            val appIcon = try {
                packageManager.getApplicationIcon(packageName)
            } catch (e: Exception) {
                null
            }

            Log.d(TAG, "Enriched UID ${raw.uid}: $appName ($packageName)")

            // Cache the metadata
            appMetadataCache.put(
                raw.uid,
                AppMetadata(packageName, appName, appIcon)
            )
            val persistedVolume = getPersistedVolume(packageName, raw.uid)
            recordSeenApp(packageName, appName, raw.uid, persistedVolume)

            AudioSession(
                sessionId = raw.sessionId,
                uid = raw.uid,
                packageName = packageName,
                appName = appName,
                appIcon = appIcon,
                streamType = raw.streamType,
                volume = persistedVolume,
                lastSeenTimestamp = System.currentTimeMillis()
            )
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Package not found for UID ${raw.uid}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error enriching session for UID ${raw.uid}", e)
            null
        }
    }

    /**
     * Get current session list (snapshot)
     */
    fun getCurrentSessions(): List<AudioSession> {
        return _sessionState.value.sessions
    }

    fun getDefaultOverlaySessions(): List<AudioSession> =
        _sessionState.value.sessions
            .filter { appSettingsCache[it.packageName]?.hiddenInOverlay != true }

    fun getExpandedOverlaySessions(now: Long = System.currentTimeMillis()): List<AudioSession> {
        val active = _sessionState.value.sessions.associateBy { it.packageName }
        val result = LinkedHashMap<String, AudioSession>()

        active.values.forEach { session ->
            if (appSettingsCache[session.packageName]?.hiddenInOverlay != true) {
                result[session.packageName] = session
            }
        }

        appSettingsCache.values
            .asSequence()
            .filter { !it.hiddenInOverlay }
            .filter { it.isCustomized || now - it.lastSeenTimestamp <= 60_000L }
            .sortedByDescending { it.lastSeenTimestamp }
            .forEach { setting ->
                if (!result.containsKey(setting.packageName)) {
                    result[setting.packageName] = setting.toSyntheticSession()
                }
            }

        return result.values.toList()
    }

    /**
     * Check if any apps are currently playing audio
     */
    fun hasActiveSessions(): Boolean {
        return _sessionState.value.sessions.isNotEmpty()
    }

    /**
     * Force refresh sessions (on-demand update)
     */
    suspend fun refreshNow() {
        updateSessions()
    }

    /**
     * Set volume for a specific app session
     *
     * Phase 5: Uses ShizukuVolumeManager UserService for privileged volume control
     * Falls back to PlayerVolumeController (local) and Shizuku shell commands
     *
     * @param sessionId The audio session ID (piid)
     * @param volume Volume level (0.0 to 1.0)
     */
    suspend fun setSessionVolume(sessionId: Int, packageName: String?, volume: Float) {
        // Find the specific session to get its UID
        val targetSession = _sessionState.value.sessions.find { it.sessionId == sessionId }

        if (targetSession != null) {
            val uid = targetSession.uid
            Log.d(TAG, "Setting volume for ${targetSession.appName} (uid=$uid, piid=$sessionId) to $volume")
            updateLocalSessionVolume(uid, volume)
            managerScope.launch {
                persistSessionVolume(targetSession, volume)
            }

            // ==============================================
            // PRIMARY: Use ShizukuVolumeManager UserService
            // ==============================================
            if (shizukuVolumeManager.isConnected.value) {
                val gain = toLogarithmicGain(volume)
                val success = shizukuVolumeManager.setPlayerVolume(sessionId, gain)
                if (success) {
                    Log.d(TAG, "✓ Volume set to $gain (linear=$volume) via ShizukuVolumeManager")

                    // Also set for all other players of the same app (same UID)
                    val otherSessionIds = _sessionState.value.sessions
                        .filter { it.uid == uid && it.sessionId != sessionId }
                        .map { it.sessionId }

                    for (otherPiid in otherSessionIds) {
                        shizukuVolumeManager.setPlayerVolume(otherPiid, gain)
                    }

                    return
                } else {
                    Log.w(TAG, "ShizukuVolumeManager failed, trying fallbacks...")
                }
            }

            // ==============================================
            // FALLBACK 1: Use PlayerVolumeController (local reflection)
            // ==============================================
            val gain = toLogarithmicGain(volume)
            val success = playerVolumeController.setPlayerVolumeByPiid(sessionId, gain)
            if (success) {
                Log.d(TAG, "✓ Volume set via PlayerVolumeController (gain=$gain)")
                return
            }

            // ==============================================
            // FALLBACK 2: Use Shizuku shell commands (legacy)
            // ==============================================
            if (shizukuRepository.permissionState.value == ShizukuPermissionState.GRANTED) {
                val allUidSessions = _sessionState.value.sessions.filter { it.uid == uid }

                val results = allUidSessions.map { session ->
                    shizukuRepository.setAppVolume(session.sessionId, session.uid, volume)
                }

                if (results.any { it }) {
                    Log.d(TAG, "Volume set via Shizuku shell fallback for ${targetSession.appName}")
                } else {
                    Log.e(TAG, "FAILED to set volume for ${targetSession.appName}")
                }
            } else {
                Log.e(TAG, "Cannot set volume: all methods failed")
            }
        } else {
            Log.w(TAG, "Session $sessionId not active; persisting package-only volume for $packageName")
            if (!packageName.isNullOrBlank()) {
                preferencesManager.setAppDefaultVolume(packageName, volume)
            }
        }
    }

    suspend fun setSessionVolume(sessionId: Int, volume: Float) {
        setSessionVolume(sessionId, null, volume)
    }

    /**
     * Update local session state with new volume
     */
    private fun updateLocalSessionVolume(uid: Int, volume: Float) {
        // Update persisted volume cache
        uidVolumeCache[uid] = volume

        val updatedSessions = _sessionState.value.sessions.map {
            if (it.uid == uid) {
                it.copy(volume = volume)
            } else {
                it
            }
        }
        _sessionState.value = _sessionState.value.copy(
            sessions = updatedSessions,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Get a session by ID
     */
    fun getSession(sessionId: Int): AudioSession? {
        return _sessionState.value.sessions.find { it.sessionId == sessionId }
    }

    /**
     * Phase 3.5: Smart Focus - Get the app that's currently in foreground (if it's playing audio)
     * Uses Shizuku to detect foreground package, then matches against active sessions
     * 
     * @param foregroundPackage Package name of the foreground app (from AccessibilityService or similar)
     * @return The audio session for the foreground app, or null if not playing audio
     */
    fun getFocusedApp(foregroundPackage: String?): AudioSession? {
        if (foregroundPackage.isNullOrBlank()) return null
        
        // Find session that matches the foreground package
        return _sessionState.value.sessions.find { 
            it.packageName == foregroundPackage 
        }
    }

    /**
     * Phase 3.5: Get the most recently active session (for Smart Focus fallback)
     * Returns the session with the most recent timestamp, or first session
     */
    fun getMostRecentSession(): AudioSession? {
        return _sessionState.value.sessions
            .maxByOrNull { it.lastSeenTimestamp }
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up AudioSessionManager")
        stopPolling()
        shizukuVolumeManager.unbindService()
        managerScope.cancel()
        appMetadataCache.evictAll()
        playerProxyMap.clear()
    }

    private fun getPersistedVolume(packageName: String, uid: Int): Float {
        val persisted = appSettingsCache[packageName]?.defaultVolume ?: uidVolumeCache[uid] ?: 1.0f
        uidVolumeCache[uid] = persisted
        return persisted
    }

    private fun recordSeenApp(packageName: String, appName: String, uid: Int, volume: Float) {
        managerScope.launch {
            preferencesManager.recordSeenApp(
                packageName = packageName,
                appName = appName,
                uid = uid,
                observedVolume = volume
            )
        }
    }

    private suspend fun persistSessionVolume(session: AudioSession, volume: Float) {
        uidVolumeCache[session.uid] = volume
        preferencesManager.recordSeenApp(
            packageName = session.packageName,
            appName = session.appName,
            uid = session.uid,
            observedVolume = volume
        )
        preferencesManager.setAppDefaultVolume(session.packageName, volume)
    }

    private fun applyPersistedVolumes(sessions: List<AudioSession>) {
        sessions.forEach { session ->
            val persisted = getPersistedVolume(session.packageName, session.uid)
            if (persisted < 1.0f) {
                val gain = toLogarithmicGain(persisted)
                if (shizukuVolumeManager.isConnected.value) {
                    shizukuVolumeManager.setPlayerVolume(session.sessionId, gain)
                } else {
                    playerVolumeController.setPlayerVolumeByPiid(session.sessionId, gain)
                }
            }
        }
    }

    private fun AppSettings.toSyntheticSession(): AudioSession {
        val icon = try {
            packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            null
        }

        return AudioSession(
            sessionId = syntheticSessionId(packageName),
            uid = uid,
            packageName = packageName,
            appName = appName,
            appIcon = icon,
            streamType = AudioManager.STREAM_MUSIC,
            volume = defaultVolume,
            lastSeenTimestamp = lastSeenTimestamp
        )
    }

    private fun syntheticSessionId(packageName: String): Int =
        -kotlin.math.abs(packageName.hashCode()).coerceAtLeast(1)
}
