package com.agentkosticka.amply.audio.session

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.util.Log
import android.util.LruCache
import com.agentkosticka.amply.audio.routing.ActiveSystemStreams
import com.agentkosticka.amply.audio.routing.StreamTopology
import com.agentkosticka.amply.settings.model.AppSettings
import com.agentkosticka.amply.settings.model.AppIdentity
import com.agentkosticka.amply.settings.data.PreferencesManager
import com.agentkosticka.amply.shizuku.client.ShizukuVolumeManager
import com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

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
    private val _activePlaybackUsages = MutableStateFlow<Set<Int>>(emptySet())
    val activePlaybackUsages: StateFlow<Set<Int>> = _activePlaybackUsages.asStateFlow()
    private val _activeSystemStreams = MutableStateFlow(ActiveSystemStreams())
    val activeSystemStreams: StateFlow<ActiveSystemStreams> = _activeSystemStreams.asStateFlow()
    private val _appVolumeControlStates = MutableStateFlow<Map<AppIdentity, AppVolumeControlState>>(emptyMap())
    val appVolumeControlStates: StateFlow<Map<AppIdentity, AppVolumeControlState>> =
        _appVolumeControlStates.asStateFlow()

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
    // LRU cache for app metadata
    private val appMetadataCache = LruCache<Int, AppMetadata>(CACHE_SIZE)
    
    // NEW: Volume persistence cache (uid -> volume)
    private val uidVolumeCache = ConcurrentHashMap<Int, Float>()
    private val appliedPlayerGains = ConcurrentHashMap<Int, Float>()
    private val seenAppWriteTimes = ConcurrentHashMap<String, Long>()
    private val volumePersistJobs = ConcurrentHashMap<String, Job>()
    private val pendingVolumeUpdates = ConcurrentHashMap<String, VolumeUpdate>()
    @Volatile
    private var appSettingsCache: Map<AppIdentity, AppSettings> = emptyMap()
    private var playerIdsByIdentity: Map<AppIdentity, Set<Int>> = emptyMap()
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
                delay(REFRESH_DEBOUNCE_MS.milliseconds)
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
                delay(delayMs.milliseconds)
                requestRefresh()
            }
        }

        connectionJob = managerScope.launch {
            shizukuVolumeManager.connectionState
                .collect { state ->
                    if (state == VolumeServiceConnectionState.CONNECTED) {
                        appliedPlayerGains.clear()
                        _appVolumeControlStates.value = emptyMap()
                        requestRefresh()
                    } else {
                        _activeSystemStreams.value = ActiveSystemStreams()
                    }
                }
        }

        volumeUpdateJob = managerScope.launch {
            for (ignored in volumeUpdateSignals) {
                delay(VOLUME_UPDATE_INTERVAL_MS.milliseconds)
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
                _activePlaybackUsages.value = playerVolumeController.getActivePlaybackUsages(configs.orEmpty())
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
        _activePlaybackUsages.value = emptySet()
        _activeSystemStreams.value = ActiveSystemStreams()
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
                val topology = shizukuVolumeManager.getStreamTopology() ?: StreamTopology.UNKNOWN
                _activeSystemStreams.value = ActiveSystemStreams(
                    rawStreamTypes = privilegedPlaybacks.mapTo(linkedSetOf()) { it.streamType },
                    topology = topology,
                    shizukuConnected = true
                )
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

        _activeSystemStreams.value = ActiveSystemStreams()

        // ==============================================
        // FALLBACK 1: PlayerVolumeController (local reflection)
        // Usually returns -1 for uid due to sanitization
        // ==============================================
        val activePlayers = playerVolumeController.getActivePlayers()
        Log.d(TAG, "PlayerVolumeController found ${activePlayers.size} active players")

        if (activePlayers.isNotEmpty()) {
            pruneAppliedPlayerGains(activePlayers.mapTo(mutableSetOf()) { it.piid })
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
                    streamType = playback.streamType,
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
                streamType = playback.streamType,
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

    fun getDefaultOverlaySessions(): List<AudioSession> =
        compactSessionsByPackage(
            _sessionState.value.sessions
            .filter { appSettingsCache[it.identity]?.hiddenInOverlay != true }
        )

    fun getOverlayApps(
        foregroundVisitSession: AudioSession?,
        shizukuConnected: Boolean = shizukuVolumeManager.isConnected.value,
        settings: Map<AppIdentity, AppSettings> = appSettingsCache
    ): List<OverlayAppEntry> {
        val activeByIdentity = compactSessionsByPackage(_sessionState.value.sessions)
            .associateBy { it.identity }
        return selectOverlayPackages(
            activeSessions = _sessionState.value.sessions,
            appSettings = settings,
            foregroundVisitSession = foregroundVisitSession,
            shizukuConnected = shizukuConnected
        ).mapNotNull { identity ->
            val active = activeByIdentity[identity]
            val setting = settings[identity]
            val foreground = foregroundVisitSession?.takeIf { it.identity == identity }
            if (active == null && setting == null && foreground == null) return@mapNotNull null

            OverlayAppEntry(
                packageName = identity.packageName,
                uid = active?.uid ?: foreground?.uid ?: setting!!.uid,
                appName = active?.appName ?: foreground?.appName ?: setting!!.appName,
                appIcon = active?.appIcon ?: foreground?.appIcon ?: runCatching {
                    packageManager.getApplicationIcon(identity.packageName)
                }.getOrNull(),
                volume = setting?.defaultVolume ?: active?.volume ?: foreground!!.volume,
                isPlaying = active != null,
                controlState = if (active == null) {
                    AppVolumeControlState.SAVED_ONLY
                } else {
                    _appVolumeControlStates.value[identity] ?: AppVolumeControlState.ACTIVE
                }
            )
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
        val key = _sessionState.value.sessions.firstOrNull { it.sessionId == sessionId }
            ?.identity
            ?.storageKey
            ?: packageName?.takeIf { it.isNotBlank() }
            ?: "session:$sessionId"
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
        val activeSessions = _sessionState.value.sessions
        val targetSession = activeSessions.find { it.sessionId == sessionId }
            ?: packageName?.let { requestedPackage ->
                activeSessions.find { it.packageName == requestedPackage }
            }

        if (targetSession != null) {
            val uid = targetSession.uid
            val identity = targetSession.identity
            val targetPackage = packageName?.takeIf { it.isNotBlank() } ?: targetSession.packageName
            val packageSessions = activeSessions.filter { it.identity == identity }
            val targetSessions = packageSessions.ifEmpty {
                activeSessions.filter { it.uid == uid }
            }
            val previousVolume = targetSession.volume
            val gain = toLogarithmicGain(volume)
            val successfulIds = linkedSetOf<Int>()

            if (shizukuVolumeManager.isConnected.value) {
                targetSessions.forEach { session ->
                    if (shizukuVolumeManager.setPlayerVolume(session.sessionId, gain)) {
                        successfulIds += session.sessionId
                        appliedPlayerGains[session.sessionId] = gain
                    }
                }
            }
            targetSessions.filterNot { it.sessionId in successfulIds }.forEach { session ->
                if (playerVolumeController.setPlayerVolumeByPiid(session.sessionId, gain)) {
                    successfulIds += session.sessionId
                }
            }

            val result = AppVolumeApplyResult(identity, targetSessions.size, successfulIds.size)
            _appVolumeControlStates.value = _appVolumeControlStates.value + (identity to result.state)
            if (successfulIds.isNotEmpty()) {
                updateLocalSessionVolume(targetPackage, uid, volume)
                schedulePersistSessionVolume(targetSession, volume)
            } else {
                updateLocalSessionVolume(targetPackage, uid, previousVolume)
                Log.w(TAG, "No active player accepted per-app volume for $identity")
            }
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

    fun setAppVolume(packageName: String, volume: Float) {
        val active = _sessionState.value.sessions.firstOrNull { it.packageName == packageName }
        if (active != null) {
            setSessionVolume(active.sessionId, packageName, volume)
        } else {
            managerScope.launch {
                preferencesManager.setAppDefaultVolume(packageName, volume, active?.uid ?: -1)
            }
        }
    }

    fun setAppVolume(app: OverlayAppEntry, volume: Float) {
        val active = _sessionState.value.sessions.firstOrNull { it.identity == app.identity }
        if (active != null) {
            setSessionVolume(active.sessionId, app.packageName, volume)
        } else {
            managerScope.launch {
                preferencesManager.persistAppVolume(
                    packageName = app.packageName,
                    appName = app.appName,
                    uid = app.uid,
                    volume = volume
                )
            }
        }
    }

    /**
     * Update local session state with new volume
     */
    private fun updateLocalSessionVolume(packageName: String, uid: Int, volume: Float) {
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
     * Cleanup resources
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up AudioSessionManager")
        stopPolling()
        managerScope.cancel()
        appMetadataCache.evictAll()
        appliedPlayerGains.clear()
        seenAppWriteTimes.clear()
    }

    private fun getPersistedVolume(packageName: String, uid: Int): Float {
        val persisted = appSettingsCache[AppIdentity.fromUid(packageName, uid)]?.defaultVolume
            ?: uidVolumeCache[uid]
            ?: 1.0f
        uidVolumeCache[uid] = persisted
        return persisted
    }

    private fun recordSeenAppIfNeeded(packageName: String, appName: String, uid: Int, volume: Float) {
        val now = System.currentTimeMillis()
        val identityKey = AppIdentity.fromUid(packageName, uid).storageKey
        val lastWrite = seenAppWriteTimes[identityKey] ?: 0L
        if (now - lastWrite < SEEN_APP_WRITE_INTERVAL_MS) return
        seenAppWriteTimes[identityKey] = now

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
        val identityKey = session.identity.storageKey
        volumePersistJobs.remove(identityKey)?.cancel()
        volumePersistJobs[identityKey] = managerScope.launch {
            delay(VOLUME_PERSIST_DEBOUNCE_MS.milliseconds)
            persistSessionVolume(session, volume)
            volumePersistJobs.remove(identityKey)
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
        val nextPlayerIds = sessions.groupBy { it.identity }
            .mapValues { (_, values) -> values.mapTo(linkedSetOf()) { it.sessionId } }
        val changedIdentities = (nextPlayerIds.keys + playerIdsByIdentity.keys).filterTo(linkedSetOf()) {
            nextPlayerIds[it] != playerIdsByIdentity[it]
        }
        if (changedIdentities.isNotEmpty()) {
            _appVolumeControlStates.value = _appVolumeControlStates.value - changedIdentities
            playerIdsByIdentity = nextPlayerIds
        }
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
        val compacted = LinkedHashMap<AppIdentity, AudioSession>()
        sessions.forEach { session ->
            val existing = compacted[session.identity]
            compacted[session.identity] = existing?.copy(
                volume = getPersistedVolume(existing.packageName, existing.uid),
                lastSeenTimestamp = maxOf(existing.lastSeenTimestamp, session.lastSeenTimestamp)
            )
                ?: session
        }
        return compacted.values.toList()
    }

}
