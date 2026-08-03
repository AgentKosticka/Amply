package com.agentkosticka.amply.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.agentkosticka.amply.settings.data.PreferencesManager
import com.agentkosticka.amply.settings.model.SettingsOperationResult
import com.agentkosticka.amply.util.readAtMost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal const val UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1_000L
private const val GITHUB_LATEST_RELEASE_API =
    "https://api.github.com/repos/AgentKosticka/Amply/releases/latest"
private const val GITHUB_RELEASE_BASE =
    "https://github.com/AgentKosticka/Amply/releases/tag/v"
private const val MAX_RELEASE_RESPONSE_BYTES = 256 * 1024

internal data class AvailableUpdate(
    val version: SemanticVersion,
    val releaseUrl: String
)

internal data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")

        fun parse(raw: String?): SemanticVersion? {
            val match = raw?.trim()?.let(pattern::matchEntire) ?: return null
            val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            return SemanticVersion(parts[0], parts[1], parts[2])
        }
    }
}

internal interface UpdateCheckStore {
    suspend fun lastSuccessfulCheckEpochMs(): Long
    suspend fun recordSuccessfulCheck(epochMs: Long)
}

internal fun interface OnlineStatus {
    fun isOnline(): Boolean
}

internal fun interface LatestReleaseSource {
    suspend fun fetchLatest(): SemanticVersion
}

internal class AppUpdateChecker(
    private val store: UpdateCheckStore,
    private val onlineStatus: OnlineStatus,
    private val releaseSource: LatestReleaseSource,
    private val currentVersion: () -> SemanticVersion?,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context, preferences: PreferencesManager) : this(
        store = PreferencesUpdateCheckStore(preferences),
        onlineStatus = AndroidOnlineStatus(context.applicationContext),
        releaseSource = GitHubLatestReleaseSource(),
        currentVersion = {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            SemanticVersion.parse(packageInfo.versionName)
        }
    )

    private val checkMutex = Mutex()
    private val _availableUpdate = MutableStateFlow<AvailableUpdate?>(null)
    val availableUpdate: StateFlow<AvailableUpdate?> = _availableUpdate.asStateFlow()

    suspend fun checkIfDue() = checkMutex.withLock {
        val now = nowEpochMs()
        val lastSuccess = store.lastSuccessfulCheckEpochMs()
        if (lastSuccess > 0L && now - lastSuccess in 0 until UPDATE_CHECK_INTERVAL_MS) return@withLock
        if (!onlineStatus.isOnline()) return@withLock

        val latest = runCatching { releaseSource.fetchLatest() }.getOrNull() ?: return@withLock
        runCatching { store.recordSuccessfulCheck(now) }

        val installed = currentVersion() ?: return@withLock
        _availableUpdate.value = if (latest > installed) {
            AvailableUpdate(
                version = latest,
                releaseUrl = "$GITHUB_RELEASE_BASE$latest"
            )
        } else {
            null
        }
    }

    fun dismissAvailableUpdate() {
        _availableUpdate.value = null
    }
}

private class PreferencesUpdateCheckStore(
    private val preferences: PreferencesManager
) : UpdateCheckStore {
    override suspend fun lastSuccessfulCheckEpochMs(): Long =
        preferences.lastSuccessfulUpdateCheckEpochMs()

    override suspend fun recordSuccessfulCheck(epochMs: Long) {
        when (val result = preferences.recordSuccessfulUpdateCheck(epochMs)) {
            SettingsOperationResult.Success -> Unit
            SettingsOperationResult.StoreCorrupt -> error("Settings store is corrupt")
            is SettingsOperationResult.ValidationFailed -> error(result.reason)
            is SettingsOperationResult.IoFailed -> error(result.reason)
        }
    }
}

private class AndroidOnlineStatus(context: Context) : OnlineStatus {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    override fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

private class GitHubLatestReleaseSource : LatestReleaseSource {
    override suspend fun fetchLatest(): SemanticVersion = withContext(Dispatchers.IO) {
        val connection = (URL(GITHUB_LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Amply-Android")
        }
        try {
            check(connection.responseCode in 200..299) {
                "GitHub returned HTTP ${connection.responseCode}"
            }
            val bytes = connection.inputStream.use {
                it.readAtMost(MAX_RELEASE_RESPONSE_BYTES + 1)
            }
            check(bytes.size <= MAX_RELEASE_RESPONSE_BYTES) { "GitHub response was too large" }
            val tag = JSONObject(bytes.toString(Charsets.UTF_8)).getString("tag_name")
            SemanticVersion.parse(tag) ?: error("GitHub release tag was not vX.Y.Z")
        } finally {
            connection.disconnect()
        }
    }
}
