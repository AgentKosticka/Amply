package com.agentkosticka.amply.setup

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentkosticka.amply.settings.data.PreferencesManager
import com.agentkosticka.amply.shizuku.client.ShizukuPermissionState
import com.agentkosticka.amply.shizuku.client.ShizukuRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Setup Wizard flow
 */
class SetupViewModel(
    private val shizukuRepository: ShizukuRepository,
    private val preferencesManager: PreferencesManager,
    context: Context
) : ViewModel() {
    private val appContext = context.applicationContext

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    val permissionState: StateFlow<ShizukuPermissionState> = shizukuRepository.permissionState

    fun goToPage(page: Int) {
        _currentPage.value = page.coerceIn(0, 4)
    }

    /**
     * Opens the Shizuku app download page
     */
    fun openShizukuDownload() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = "https://github.com/RikkaApps/Shizuku/releases".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        appContext.startActivity(intent)
    }

    /**
     * Opens the Shizuku app (to start the service)
     */
    fun openShizukuApp() {
        val intent = appContext.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (intent != null) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            appContext.startActivity(intent)
        }
    }

    /**
     * Requests Shizuku permission
     */
    fun requestShizukuPermission() {
        shizukuRepository.requestPermission()
    }

    /**
     * Checks Shizuku permission state (refreshes)
     */
    fun checkShizukuState() {
        shizukuRepository.checkPermissionState()
    }

    /**
     * Completes the setup wizard
     */
    fun completeSetup() {
        viewModelScope.launch {
            preferencesManager.setSetupCompleted(true)
        }
    }

}
