package com.agentkosticka.amply.profiles

import com.agentkosticka.amply.audio.ringer.NotificationAlertMode
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.settings.model.AppIdentity
import com.agentkosticka.amply.settings.data.AppSettingsCodec
import com.agentkosticka.amply.settings.model.AppSettingsStoreHealth
import org.json.JSONArray
import org.json.JSONObject

internal object ProfileCodec {
    private const val SCHEMA_VERSION = 1
    private const val MAX_PROFILES = 64
    private const val MAX_DEVICES = 256
    private const val MAX_TOTAL_APP_VOLUMES = 10_000

    fun validatedEncoding(store: ProfileStore): String {
        val encoded = encode(store)
        require(decode(encoded) == store) { "Profile data is not valid for storage" }
        return encoded
    }

    fun resolve(primary: String?, backup: String?): ProfileStoreResolution {
        if (!primary.isNullOrBlank()) {
            try {
                return ProfileStoreResolution(decode(primary), AppSettingsStoreHealth.HEALTHY)
            } catch (_: Exception) {
                // Fall through to the backup before declaring the store corrupt.
            }
            if (backup.isNullOrBlank()) {
                return ProfileStoreResolution(ProfileStore(), AppSettingsStoreHealth.CORRUPT)
            }
        } else if (backup.isNullOrBlank()) {
            return ProfileStoreResolution(ProfileStore(), AppSettingsStoreHealth.HEALTHY)
        }
        return try {
            ProfileStoreResolution(decode(backup), AppSettingsStoreHealth.RECOVERED_FROM_BACKUP)
        } catch (_: Exception) {
            ProfileStoreResolution(ProfileStore(), AppSettingsStoreHealth.CORRUPT)
        }
    }

