package com.agentkosticka.amply.shizuku.client

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.os.SystemClock
import com.agentkosticka.amply.util.readAtMost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.TimeUnit

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
    suspend fun injectVolumeKey(keyCode: Int): Boolean {
        require(keyCode == 24 || keyCode == 25) { "Only volume keys are allowed" }
        if (_permissionState.value != ShizukuPermissionState.GRANTED) {
            return false
        }

        return withContext(Dispatchers.IO) {
            val process = createShizukuProcess(arrayOf("input", "keyevent", keyCode.toString()))
                ?: return@withContext false
            val completed = runCatching {
                coroutineScope {
                    val stdout = async(Dispatchers.IO) { process.inputStream.readAtMost(64 * 1024) }
                    val stderr = async(Dispatchers.IO) { process.errorStream.readAtMost(64 * 1024) }
                    val exited = process.waitFor(5, TimeUnit.SECONDS)
                    if (!exited) {
                        process.destroy()
                        if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                        stdout.cancel()
                        stderr.cancel()
                        return@coroutineScope false
                    }
                    stdout.await()
                    stderr.await()
                    process.exitValue() == 0
                }
            }.getOrDefault(false)
            if (!completed && process.isAlive) process.destroyForcibly()
            completed
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
