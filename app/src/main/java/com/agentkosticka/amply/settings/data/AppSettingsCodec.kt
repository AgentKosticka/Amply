package com.agentkosticka.amply.settings.data

import com.agentkosticka.amply.settings.model.*

import org.json.JSONObject


internal object AppSettingsCodec {
    private const val SCHEMA_VERSION = 3
    const val MAX_APP_RECORDS = 10_000
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")

    fun isValidPackageName(value: String): Boolean = PACKAGE_NAME.matches(value)

    fun decode(raw: String?): Map<AppIdentity, AppSettings> =
        (decodeResult(raw) as? AppSettingsDecodeResult.Success)?.settings.orEmpty()

    fun decodeResult(raw: String?): AppSettingsDecodeResult {
        if (raw.isNullOrBlank()) return AppSettingsDecodeResult.Success(emptyMap())
        return try {
            val root = JSONObject(raw)
            val schema = root.optInt("_schemaVersion", 1)
            require(schema in 1..SCHEMA_VERSION) { "Unsupported app-settings schema $schema" }
            val apps = root.optJSONObject("apps") ?: root
            val decoded = buildMap {
                val keys = apps.keys()
                while (keys.hasNext()) {
                    require(size < MAX_APP_RECORDS) { "Too many app records" }
                    val storedKey = keys.next()
                    if (storedKey == "_schemaVersion") continue
                    val item = apps.optJSONObject(storedKey)
                        ?: error("App setting '$storedKey' is not an object")
                    val packageName = if (item.has("packageName")) {
                        require(item.get("packageName") is String) { "Invalid package name" }
                        item.getString("packageName")
                    } else {
                        AppIdentity.fromStorageKey(storedKey)?.packageName ?: storedKey
                    }
                    require(PACKAGE_NAME.matches(packageName)) { "Invalid package name" }
                    val uid = item.opt("uid")?.let { rawUid ->
                        require(rawUid is Number) { "Invalid UID" }
                        val numeric = rawUid.toDouble()
                        require(
                            numeric.isFinite() && numeric % 1.0 == 0.0 &&
                                numeric in -1.0..Int.MAX_VALUE.toDouble()
                        ) { "Invalid UID" }
                        numeric.toInt()
                    } ?: -1
                    require(uid >= -1) { "Invalid UID" }
                    val identity = AppIdentity.fromStorageKey(storedKey)
                        ?: AppIdentity.fromUid(packageName, uid)
                    require(identity.userId >= 0) { "Invalid user ID" }
                    require(identity !in this) { "Duplicate app identity" }
                    val rawVolume = item.opt("defaultVolume")?.let { raw ->
                        require(raw is Number) { "Invalid volume" }
                        raw.toDouble()
                    } ?: 1.0
                    require(rawVolume.isFinite() && rawVolume in 0.0..1.0) { "Invalid volume" }
                    val timestamp = item.opt("lastSeenTimestamp")?.let { raw ->
                        require(raw is Number) { "Invalid timestamp" }
                        val numeric = raw.toDouble()
                        require(numeric.isFinite() && numeric % 1.0 == 0.0) { "Invalid timestamp" }
                        raw.toLong()
                    } ?: 0L
                    require(timestamp >= 0L) { "Invalid timestamp" }
                    val appName = if (item.has("appName")) {
                        require(item.get("appName") is String) { "Invalid app name" }
                        item.getString("appName").take(256)
                    } else packageName
                    val overlayMode = if (item.has("overlayMode")) {
                        require(item.get("overlayMode") is String) { "Invalid overlay mode" }
                        OverlayAppMode.entries.firstOrNull { it.name == item.getString("overlayMode") }
                            ?: error("Invalid overlay mode")
                    } else {
                        val legacyHidden = if (item.has("hiddenInOverlay")) {
                            require(item.get("hiddenInOverlay") is Boolean) { "Invalid hidden flag" }
                            item.getBoolean("hiddenInOverlay")
                        } else false
                        OverlayAppMode.fromStored(null, legacyHidden)
                    }
                    put(
                        identity,
                        AppSettings(
                            packageName = packageName,
                            appName = appName,
                            uid = uid,
                            userId = identity.userId,
                            defaultVolume = rawVolume.toFloat(),
                            overlayMode = overlayMode,
                            lastSeenTimestamp = timestamp
                        )
                    )
                }
            }
            AppSettingsDecodeResult.Success(decoded)
        } catch (error: Exception) {
            AppSettingsDecodeResult.Corrupt(raw, error.message ?: error.javaClass.simpleName)
        }
    }

    fun encode(settings: Map<AppIdentity, AppSettings>): String {
        val apps = JSONObject()
        settings.forEach { (identity, setting) ->
            apps.put(
                identity.storageKey,
                JSONObject()
                    .put("packageName", setting.packageName)
                    .put("appName", setting.appName)
                    .put("uid", setting.uid)
                    .put("userId", setting.userId)
                    .put("defaultVolume", setting.defaultVolume.toDouble())
                    .put("overlayMode", setting.overlayMode.name)
                    .put("lastSeenTimestamp", setting.lastSeenTimestamp)
            )
        }
        return JSONObject()
            .put("_schemaVersion", SCHEMA_VERSION)
            .put("apps", apps)
            .toString()
    }

    fun legacyPassThroughPackages(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching {
            val root = JSONObject(raw)
            val apps = root.optJSONObject("apps") ?: root
            buildSet {
                val keys = apps.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == "_schemaVersion") continue
                    val item = apps.optJSONObject(key) ?: continue
                    if (!item.optBoolean("passVolumeKeysToApp", false)) continue
                    val packageName = item.optString("packageName")
                        .takeIf(String::isNotBlank)
                        ?: AppIdentity.fromStorageKey(key)?.packageName
                        ?: key
                    if (PACKAGE_NAME.matches(packageName)) add(packageName)
                }
            }
        }.getOrDefault(emptySet())
    }
}

