package com.agentkosticka.amply.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.agentkosticka.amply.AmplyApplication
import com.agentkosticka.amply.AmplyRuntime
import com.agentkosticka.amply.R
import com.agentkosticka.amply.data.OverlaySide
import com.agentkosticka.amply.audio.VolumeTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Foreground service that manages the floating volume overlay
 * using OverlayManager singleton
 */
class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "amply_overlay_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_RUNTIME = "com.agentkosticka.amply.ACTION_START_RUNTIME"
        const val ACTION_SHOW_OVERLAY = "com.agentkosticka.amply.ACTION_SHOW_OVERLAY"
        const val EXTRA_VOLUME_TARGET = "extra_volume_target"
        const val EXTRA_ICON_TYPE = "extra_icon_type"
        const val EXTRA_FOCUSED_PACKAGE = "extra_focused_package"

        private var activeRuntimeRef: WeakReference<OverlayService>? = null

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
                val intent = Intent(host, OverlayService::class.java).apply {
                    action = ACTION_SHOW_OVERLAY
                    putExtra(EXTRA_VOLUME_TARGET, target.name)
                    putExtra(EXTRA_ICON_TYPE, iconType)
                    putExtra(EXTRA_FOCUSED_PACKAGE, foregroundPackage)
                }
                ContextCompat.startForegroundService(host, intent)
                return
            }

            runtime.showOverlay(
                hostContext = host,
                target = target,
                iconType = iconType,
                foregroundPackage = foregroundPackage,
                windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
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

    override fun onCreate() {
        super.onCreate()
        activeRuntimeRef = WeakReference(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        initializeRuntime()

        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> {
                val target = intent.getStringExtra(EXTRA_VOLUME_TARGET)
                    ?.let { runCatching { VolumeTarget.valueOf(it) }.getOrNull() }
                    ?: VolumeTarget.MEDIA
                val iconType = intent.getStringExtra(EXTRA_ICON_TYPE) ?: "MUSIC"
                val focusedPackage = intent.getStringExtra(EXTRA_FOCUSED_PACKAGE)
                showOverlay(
                    hostContext = this,
                    target = target,
                    iconType = iconType,
                    foregroundPackage = focusedPackage,
                    windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                )
            }
        }

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
                    runtime.selectedVolumeTarget.collect { target ->
                        OverlayManager.updateSelectedVolumeTarget(target)
                    }
                },
                serviceScope.launch {
                    runtime.dynamicStreamState.collect(OverlayManager::updateDynamicStreams)
                },
                serviceScope.launch {
                    combine(
                        runtime.sessionState,
                        preferences.appSettings,
                        runtime.connectionState,
                        runtime.foregroundVisitState
                    ) { _, settings, connectionState, foregroundVisit ->
                        connectionState to sessionManager.getOverlayApps(
                            foregroundVisitSession = foregroundVisit.lastAudioSession
                                ?.takeIf { foregroundVisit.heardAudio },
                            shizukuConnected = connectionState == com.agentkosticka.amply.shizuku.VolumeServiceConnectionState.CONNECTED,
                            settings = settings
                        )
                    }.collect { (connectionState, apps) ->
                        OverlayManager.updateApps(apps, connectionState)
                    }
                }
            )

            OverlayManager.setAppVolumeCallback { app, volume ->
                sessionManager.setAppVolume(app, volume)
            }
            OverlayManager.setVolumeTargetCallbacks(
                onSelected = runtime.volumeTargetSessionController::onUserSelected,
                onShown = runtime::onOverlayShown,
                onHidden = runtime::onOverlayHidden
            )
            OverlayManager.setSystemStreamVolumeCallback(runtime::setSystemStreamVolume)
            OverlayManager.setPauseAmplyCallback {
                serviceScope.launch { preferences.pauseAmply() }
            }
            OverlayManager.setNotificationModeToggleCallback {
                serviceScope.launch {
                    runtime.ringerExperimentExecutor.toggleSelected {
                        OverlayManager.refreshStreamVolumes()
                    }
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
        foregroundPackage: String?,
        windowType: Int
    ) {
        initializeRuntime()
        runtime.onForegroundPackageChanged(foregroundPackage)

        val sessionManager = runtime.audioSessionManager
        if (!OverlayManager.isShowing()) {
            sessionManager.requestRefresh()
        }
        val connectionState = runtime.connectionState.value
        val foregroundVisit = runtime.foregroundVisitState.value
        val apps = sessionManager.getOverlayApps(
            foregroundVisitSession = foregroundVisit.lastAudioSession
                ?.takeIf { foregroundVisit.heardAudio },
            shizukuConnected = connectionState == com.agentkosticka.amply.shizuku.VolumeServiceConnectionState.CONNECTED
        )

        Log.d(
            "OverlayService",
            "showOverlay: target=$target apps=${apps.size} connection=$connectionState windowType=$windowType"
        )

        OverlayManager.show(
            context = hostContext,
            selectedTarget = target,
            newIconType = iconType,
            apps = apps,
            connectionState = connectionState,
            overlaySide = overlaySide,
            overlayVerticalFraction = overlayVerticalFraction,
            windowType = windowType
        )
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

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Amply (1)")
            .setContentText("Volume controls active")
            .setSmallIcon(R.drawable.ic_amply_logo_monochrome)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeRuntimeRef?.get() === this) {
            activeRuntimeRef = null
        }
        preferenceJobs.forEach { it.cancel() }
        serviceScope.cancel()
        OverlayManager.cleanup()
        OverlayManager.clearAppVolumeCallback()
        OverlayManager.clearVolumeTargetCallbacks()
        OverlayManager.clearPauseAmplyCallback()
        OverlayManager.clearNotificationModeToggleCallback()
        OverlayManager.clearSystemStreamVolumeCallback()
    }
}
