package com.agentkosticka.amply.audio.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ForegroundVisitState(
    val packageName: String? = null,
    val visitId: Long = 0L,
    val heardAudio: Boolean = false,
    val lastAudioSession: AudioSession? = null
)

class ForegroundVisitTracker {
    private val _state = MutableStateFlow(ForegroundVisitState())
    val state: StateFlow<ForegroundVisitState> = _state.asStateFlow()
    private var latestSessions: List<AudioSession> = emptyList()

    @Synchronized
    fun onForegroundChanged(packageName: String?) {
        val normalized = packageName?.takeIf { it.isNotBlank() }
        if (_state.value.packageName == normalized) {
            markCurrentVisitIfPlaying()
            return
        }

        val inferred = _state.value.lastAudioSession
            ?.takeIf { _state.value.packageName == null && it.packageName == normalized }
        val active = latestSessions.firstOrNull { it.packageName == normalized } ?: inferred
        _state.value = ForegroundVisitState(
            packageName = normalized,
            visitId = _state.value.visitId + 1L,
            heardAudio = active != null,
            lastAudioSession = active
        )
    }

    @Synchronized
    fun onSessionsChanged(sessions: List<AudioSession>) {
        latestSessions = sessions
        markCurrentVisitIfPlaying()
    }

    private fun markCurrentVisitIfPlaying() {
        val current = _state.value
        val packageName = current.packageName
        if (packageName == null) {
            val identities = latestSessions.mapTo(linkedSetOf()) { it.identity }
            if (identities.size == 1) {
                val active = latestSessions.first()
                if (!current.heardAudio || current.lastAudioSession?.identity != active.identity) {
                    _state.value = current.copy(heardAudio = true, lastAudioSession = active)
                }
            }
            return
        }
        val active = latestSessions.firstOrNull { it.packageName == packageName } ?: return
        if (!current.heardAudio || current.lastAudioSession != active) {
            _state.value = current.copy(heardAudio = true, lastAudioSession = active)
        }
    }
}
