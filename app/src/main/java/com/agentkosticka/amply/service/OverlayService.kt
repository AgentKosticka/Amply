package com.agentkosticka.amply.service

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.ResultReceiver
import android.util.Log
import androidx.core.app.NotificationCompat
import com.agentkosticka.amply.data.ParcelableAudioSession
import com.agentkosticka.amply.data.toAudioSession

/**
 * Foreground service that manages the floating volume overlay
 * using OverlayManager singleton
 */
class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "amply_overlay_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_SHOW_OVERLAY = "com.agentkosticka.amply.ACTION_SHOW_OVERLAY"
        const val ACTION_FORCE_REFRESH = "com.agentkosticka.amply.ACTION_FORCE_REFRESH"
        const val EXTRA_VOLUME = "extra_volume"
        const val EXTRA_MAX_VOLUME = "extra_max_volume"
        const val EXTRA_ICON_TYPE = "extra_icon_type"
        const val EXTRA_SESSIONS = "extra_sessions"
        const val EXTRA_FOCUSED_APP = "extra_focused_app" // Phase 3.5: Smart Focus
        const val EXTRA_VOLUME_RECEIVER = "extra_volume_receiver" // Phase 3: ResultReceiver for per-app volume
    }

    // Callback for per-app volume changes (Phase 3)
    private var onSessionVolumeChangeCallback: ((Int, Float) -> Unit)? = null

    fun setSessionVolumeChangeCallback(callback: (Int, Float) -> Unit) {
        onSessionVolumeChangeCallback = callback
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> {
                val volume = intent.getIntExtra(EXTRA_VOLUME, 0)
                val iconType = intent.getStringExtra(EXTRA_ICON_TYPE) ?: "MUSIC"

                // Extract sessions (Phase 3)
                val parcelableSessions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(EXTRA_SESSIONS, ParcelableAudioSession::class.java)
                } else {
                    intent.getParcelableArrayListExtra(EXTRA_SESSIONS)
                }
                
                // DEBUG: Log received sessions
                Log.d("OverlayService", "Received ${parcelableSessions?.size ?: 0} parcelable sessions")

                // Phase 3.5: Extract focused app for Smart Focus
                val focusedAppParcelable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_FOCUSED_APP, ParcelableAudioSession::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_FOCUSED_APP)
                }

                // Convert to AudioSession with icons loaded
                val sessions = parcelableSessions?.map { parcelable ->
                    val icon = try {
                        packageManager.getApplicationIcon(parcelable.packageName)
                    } catch (e: Exception) {
                        null
                    }
                    parcelable.toAudioSession(icon)
                } ?: emptyList()
                
                // DEBUG: Log converted sessions
                Log.d("OverlayService", "Converted to ${sessions.size} AudioSessions")
                sessions.forEachIndexed { i, s ->
                    Log.d("OverlayService", "  [$i] ${s.appName} pkg=${s.packageName}")
                }

                // Convert focused app with icon
                val focusedApp = focusedAppParcelable?.let { parcelable ->
                    val icon = try {
                        packageManager.getApplicationIcon(parcelable.packageName)
                    } catch (e: Exception) {
                        null
                    }
                    parcelable.toAudioSession(icon)
                }
                Log.d("OverlayService", "Focused app: ${focusedApp?.appName ?: "none"}")

                val volumeReceiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_VOLUME_RECEIVER)
                
                // Pass to OverlayManager with session volume change callback and focused app
                OverlayManager.show(
                    context = this,
                    volume = volume,
                    newIconType = iconType,
                    sessions = sessions,
                    focusedAppSession = focusedApp,
                    volumeReceiver = volumeReceiver
                )
            }
            ACTION_FORCE_REFRESH -> {
                val volume = intent.getIntExtra(EXTRA_VOLUME, 0)
                val iconType = intent.getStringExtra(EXTRA_ICON_TYPE) ?: "MUSIC"
                // Force Z-order refresh: hide then immediately show
                OverlayManager.hide()
                OverlayManager.show(this, volume, iconType)
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Amply (1)")
            .setContentText("Volume controls active")
            .setSmallIcon(R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        OverlayManager.cleanup()
    }
}
