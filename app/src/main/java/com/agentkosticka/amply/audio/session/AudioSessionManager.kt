package com.agentkosticka.amply.audio.session

import android.content.Context
import android.app.ActivityManager
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.UserHandle
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
import com.agentkosticka.amply.profiles.ProfileCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages audio session detection and app metadata enrichment.
 *
 * Public playback callbacks provide activity hints; the Shizuku UserService is the
 * only component allowed to discover and control privileged player interfaces.
 */
class AudioSessionManager(
    context: Context,
    private val preferencesManager: PreferencesManager,
    private val shizukuVolumeManager: ShizukuVolumeManager,
    private val profileCoordinator: ProfileCoordinator
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

    private val packageManager: PackageManager = context.packageManager
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val activityManager = context.getSystemService(ActivityManager::class.java)
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

    // Public callback is used only to prompt privileged-session refreshes.
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    // Profile-aware label and icon cache; player mutation remains in UserService.
    private val appMetadataCache = LruCache<String, AppMetadata>(CACHE_SIZE)
    
    // Volume persistence cache keyed by Android UID.
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
        val target: AppVolumeTarget,
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
            combine(
                preferencesManager.appSettings,
                profileCoordinator.effectiveAppVolumes
            ) { settings, overrides ->
                settings.mapValues { (identity, setting) ->
                    setting.copy(defaultVolume = overrides[identity] ?: setting.defaultVolume)
                }
            }.collect { settings ->
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
            processRefreshRequests(refreshRequests, REFRESH_DEBOUNCE_MS) {
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
                    applySessionVolume(update.sessionId, update.target, update.volume)
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
                _activePlaybackUsages.value = configs.orEmpty()
                    .mapTo(linkedSetOf()) { it.audioAttributes.usage }
                requestRefresh()
            }
        }

        audioManager.registerAudioPlaybackCallback(playbackCallback!!, null)
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
            audioManager.unregisterAudioPlaybackCallback(it)
            playbackCallback = null
        }
        _activePlaybackUsages.value = emptySet()
        _activeSystemStreams.value = ActiveSystemStreams()
    }

    /**
     * Update session state using the privileged Shizuku service.
     */
    private fun updateSessions() {
        // Get current global volume
        val globalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        // Privileged discovery is the sole source of controllable sessions.
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

        pruneAppliedPlayerGains(emptySet())
        publishSessionState(emptyList(), globalVolume, maxVolume)
    }

    fun requestRefresh() {
        refreshRequests.trySend(Unit)
    }

    /** Adds profile-aware app metadata to a privileged playback record. */
    private fun enrichPrivilegedPlayback(
        playback: ShizukuVolumeManager.PrivilegedPlayback
    ): AudioSession? {
        return try {
            val uid = playback.uid
            val packageName = resolvePlaybackPackage(uid, playback.pid) ?: return null
            val identity = AppIdentity(userId = playback.userId, packageName = packageName)

            // Check cache first
            val cached = appMetadataCache.get(identity.storageKey)
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

            val profile = UserHandle.getUserHandleForUid(uid)
            val appInfo = launcherApps.getApplicationInfo(packageName, 0, profile)
            val appName = packageManager.getApplicationLabel(appInfo).toString()
            val appIcon = try {
                launcherApps.getApplicationInfo(packageName, 0, profile).loadIcon(packageManager)
            } catch (_: Exception) {
                null
            }

            // Cache metadata
            appMetadataCache.put(identity.storageKey, AppMetadata(packageName, appName, appIcon))
            val persistedVolume = getPersistedVolume(packageName, uid)
            recordSeenAppIfNeeded(packageName, appName, uid, persistedVolume)

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
            Log.w(TAG, "Playback package is no longer installed")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Could not resolve playback metadata", e)
            null
        }
    }

    private fun resolvePlaybackPackage(uid: Int, pid: Int): String? {
        val candidates = packageManager.getPackagesForUid(uid)
            ?.filter(String::isNotBlank)
            ?.distinct()
            .orEmpty()
        if (candidates.size == 1) return candidates.single()
        if (candidates.isEmpty()) return null

        val processName = activityManager.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
        return resolveSharedUidPackage(candidates, processName)
    }

    fun getOverlayApps(
        foregroundVisitSession: AudioSession?,
        shizukuConnected: Boolean = shizukuVolumeManager.isConnected.value,
        settings: Map<AppIdentity, AppSettings> = appSettingsCache,
        activeSessions: List<AudioSession> = _sessionState.value.sessions,
        controlStates: Map<AppIdentity, AppVolumeControlState> = _appVolumeControlStates.value,
        appOrder: List<AppIdentity> = emptyList()
    ): List<OverlayAppEntry> {
        val activeByIdentity = compactSessionsByPackage(activeSessions)
            .associateBy { it.identity }
        return selectOverlayPackages(
            activeSessions = activeSessions,
            appSettings = settings,
            foregroundVisitSession = foregroundVisitSession,
            shizukuConnected = shizukuConnected,
            appOrder = appOrder
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
                    controlStates[identity] ?: AppVolumeControlState.ACTIVE
                }
            )
        }
    }

    /**
     * Set volume for a specific app session
     *
     * @param sessionId The audio session ID (piid)
     * @param volume Volume level (0.0 to 1.0)
     */
    private fun setSessionVolume(sessionId: Int, target: AppVolumeTarget, volume: Float) {
        val key = target.pendingUpdateKey
        synchronized(pendingVolumeLock) {
            pendingVolumeUpdates[key] = VolumeUpdate(
                sessionId = sessionId,
                target = target,
                volume = volume.coerceIn(0f, 1f)
            )
        }
        volumeUpdateSignals.trySend(Unit)
    }

    private suspend fun applySessionVolume(sessionId: Int, target: AppVolumeTarget, volume: Float) {
        val activeSessions = _sessionState.value.sessions
        val targetSession = resolveAppVolumeSession(activeSessions, sessionId, target)

        if (targetSession != null) {
            val uid = targetSession.uid
            val identity = targetSession.identity
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
            val result = AppVolumeApplyResult(identity, targetSessions.size, successfulIds.size)
            _appVolumeControlStates.value += (identity to result.state)
            if (successfulIds.isNotEmpty()) {
                updateLocalSessionVolume(uid, volume)
                schedulePersistSessionVolume(targetSession, volume)
            } else {
                updateLocalSessionVolume(uid, previousVolume)
                Log.w(TAG, "No active player accepted per-app volume for $identity")
            }
        } else {
            Log.w(TAG, "Playback session is no longer active; persisting its saved volume")
            persistAppVolume(target, volume)
        }
    }

    fun setAppVolume(target: AppVolumeTarget, volume: Float) {
        val active = _sessionState.value.sessions.firstOrNull { it.identity == target.identity }
        if (active != null) {
            setSessionVolume(active.sessionId, target, volume)
        } else {
            managerScope.launch {
                persistAppVolume(target, volume)
            }
        }
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
        val target = AppVolumeTarget(
            packageName = session.packageName,
            appName = session.appName,
            uid = session.uid
        )
        persistAppVolume(target, volume)
    }

    private suspend fun persistAppVolume(target: AppVolumeTarget, volume: Float) {
        if (profileCoordinator.recordAppVolume(target.identity, volume)) return
        preferencesManager.persistAppVolume(
            packageName = target.packageName,
            appName = target.appName,
            uid = target.uid,
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

            val applied = shizukuVolumeManager.isConnected.value &&
                shizukuVolumeManager.setPlayerVolume(session.sessionId, gain)
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
            _appVolumeControlStates.value -= changedIdentities
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

internal fun resolveSharedUidPackage(
    candidates: List<String>,
    processName: String?
): String? {
    val unique = candidates.filter(String::isNotBlank).distinct()
    if (unique.size == 1) return unique.single()
    val normalizedProcess = processName?.substringBefore(':') ?: return null
    return unique.singleOrNull { it == normalizedProcess }
}
