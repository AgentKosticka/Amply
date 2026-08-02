package com.agentkosticka.amply.shizuku.client

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Repository for managing Shizuku integration.
 * Handles permission checks, requests, and shell command execution.
 */
class ShizukuRepository(private val context: Context) {

    private val _permissionState = MutableStateFlow(ShizukuPermissionState.UNKNOWN)
    val permissionState: StateFlow<ShizukuPermissionState> = _permissionState.asStateFlow()

    private val requestCode = 1001
    @Volatile private var lastPermissionCheckElapsedMs = Long.MIN_VALUE

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        runCatching { checkPermissionState() }
            .onFailure { Log.w(TAG, "Binder-received permission refresh failed", it) }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died")
        runCatching { _permissionState.value = ShizukuPermissionState.SHIZUKU_NOT_RUNNING }
    }

    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == this.requestCode) {
                runCatching {
                    _permissionState.value = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        ShizukuPermissionState.GRANTED
                    } else {
                        ShizukuPermissionState.DENIED
                    }
                    Log.i(TAG, "Shizuku permission result: ${_permissionState.value}")
                }.onFailure {
                    Log.w(TAG, "Shizuku permission-result callback failed", it)
                    _permissionState.value = ShizukuPermissionState.SHIZUKU_NOT_RUNNING
                }
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
        } catch (_: Exception) {
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
        lastPermissionCheckElapsedMs = SystemClock.elapsedRealtime()
        val newState = runCatching {
            resolveShizukuPermissionState(
                installed = isShizukuInstalled(),
                running = isShizukuRunning(),
                checkPermission = { Shizuku.checkSelfPermission() },
                shouldShowRationale = { Shizuku.shouldShowRequestPermissionRationale() }
            )
        }.getOrElse {
            Log.w(TAG, "Shizuku permission refresh failed", it)
            ShizukuPermissionState.SHIZUKU_NOT_RUNNING
        }
        if (_permissionState.value != newState) {
            Log.i(TAG, "Shizuku state ${_permissionState.value} -> $newState")
        }
        _permissionState.value = newState
    }

    fun checkPermissionStateThrottled(minIntervalMs: Long = 1_000L) {
        val now = SystemClock.elapsedRealtime()
        if (lastPermissionCheckElapsedMs != Long.MIN_VALUE &&
            now - lastPermissionCheckElapsedMs < minIntervalMs
        ) return
        runCatching { checkPermissionState() }
            .onFailure {
                Log.w(TAG, "Throttled Shizuku permission refresh failed", it)
                _permissionState.value = ShizukuPermissionState.SHIZUKU_NOT_RUNNING
            }
    }

    /**
     * Requests Shizuku permission from the user
     */
    fun requestPermission() {
        try {
            if (isShizukuRunning()) {
                Shizuku.requestPermission(requestCode)
            } else {
                _permissionState.value = ShizukuPermissionState.SHIZUKU_NOT_RUNNING
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Shizuku permission request failed", e)
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
                val output = withTimeoutOrNull(5000L.milliseconds) {
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

internal fun resolveShizukuPermissionState(
    installed: Boolean,
    running: Boolean,
    checkPermission: () -> Int,
    shouldShowRationale: () -> Boolean
): ShizukuPermissionState {
    if (!installed) return ShizukuPermissionState.SHIZUKU_NOT_INSTALLED
    if (!running) return ShizukuPermissionState.SHIZUKU_NOT_RUNNING
    return try {
        when {
            checkPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuPermissionState.GRANTED
            shouldShowRationale() -> ShizukuPermissionState.SHOULD_SHOW_RATIONALE
            else -> ShizukuPermissionState.NOT_GRANTED
        }
    } catch (_: RuntimeException) {
        ShizukuPermissionState.SHIZUKU_NOT_RUNNING
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
