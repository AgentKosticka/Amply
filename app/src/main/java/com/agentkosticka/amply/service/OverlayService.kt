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
import com.agentkosticka.amply.R
import com.agentkosticka.amply.audio.AudioSessionManager
import com.agentkosticka.amply.data.OverlaySide
import com.agentkosticka.amply.data.PreferencesManager
import com.agentkosticka.amply.shizuku.ShizukuRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        const val EXTRA_VOLUME = "extra_volume"
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
            volume: Int,
            iconType: String,
            foregroundPackage: String?
        ) {
            val runtime = activeRuntimeRef?.get()
            if (runtime == null) {
                startRuntime(host)
                OverlayManager.show(
                    context = host,
                    volume = volume,
                    newIconType = iconType,
                    windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                )
                return
            }

            runtime.showOverlay(
                hostContext = host,
                volume = volume,
                iconType = iconType,
                foregroundPackage = foregroundPackage,
                windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            )
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var preferencesManager: PreferencesManager? = null
    private var shizukuRepository: ShizukuRepository? = null
    private var audioSessionManager: AudioSessionManager? = null
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
                val volume = intent.getIntExtra(EXTRA_VOLUME, 0)
                val iconType = intent.getStringExtra(EXTRA_ICON_TYPE) ?: "MUSIC"
                val focusedPackage = intent.getStringExtra(EXTRA_FOCUSED_PACKAGE)
                showOverlay(
                    hostContext = this,
                    volume = volume,
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
            val preferences = PreferencesManager(this)
            val shizuku = ShizukuRepository(this)
            val sessionManager = AudioSessionManager(this, shizuku, preferences)

            preferencesManager = preferences
            shizukuRepository = shizuku
            audioSessionManager = sessionManager
            runtimeInitialized = true

            sessionManager.startPolling()
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
                }
            )

            OverlayManager.setSessionVolumeCallback { sessionId, packageName, volume ->
                serviceScope.launch {
                    sessionManager.setSessionVolume(sessionId, packageName, volume)
                }
            }

            Log.d("OverlayService", "Runtime initialized and audio session polling started")
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to initialize runtime", e)
        }
    }

    private fun showOverlay(
        hostContext: Context,
        volume: Int,
        iconType: String,
        foregroundPackage: String?,
        windowType: Int
    ) {
        initializeRuntime()

        val sessions = audioSessionManager?.getDefaultOverlaySessions() ?: emptyList()
        val focusedApp = audioSessionManager?.getFocusedApp(foregroundPackage)
            ?: audioSessionManager?.getMostRecentSession()

        Log.d(
            "OverlayService",
            "showOverlay: volume=$volume sessions=${sessions.size} focused=${focusedApp?.appName ?: "none"} windowType=$windowType"
        )

        OverlayManager.show(
            context = hostContext,
            volume = volume,
            newIconType = iconType,
            sessions = sessions,
            focusedAppSession = focusedApp,
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
        audioSessionManager?.cleanup()
        shizukuRepository?.cleanup()
        serviceScope.cancel()
        OverlayManager.clearSessionVolumeCallback()
        OverlayManager.cleanup()
    }
}
