package com.agentkosticka.amply.settings.data

import com.agentkosticka.amply.settings.model.*

import android.content.Context
import android.content.pm.LauncherApps
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
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
import org.json.JSONArray


class PreferencesManager(private val context: Context) {

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "amply_preferences")
        private val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        private val GAME_MODE_ENABLED = booleanPreferencesKey("game_mode_enabled")
        private val OVERLAY_SIDE = stringPreferencesKey("overlay_side")
        private val OVERLAY_VERTICAL_FRACTION = floatPreferencesKey("overlay_vertical_fraction")
        private val VOLUME_DOT_SCALE_MODE = stringPreferencesKey("volume_dot_scale_mode")
        private val VOLUME_DOT_CUSTOM_COUNT = intPreferencesKey("volume_dot_custom_count")
        private val APP_SETTINGS_JSON = stringPreferencesKey("app_settings_json")
        private val APP_SETTINGS_JSON_V2 = stringPreferencesKey("app_settings_json_v2")
        private val APP_SETTINGS_JSON_BACKUP = stringPreferencesKey("app_settings_json_v2_backup")
        private val VOLUME_KEY_PASS_THROUGH_JSON = stringPreferencesKey("volume_key_pass_through_packages")
        private val AMPLY_PAUSE_DURATION_MINUTES = intPreferencesKey("amply_pause_duration_minutes")
        private val AMPLY_PAUSE_DURATION = stringPreferencesKey("amply_pause_duration")
        private val AMPLY_PAUSED_UNTIL_EPOCH_MS = longPreferencesKey("amply_paused_until_epoch_ms")
        private val LAST_STALE_APP_CLEANUP_EPOCH_MS = longPreferencesKey("last_stale_app_cleanup_epoch_ms")
        private val RINGER_METHOD = stringPreferencesKey("ringer_method")
        private const val AUTO_PRUNE_AGE_MS = 90L * 24L * 60L * 60L * 1_000L
        private const val AUTO_PRUNE_INTERVAL_MS = 7L * 24L * 60L * 60L * 1_000L
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

    val volumeDotScaleConfig: Flow<VolumeDotScaleConfig> = context.dataStore.data.map { preferences ->
        VolumeDotScaleConfig(
            mode = runCatching {
                VolumeDotScaleMode.valueOf(preferences[VOLUME_DOT_SCALE_MODE].orEmpty())
            }.getOrDefault(VolumeDotScaleMode.AUTO),
            customDotCount = (preferences[VOLUME_DOT_CUSTOM_COUNT] ?: 16).coerceIn(4, 60)
        )
    }

    suspend fun setVolumeDotScale(config: VolumeDotScaleConfig) {
        context.dataStore.edit { preferences ->
            preferences[VOLUME_DOT_SCALE_MODE] = config.mode.name
            preferences[VOLUME_DOT_CUSTOM_COUNT] = config.customDotCount.coerceIn(4, 60)
        }
    }

    val appSettings: Flow<Map<AppIdentity, AppSettings>> = context.dataStore.data
        .map { preferences ->
            readAppSettings(preferences).first
        }

    val appSettingsStoreHealth: Flow<AppSettingsStoreHealth> = context.dataStore.data.map { preferences ->
        readAppSettings(preferences).second
    }

    val volumeKeyPassThroughPackages: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        decodeStringSet(preferences[VOLUME_KEY_PASS_THROUGH_JSON])
            ?: readAppSettings(preferences).first.values
                .filter { it.passVolumeKeysToApp }
                .mapTo(linkedSetOf()) { it.packageName }
    }

    val amplyPauseDuration: Flow<AmplyPauseDuration> = context.dataStore.data.map { preferences ->
        AmplyPauseDuration.fromStored(
            value = preferences[AMPLY_PAUSE_DURATION],
            legacyMinutes = preferences[AMPLY_PAUSE_DURATION_MINUTES] ?: DEFAULT_AMPLY_PAUSE_MINUTES
        )
    }

    val amplyPausedUntilEpochMs: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[AMPLY_PAUSED_UNTIL_EPOCH_MS] ?: 0L
    }

    val ringerMethod: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[RINGER_METHOD] ?: "SHIZUKU_INTERNAL_MODE"
    }

    suspend fun setRingerMethod(methodName: String) {
        context.dataStore.edit { it[RINGER_METHOD] = methodName }
    }

    suspend fun setAmplyPauseDuration(duration: AmplyPauseDuration) {
        context.dataStore.edit { it[AMPLY_PAUSE_DURATION] = duration.name }
    }

    suspend fun pauseAmply(nowEpochMs: Long = System.currentTimeMillis()) {
        context.dataStore.edit { preferences ->
            val duration = AmplyPauseDuration.fromStored(
                preferences[AMPLY_PAUSE_DURATION],
                preferences[AMPLY_PAUSE_DURATION_MINUTES] ?: DEFAULT_AMPLY_PAUSE_MINUTES
            )
            preferences[AMPLY_PAUSED_UNTIL_EPOCH_MS] = calculateAmplyPauseUntil(nowEpochMs, duration)
        }
    }

    suspend fun restoreAmplyNow() {
        context.dataStore.edit { it[AMPLY_PAUSED_UNTIL_EPOCH_MS] = 0L }
    }

    suspend fun getAppSettingsSnapshot(): Map<AppIdentity, AppSettings> =
        appSettings.first()

    suspend fun recordSeenApp(
        packageName: String,
        appName: String,
        uid: Int,
        observedVolume: Float? = null,
        timestamp: Long = System.currentTimeMillis()
    ) {
        updateAppSettings { current ->
            val identity = AppIdentity.fromUid(packageName, uid)
            val existing = current[identity]
            current[identity] = AppSettings(
                packageName = packageName,
                appName = appName,
                uid = uid,
                userId = identity.userId,
                defaultVolume = existing?.defaultVolume ?: observedVolume ?: 1.0f,
                overlayMode = existing?.overlayMode ?: OverlayAppMode.AUTO,
                passVolumeKeysToApp = existing?.passVolumeKeysToApp ?: false,
                lastSeenTimestamp = timestamp
            )
        }
    }

    suspend fun setAppDefaultVolume(packageName: String, volume: Float, uid: Int = -1) {
        updateAppSettings { current ->
            val identity = identityFor(current, packageName, uid)
            val existing = current[identity] ?: AppSettings(
                packageName = packageName,
                appName = packageName,
                uid = uid,
                userId = identity.userId
            )
            current[identity] = existing.copy(defaultVolume = volume.coerceIn(0f, 1f))
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
            val identity = AppIdentity.fromUid(packageName, uid)
            val existing = current[identity]
            current[identity] = AppSettings(
                packageName = packageName,
                appName = appName,
                uid = uid,
                userId = identity.userId,
                defaultVolume = volume.coerceIn(0f, 1f),
                overlayMode = existing?.overlayMode ?: OverlayAppMode.AUTO,
                passVolumeKeysToApp = existing?.passVolumeKeysToApp ?: false,
                lastSeenTimestamp = timestamp
            )
        }
    }

    suspend fun setAppOverlayMode(packageName: String, mode: OverlayAppMode, uid: Int = -1) {
        updateAppSettings { current ->
            val identity = identityFor(current, packageName, uid)
            val existing = current[identity] ?: AppSettings(
                packageName = packageName,
                appName = packageName,
                uid = uid,
                userId = identity.userId
            )
            current[identity] = existing.copy(overlayMode = mode)
        }
    }

    suspend fun setPassVolumeKeysToApp(
        packageName: String,
        appName: String,
        uid: Int,
        enabled: Boolean
    ) {
        context.dataStore.edit { preferences ->
            val (decoded, health) = readAppSettings(preferences)
            if (health == AppSettingsStoreHealth.CORRUPT) return@edit
            val current = decoded.toMutableMap()
            val matching = current.keys.filter { it.packageName == packageName }
            if (matching.isEmpty()) {
                val identity = AppIdentity.fromUid(packageName, uid)
                current[identity] = AppSettings(
                    packageName = packageName,
                    appName = appName,
                    uid = uid,
                    userId = identity.userId,
                    passVolumeKeysToApp = enabled
                )
            } else {
                matching.forEach { identity ->
                    current[identity] = current.getValue(identity).copy(passVolumeKeysToApp = enabled)
                }
            }
            val packages = (decodeStringSet(preferences[VOLUME_KEY_PASS_THROUGH_JSON])
                ?: decoded.values.filter { it.passVolumeKeysToApp }.mapTo(linkedSetOf()) { it.packageName })
                .toMutableSet()
            if (enabled) packages += packageName else packages -= packageName
            writeAppSettings(preferences, decoded, current)
            preferences[VOLUME_KEY_PASS_THROUGH_JSON] = encodeStringSet(packages)
        }
    }

    suspend fun resetApp(identity: AppIdentity) {
        updateAppSettings { it.remove(identity) }
    }

    suspend fun findStaleApps(includeCustomized: Boolean = true): List<AppSettings> =
        getAppSettingsSnapshot().values.filter { setting ->
            (includeCustomized || !setting.isCustomized) && isConfirmedUninstalled(setting)
        }

    suspend fun pruneStaleApps(
        automatic: Boolean,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Int {
        if (automatic) {
            val lastRun = context.dataStore.data.first()[LAST_STALE_APP_CLEANUP_EPOCH_MS] ?: 0L
            if (nowEpochMs - lastRun < AUTO_PRUNE_INTERVAL_MS) return 0
        }
        val stale = getAppSettingsSnapshot().values.filter { setting ->
            val oldEnough = !automatic || nowEpochMs - setting.lastSeenTimestamp >= AUTO_PRUNE_AGE_MS
            val allowed = !automatic || !setting.isCustomized
            oldEnough && allowed && isConfirmedUninstalled(setting)
        }.mapTo(linkedSetOf()) { it.identity }
        if (stale.isNotEmpty()) updateAppSettings { it.keys.removeAll(stale) }
        if (automatic) {
            context.dataStore.edit { it[LAST_STALE_APP_CLEANUP_EPOCH_MS] = nowEpochMs }
        }
        return stale.size
    }

    suspend fun resetAllUserSettings() {
        context.dataStore.edit { preferences ->
            preferences.remove(OVERLAY_SIDE)
            preferences.remove(OVERLAY_VERTICAL_FRACTION)
            preferences.remove(VOLUME_DOT_SCALE_MODE)
            preferences.remove(VOLUME_DOT_CUSTOM_COUNT)
            preferences.remove(APP_SETTINGS_JSON)
            preferences.remove(APP_SETTINGS_JSON_V2)
            preferences.remove(APP_SETTINGS_JSON_BACKUP)
            preferences.remove(VOLUME_KEY_PASS_THROUGH_JSON)
            preferences.remove(AMPLY_PAUSE_DURATION_MINUTES)
            preferences.remove(AMPLY_PAUSE_DURATION)
            preferences.remove(AMPLY_PAUSED_UNTIL_EPOCH_MS)
            preferences.remove(LAST_STALE_APP_CLEANUP_EPOCH_MS)
            preferences.remove(GAME_MODE_ENABLED)
            preferences.remove(RINGER_METHOD)
        }
    }

    suspend fun exportSettings(): String {
        val preferences = context.dataStore.data.first()
        val (settings, health) = readAppSettings(preferences)
        val passThrough = decodeStringSet(preferences[VOLUME_KEY_PASS_THROUGH_JSON])
            ?: settings.values.filter { it.passVolumeKeysToApp }.mapTo(linkedSetOf()) { it.packageName }
        val export = JSONObject()
            .put("schemaVersion", 1)
            .put("overlaySide", OverlaySide.fromStored(preferences[OVERLAY_SIDE]).name)
            .put("overlayVerticalFraction", preferences[OVERLAY_VERTICAL_FRACTION] ?: 0.5f)
            .put("dotScaleMode", preferences[VOLUME_DOT_SCALE_MODE] ?: VolumeDotScaleMode.AUTO.name)
            .put("customDotCount", preferences[VOLUME_DOT_CUSTOM_COUNT] ?: 16)
            .put(
                "pauseDuration",
                AmplyPauseDuration.fromStored(
                    preferences[AMPLY_PAUSE_DURATION],
                    preferences[AMPLY_PAUSE_DURATION_MINUTES]
                ).name
            )
            .put("ringerMethod", preferences[RINGER_METHOD] ?: "SHIZUKU_INTERNAL_MODE")
            .put("standDownPackages", JSONArray(passThrough.sorted()))
            .put("appSettings", JSONObject(AppSettingsCodec.encode(settings)))
            .put("appSettingsHealth", health.name)
        if (health != AppSettingsStoreHealth.HEALTHY) {
            export.put(
                "recoveryRawAppSettings",
                preferences[APP_SETTINGS_JSON_V2] ?: preferences[APP_SETTINGS_JSON] ?: JSONObject.NULL
            )
        }
        return export.toString(2)
    }

    fun previewImport(raw: String): SettingsImportPreview = try {
        val root = JSONObject(raw)
        require(root.optInt("schemaVersion", -1) == 1) { "Unsupported settings version" }
        val decoded = AppSettingsCodec.decodeResult(root.getJSONObject("appSettings").toString())
        require(decoded is AppSettingsDecodeResult.Success) { "App settings are malformed" }
        SettingsImportPreview(
            appCount = decoded.settings.size,
            customizedAppCount = decoded.settings.values.count { it.isCustomized },
            valid = true
        )
    } catch (error: Exception) {
        SettingsImportPreview(0, 0, false, error.message ?: "Invalid settings file")
    }

    suspend fun importSettings(raw: String, replace: Boolean) {
        val root = JSONObject(raw)
        require(root.getInt("schemaVersion") == 1) { "Unsupported settings version" }
        val importedResult = AppSettingsCodec.decodeResult(root.getJSONObject("appSettings").toString())
        require(importedResult is AppSettingsDecodeResult.Success) { "App settings are malformed" }
        context.dataStore.edit { preferences ->
            val (existing, health) = readAppSettings(preferences)
            check(health != AppSettingsStoreHealth.CORRUPT) {
                "Current settings are corrupt; export or reset them before importing"
            }
            val merged = if (replace) {
                importedResult.settings
            } else {
                existing + importedResult.settings
            }
            writeAppSettings(preferences, existing, merged)
            preferences[OVERLAY_SIDE] = OverlaySide.fromStored(root.optString("overlaySide")).name
            preferences[OVERLAY_VERTICAL_FRACTION] = root.optDouble("overlayVerticalFraction", 0.5)
                .toFloat().coerceIn(0f, 1f)
            preferences[VOLUME_DOT_SCALE_MODE] = runCatching {
                VolumeDotScaleMode.valueOf(root.optString("dotScaleMode"))
            }.getOrDefault(VolumeDotScaleMode.AUTO).name
            preferences[VOLUME_DOT_CUSTOM_COUNT] = root.optInt("customDotCount", 16).coerceIn(4, 60)
            preferences[AMPLY_PAUSE_DURATION] = AmplyPauseDuration.fromStored(
                root.optString("pauseDuration")
            ).name
            preferences[RINGER_METHOD] = root.optString("ringerMethod", "SHIZUKU_INTERNAL_MODE")
            val packages = root.optJSONArray("standDownPackages")?.let { array ->
                buildSet { for (index in 0 until array.length()) add(array.getString(index)) }
            }.orEmpty()
            val existingPackages = decodeStringSet(preferences[VOLUME_KEY_PASS_THROUGH_JSON])
                ?: existing.values.filter { it.passVolumeKeysToApp }
                    .mapTo(linkedSetOf()) { it.packageName }
            preferences[VOLUME_KEY_PASS_THROUGH_JSON] = encodeStringSet(
                if (replace) packages else existingPackages + packages
            )
            preferences[AMPLY_PAUSED_UNTIL_EPOCH_MS] = 0L
        }
    }

    private suspend fun updateAppSettings(update: (MutableMap<AppIdentity, AppSettings>) -> Unit) {
        context.dataStore.edit { preferences ->
            val (decoded, health) = readAppSettings(preferences)
            if (health == AppSettingsStoreHealth.CORRUPT) return@edit
            val current = decoded.toMutableMap()
            update(current)
            writeAppSettings(preferences, decoded, current)
        }
    }

    private fun identityFor(
        settings: Map<AppIdentity, AppSettings>,
        packageName: String,
        uid: Int
    ): AppIdentity = if (uid >= 0) {
        AppIdentity.fromUid(packageName, uid)
    } else {
        settings.keys.firstOrNull { it.packageName == packageName }
            ?: AppIdentity.fromUid(packageName, uid)
    }

    private fun isConfirmedUninstalled(setting: AppSettings): Boolean {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val profile = runCatching {
            launcherApps.profiles.firstOrNull { it.hashCode() == setting.userId }
        }.getOrNull() ?: return false
        return try {
            launcherApps.getApplicationInfo(setting.packageName, 0, profile)
            false
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun readAppSettings(preferences: Preferences): Pair<Map<AppIdentity, AppSettings>, AppSettingsStoreHealth> {
        val primary = preferences[APP_SETTINGS_JSON_V2] ?: preferences[APP_SETTINGS_JSON]
        val resolved = AppSettingsRecovery.resolve(primary, preferences[APP_SETTINGS_JSON_BACKUP])
        return resolved.settings to resolved.health
    }

    private fun writeAppSettings(
        preferences: MutablePreferences,
        previous: Map<AppIdentity, AppSettings>,
        updated: Map<AppIdentity, AppSettings>
    ) {
        preferences[APP_SETTINGS_JSON_BACKUP] = AppSettingsCodec.encode(previous)
        preferences[APP_SETTINGS_JSON_V2] = AppSettingsCodec.encode(updated)
    }

    private fun decodeStringSet(raw: String?): Set<String>? = runCatching {
        if (raw == null) return null
        val array = JSONArray(raw)
        buildSet { for (index in 0 until array.length()) add(array.getString(index)) }
    }.getOrNull()

    private fun encodeStringSet(values: Set<String>): String = JSONArray(values.sorted()).toString()
}

