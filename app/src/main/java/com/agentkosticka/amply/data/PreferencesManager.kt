package com.agentkosticka.amply.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

enum class OverlaySide {
    LEFT,
    RIGHT;

    companion object {
        fun fromStored(value: String?): OverlaySide =
            entries.firstOrNull { it.name == value } ?: LEFT
    }
}

data class AppSettings(
    val packageName: String,
    val appName: String,
    val uid: Int,
    val defaultVolume: Float = 1.0f,
    val hiddenInOverlay: Boolean = false,
    val passVolumeKeysToApp: Boolean = false,
    val lastSeenTimestamp: Long = 0L
) {
    val isCustomized: Boolean
        get() = hiddenInOverlay || passVolumeKeysToApp || kotlin.math.abs(defaultVolume - 1.0f) > 0.001f
}

const val DEFAULT_AMPLY_PAUSE_MINUTES = 5

internal fun calculateAmplyPauseUntil(nowEpochMs: Long, durationMinutes: Int): Long =
    nowEpochMs + durationMinutes.coerceIn(1, 120) * 60_000L

/**
 * Manages app preferences using DataStore
 */
class PreferencesManager(private val context: Context) {

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "amply_preferences")
        private val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        private val GAME_MODE_ENABLED = booleanPreferencesKey("game_mode_enabled")
        private val OVERLAY_SIDE = stringPreferencesKey("overlay_side")
        private val OVERLAY_VERTICAL_FRACTION = floatPreferencesKey("overlay_vertical_fraction")
        private val APP_SETTINGS_JSON = stringPreferencesKey("app_settings_json")
        private val AMPLY_PAUSE_DURATION_MINUTES = intPreferencesKey("amply_pause_duration_minutes")
        private val AMPLY_PAUSED_UNTIL_EPOCH_MS = longPreferencesKey("amply_paused_until_epoch_ms")
    }

    /**
     * Flow that emits whether the setup wizard has been completed
     */
    val isSetupCompleted: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[SETUP_COMPLETED] ?: false
        }

    /**
     * Marks the setup wizard as completed
     */
    suspend fun setSetupCompleted(completed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SETUP_COMPLETED] = completed
        }
    }

    /**
     * Flow that emits the game mode state
     */
    val isGameModeEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[GAME_MODE_ENABLED] ?: false
        }

    /**
     * Toggles game mode on/off
     */
    suspend fun setGameMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[GAME_MODE_ENABLED] = enabled
        }
    }

    val overlaySide: Flow<OverlaySide> = context.dataStore.data
        .map { preferences ->
            OverlaySide.fromStored(preferences[OVERLAY_SIDE])
        }

    suspend fun setOverlaySide(side: OverlaySide) {
        context.dataStore.edit { preferences ->
            preferences[OVERLAY_SIDE] = side.name
        }
    }

    val overlayVerticalFraction: Flow<Float> = context.dataStore.data
        .map { preferences ->
            (preferences[OVERLAY_VERTICAL_FRACTION] ?: 0.5f).coerceIn(0f, 1f)
        }

    suspend fun setOverlayVerticalFraction(fraction: Float) {
        context.dataStore.edit { preferences ->
            preferences[OVERLAY_VERTICAL_FRACTION] = fraction.coerceIn(0f, 1f)
        }
    }

    val appSettings: Flow<Map<String, AppSettings>> = context.dataStore.data
        .map { preferences ->
            AppSettingsCodec.decode(preferences[APP_SETTINGS_JSON])
        }

    val volumeKeyPassThroughPackages: Flow<Set<String>> = appSettings.map { settings ->
        settings.values.filter { it.passVolumeKeysToApp }.mapTo(mutableSetOf()) { it.packageName }
    }

    val amplyPauseDurationMinutes: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[AMPLY_PAUSE_DURATION_MINUTES] ?: DEFAULT_AMPLY_PAUSE_MINUTES).coerceIn(1, 120)
    }

    val amplyPausedUntilEpochMs: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[AMPLY_PAUSED_UNTIL_EPOCH_MS] ?: 0L
    }

    suspend fun setAmplyPauseDurationMinutes(minutes: Int) {
        context.dataStore.edit { it[AMPLY_PAUSE_DURATION_MINUTES] = minutes.coerceIn(1, 120) }
    }

    suspend fun pauseAmply(nowEpochMs: Long = System.currentTimeMillis()) {
        context.dataStore.edit { preferences ->
            val minutes = preferences[AMPLY_PAUSE_DURATION_MINUTES] ?: DEFAULT_AMPLY_PAUSE_MINUTES
            preferences[AMPLY_PAUSED_UNTIL_EPOCH_MS] = calculateAmplyPauseUntil(nowEpochMs, minutes)
        }
    }

    suspend fun restoreAmplyNow() {
        context.dataStore.edit { it[AMPLY_PAUSED_UNTIL_EPOCH_MS] = 0L }
    }

    suspend fun getAppSettingsSnapshot(): Map<String, AppSettings> =
        appSettings.first()

    suspend fun recordSeenApp(
        packageName: String,
        appName: String,
        uid: Int,
        observedVolume: Float? = null,
        timestamp: Long = System.currentTimeMillis()
    ) {
        updateAppSettings { current ->
            val existing = current[packageName]
            current[packageName] = AppSettings(
                packageName = packageName,
                appName = appName,
                uid = uid,
                defaultVolume = existing?.defaultVolume ?: observedVolume ?: 1.0f,
                hiddenInOverlay = existing?.hiddenInOverlay ?: false,
                passVolumeKeysToApp = existing?.passVolumeKeysToApp ?: false,
                lastSeenTimestamp = timestamp
            )
        }
    }

    suspend fun setAppDefaultVolume(packageName: String, volume: Float) {
        updateAppSettings { current ->
            val existing = current[packageName] ?: AppSettings(
                packageName = packageName,
                appName = packageName,
                uid = -1
            )
            current[packageName] = existing.copy(defaultVolume = volume.coerceIn(0f, 1f))
        }
    }

    suspend fun persistAppVolume(
        packageName: String,
        appName: String,
        uid: Int,
        volume: Float,
        timestamp: Long = System.currentTimeMillis()
    ) {
        updateAppSettings { current ->
            val existing = current[packageName]
            current[packageName] = AppSettings(
                packageName = packageName,
                appName = appName,
                uid = uid,
                defaultVolume = volume.coerceIn(0f, 1f),
                hiddenInOverlay = existing?.hiddenInOverlay ?: false,
                passVolumeKeysToApp = existing?.passVolumeKeysToApp ?: false,
                lastSeenTimestamp = timestamp
            )
        }
    }

    suspend fun setAppHiddenInOverlay(packageName: String, hidden: Boolean) {
        updateAppSettings { current ->
            val existing = current[packageName] ?: AppSettings(
                packageName = packageName,
                appName = packageName,
                uid = -1
            )
            current[packageName] = existing.copy(hiddenInOverlay = hidden)
        }
    }

    suspend fun setPassVolumeKeysToApp(
        packageName: String,
        appName: String,
        uid: Int,
        enabled: Boolean
    ) {
        updateAppSettings { current ->
            val existing = current[packageName] ?: AppSettings(packageName, appName, uid)
            current[packageName] = existing.copy(
                appName = appName,
                uid = uid,
                passVolumeKeysToApp = enabled
            )
        }
    }

    private suspend fun updateAppSettings(update: (MutableMap<String, AppSettings>) -> Unit) {
        context.dataStore.edit { preferences ->
            val current = AppSettingsCodec.decode(preferences[APP_SETTINGS_JSON]).toMutableMap()
            update(current)
            preferences[APP_SETTINGS_JSON] = AppSettingsCodec.encode(current)
        }
    }
}

