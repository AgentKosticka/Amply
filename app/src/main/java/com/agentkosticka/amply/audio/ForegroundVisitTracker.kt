package com.agentkosticka.amply.audio

import com.agentkosticka.amply.data.AudioSession
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

        val active = latestSessions.firstOrNull { it.packageName == normalized }
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
        val packageName = current.packageName ?: return
        val active = latestSessions.firstOrNull { it.packageName == packageName } ?: return
        if (!current.heardAudio || current.lastAudioSession != active) {
            _state.value = current.copy(heardAudio = true, lastAudioSession = active)
        }
    }
}
