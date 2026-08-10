package com.agentkosticka.amply.overlay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.agentkosticka.amply.AmplyApplication
import com.agentkosticka.amply.AmplyRuntime
import com.agentkosticka.amply.MainActivity
import com.agentkosticka.amply.R
import com.agentkosticka.amply.runtime.RuntimeErrorCode
import com.agentkosticka.amply.runtime.RuntimeHealth
import com.agentkosticka.amply.settings.model.OverlaySide
import com.agentkosticka.amply.settings.model.AppIdentity
import com.agentkosticka.amply.settings.model.AppSettings
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.audio.session.AppVolumeControlState
import com.agentkosticka.amply.audio.session.AudioSessionState
import com.agentkosticka.amply.audio.session.ForegroundVisitState
import com.agentkosticka.amply.overlay.window.OverlayManager
import com.agentkosticka.amply.overlay.window.OverlayAttachResult
import com.agentkosticka.amply.overlay.window.OverlayPresentationMode
import com.agentkosticka.amply.overlay.ui.OverlayAppPresentation
import com.agentkosticka.amply.service.OverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.milliseconds

private data class OverlayProjectionInput(
    val sessionState: AudioSessionState,
    val settings: Map<AppIdentity, AppSettings>,
    val appOrder: List<AppIdentity>,
    val connectionState: com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState,
    val foregroundVisit: ForegroundVisitState,
    val controlStates: Map<AppIdentity, AppVolumeControlState>
)

/**
 * Foreground service that manages the floating volume overlay
 * using OverlayManager singleton
 */
