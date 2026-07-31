package com.agentkosticka.amply.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.log10

/**
 * Repository for managing Shizuku integration.
 * Handles permission checks, requests, and shell command execution.
 */
class ShizukuRepository(private val context: Context) {

    private val _permissionState = MutableStateFlow(ShizukuPermissionState.UNKNOWN)
    val permissionState: StateFlow<ShizukuPermissionState> = _permissionState.asStateFlow()

    private val requestCode = 1001

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        checkPermissionState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died")
        _permissionState.value = ShizukuPermissionState.SHIZUKU_NOT_RUNNING
    }

    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == this.requestCode) {
                _permissionState.value = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    ShizukuPermissionState.GRANTED
                } else {
                    ShizukuPermissionState.DENIED
                }
                Log.i(TAG, "Shizuku permission result: ${_permissionState.value}")
            }
        }

    init {
        // Register listeners
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)

        // Initial check
        checkPermissionState()
    }

    /**
     * Checks if Shizuku app is installed on the device
     */
    fun isShizukuInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Checks if Shizuku service is currently running
     */
    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks current permission state and updates the StateFlow
     */
    fun checkPermissionState() {
        val newState = when {
            !isShizukuInstalled() -> ShizukuPermissionState.SHIZUKU_NOT_INSTALLED
            !isShizukuRunning() -> ShizukuPermissionState.SHIZUKU_NOT_RUNNING
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                ShizukuPermissionState.GRANTED
            Shizuku.shouldShowRequestPermissionRationale() ->
                ShizukuPermissionState.SHOULD_SHOW_RATIONALE
            else -> ShizukuPermissionState.NOT_GRANTED
        }
        if (_permissionState.value != newState) {
            Log.i(TAG, "Shizuku state ${_permissionState.value} -> $newState")
        }
        _permissionState.value = newState
    }

    /**
     * Requests Shizuku permission from the user
     */
    fun requestPermission() {
        if (isShizukuRunning()) {
            Shizuku.requestPermission(requestCode)
        } else {
            _permissionState.value = ShizukuPermissionState.SHIZUKU_NOT_RUNNING
        }
    }

    /**
     * Executes a shell command using Shizuku's elevated permissions
     * Uses reflection to access Shizuku.newProcess() which is marked as private
     * @param command The shell command to execute
     * @return The command output as a string, or null if failed
     */
    suspend fun executeShellCommand(command: String): String? {
        if (_permissionState.value != ShizukuPermissionState.GRANTED) {
            Log.d(TAG, "Shell command skipped: Shizuku not granted")
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                // Use reflection to access Shizuku.newProcess() which is private
                val process = createShizukuProcess(arrayOf("sh", "-c", command))

                if (process == null) {
                    Log.e(TAG, "Failed to create Shizuku process")
                    return@withContext null
                }

                // Read output with timeout
                val output = withTimeoutOrNull(5000L) {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val result = StringBuilder()

                    reader.use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            result.append(line).append("\n")
                        }
                    }

                    result.toString()
                }

                // Also read error stream for debugging
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                val errorOutput = errorReader.use { it.readText() }
                if (errorOutput.isNotBlank()) {
                    Log.d(TAG, "Shell command stderr: $errorOutput")
                }

                // Wait for process completion
                process.waitFor()

                if (output == null) {
                    Log.w(TAG, "Shell command timed out: $command")
                }

                output
            } catch (e: Exception) {
                Log.e(TAG, "Shell command failed: $command", e)
                null
            }
        }
    }

    /**
     * Creates a process using Shizuku's newProcess method via reflection
     * This is necessary because newProcess is marked as private in some Shizuku versions
     */
    private fun createShizukuProcess(cmd: Array<String>): Process? {
        return try {
            // Try to find and invoke Shizuku.newProcess using reflection
            val shizukuClass = Shizuku::class.java
            val newProcessMethod = shizukuClass.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            newProcessMethod.invoke(null, cmd, null, null) as? Process
        } catch (e: NoSuchMethodException) {
            Log.e(TAG, "Shizuku.newProcess method not found", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to invoke Shizuku.newProcess", e)
            null
        }
    }

    /**
     * Dumps audio information to detect active audio sessions
     * Tries multiple service names as they vary by Android version/device
     * @return Raw dumpsys output or null if failed
     */
    suspend fun dumpAudioFlinger(): String? {
        // Try multiple service names - they vary by Android version and manufacturer
        val serviceNames = listOf(
            "audio",              // Most common on modern Android
            "media.audio_flinger", // Some devices use this
            "audio_flinger",       // Older Android versions
            "media.audio_policy"   // Alternative service
        )

        for (serviceName in serviceNames) {
            val result = executeShellCommand("dumpsys $serviceName")
            if (!result.isNullOrBlank() && !result.contains("Can't find service")) {
                Log.d(TAG, "Successfully got output from: $serviceName")
                return result
            }
        }

        Log.w(TAG, "No audio service found from any known service name")
        return null
    }

    /**
     * Dumps media session information - alternative method to detect playing apps
     * @return Raw dumpsys output or null if failed
     */
    suspend fun dumpMediaSession(): String? {
        return executeShellCommand("dumpsys media_session")
    }

    /**
     * Dumps audio service information (alternative to audio_flinger)
     * @return Raw dumpsys output or null if failed
     */
    suspend fun dumpAudioService(): String? {
        return executeShellCommand("dumpsys audio")
    }

    /**
     * Gets list of packages for a given UID using Shizuku shell command
     * @return Package name or null if failed
     */
    suspend fun getPackageForUid(uid: Int): String? {
        val output = executeShellCommand("cmd package list packages -U") ?: return null
        
        // Parse output: each line is "package:com.example.app uid:10123"
        val uidPattern = Regex("""package:(\S+)\s+uid:(\d+)""")
        
        for (line in output.lines()) {
            val match = uidPattern.find(line)
            if (match != null) {
                val packageName = match.groupValues[1]
                val lineUid = match.groupValues[2].toIntOrNull()
                if (lineUid == uid) {
                    return packageName
                }
            }
        }
        return null
    }

    /**
     * Gets a map of UID to package name for efficient lookup
     * This is more efficient than calling getPackageForUid for each UID
     * @return Map of UID to package name
     */
    suspend fun getUidPackageMap(): Map<Int, String> {
        val output = executeShellCommand("cmd package list packages -U") ?: return emptyMap()
        
        val result = mutableMapOf<Int, String>()
        val uidPattern = Regex("""package:(\S+)\s+uid:(\d+)""")
        
        for (line in output.lines()) {
            val match = uidPattern.find(line)
            if (match != null) {
                val packageName = match.groupValues[1]
                val uid = match.groupValues[2].toIntOrNull()
                if (uid != null) {
                    result[uid] = packageName
                }
            }
        }
        
        Log.d(TAG, "Built UID-package map with ${result.size} entries")
        return result
    }

    /**
     * Sets volume for a specific audio session/UID using Shizuku
     * This bypasses the normal Android limitations on per-app volume control
     *
     * @param sessionId The audio session ID
     * @param uid The app's UID
     * @param volume Volume level (0.0 to 1.0)
     * @return true if successful
     */
    suspend fun setAppVolume(sessionId: Int, uid: Int, volume: Float): Boolean {
        if (_permissionState.value != ShizukuPermissionState.GRANTED) {
            Log.d(TAG, "setAppVolume skipped: Shizuku not granted")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                // Convert volume (0-1) to percentage (0-100) for some commands
                val volumePercent = (volume * 100).toInt()

                // Method 1: Try a list of potential transaction codes
                // "service call audio <code> i32 <piid> f <volume>"
                // Codes vary by Android version. Common ones: 10, 12, 18, 22, 23
                val codes = listOf(10, 11, 12, 18, 20, 22, 23)
                
                Log.d(TAG, "Attempting setAppVolume for sessionId=$sessionId with codes: $codes")
                
                var success = false
                for (code in codes) {
                    // Assuming signature: setPlayerVolume(int piid, float volume)
                    // i32 <piid> f <volume>
                    val cmd = "service call audio $code i32 $sessionId f $volume"
                    
                    val result = executeShellCommand(cmd)
                    if (result?.contains("Result") == true && 
                        !result.contains("Exception") && 
                        !result.contains("null object reference")) {
                        Log.d(TAG, "  Code $code executed successfully: result=$result")
                        // success = true // Don't stop early yet, assume we might need multiple calls or this might be a dud
                    } else {
                        // Log.w(TAG, "  Code $code failed or threw exception: $result")
                    }
                }

                // Method 2: Try using media.audio_flinger direct command for session
                val afCmd = "cmd media.audio_flinger set-volume $sessionId $volume"
                executeShellCommand(afCmd)

                // Method 3: Try using media.audio_flinger for UID (Strong fallback for Android 12+)
                val afUidCmd = "cmd media.audio_flinger setVolumeForUid $uid $volume"
                val uidResult = executeShellCommand(afUidCmd)
                if (uidResult != null && !uidResult.contains("error", ignoreCase = true)) {
                     Log.d(TAG, "  AudioFlinger setVolumeForUid executed for uid $uid")
                }

                Log.d(TAG, "Finished trying volume codes for session $sessionId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set app volume for session $sessionId", e)
                false
            }
        }
    }

    /**
     * Alternative: Set volume using AudioTrack session control
     */
    suspend fun setSessionVolume(sessionId: Int, volume: Float): Boolean {
        if (_permissionState.value != ShizukuPermissionState.GRANTED) {
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                // Use audio_flinger to set volume for specific track
                val volumeDb = if (volume > 0) (20 * log10(volume)).coerceAtLeast(-96f) else -96f

                val result = executeShellCommand(
                    "dumpsys media.audio_flinger --set-volume $sessionId $volumeDb"
                )

                Log.d(TAG, "setSessionVolume result for session $sessionId: $result")
                result != null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set session volume", e)
                false
            }
        }
    }

    companion object {
        private const val TAG = "ShizukuRepository"
    }

    /**
     * Cleanup listeners when repository is destroyed
     */
    fun cleanup() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
    }
}

/**
 * Represents the current state of Shizuku permission
 */
enum class ShizukuPermissionState {
    UNKNOWN,
    SHIZUKU_NOT_INSTALLED,
    SHIZUKU_NOT_RUNNING,
    NOT_GRANTED,
    SHOULD_SHOW_RATIONALE,
    DENIED,
    GRANTED
}
