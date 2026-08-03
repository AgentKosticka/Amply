package com.agentkosticka.amply.settings.data

import com.agentkosticka.amply.settings.model.*

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
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
import com.agentkosticka.amply.tutorial.TutorialStage


class PreferencesManager(private val context: Context) {

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "amply_preferences")
        private val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        private val TUTORIAL_STAGE = stringPreferencesKey("tutorial_stage")
        private val LEGACY_GAME_MODE_ENABLED = booleanPreferencesKey("game_mode_enabled")
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
        private val DISABLE_SHIZUKU_DISCONNECTED_WARNING =
            booleanPreferencesKey("disable_shizuku_disconnected_warning")
        private val LAST_STALE_APP_CLEANUP_EPOCH_MS = longPreferencesKey("last_stale_app_cleanup_epoch_ms")
        private val LAST_SUCCESSFUL_UPDATE_CHECK_EPOCH_MS =
            longPreferencesKey("last_successful_update_check_epoch_ms")
        private val LEGACY_RINGER_METHOD = stringPreferencesKey("ringer_method")
        const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
        const val EXPORT_SCHEMA_VERSION = 3
        private const val AUTO_PRUNE_AGE_MS = 90L * 24L * 60L * 60L * 1_000L
        private const val AUTO_PRUNE_INTERVAL_MS = 7L * 24L * 60L * 60L * 1_000L
    }

    /**
     * Flow that emits whether the setup wizard has been completed
     */
    val isSetupIntroductionSeen: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[SETUP_COMPLETED] ?: false
        }

    /**
     * Marks the setup wizard as completed
     */
    suspend fun setSetupIntroductionSeen(completed: Boolean): SettingsOperationResult =
        editSettings { preferences ->
            preferences[SETUP_COMPLETED] = completed
        }

    internal val tutorialStage: Flow<TutorialStage> = context.dataStore.data.map { preferences ->
        TutorialStage.fromStored(
            value = preferences[TUTORIAL_STAGE],
            introductionSeen = preferences[SETUP_COMPLETED] ?: false
        )
    }

    internal suspend fun setTutorialStage(stage: TutorialStage): SettingsOperationResult =
        editSettings { preferences -> preferences[TUTORIAL_STAGE] = stage.name }

    internal suspend fun completeSetupAndSetTutorialStage(
        stage: TutorialStage
    ): SettingsOperationResult = editSettings { preferences ->
        preferences[SETUP_COMPLETED] = true
        preferences[TUTORIAL_STAGE] = stage.name
    }

    internal suspend fun lastSuccessfulUpdateCheckEpochMs(): Long =
        context.dataStore.data.first()[LAST_SUCCESSFUL_UPDATE_CHECK_EPOCH_MS] ?: 0L

    internal suspend fun recordSuccessfulUpdateCheck(epochMs: Long): SettingsOperationResult {
        if (epochMs <= 0L) {
            return SettingsOperationResult.ValidationFailed("Invalid update-check timestamp")
        }
        return editSettings { preferences ->
            preferences[LAST_SUCCESSFUL_UPDATE_CHECK_EPOCH_MS] = epochMs
        }
    }

    val overlaySide: Flow<OverlaySide> = context.dataStore.data
        .map { preferences ->
            OverlaySide.fromStored(preferences[OVERLAY_SIDE])
        }

    suspend fun setOverlaySide(side: OverlaySide): SettingsOperationResult =
        editSettings { preferences ->
            preferences[OVERLAY_SIDE] = side.name
        }

    val overlayVerticalFraction: Flow<Float> = context.dataStore.data
        .map { preferences ->
            (preferences[OVERLAY_VERTICAL_FRACTION] ?: 0.5f).coerceIn(0f, 1f)
        }

    suspend fun setOverlayVerticalFraction(fraction: Float): SettingsOperationResult {
        if (!fraction.isFinite() || fraction !in 0f..1f) {
            return SettingsOperationResult.ValidationFailed("Invalid overlay position")
        }
        return editSettings { preferences ->
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

    suspend fun setVolumeDotScale(config: VolumeDotScaleConfig): SettingsOperationResult {
        if (config.customDotCount !in 4..60) {
            return SettingsOperationResult.ValidationFailed("Invalid custom dot count")
        }
        return editSettings { preferences ->
            preferences[VOLUME_DOT_SCALE_MODE] = config.mode.name
            preferences[VOLUME_DOT_CUSTOM_COUNT] = config.customDotCount
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
            ?: AppSettingsCodec.legacyPassThroughPackages(
                preferences[APP_SETTINGS_JSON_V2] ?: preferences[APP_SETTINGS_JSON]
            )
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

    val disableShizukuDisconnectedWarning: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[DISABLE_SHIZUKU_DISCONNECTED_WARNING] ?: false
    }

    suspend fun setDisableShizukuDisconnectedWarning(disabled: Boolean): SettingsOperationResult =
        editSettings { it[DISABLE_SHIZUKU_DISCONNECTED_WARNING] = disabled }

    suspend fun setAmplyPauseDuration(duration: AmplyPauseDuration): SettingsOperationResult =
        editSettings { it[AMPLY_PAUSE_DURATION] = duration.name }

    suspend fun pauseAmply(
        nowEpochMs: Long = System.currentTimeMillis()
    ): SettingsOperationResult {
        if (nowEpochMs < 0L) return SettingsOperationResult.ValidationFailed("Invalid time")
        return editSettings { preferences ->
            val duration = AmplyPauseDuration.fromStored(
                preferences[AMPLY_PAUSE_DURATION],
                preferences[AMPLY_PAUSE_DURATION_MINUTES] ?: DEFAULT_AMPLY_PAUSE_MINUTES
            )
            preferences[AMPLY_PAUSED_UNTIL_EPOCH_MS] = calculateAmplyPauseUntil(nowEpochMs, duration)
        }
    }

    suspend fun restoreAmplyNow(): SettingsOperationResult =
        editSettings { it[AMPLY_PAUSED_UNTIL_EPOCH_MS] = 0L }

    suspend fun getAppSettingsSnapshot(): Map<AppIdentity, AppSettings> =
        appSettings.first()

    suspend fun recordSeenApp(
        packageName: String,
        appName: String,
        uid: Int,
        observedVolume: Float? = null,
        timestamp: Long = System.currentTimeMillis()
    ): SettingsOperationResult = updateAppSettings { current ->
        require(AppSettingsCodec.isValidPackageName(packageName)) { "Invalid package name" }
        require(uid >= -1) { "Invalid UID" }
        require(observedVolume == null || (observedVolume.isFinite() && observedVolume in 0f..1f)) {
            "Invalid volume"
        }
        require(timestamp >= 0L) { "Invalid timestamp" }
        val identity = AppIdentity.fromUid(packageName, uid)
        val existing = current[identity]
        current[identity] = AppSettings(
            packageName = packageName,
            appName = appName,
            uid = uid,
            userId = identity.userId,
            defaultVolume = existing?.defaultVolume ?: observedVolume ?: 1.0f,
            overlayMode = existing?.overlayMode ?: OverlayAppMode.AUTO,
            lastSeenTimestamp = timestamp
        )
    }

    suspend fun setAppDefaultVolume(
        packageName: String,
        volume: Float,
        uid: Int = -1
    ): SettingsOperationResult = updateAppSettings { current ->
        require(AppSettingsCodec.isValidPackageName(packageName)) { "Invalid package name" }
        require(uid >= -1) { "Invalid UID" }
        require(volume.isFinite() && volume in 0f..1f) { "Invalid volume" }
        val identity = identityFor(current, packageName, uid)
        val existing = current[identity] ?: AppSettings(
            packageName = packageName,
            appName = packageName,
            uid = uid,
            userId = identity.userId
        )
        current[identity] = existing.copy(defaultVolume = volume.coerceIn(0f, 1f))
    }

    suspend fun persistAppVolume(
        packageName: String,
        appName: String,
        uid: Int,
        volume: Float,
        timestamp: Long = System.currentTimeMillis()
    ): SettingsOperationResult = updateAppSettings { current ->
        require(AppSettingsCodec.isValidPackageName(packageName)) { "Invalid package name" }
        require(uid >= -1) { "Invalid UID" }
        require(volume.isFinite() && volume in 0f..1f) { "Invalid volume" }
        require(timestamp >= 0L) { "Invalid timestamp" }
        val identity = AppIdentity.fromUid(packageName, uid)
        val existing = current[identity]
        current[identity] = AppSettings(
            packageName = packageName,
            appName = appName,
            uid = uid,
            userId = identity.userId,
            defaultVolume = volume.coerceIn(0f, 1f),
            overlayMode = existing?.overlayMode ?: OverlayAppMode.AUTO,
            lastSeenTimestamp = timestamp
        )
    }

    suspend fun setAppOverlayMode(
        packageName: String,
        mode: OverlayAppMode,
        uid: Int = -1
    ): SettingsOperationResult = updateAppSettings { current ->
        require(AppSettingsCodec.isValidPackageName(packageName)) { "Invalid package name" }
        require(uid >= -1) { "Invalid UID" }
        val identity = identityFor(current, packageName, uid)
        val existing = current[identity] ?: AppSettings(
            packageName = packageName,
            appName = packageName,
            uid = uid,
            userId = identity.userId
        )
        current[identity] = existing.copy(overlayMode = mode)
    }

    suspend fun setPassVolumeKeysToApp(
        packageName: String,
        @Suppress("UNUSED_PARAMETER") appName: String,
        @Suppress("UNUSED_PARAMETER") uid: Int,
        enabled: Boolean
    ): SettingsOperationResult {
        if (!AppSettingsCodec.isValidPackageName(packageName)) {
            return SettingsOperationResult.ValidationFailed("Invalid package name")
        }
        return try {
            context.dataStore.edit { preferences ->
                val packages = (decodeStringSet(preferences[VOLUME_KEY_PASS_THROUGH_JSON])
                    ?: AppSettingsCodec.legacyPassThroughPackages(
                        preferences[APP_SETTINGS_JSON_V2] ?: preferences[APP_SETTINGS_JSON]
                    ))
                    .toMutableSet()
                if (enabled) packages += packageName else packages -= packageName
                preferences[VOLUME_KEY_PASS_THROUGH_JSON] = encodeStringSet(packages)
            }
            SettingsOperationResult.Success
        } catch (error: Exception) {
            SettingsOperationResult.IoFailed(error.message ?: "Settings storage failed")
        }
    }

    suspend fun resetApp(identity: AppIdentity): SettingsOperationResult =
        updateAppSettings { it.remove(identity) }

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
        val packagesAbsentFromEveryProfile = stale.asSequence()
            .map { it.packageName }
            .distinct()
            .filterNot(::isPackageInstalledInAnyProfile)
            .toSet()
        context.dataStore.edit { preferences ->
            val (decoded, health) = readAppSettings(preferences)
            if (health != AppSettingsStoreHealth.CORRUPT && stale.isNotEmpty()) {
                val current = decoded.toMutableMap()
                current.keys.removeAll(stale)
                writeAppSettings(preferences, decoded, current)
                val stoodDown = (decodeStringSet(preferences[VOLUME_KEY_PASS_THROUGH_JSON])
                    ?: AppSettingsCodec.legacyPassThroughPackages(
                        preferences[APP_SETTINGS_JSON_V2] ?: preferences[APP_SETTINGS_JSON]
                    )).toMutableSet()
                stoodDown.removeAll(packagesAbsentFromEveryProfile)
                preferences[VOLUME_KEY_PASS_THROUGH_JSON] = encodeStringSet(stoodDown)
            }
            if (automatic) preferences[LAST_STALE_APP_CLEANUP_EPOCH_MS] = nowEpochMs
        }
        return stale.size
    }

    suspend fun resetAllUserSettings(): SettingsOperationResult =
        editSettings { preferences ->
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
            preferences.remove(DISABLE_SHIZUKU_DISCONNECTED_WARNING)
            preferences.remove(LAST_STALE_APP_CLEANUP_EPOCH_MS)
            preferences.remove(LEGACY_GAME_MODE_ENABLED)
            preferences.remove(LEGACY_RINGER_METHOD)
        }

    suspend fun repairAppSettingsStore(): SettingsOperationResult =
        editSettings { preferences ->
            val empty = AppSettingsCodec.encode(emptyMap())
            preferences[APP_SETTINGS_JSON_BACKUP] = empty
            preferences[APP_SETTINGS_JSON_V2] = empty
            preferences.remove(APP_SETTINGS_JSON)
        }

    suspend fun exportSettings(): String {
        val preferences = context.dataStore.data.first()
        val (settings, health) = readAppSettings(preferences)
        val passThrough = decodeStringSet(preferences[VOLUME_KEY_PASS_THROUGH_JSON])
            ?: AppSettingsCodec.legacyPassThroughPackages(
                preferences[APP_SETTINGS_JSON_V2] ?: preferences[APP_SETTINGS_JSON]
            )
        val export = JSONObject()
            .put("schemaVersion", EXPORT_SCHEMA_VERSION)
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
            .put(
                "disableShizukuDisconnectedWarning",
                preferences[DISABLE_SHIZUKU_DISCONNECTED_WARNING] ?: false
            )
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
        val validated = validateImport(raw)
        SettingsImportPreview(
            appCount = validated.settings.size,
            customizedAppCount = validated.settings.values.count { it.isCustomized },
            standDownCount = validated.standDownPackages.size,
            valid = true
        )
    } catch (error: Exception) {
        SettingsImportPreview(0, 0, valid = false, error = error.message ?: "Invalid settings file")
    }

    suspend fun importSettings(raw: String, mode: ImportMode): SettingsOperationResult {
        val imported = try {
            validateImport(raw)
        } catch (error: Exception) {
            return SettingsOperationResult.ValidationFailed(
                error.message ?: "Invalid settings file"
            )
        }
        return try {
            var result: SettingsOperationResult = SettingsOperationResult.Success
            context.dataStore.edit { preferences ->
                val (existing, health) = readAppSettings(preferences)
                if (health == AppSettingsStoreHealth.CORRUPT) {
                    result = SettingsOperationResult.StoreCorrupt
                    return@edit
                }
                val merged = when (mode) {
                    ImportMode.REPLACE -> imported.settings
                    ImportMode.MERGE -> existing + imported.settings
                }
                writeAppSettings(preferences, existing, merged)
                preferences[OVERLAY_SIDE] = imported.overlaySide.name
                preferences[OVERLAY_VERTICAL_FRACTION] = imported.overlayVerticalFraction
                preferences[VOLUME_DOT_SCALE_MODE] = imported.dotScaleMode.name
                preferences[VOLUME_DOT_CUSTOM_COUNT] = imported.customDotCount
                preferences[AMPLY_PAUSE_DURATION] = imported.pauseDuration.name
                preferences[DISABLE_SHIZUKU_DISCONNECTED_WARNING] =
                    imported.disableShizukuDisconnectedWarning
                val existingPackages = decodeStringSet(preferences[VOLUME_KEY_PASS_THROUGH_JSON])
                    ?: AppSettingsCodec.legacyPassThroughPackages(
                        preferences[APP_SETTINGS_JSON_V2] ?: preferences[APP_SETTINGS_JSON]
                    )
                preferences[VOLUME_KEY_PASS_THROUGH_JSON] = encodeStringSet(
                    if (mode == ImportMode.REPLACE) {
                        imported.standDownPackages
                    } else {
                        existingPackages + imported.standDownPackages
                    }
                )
                preferences[AMPLY_PAUSED_UNTIL_EPOCH_MS] = 0L
                preferences.remove(LEGACY_GAME_MODE_ENABLED)
                preferences.remove(LEGACY_RINGER_METHOD)
            }
            result
        } catch (error: Exception) {
            SettingsOperationResult.IoFailed(error.message ?: "Settings storage failed")
        }
    }

    private data class ValidatedImport(
        val settings: Map<AppIdentity, AppSettings>,
        val overlaySide: OverlaySide,
        val overlayVerticalFraction: Float,
        val dotScaleMode: VolumeDotScaleMode,
        val customDotCount: Int,
        val pauseDuration: AmplyPauseDuration,
        val disableShizukuDisconnectedWarning: Boolean,
        val standDownPackages: Set<String>
    )

    private fun validateImport(raw: String): ValidatedImport {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_BYTES) {
            "Settings file exceeds 2 MiB"
        }
        val root = JSONObject(raw)
        val rawSchema = root.get("schemaVersion")
        require(rawSchema is Number && rawSchema.toDouble() % 1.0 == 0.0) {
            "Invalid settings version"
        }
        require(rawSchema.toInt() in 1..EXPORT_SCHEMA_VERSION) {
            "Unsupported settings version"
        }
        val decoded = AppSettingsCodec.decodeResult(root.getJSONObject("appSettings").toString())
        require(decoded is AppSettingsDecodeResult.Success) {
            (decoded as? AppSettingsDecodeResult.Corrupt)?.reason ?: "App settings are malformed"
        }
        val overlaySide = if (root.has("overlaySide")) {
            require(root.get("overlaySide") is String) { "Invalid overlay side" }
            OverlaySide.entries.firstOrNull { it.name == root.getString("overlaySide") }
                ?: error("Invalid overlay side")
        } else OverlaySide.LEFT
        val vertical = if (root.has("overlayVerticalFraction")) {
            val rawPosition = root.get("overlayVerticalFraction")
            require(rawPosition is Number) { "Invalid overlay position" }
            rawPosition.toDouble()
        } else 0.5
        require(vertical.isFinite() && vertical in 0.0..1.0) { "Invalid overlay position" }
        val dotMode = if (root.has("dotScaleMode")) {
            require(root.get("dotScaleMode") is String) { "Invalid dot scale mode" }
            VolumeDotScaleMode.entries.firstOrNull { it.name == root.getString("dotScaleMode") }
                ?: error("Invalid dot scale mode")
        } else VolumeDotScaleMode.AUTO
        val customDotCount = if (root.has("customDotCount")) {
            val rawCount = root.get("customDotCount")
            require(rawCount is Number && rawCount.toDouble() % 1.0 == 0.0) {
                "Invalid custom dot count"
            }
            rawCount.toInt()
        } else 16
        require(customDotCount in 4..60) { "Invalid custom dot count" }
        val pauseDuration = if (root.has("pauseDuration")) {
            require(root.get("pauseDuration") is String) { "Invalid pause duration" }
            AmplyPauseDuration.entries.firstOrNull { it.name == root.getString("pauseDuration") }
                ?: error("Invalid pause duration")
        } else AmplyPauseDuration.FIVE_MINUTES
        val warningDisabled = if (root.has("disableShizukuDisconnectedWarning")) {
            require(root.get("disableShizukuDisconnectedWarning") is Boolean) {
                "Invalid Shizuku warning setting"
            }
            root.getBoolean("disableShizukuDisconnectedWarning")
        } else false
        val standDown = linkedSetOf<String>()
        require(!root.has("standDownPackages") || root.get("standDownPackages") is JSONArray) {
            "Invalid Stand-Down records"
        }
        root.optJSONArray("standDownPackages")?.let { packages ->
            require(packages.length() <= AppSettingsCodec.MAX_APP_RECORDS) {
                "Too many Stand-Down records"
            }
            for (index in 0 until packages.length()) {
                require(packages.get(index) is String) { "Invalid Stand-Down package name" }
                val packageName = packages.getString(index)
                require(AppSettingsCodec.isValidPackageName(packageName)) {
                    "Invalid Stand-Down package name"
                }
                require(standDown.add(packageName)) { "Duplicate Stand-Down package" }
            }
        }
        return ValidatedImport(
            settings = decoded.settings,
            overlaySide = overlaySide,
            overlayVerticalFraction = vertical.toFloat(),
            dotScaleMode = dotMode,
            customDotCount = customDotCount,
            pauseDuration = pauseDuration,
            disableShizukuDisconnectedWarning = warningDisabled,
            standDownPackages = standDown
        )
    }

    private suspend fun editSettings(
        update: (MutablePreferences) -> Unit
    ): SettingsOperationResult = try {
        context.dataStore.edit(update)
        SettingsOperationResult.Success
    } catch (error: IllegalArgumentException) {
        SettingsOperationResult.ValidationFailed(error.message ?: "Invalid setting")
    } catch (error: Exception) {
        SettingsOperationResult.IoFailed(error.message ?: "Settings storage failed")
    }

    private suspend fun updateAppSettings(
        update: (MutableMap<AppIdentity, AppSettings>) -> Unit
    ): SettingsOperationResult {
        return try {
            var result: SettingsOperationResult = SettingsOperationResult.Success
            context.dataStore.edit { preferences ->
                val (decoded, health) = readAppSettings(preferences)
                if (health == AppSettingsStoreHealth.CORRUPT) {
                    result = SettingsOperationResult.StoreCorrupt
                    return@edit
                }
                val current = decoded.toMutableMap()
                update(current)
                writeAppSettings(preferences, decoded, current)
            }
            result
        } catch (error: IllegalArgumentException) {
            SettingsOperationResult.ValidationFailed(error.message ?: "Invalid setting")
        } catch (error: Exception) {
            SettingsOperationResult.IoFailed(error.message ?: "Settings storage failed")
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
            val requestedProfile = UserHandle.getUserHandleForUid(setting.userId * 100_000)
            launcherApps.profiles.firstOrNull { it == requestedProfile }
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

    private fun isPackageInstalledInAnyProfile(packageName: String): Boolean {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        return launcherApps.profiles.any { profile ->
            runCatching { launcherApps.getApplicationInfo(packageName, 0, profile) }.isSuccess
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