open class OverlayForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "amply_overlay_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_RUNTIME = "com.agentkosticka.amply.ACTION_START_RUNTIME"
        const val ACTION_PAUSE = "com.agentkosticka.amply.ACTION_PAUSE"
        const val ACTION_RESUME = "com.agentkosticka.amply.ACTION_RESUME"
        const val ACTION_RETRY = "com.agentkosticka.amply.ACTION_RETRY"

        private const val PENDING_SHOW_TTL_MS = 1_000L

        private data class PendingShowRequest(
            val host: WeakReference<Context>,
            val target: VolumeTarget,
            val iconType: String,
            val foregroundPackage: String?,
            val expiresAtElapsedMs: Long
        )

        private var activeRuntimeRef: WeakReference<OverlayForegroundService>? = null
        @Volatile private var pendingShowRequest: PendingShowRequest? = null

        fun startRuntime(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_START_RUNTIME
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun showFromAccessibilityHost(
            host: Context,
            target: VolumeTarget,
            iconType: String,
            foregroundPackage: String?
        ) {
            val runtime = activeRuntimeRef?.get()
            if (runtime == null) {
                pendingShowRequest = PendingShowRequest(
                    host = WeakReference(host),
                    target = target,
                    iconType = iconType,
                    foregroundPackage = foregroundPackage,
                    expiresAtElapsedMs = android.os.SystemClock.elapsedRealtime() +
                        PENDING_SHOW_TTL_MS
                )
                startRuntime(host)
                return
            }

            runtime.showOverlay(
                hostContext = host,
                target = target,
                iconType = iconType,
                foregroundPackage = foregroundPackage
            )
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val runtime: AmplyRuntime
        get() = (application as AmplyApplication).runtime
    private var runtimeInitialized = false
    private var overlaySide: OverlaySide = OverlaySide.LEFT
    private var overlayVerticalFraction: Float = 0.5f
    private var preferenceJobs: List<Job> = emptyList()
    private var notificationJob: Job? = null
    private lateinit var appPresenter: OverlayAppPresenter
    private var latestPresentedApps: List<OverlayAppPresentation> = emptyList()
    private var screenOffReceiverRegistered = false
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                OverlayManager.dismissImmediatelyForScreenOff()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeRuntimeRef = WeakReference(this)
        createNotificationChannel()
        runCatching {
            ContextCompat.registerReceiver(
                this,
                screenOffReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            screenOffReceiverRegistered = true
        }.onFailure { error ->
            Log.w("OverlayService", "Could not register screen-off receiver", error)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        runtime.setForegroundServiceRunning(true)
        initializeRuntime()

        when (intent?.action) {
            ACTION_PAUSE -> serviceScope.launch { runtime.preferencesManager.pauseAmply() }
            ACTION_RESUME -> serviceScope.launch { runtime.preferencesManager.restoreAmplyNow() }
            ACTION_RETRY -> runtime.retryVolumeServiceConnection()
        }
        drainPendingShowRequest()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun initializeRuntime() {
        if (runtimeInitialized) return

        try {
            val preferences = runtime.preferencesManager
            val sessionManager = runtime.audioSessionManager
            appPresenter = OverlayAppPresenter(applicationContext)
            runtimeInitialized = true

            preferenceJobs = listOf(
                serviceScope.launch {
                    preferences.overlaySide.collect { side ->
                        overlaySide = side
                    }
                },
                serviceScope.launch {
                    preferences.overlayVerticalFraction.collect { fraction ->
                        overlayVerticalFraction = fraction
                    }
                },
                serviceScope.launch {
                    preferences.volumeDotScaleConfig.collect(OverlayManager::updateVolumeDotScaleConfig)
                },
                serviceScope.launch {
                    preferences.disableShizukuDisconnectedWarning.collect { disabled ->
                        OverlayManager.updateShizukuDisconnectedWarningEnabled(!disabled)
                    }
                },
                serviceScope.launch {
                    preferences.hidePerAppVolumeControl.collect { hidden ->
                        OverlayManager.updatePerAppVolumeControlEnabled(!hidden)
                    }
                },
                serviceScope.launch {
                    preferences.hideAppProfileIdentity.collect { hidden ->
                        OverlayManager.updateAppProfileIdentityVisible(!hidden)
                    }
                },
                serviceScope.launch {
                    preferences.hideStandDownButton.collect { hidden ->
                        OverlayManager.updateStandDownButtonEnabled(!hidden)
                    }
                },
                serviceScope.launch {
                    preferences.showDndButton.collect(OverlayManager::updateDndButtonEnabled)
                },
                serviceScope.launch {
                    runtime.dndController.active.collect(OverlayManager::updateDndActive)
                },
                serviceScope.launch {
                    runtime.profileCoordinator.state.collect(OverlayManager::updateProfileState)
                },
                serviceScope.launch {
                    runtime.selectedVolumeTarget.collect { target ->
                        OverlayManager.updateSelectedVolumeTarget(target)
                    }
                },
                serviceScope.launch {
                    runtime.dynamicStreamState.collect(OverlayManager::updateDynamicStreams)
                },
                serviceScope.launch {
                    val settingsAndOrder = combine(
                        preferences.appSettings,
                        preferences.appOverlayOrder,
                        runtime.profileCoordinator.effectiveAppVolumes
                    ) { settings, order, overrides ->
                        settings.mapValues { (identity, setting) ->
                            setting.copy(defaultVolume = overrides[identity] ?: setting.defaultVolume)
                        } to order
                    }
                    combine(
                        runtime.sessionState,
                        settingsAndOrder,
                        runtime.connectionState,
                        runtime.foregroundVisitState,
                        sessionManager.appVolumeControlStates
                    ) { sessionState, settingsWithOrder, connectionState, foregroundVisit, controlStates ->
                        OverlayProjectionInput(
                            sessionState = sessionState,
                            settings = settingsWithOrder.first,
                            appOrder = settingsWithOrder.second,
                            connectionState = connectionState,
                            foregroundVisit = foregroundVisit,
                            controlStates = controlStates
                        )
                    }.map { input ->
                        withContext(Dispatchers.Default) {
                            val entries = sessionManager.getOverlayApps(
                                foregroundVisitSession = input.foregroundVisit.lastAudioSession
                                    ?.takeIf { input.foregroundVisit.heardAudio },
                                shizukuConnected = input.connectionState == com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState.CONNECTED,
                                settings = input.settings,
                                activeSessions = input.sessionState.sessions,
                                controlStates = input.controlStates,
                                appOrder = input.appOrder
                            )
                            input.connectionState to appPresenter.present(entries)
                        }
                    }.distinctUntilChanged().collect { (connectionState, apps) ->
                        latestPresentedApps = apps
                        OverlayManager.updateApps(apps, connectionState)
                    }
                },
                serviceScope.launch(Dispatchers.Default) {
                    val icon = appPresenter.loadShizukuIcon()
                    withContext(Dispatchers.Main) {
                        OverlayManager.updateShizukuIcon(icon)
                    }
                }
            )

            notificationJob = serviceScope.launch {
                runtime.runtimeHealth.collect { health ->
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID,
                        createNotification(health)
                    )
                }
            }

            OverlayManager.setAppVolumeCallback { app, volume ->
                sessionManager.setAppVolume(app, volume)
            }
            OverlayManager.setVolumeTargetCallbacks(
                onSelected = runtime.volumeTargetSessionController::onUserSelected,
                onShown = runtime::onOverlayShown,
                onHidden = runtime::onOverlayHidden,
                onTutorialPreviewFinished = runtime::onTutorialOverlayPreviewFinished
            )
            OverlayManager.setSystemStreamVolumeCallback(runtime::setSystemStreamVolume)
            OverlayManager.setPauseAmplyCallback {
                serviceScope.launch { preferences.pauseAmply() }
            }
            OverlayManager.setDndToggleCallback {
                runtime.dndController.toggleFromOverlay()
            }
            OverlayManager.setProfileCallback { id ->
                serviceScope.launch { runtime.profileCoordinator.activateProfile(id) }
            }
            OverlayManager.setNotificationModeToggleCallback {
                serviceScope.launch {
                    runtime.ringerExperimentExecutor.toggleProductionAlertMode()
                    OverlayManager.refreshStreamVolumes()
                }
            }

            Log.d("OverlayService", "Connected to process-owned audio runtime")
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to initialize runtime", e)
        }
    }

    private fun showOverlay(
        hostContext: Context,
        target: VolumeTarget,
        iconType: String,
        foregroundPackage: String?
    ) {
        initializeRuntime()
        val powerManager = getSystemService(PowerManager::class.java)
        if (!powerManager.isInteractive) {
            OverlayManager.dismissImmediatelyForScreenOff()
            return
        }
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!locked) foregroundPackage?.let(runtime::onForegroundPackageChanged)

        val sessionManager = runtime.audioSessionManager
        if (!OverlayManager.isShowing()) {
            sessionManager.requestRefresh()
        }
        val connectionState = runtime.connectionState.value
        val apps = if (locked) emptyList() else latestPresentedApps

        Log.d(
            "OverlayService",
            "showOverlay: target=$target apps=${apps.size} connection=$connectionState"
        )

        val result = OverlayManager.show(
            context = hostContext,
            selectedTarget = target,
            newIconType = iconType,
            apps = apps,
            connectionState = connectionState,
            overlaySide = overlaySide,
            overlayVerticalFraction = overlayVerticalFraction,
            requestedPresentationMode = if (locked) {
                OverlayPresentationMode.LOCK_SCREEN_SYSTEM_ONLY
            } else {
                OverlayPresentationMode.NORMAL
            }
        )
        if (result == OverlayAttachResult.FAILED) {
            runtime.reportRuntimeError(RuntimeErrorCode.OVERLAY_ATTACH_FAILED)
        } else {
            runtime.clearRuntimeError(RuntimeErrorCode.OVERLAY_ATTACH_FAILED)
        }
    }

    private fun drainPendingShowRequest() {
        val request = pendingShowRequest ?: return
        pendingShowRequest = null
        val host = request.host.get()
        if (host == null || android.os.SystemClock.elapsedRealtime() > request.expiresAtElapsedMs) {
            runtime.reportRuntimeError(RuntimeErrorCode.OVERLAY_HOST_UNAVAILABLE)
            return
        }
        showOverlay(
            hostContext = host,
            target = request.target,
            iconType = request.iconType,
            foregroundPackage = request.foregroundPackage
        )
        serviceScope.launch {
            delay(PENDING_SHOW_TTL_MS.milliseconds)
            runtime.clearRuntimeError(RuntimeErrorCode.OVERLAY_HOST_UNAVAILABLE)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Volume Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Amply (1) overlay active"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(health: RuntimeHealth = runtime.runtimeHealth.value): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val status = when {
            health.recoverableError != null -> "Needs attention: ${health.recoverableError.code.name.lowercase().replace('_', ' ')}"
            health.isPaused -> "Volume controls paused"
            !health.accessibilityConnected -> "Enable the Amply Accessibility Service"
            health.shizukuPermission != com.agentkosticka.amply.shizuku.client.ShizukuPermissionState.GRANTED ->
                "Shizuku permission required"
            health.volumeServiceConnection != com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState.CONNECTED ->
                "Connecting to Shizuku"
            else -> "Volume controls active"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Amply (1)")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_amply_logo_monochrome)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openApp)

        val pauseAction = if (health.isPaused) {
            notificationAction(ACTION_RESUME, 2, "Resume")
        } else {
            notificationAction(ACTION_PAUSE, 3, "Pause")
        }
        builder.addAction(0, if (health.isPaused) "Resume" else "Pause", pauseAction)
        if (health.volumeServiceConnection !=
            com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState.CONNECTED ||
            health.recoverableError != null
        ) {
            builder.addAction(0, "Retry", notificationAction(ACTION_RETRY, 4, "Retry"))
        }
        return builder.build()
    }

    private fun notificationAction(action: String, requestCode: Int, label: String): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, OverlayService::class.java).setAction(action).putExtra("label", label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    override fun onDestroy() {
        if (screenOffReceiverRegistered) {
            runCatching { unregisterReceiver(screenOffReceiver) }
            screenOffReceiverRegistered = false
        }
        super.onDestroy()
        if (activeRuntimeRef?.get() === this) {
            activeRuntimeRef = null
        }
        runtime.setForegroundServiceRunning(false)
        notificationJob?.cancel()
        preferenceJobs.forEach { it.cancel() }
        if (::appPresenter.isInitialized) appPresenter.clear()
        serviceScope.cancel()
        OverlayManager.cleanup()
        OverlayManager.clearAppVolumeCallback()
        OverlayManager.clearVolumeTargetCallbacks()
        OverlayManager.clearPauseAmplyCallback()
        OverlayManager.clearDndToggleCallback()
        OverlayManager.clearProfileCallback()
        OverlayManager.clearNotificationModeToggleCallback()
        OverlayManager.clearSystemStreamVolumeCallback()
    }
}
