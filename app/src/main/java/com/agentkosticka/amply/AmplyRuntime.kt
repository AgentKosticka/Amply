package com.agentkosticka.amply

import android.content.Context
import android.util.Log
import com.agentkosticka.amply.audio.AudioSessionManager
import com.agentkosticka.amply.audio.ForegroundVisitTracker
import com.agentkosticka.amply.data.PreferencesManager
import com.agentkosticka.amply.shizuku.ShizukuRepository
import com.agentkosticka.amply.shizuku.ShizukuVolumeManager
import com.agentkosticka.amply.shizuku.VolumeServiceConnectionCoordinator
import com.agentkosticka.amply.shizuku.VolumeServiceConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AmplyRuntime(context: Context) {
    companion object {
        private const val TAG = "AmplyRuntime"
    }

    private val appContext = context.applicationContext
    private val runtimeScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    val preferencesManager = PreferencesManager(appContext)
    val shizukuRepository = ShizukuRepository(appContext)
    val shizukuVolumeManager = ShizukuVolumeManager(appContext.packageName)
    val audioSessionManager = AudioSessionManager(
        context = appContext,
        preferencesManager = preferencesManager,
        shizukuVolumeManager = shizukuVolumeManager
    )
    val foregroundVisitTracker = ForegroundVisitTracker()
    val foregroundVisitState = foregroundVisitTracker.state

    private val connectionCoordinator = VolumeServiceConnectionCoordinator(
        scope = runtimeScope,
        permissionState = shizukuRepository.permissionState,
        connector = shizukuVolumeManager,
        permissionRefresher = { shizukuRepository.checkPermissionState() }
    )

    val sessionState = audioSessionManager.sessionState
    val permissionState = shizukuRepository.permissionState
    val connectionState: StateFlow<VolumeServiceConnectionState> = shizukuVolumeManager.connectionState

    init {
        Log.i(TAG, "Creating process-owned Amply runtime")
        runtimeScope.launch {
            sessionState.collect { state ->
                foregroundVisitTracker.onSessionsChanged(state.sessions)
            }
        }
        connectionCoordinator.start()
        audioSessionManager.startPolling()
    }

    fun onForegroundPackageChanged(packageName: String?) {
        foregroundVisitTracker.onForegroundChanged(packageName)
    }

    fun retryVolumeServiceConnection() {
        connectionCoordinator.retryNow()
    }
}
