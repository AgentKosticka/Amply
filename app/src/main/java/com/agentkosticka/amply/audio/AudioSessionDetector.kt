package com.agentkosticka.amply.audio

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.agentkosticka.amply.data.DumpsysParseResult
import com.agentkosticka.amply.data.RawAudioSession

/**
 * Parses dumpsys audio output to extract active audio sessions.
 * Uses AGGRESSIVE pattern matching to catch ALL playing apps.
 * Phase 3.5: Enhanced detection with multiple strategies.
 * Supports Android 10+ (API 29+).
 */
class AudioSessionDetector(private val context: Context) {

    companion object {
        private const val TAG = "AudioSessionDetector"

        // Common audio stream types
        const val STREAM_MUSIC = AudioManager.STREAM_MUSIC // 3
        const val STREAM_VOICE_CALL = AudioManager.STREAM_VOICE_CALL // 0
        const val STREAM_NOTIFICATION = AudioManager.STREAM_NOTIFICATION // 5

        // System UIDs to filter out (<10000 are system, plus specific known system packages)
        private val SYSTEM_UID_THRESHOLD = 10000
        private val BLOCKED_UIDS = setOf(
            1000,  // system
            1001,  // radio
            1013,  // media
            1019,  // drm
            1041,  // audioserver
            1047,  // mediacodec
            1053,  // webview
            1058,  // cameraserver
            1066,  // statsd
            9999,  // shared_media
        )

        // Package names to filter out (system sounds, TTS, etc.)
        private val BLOCKED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.google.android.googlequicksearchbox",
            "com.google.android.tts",
            "com.android.bluetooth",
            "com.android.settings",
            "com.android.shell",
            "com.android.keychain",
            "com.nothing.systemui", // Nothing Phone specific
            "com.nothing.launcher",
            "com.google.android.inputmethod",
            "com.google.android.gms", // Google Play Services (often fires notification sounds)
        )
    }

    /**
     * Parse dumpsys audio output to extract active sessions.
     * Uses multiple strategies to find all playing apps.
     *
     * @param dumpsysOutput Raw output from `dumpsys audio`
     * @return DumpsysParseResult with parsed sessions or error
     */
    fun parseDumpsysOutput(dumpsysOutput: String?): DumpsysParseResult {
        if (dumpsysOutput == null) {
            return DumpsysParseResult.ShizukuNotAvailable
        }

        if (dumpsysOutput.isBlank()) {
            return DumpsysParseResult.Empty
        }

        return try {
            val allSessions = mutableListOf<RawAudioSession>()

            // Strategy 1: Parse AudioPlaybackConfiguration entries (most reliable)
            val playbackSessions = parsePlaybackConfigurations(dumpsysOutput)
            allSessions.addAll(playbackSessions)
            Log.d(TAG, "Strategy 1 (PlaybackConfig): Found ${playbackSessions.size} sessions")

            // Strategy 2: Parse "player piid:" entries
            val playerSessions = parsePlayerEntries(dumpsysOutput)
            allSessions.addAll(playerSessions)
            Log.d(TAG, "Strategy 2 (Players): Found ${playerSessions.size} sessions")

            // Strategy 3: Parse AudioTrack entries
            val trackSessions = parseAudioTracks(dumpsysOutput)
            allSessions.addAll(trackSessions)
            Log.d(TAG, "Strategy 3 (AudioTracks): Found ${trackSessions.size} sessions")

            // Strategy 4: Aggressive UID extraction from context
            val aggressiveSessions = parseAggressiveUidSearch(dumpsysOutput)
            allSessions.addAll(aggressiveSessions)
            Log.d(TAG, "Strategy 4 (Aggressive): Found ${aggressiveSessions.size} sessions")

            // Deduplicate by UID and filter system apps
            val uniqueSessions = allSessions
                .distinctBy { it.uid }
                .filter { isUserApp(it.uid) }

            Log.d(TAG, "Total unique user sessions: ${uniqueSessions.size}")

            if (uniqueSessions.isEmpty()) {
                DumpsysParseResult.Empty
            } else {
                DumpsysParseResult.Success(uniqueSessions)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse dumpsys output", e)
            DumpsysParseResult.Error(e.message ?: "Unknown parsing error")
        }
    }

    /**
     * Strategy 1: Parse AudioPlaybackConfiguration blocks
     * Format: AudioPlaybackConfiguration piid:X ... u/pid:UID/PID
     */
    private fun parsePlaybackConfigurations(output: String): List<RawAudioSession> {
        val sessions = mutableListOf<RawAudioSession>()
        var sessionCounter = 1000

        // Pattern for "piid:X" followed by "u/pid:UID/PID" or "uid:UID"
        val patterns = listOf(
            // piid:123 ... u/pid:10234/1234
            Regex("""piid[:\s]*(\d+)[^\n]*?u/pid[:\s]*(\d+)/\d+""", RegexOption.IGNORE_CASE),
            // piid:123 ... uid:10234
            Regex("""piid[:\s]*(\d+)[^\n]*?uid[:\s]*(\d+)""", RegexOption.IGNORE_CASE),
            // state:started ... u/pid:10234/1234
            Regex("""state[:\s]*(started|active)[^\n]*?u/pid[:\s]*(\d+)/\d+""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            pattern.findAll(output).forEach { match ->
                val sessionId = match.groupValues.getOrNull(1)?.toIntOrNull() ?: sessionCounter++
                val uid = match.groupValues.getOrNull(2)?.toIntOrNull()

                if (uid != null && isUserApp(uid) && sessions.none { it.uid == uid }) {
                    sessions.add(createSession(sessionId, uid))
                    Log.d(TAG, "PlaybackConfig: Found uid=$uid, piid=$sessionId")
                }
            }
        }

        return sessions
    }

    /**
     * Strategy 2: Parse "player piid:" entries
     * Format: player piid:X uid:Y state:started
     */
    private fun parsePlayerEntries(output: String): List<RawAudioSession> {
        val sessions = mutableListOf<RawAudioSession>()

        // Look for player entries
        val playerPattern = Regex(
            """player\s+piid[:\s]*(\d+)[^\n]*?uid[:\s]*(\d+)""",
            RegexOption.IGNORE_CASE
        )

        playerPattern.findAll(output).forEach { match ->
            val sessionId = match.groupValues[1].toIntOrNull() ?: return@forEach
            val uid = match.groupValues[2].toIntOrNull() ?: return@forEach

            if (isUserApp(uid) && sessions.none { it.uid == uid }) {
                sessions.add(createSession(sessionId, uid))
                Log.d(TAG, "Player: Found uid=$uid, piid=$sessionId")
            }
        }

        return sessions
    }

    /**
     * Strategy 3: Parse AudioTrack entries
     * Format: AudioTrack ... session X ... uid Y
     */
    private fun parseAudioTracks(output: String): List<RawAudioSession> {
        val sessions = mutableListOf<RawAudioSession>()
        var sessionCounter = 2000

        // Pattern for AudioTrack with session and uid
        val trackPatterns = listOf(
            Regex("""AudioTrack[^\n]*?session[:\s]*(\d+)[^\n]*?uid[:\s]*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""AudioTrack[^\n]*?uid[:\s]*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Track\s+\d+[^\n]*?uid[:\s]*(\d+)""", RegexOption.IGNORE_CASE)
        )

        for (pattern in trackPatterns) {
            pattern.findAll(output).forEach { match ->
                val uid = when (match.groupValues.size) {
                    3 -> match.groupValues[2].toIntOrNull()
                    2 -> match.groupValues[1].toIntOrNull()
                    else -> null
                }
                val sessionId = match.groupValues.getOrNull(1)?.toIntOrNull() ?: sessionCounter++

                if (uid != null && isUserApp(uid) && sessions.none { it.uid == uid }) {
                    sessions.add(createSession(sessionId, uid))
                    Log.d(TAG, "AudioTrack: Found uid=$uid")
                }
            }
        }

        return sessions
    }

    /**
     * Strategy 4: AGGRESSIVE UID search
     * Look for any UIDs in audio-related contexts with extended window
     * Phase 3.5: Enhanced with more context keywords and bigger search window
     */
    private fun parseAggressiveUidSearch(output: String): List<RawAudioSession> {
        val sessions = mutableListOf<RawAudioSession>()
        var sessionCounter = 3000

        // Extended list of audio-related context keywords
        val audioSections = listOf(
            "AudioPlaybackConfiguration",
            "Players:",
            "AudioTrack",
            "STREAM_MUSIC",
            "active players",
            "playback configurations",
            "AudioFlinger",
            "MediaPlayer",
            "ExoPlayer",
            "state:started",
            "state: started",
            "stream:", 
            "usage=USAGE_MEDIA",
            "content=CONTENT_TYPE_MUSIC",
            "AUDIO_OUTPUT_FLAG",
            "AudioMix",
            "AudioRecord",
            "session:",
            "audio focus",
            "ducking"
        )

        // Find lines near audio context that contain UIDs
        val lines = output.lines()
        var inAudioContext = false
        var contextLines = 0

        for (line in lines) {
            // Check if we're entering an audio context
            if (audioSections.any { line.contains(it, ignoreCase = true) }) {
                inAudioContext = true
                contextLines = 20 // Extended window: look at next 20 lines
            }

            if (inAudioContext && contextLines > 0) {
                contextLines--

                // Expanded UID patterns to catch more formats
                val uidPatterns = listOf(
                    Regex("""uid[:\s=]*(\d{5,})""", RegexOption.IGNORE_CASE),
                    Regex("""u/pid[:\s]*(\d{5,})/\d+""", RegexOption.IGNORE_CASE),
                    Regex("""\buid\s*=\s*(\d{5,})""", RegexOption.IGNORE_CASE),
                    Regex("""appId[:\s=]*(\d{5,})""", RegexOption.IGNORE_CASE),
                    Regex("""callingUid[:\s=]*(\d{5,})""", RegexOption.IGNORE_CASE),
                    Regex("""clientUid[:\s=]*(\d{5,})""", RegexOption.IGNORE_CASE),
                    Regex("""\(uid=(\d{5,})\)""", RegexOption.IGNORE_CASE)
                )

                for (pattern in uidPatterns) {
                    pattern.find(line)?.let { match ->
                        val uid = match.groupValues[1].toIntOrNull()
                        if (uid != null && isUserApp(uid) && sessions.none { it.uid == uid }) {
                            sessions.add(createSession(sessionCounter++, uid))
                            Log.d(TAG, "Aggressive: Found uid=$uid in audio context")
                        }
                    }
                }

                if (contextLines == 0) {
                    inAudioContext = false
                }
            }
        }

        // BONUS: Also look for all UIDs with active=true pattern anywhere
        val activeUidPattern = Regex("""active\s*[=:]\s*true[^}]*?uid[:\s=]*(\d{5,})""", 
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        activeUidPattern.findAll(output).forEach { match ->
            val uid = match.groupValues[1].toIntOrNull()
            if (uid != null && isUserApp(uid) && sessions.none { it.uid == uid }) {
                sessions.add(createSession(sessionCounter++, uid))
                Log.d(TAG, "Aggressive (active): Found uid=$uid")
            }
        }

        return sessions
    }

    /**
     * Parse media_session dumpsys output as fallback
     */
    fun parseMediaSessionOutput(output: String?): List<RawAudioSession> {
        if (output.isNullOrBlank()) return emptyList()

        val sessions = mutableListOf<RawAudioSession>()
        var sessionCounter = 4000

        // Look for active sessions with package and uid
        val patterns = listOf(
            Regex("""package\s*=\s*([^\s,]+)[^\n]*?uid\s*=\s*(\d+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            Regex("""uid[:\s]*(\d+)[^\n]*?package[:\s]*([^\s,]+)""", RegexOption.IGNORE_CASE),
            Regex("""MediaSession[^\n]*?uid[:\s]*(\d+)""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            pattern.findAll(output).forEach { match ->
                val uid = when {
                    match.groupValues.size >= 3 && match.groupValues[2].all { it.isDigit() } ->
                        match.groupValues[2].toIntOrNull()
                    match.groupValues.size >= 2 && match.groupValues[1].all { it.isDigit() } ->
                        match.groupValues[1].toIntOrNull()
                    else -> null
                }

                if (uid != null && isUserApp(uid) && sessions.none { it.uid == uid }) {
                    sessions.add(createSession(sessionCounter++, uid))
                    Log.d(TAG, "MediaSession: Found uid=$uid")
                }
            }
        }

        return sessions.distinctBy { it.uid }
    }

    /**
     * Check if UID belongs to a user app (not system)
     */
    private fun isUserApp(uid: Int): Boolean {
        // System apps have UID < 10000
        if (uid < SYSTEM_UID_THRESHOLD) return false

        // Check blocked UIDs
        if (uid in BLOCKED_UIDS) return false

        // Try to get package name and check blocklist
        try {
            val packages = context.packageManager.getPackagesForUid(uid)
            if (packages != null) {
                for (pkg in packages) {
                    if (pkg in BLOCKED_PACKAGES) {
                        return false
                    }
                }
            }
        } catch (_: Exception) {
            // If we can't check, assume it's okay
        }

        return true
    }

    /**
     * Create a RawAudioSession with defaults
     */
    private fun createSession(sessionId: Int, uid: Int): RawAudioSession {
        return RawAudioSession(
            sessionId = sessionId,
            uid = uid,
            streamType = STREAM_MUSIC,
            volumeLeft = 1.0f,
            volumeRight = 1.0f
        )
    }
}
