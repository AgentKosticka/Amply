package com.agentkosticka.amply

import android.app.Application
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Application class for Amply.
 * Initializes HiddenApiBypass to allow reflection access to hidden Android APIs
 * required for per-app volume control via AudioPlaybackConfiguration.
 */
class AmplyApplication : Application() {

    companion object {
        private const val TAG = "AmplyApplication"
    }

    lateinit var runtime: AmplyRuntime
        private set

    override fun onCreate() {
        super.onCreate()

        // Initialize HiddenApiBypass. The app's minSdk is already above Android P.
        // This allows us to access hidden APIs like:
        // - AudioPlaybackConfiguration.getPlayerProxy()
        // - AudioPlaybackConfiguration.getClientUid()
        // - PlayerProxy.setVolume()
        try {
            // Exempt all hidden APIs (empty string matches all - from VolumeManager)
            HiddenApiBypass.addHiddenApiExemptions("")
            Log.d(TAG, "HiddenApiBypass initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize HiddenApiBypass", e)
        }

        runtime = AmplyRuntime(this)
    }
}
