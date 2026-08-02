package com.agentkosticka.amply.settings.data

import com.agentkosticka.amply.settings.model.*

import org.json.JSONObject


internal object AppSettingsCodec {
    private const val SCHEMA_VERSION = 2

    fun decode(raw: String?): Map<AppIdentity, AppSettings> =
        (decodeResult(raw) as? AppSettingsDecodeResult.Success)?.settings.orEmpty()

    fun decodeResult(raw: String?): AppSettingsDecodeResult {
        if (raw.isNullOrBlank()) return AppSettingsDecodeResult.Success(emptyMap())
        return try {
            val root = JSONObject(raw)
            val apps = root.optJSONObject("apps") ?: root
            val decoded = buildMap {
                val keys = apps.keys()
                while (keys.hasNext()) {
                    val storedKey = keys.next()
                    if (storedKey == "_schemaVersion") continue
                    val item = apps.optJSONObject(storedKey)
                        ?: error("App setting '$storedKey' is not an object")
                    val packageName = item.optString("packageName")
                        .takeIf { it.isNotBlank() }
                        ?: AppIdentity.fromStorageKey(storedKey)?.packageName
                        ?: storedKey
                    val uid = item.optInt("uid", -1)
                    val identity = AppIdentity.fromStorageKey(storedKey)
                        ?: AppIdentity.fromUid(packageName, uid)
                    put(
                        identity,
                        AppSettings(
                            packageName = packageName,
                            appName = item.optString("appName", packageName),
                            uid = uid,
                            userId = identity.userId,
                            defaultVolume = item.optDouble("defaultVolume", 1.0).toFloat().coerceIn(0f, 1f),
                            overlayMode = OverlayAppMode.fromStored(
                                value = item.optString("overlayMode").takeIf { it.isNotBlank() },
                                legacyHidden = item.optBoolean("hiddenInOverlay", false)
                            ),
                            passVolumeKeysToApp = item.optBoolean("passVolumeKeysToApp", false),
                            lastSeenTimestamp = item.optLong("lastSeenTimestamp", 0L)
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
                    .put("passVolumeKeysToApp", setting.passVolumeKeysToApp)
                    .put("lastSeenTimestamp", setting.lastSeenTimestamp)
            )
        }
        return JSONObject()
            .put("_schemaVersion", SCHEMA_VERSION)
            .put("apps", apps)
            .toString()
    }
}