    fun encode(store: ProfileStore, includeTransient: Boolean = true): String {
        val root = JSONObject().put("schemaVersion", SCHEMA_VERSION)
        val profiles = JSONArray()
        store.profiles.values.sortedBy { it.createdAtEpochMs }.forEach { profile ->
            profiles.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("saveMode", profile.saveMode.name)
                    .put("createdAt", profile.createdAtEpochMs)
                    .put("updatedAt", profile.updatedAtEpochMs)
                    .put("snapshot", encodeSnapshot(profile.snapshot))
            )
        }
        val devices = JSONArray()
        store.devices.values.sortedBy { it.descriptor.displayName.lowercase() }.forEach { device ->
            devices.put(
                JSONObject()
                    .put("key", device.descriptor.key)
                    .put("kind", device.descriptor.kind.name)
                    .put("name", device.descriptor.displayName)
                    .put("quality", device.descriptor.identityQuality.name)
                    .put("profileId", device.assignedProfileId ?: JSONObject.NULL)
                    .put("unassigned", device.explicitlyUnassigned)
            )
        }
        root.put("profiles", profiles).put("devices", devices)
        if (includeTransient) {
            root.put("activeProfileId", store.activeProfileId ?: JSONObject.NULL)
            root.put("activeDraft", store.activeDraft?.let(::encodeSnapshot) ?: JSONObject.NULL)
        }
        return root.toString()
    }

    fun decode(raw: String?): ProfileStore {
        if (raw.isNullOrBlank()) return ProfileStore()
        val root = JSONObject(raw)
        require(root.optInt("schemaVersion", 0) == SCHEMA_VERSION) { "Unsupported profile schema" }
        val profilesArray = root.optJSONArray("profiles") ?: JSONArray()
        require(profilesArray.length() <= MAX_PROFILES) { "Too many profiles" }
        var totalApps = 0
        val profiles = buildMap {
            for (index in 0 until profilesArray.length()) {
                val item = profilesArray.getJSONObject(index)
                val id = requiredText(item, "id", 128)
                val name = requiredText(item, "name", 40)
                val snapshot = decodeSnapshot(item.getJSONObject("snapshot"))
                totalApps += snapshot.appVolumes.size
                require(totalApps <= MAX_TOTAL_APP_VOLUMES) { "Too many profile app volumes" }
                require(id !in this) { "Duplicate profile ID" }
                put(
                    id,
                    AudioProfile(
                        id = id,
                        name = name,
                        saveMode = ProfileSaveMode.valueOf(requiredText(item, "saveMode", 32)),
                        snapshot = snapshot,
                        createdAtEpochMs = nonNegativeLong(item, "createdAt"),
                        updatedAtEpochMs = nonNegativeLong(item, "updatedAt")
                    )
                )
            }
        }
        require(profiles.values.map { it.name.lowercase() }.distinct().size == profiles.size) {
            "Duplicate profile name"
        }
        val devicesArray = root.optJSONArray("devices") ?: JSONArray()
        require(devicesArray.length() <= MAX_DEVICES) { "Too many output devices" }
        val devices = buildMap {
            for (index in 0 until devicesArray.length()) {
                val item = devicesArray.getJSONObject(index)
                val key = requiredText(item, "key", 128)
                val profileId = if (!item.has("profileId") || item.isNull("profileId")) {
                    null
                } else {
                    requiredText(item, "profileId", 128)
                }
                require(profileId == null || profileId in profiles) { "Unknown assigned profile" }
                require(key !in this) { "Duplicate output device" }
                put(
                    key,
                    KnownOutputDevice(
                        descriptor = OutputDeviceDescriptor(
                            key = key,
                            kind = OutputKind.valueOf(requiredText(item, "kind", 32)),
                            displayName = requiredText(item, "name", 80),
                            identityQuality = OutputIdentityQuality.valueOf(
                                requiredText(item, "quality", 32)
                            )
                        ),
                        assignedProfileId = profileId,
                        explicitlyUnassigned = item.optBoolean("unassigned", false)
                    )
                )
            }
        }
        val activeId = root.optString("activeProfileId").takeIf { it in profiles }
        val draft = root.optJSONObject("activeDraft")?.let(::decodeSnapshot)
        return ProfileStore(profiles, devices, activeId, draft?.takeIf { activeId != null })
    }

    private fun encodeSnapshot(snapshot: AudioProfileSnapshot): JSONObject {
        val apps = JSONObject()
        snapshot.appVolumes.forEach { (identity, volume) -> apps.put(identity.storageKey, volume) }
        val streams = JSONObject()
        snapshot.systemVolumes.forEach { (target, volume) -> streams.put(target.name, volume) }
        return JSONObject()
            .put("apps", apps)
            .put("streams", streams)
            .put("ringer", snapshot.ringerMode.name)
            .put("dnd", snapshot.dndEnabled ?: JSONObject.NULL)
    }

    private fun decodeSnapshot(item: JSONObject): AudioProfileSnapshot {
        val appsObject = item.optJSONObject("apps") ?: JSONObject()
        val apps = buildMap {
            val keys = appsObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val identity = AppIdentity.fromStorageKey(key) ?: error("Invalid app identity")
                require(identity.userId >= 0 && AppSettingsCodec.isValidPackageName(identity.packageName)) {
                    "Invalid app identity"
                }
                require(identity !in this) { "Duplicate app identity" }
                put(identity, fraction(appsObject.get(key)))
            }
        }
        val streamsObject = item.optJSONObject("streams") ?: JSONObject()
        val streams = buildMap {
            val keys = streamsObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val target = VolumeTarget.entries.firstOrNull { it.name == key }
                    ?: error("Invalid volume target")
                require(target in PROFILE_SYSTEM_TARGETS) { "Unsupported profile volume target" }
                put(target, fraction(streamsObject.get(key)))
            }
        }
        val dnd = if (item.isNull("dnd") || !item.has("dnd")) null else {
            require(item.get("dnd") is Boolean) { "Invalid DND state" }
            item.getBoolean("dnd")
        }
        return AudioProfileSnapshot(
            appVolumes = apps,
            systemVolumes = streams,
            ringerMode = NotificationAlertMode.valueOf(requiredText(item, "ringer", 32)),
            dndEnabled = dnd
        )
    }

    private fun fraction(raw: Any): Float {
        require(raw is Number) { "Invalid volume" }
        return raw.toFloat().also { require(it.isFinite() && it in 0f..1f) { "Invalid volume" } }
    }

    private fun requiredText(item: JSONObject, key: String, maxLength: Int): String {
        require(item.has(key) && item.get(key) is String) { "Invalid $key" }
        return item.getString(key).trim().also {
            require(it.isNotEmpty() && it.length <= maxLength) { "Invalid $key" }
        }
    }

    private fun nonNegativeLong(item: JSONObject, key: String): Long {
        val raw = item.get(key)
        require(raw is Number) { "Invalid $key" }
        return raw.toLong().also { require(it >= 0L) { "Invalid $key" } }
    }
}
