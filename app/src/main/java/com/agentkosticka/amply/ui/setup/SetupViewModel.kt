package com.agentkosticka.amply.ui.setup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentkosticka.amply.data.PreferencesManager
import com.agentkosticka.amply.shizuku.ShizukuPermissionState
import com.agentkosticka.amply.shizuku.ShizukuRepository
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
    private val context: Context
) : ViewModel() {

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    val permissionState: StateFlow<ShizukuPermissionState> = shizukuRepository.permissionState

    fun nextPage() {
        _currentPage.value = (_currentPage.value + 1).coerceAtMost(4)
    }

    fun previousPage() {
        _currentPage.value = (_currentPage.value - 1).coerceAtLeast(0)
    }

    fun goToPage(page: Int) {
        _currentPage.value = page.coerceIn(0, 4)
    }

    /**
     * Opens the Shizuku app download page
     */
    fun openShizukuDownload() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://github.com/RikkaApps/Shizuku/releases")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /**
     * Opens the Shizuku app (to start the service)
     */
    fun openShizukuApp() {
        val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (intent != null) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
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

    override fun onCleared() {
        super.onCleared()
        shizukuRepository.cleanup()
    }
}
