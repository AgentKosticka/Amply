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
import com.agentkosticka.amply.data.PreferencesManager
import com.agentkosticka.amply.shizuku.ShizukuVolumeManager
import com.agentkosticka.amply.shizuku.VolumeServiceConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

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
    private val preferencesManager: PreferencesManager,
    private val shizukuVolumeManager: ShizukuVolumeManager
) {
    companion object {
        private const val TAG = "AudioSessionManager"
        private const val ACTIVE_SAFETY_REFRESH_MS = 30_000L
        private const val IDLE_SAFETY_REFRESH_MS = 120_000L
        private const val REFRESH_DEBOUNCE_MS = 100L
        private const val SEEN_APP_WRITE_INTERVAL_MS = 5 * 60_000L
        private const val VOLUME_UPDATE_INTERVAL_MS = 32L
        private const val VOLUME_PERSIST_DEBOUNCE_MS = 300L
        private const val CACHE_SIZE = 50
        private const val PLAYER_STATE_STARTED = 2
        private const val VOLUME_EPSILON = 0.0001f
    }

    // Fallback: PlayerVolumeController for local reflection (usually returns -1 for uid)
    private val playerVolumeController = PlayerVolumeController(context)

    private val packageManager: PackageManager = context.packageManager
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Session state observable
    private val _sessionState = MutableStateFlow(AudioSessionState.empty())
    val sessionState: StateFlow<AudioSessionState> = _sessionState.asStateFlow()

    private var refreshJob: Job? = null
    private var safetyRefreshJob: Job? = null
    private var connectionJob: Job? = null
    private var volumeUpdateJob: Job? = null
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val volumeUpdateSignals = Channel<Unit>(Channel.CONFLATED)
    private val refreshMutex = Mutex()
    private val pendingVolumeLock = Any()

    // NEW: Playback callback for real-time updates
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    // NEW: Map of piid -> PlayerProxy for volume control
    private val playerProxyMap = ConcurrentHashMap<Int, Any?>()

    // LRU cache for app metadata
    private val appMetadataCache = LruCache<Int, AppMetadata>(CACHE_SIZE)
    
    // NEW: Volume persistence cache (uid -> volume)
    private val uidVolumeCache = ConcurrentHashMap<Int, Float>()
    private val appliedPlayerGains = ConcurrentHashMap<Int, Float>()
    private val seenAppWriteTimes = ConcurrentHashMap<String, Long>()
    private val volumePersistJobs = ConcurrentHashMap<String, Job>()
    private val pendingVolumeUpdates = ConcurrentHashMap<String, VolumeUpdate>()
    @Volatile
    private var appSettingsCache: Map<String, AppSettings> = emptyMap()
    private var appSettingsJob: Job? = null

    data class AppMetadata(
        val packageName: String,
        val appName: String,
        val appIcon: Drawable?
    )

    private data class VolumeUpdate(
        val sessionId: Int,
        val packageName: String?,
        val volume: Float
    )

    /** Starts callback-driven monitoring with a sparse safety refresh. */
    fun startPolling() {
        if (refreshJob?.isActive == true) {
            Log.d(TAG, "Audio session monitoring already active")
            return
        }

        Log.i(TAG, "Starting callback-driven audio session monitoring")

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
                requestRefresh()
            }
        }

        // Register real-time playback callback (for local fallback)
        registerPlaybackCallback()

        refreshJob = managerScope.launch {
            requestRefresh()
            for (ignored in refreshRequests) {
                delay(REFRESH_DEBOUNCE_MS)
                while (refreshRequests.tryReceive().isSuccess) {
                    // Collapse callback bursts into one privileged query.
                }
                try {
                    refreshMutex.withLock {
                        updateSessions()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during session update", e)
                }
            }
        }

        safetyRefreshJob = managerScope.launch {
            while (isActive) {
                val delayMs = if (_sessionState.value.sessions.isEmpty()) {
                    IDLE_SAFETY_REFRESH_MS
                } else {
                    ACTIVE_SAFETY_REFRESH_MS
                }
                delay(delayMs)
                requestRefresh()
            }
        }

        connectionJob = managerScope.launch {
            shizukuVolumeManager.connectionState
                .collect { state ->
                    if (state == VolumeServiceConnectionState.CONNECTED) {
                        appliedPlayerGains.clear()
                        requestRefresh()
                    }
                }
        }

        volumeUpdateJob = managerScope.launch {
            for (ignored in volumeUpdateSignals) {
                delay(VOLUME_UPDATE_INTERVAL_MS)
                val updates = synchronized(pendingVolumeLock) {
                    pendingVolumeUpdates.values.toList().also {
                        pendingVolumeUpdates.clear()
                    }
                }
                updates.forEach { update ->
                    applySessionVolume(update.sessionId, update.packageName, update.volume)
                }
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
                requestRefresh()
            }
        }

        playerVolumeController.registerPlaybackCallback(playbackCallback!!)
        Log.d(TAG, "Registered playback callback for real-time updates")
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
        Log.d(TAG, "Stopping audio session monitoring")
        refreshJob?.cancel()
        refreshJob = null
        safetyRefreshJob?.cancel()
        safetyRefreshJob = null
        connectionJob?.cancel()
        connectionJob = null
        volumeUpdateJob?.cancel()
        volumeUpdateJob = null
        appSettingsJob?.cancel()
        appSettingsJob = null
        volumePersistJobs.values.forEach { it.cancel() }
        volumePersistJobs.clear()
        pendingVolumeUpdates.clear()

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
            val playbackResult = shizukuVolumeManager.getActivePlaybacks()
            if (playbackResult != null) {
                val privilegedPlaybacks = playbackResult
                    .filter { it.state == PLAYER_STATE_STARTED }
                pruneAppliedPlayerGains(privilegedPlaybacks.mapTo(mutableSetOf()) { it.piid })

                if (privilegedPlaybacks.isNotEmpty()) {
                    // Convert PrivilegedPlaybacks to AudioSessions with app metadata
                    val enrichedSessions = privilegedPlaybacks.mapNotNull { playback ->
                        enrichPrivilegedPlayback(playback)
                    }
                    applyPersistedVolumes(enrichedSessions)

                    publishSessionState(enrichedSessions, globalVolume, maxVolume)
                    return
                }

                publishSessionState(emptyList(), globalVolume, maxVolume)
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
            pruneAppliedPlayerGains(activePlayers.mapTo(mutableSetOf()) { it.piid })
            // Store PlayerProxy references for volume control
            playerProxyMap.clear()
            activePlayers.forEach { player ->
                playerProxyMap[player.piid] = player.playerProxy
            }

            // Convert ActivePlayers to AudioSessions with app metadata
            val enrichedSessions = activePlayers.mapNotNull { player ->
                enrichPlayerWithMetadata(player)
            }
            applyPersistedVolumes(enrichedSessions)

            Log.d(TAG, "Enriched ${enrichedSessions.size} sessions from PlayerVolumeController")

            if (enrichedSessions.isNotEmpty()) {
                publishSessionState(enrichedSessions, globalVolume, maxVolume)

                Log.d(TAG, "Active audio sessions (Fallback - PlayerVolumeController):")
                enrichedSessions.forEach { session ->
                    Log.d(TAG, "  - ${session.appName} (piid=${session.sessionId}, uid=${session.uid})")
                }
                return
            }
        }

        pruneAppliedPlayerGains(emptySet())
        publishSessionState(emptyList(), globalVolume, maxVolume)
    }

    fun requestRefresh() {
        refreshRequests.trySend(Unit)
    }

    /**
     * Enrich ActivePlayer with app metadata (name, icon)
     * Used by the Phase 4 PlayerVolumeController approach
     */
    private fun enrichPlayerWithMetadata(
        player: PlayerVolumeController.ActivePlayer
    ): AudioSession? {
        return try {
            val uid = player.uid

            // Check cache first
            val cached = appMetadataCache.get(uid)
            if (cached != null) {
                val persistedVolume = getPersistedVolume(cached.packageName, uid)
                recordSeenAppIfNeeded(cached.packageName, cached.appName, uid, persistedVolume)
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
            val packageName = packageManager.getPackagesForUid(uid)?.firstOrNull()

            if (packageName == null) {
                Log.w(TAG, "No package found for UID $uid (player piid=${player.piid})")
                return null
            }

            // Get app info
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val appName = packageManager.getApplicationLabel(appInfo).toString()
            val appIcon = try {
                packageManager.getApplicationIcon(packageName)
            } catch (_: Exception) {
                null
            }

            // Cache metadata
            appMetadataCache.put(uid, AppMetadata(packageName, appName, appIcon))
            val persistedVolume = getPersistedVolume(packageName, uid)
            recordSeenAppIfNeeded(packageName, appName, uid, persistedVolume)

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
        } catch (_: PackageManager.NameNotFoundException) {
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
        playback: ShizukuVolumeManager.PrivilegedPlayback
    ): AudioSession? {
        return try {
            val uid = playback.uid

            // Check cache first
            val cached = appMetadataCache.get(uid)
            if (cached != null) {
                val persistedVolume = getPersistedVolume(cached.packageName, uid)
                recordSeenAppIfNeeded(cached.packageName, cached.appName, uid, persistedVolume)
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
            val packageName = packageManager.getPackagesForUid(uid)?.firstOrNull()

            if (packageName == null) {
                Log.w(TAG, "No package found for UID $uid (privileged playback piid=${playback.piid})")
                return null
            }

            // Get app info
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val appName = packageManager.getApplicationLabel(appInfo).toString()
            val appIcon = try {
                packageManager.getApplicationIcon(packageName)
            } catch (_: Exception) {
                null
            }

            // Cache metadata
            appMetadataCache.put(uid, AppMetadata(packageName, appName, appIcon))
            val persistedVolume = getPersistedVolume(packageName, uid)
            recordSeenAppIfNeeded(packageName, appName, uid, persistedVolume)

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
        } catch (_: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Package not found for privileged playback uid=${playback.uid}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error enriching privileged playback uid=${playback.uid}", e)
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
        compactSessionsByPackage(
            _sessionState.value.sessions
            .filter { appSettingsCache[it.packageName]?.hiddenInOverlay != true }
        )

    fun getExpandedOverlaySessions(): List<AudioSession> =
        getDefaultOverlaySessions()

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
        refreshMutex.withLock {
            updateSessions()
        }
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
    fun setSessionVolume(sessionId: Int, packageName: String?, volume: Float) {
        val key = packageName?.takeIf { it.isNotBlank() } ?: "session:$sessionId"
        synchronized(pendingVolumeLock) {
            pendingVolumeUpdates[key] = VolumeUpdate(
                sessionId = sessionId,
                packageName = packageName,
                volume = volume.coerceIn(0f, 1f)
            )
        }
        volumeUpdateSignals.trySend(Unit)
    }

    private suspend fun applySessionVolume(sessionId: Int, packageName: String?, volume: Float) {
        // Find the specific session to get its UID
        val activeSessions = _sessionState.value.sessions
        val targetSession = activeSessions.find { it.sessionId == sessionId }
            ?: packageName?.let { requestedPackage ->
                activeSessions.find { it.packageName == requestedPackage }
            }

        if (targetSession != null) {
            val uid = targetSession.uid
            val targetPackage = packageName?.takeIf { it.isNotBlank() } ?: targetSession.packageName
            val packageSessions = activeSessions.filter { it.packageName == targetPackage }
            val targetSessions = packageSessions.ifEmpty {
                activeSessions.filter { it.uid == uid }
            }

            updateLocalSessionVolume(targetPackage, uid, volume)
            schedulePersistSessionVolume(targetSession, volume)

            // ==============================================
            // PRIMARY: Use ShizukuVolumeManager UserService
            // ==============================================
            if (shizukuVolumeManager.isConnected.value) {
                val gain = toLogarithmicGain(volume)
                val results = targetSessions.map { session ->
                    val success = shizukuVolumeManager.setPlayerVolume(session.sessionId, gain)
                    if (success) {
                        appliedPlayerGains[session.sessionId] = gain
                    }
                    success
                }
                if (results.any { it }) {
                    return
                } else {
                    Log.w(TAG, "ShizukuVolumeManager failed, trying fallbacks...")
                }
            }

            // ==============================================
            // FALLBACK 1: Use PlayerVolumeController (local reflection)
            // ==============================================
            val gain = toLogarithmicGain(volume)
            val localSuccessCount = targetSessions.count { session ->
                playerVolumeController.setPlayerVolumeByPiid(session.sessionId, gain)
            }
            if (localSuccessCount > 0) {
                return
            }

            Log.w(TAG, "Volume update deferred while the privileged service reconnects")
        } else {
            Log.w(TAG, "Session $sessionId not active; persisting package-only volume for $packageName")
            if (!packageName.isNullOrBlank()) {
                preferencesManager.setAppDefaultVolume(packageName, volume)
            }
        }
    }

    fun setSessionVolume(sessionId: Int, volume: Float) {
        setSessionVolume(sessionId, null, volume)
    }

    /**
     * Update local session state with new volume
     */
    private fun updateLocalSessionVolume(packageName: String, uid: Int, volume: Float) {
        // Update persisted volume cache
        uidVolumeCache[uid] = volume

        val updatedSessions = _sessionState.value.sessions.map {
            if (it.packageName == packageName || it.uid == uid) {
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
        managerScope.cancel()
        appMetadataCache.evictAll()
        playerProxyMap.clear()
        appliedPlayerGains.clear()
        seenAppWriteTimes.clear()
    }

    private fun getPersistedVolume(packageName: String, uid: Int): Float {
        val persisted = appSettingsCache[packageName]?.defaultVolume ?: uidVolumeCache[uid] ?: 1.0f
        uidVolumeCache[uid] = persisted
        return persisted
    }

    private fun recordSeenAppIfNeeded(packageName: String, appName: String, uid: Int, volume: Float) {
        val now = System.currentTimeMillis()
        val lastWrite = seenAppWriteTimes[packageName] ?: 0L
        if (now - lastWrite < SEEN_APP_WRITE_INTERVAL_MS) return
        seenAppWriteTimes[packageName] = now

        managerScope.launch {
            preferencesManager.recordSeenApp(
                packageName = packageName,
                appName = appName,
                uid = uid,
                observedVolume = volume,
                timestamp = now
            )
        }
    }

    private fun schedulePersistSessionVolume(session: AudioSession, volume: Float) {
        volumePersistJobs.remove(session.packageName)?.cancel()
        volumePersistJobs[session.packageName] = managerScope.launch {
            delay(VOLUME_PERSIST_DEBOUNCE_MS)
            persistSessionVolume(session, volume)
            volumePersistJobs.remove(session.packageName)
        }
    }

    private suspend fun persistSessionVolume(session: AudioSession, volume: Float) {
        uidVolumeCache[session.uid] = volume
        preferencesManager.persistAppVolume(
            packageName = session.packageName,
            appName = session.appName,
            uid = session.uid,
            volume = volume
        )
    }

    private fun applyPersistedVolumes(sessions: List<AudioSession>) {
        sessions.forEach { session ->
            val persisted = getPersistedVolume(session.packageName, session.uid)
            val gain = toLogarithmicGain(persisted)
            val previousGain = appliedPlayerGains[session.sessionId]
            if (previousGain == null && persisted >= 1.0f - VOLUME_EPSILON) return@forEach
            if (previousGain != null && abs(previousGain - gain) <= VOLUME_EPSILON) return@forEach

            val applied = if (shizukuVolumeManager.isConnected.value) {
                shizukuVolumeManager.setPlayerVolume(session.sessionId, gain)
            } else {
                playerVolumeController.setPlayerVolumeByPiid(session.sessionId, gain)
            }
            if (applied) {
                appliedPlayerGains[session.sessionId] = gain
            }
        }
    }

    private fun pruneAppliedPlayerGains(activePlayerIds: Set<Int>) {
        appliedPlayerGains.keys.removeIf { it !in activePlayerIds }
    }

    private fun publishSessionState(
        sessions: List<AudioSession>,
        globalVolume: Int,
        maxVolume: Int
    ) {
        val current = _sessionState.value
        val sessionsChanged = current.sessions.size != sessions.size ||
            current.sessions.zip(sessions).any { (old, new) ->
                old.sessionId != new.sessionId ||
                    old.uid != new.uid ||
                    old.packageName != new.packageName ||
                    abs(old.volume - new.volume) > VOLUME_EPSILON
            }

        if (!sessionsChanged && current.globalVolume == globalVolume && current.maxVolume == maxVolume) {
            return
        }

        _sessionState.value = AudioSessionState(
            sessions = sessions,
            globalVolume = globalVolume,
            maxVolume = maxVolume,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun compactSessionsByPackage(sessions: List<AudioSession>): List<AudioSession> {
        val compacted = LinkedHashMap<String, AudioSession>()
        sessions.forEach { session ->
            val existing = compacted[session.packageName]
            compacted[session.packageName] = existing?.copy(
                volume = getPersistedVolume(existing.packageName, existing.uid),
                lastSeenTimestamp = maxOf(existing.lastSeenTimestamp, session.lastSeenTimestamp)
            )
                ?: session
        }
        return compacted.values.toList()
    }

}