internal object AppSettingsCodec {
    fun decode(raw: String?): Map<String, AppSettings> {
        if (raw.isNullOrBlank()) return emptyMap()

        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val packageName = keys.next()
                    val item = root.optJSONObject(packageName) ?: continue
                    put(
                        packageName,
                        AppSettings(
                            packageName = packageName,
                            appName = item.optString("appName", packageName),
                            uid = item.optInt("uid", -1),
                            defaultVolume = item.optDouble("defaultVolume", 1.0).toFloat().coerceIn(0f, 1f),
                            hiddenInOverlay = item.optBoolean("hiddenInOverlay", false),
                            passVolumeKeysToApp = item.optBoolean("passVolumeKeysToApp", false),
                            lastSeenTimestamp = item.optLong("lastSeenTimestamp", 0L)
                        )
                    )
                }
            }
        }.getOrElse {
            emptyMap()
        }
    }

    fun encode(settings: Map<String, AppSettings>): String {
        val root = JSONObject()
        settings.forEach { (packageName, setting) ->
            root.put(
                packageName,
                JSONObject()
                    .put("appName", setting.appName)
                    .put("uid", setting.uid)
                    .put("defaultVolume", setting.defaultVolume.toDouble())
                    .put("hiddenInOverlay", setting.hiddenInOverlay)
                    .put("passVolumeKeysToApp", setting.passVolumeKeysToApp)
                    .put("lastSeenTimestamp", setting.lastSeenTimestamp)
            )
        }
        return root.toString()
    }
}
