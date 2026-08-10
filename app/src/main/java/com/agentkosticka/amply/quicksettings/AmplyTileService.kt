package com.agentkosticka.amply.quicksettings

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.agentkosticka.amply.AmplyApplication
import com.agentkosticka.amply.R
import com.agentkosticka.amply.settings.model.SettingsOperationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AmplyTileService : TileService() {
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var listeningJob: Job? = null

    private val preferences
        get() = (application as AmplyApplication).runtime.preferencesManager

    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()
        listeningJob = serviceScope.launch {
            preferences.amplyPausedUntilEpochMs.collectLatest { pausedUntilEpochMs ->
                updateTile(pausedUntilEpochMs, System.currentTimeMillis())
                if (pausedUntilEpochMs in 1 until Long.MAX_VALUE) {
                    val remainingMs = pausedUntilEpochMs - System.currentTimeMillis()
                    if (remainingMs > 0L) {
                        delay(remainingMs)
                        updateTile(pausedUntilEpochMs, System.currentTimeMillis())
                    }
                }
            }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        serviceScope.launch {
            val nowEpochMs = System.currentTimeMillis()
            val pausedUntilEpochMs = preferences.amplyPausedUntilEpochMs.first()
            val result = if (amplyTileIsActive(pausedUntilEpochMs, nowEpochMs)) {
                preferences.pauseAmply(nowEpochMs)
            } else {
                preferences.restoreAmplyNow()
            }
            if (result is SettingsOperationResult.Success) {
                val updatedPausedUntil = preferences.amplyPausedUntilEpochMs.first()
                updateTile(updatedPausedUntil, System.currentTimeMillis())
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateTile(pausedUntilEpochMs: Long, nowEpochMs: Long) {
        val active = amplyTileIsActive(pausedUntilEpochMs, nowEpochMs)
        qsTile?.apply {
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.quick_settings_tile_label)
            icon = Icon.createWithResource(this@AmplyTileService, R.drawable.ic_amply_logo_monochrome)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = getString(
                    if (active) R.string.quick_settings_tile_on else R.string.quick_settings_tile_paused
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                stateDescription = getString(
                    if (active) {
                        R.string.quick_settings_tile_on_description
                    } else {
                        R.string.quick_settings_tile_paused_description
                    }
                )
            }
            updateTile()
        }
    }
}

internal fun amplyTileIsActive(pausedUntilEpochMs: Long, nowEpochMs: Long): Boolean =
    pausedUntilEpochMs <= nowEpochMs
