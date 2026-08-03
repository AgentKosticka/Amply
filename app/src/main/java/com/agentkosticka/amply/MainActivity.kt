package com.agentkosticka.amply

import android.Manifest
import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.agentkosticka.amply.settings.ui.SettingsDashboard
import com.agentkosticka.amply.permissions.AppPermissionState
import com.agentkosticka.amply.service.VolumeKeyService
import com.agentkosticka.amply.setup.SetupViewModel
import com.agentkosticka.amply.setup.SetupWizardScreen
import com.agentkosticka.amply.setup.SetupReadiness
import com.agentkosticka.amply.ui.theme.AmplyTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {

    private lateinit var runtime: AmplyRuntime
    private val _appPermissionState = MutableStateFlow(AppPermissionState())
    private val appPermissionState = _appPermissionState.asStateFlow()
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionState() }
    private val phoneStatePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshPermissionState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen BEFORE super.onCreate()
        installSplashScreen()
        
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        runtime = (application as AmplyApplication).runtime

        setContent {
            AmplyTheme {
                MainContent()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::runtime.isInitialized) {
            refreshPermissionState()
            runtime.shizukuRepository.checkPermissionState()
        }
    }

    @Composable
    fun MainContent() {
        val introductionSeen by runtime.preferencesManager.isSetupIntroductionSeen.collectAsState(initial = false)
        val permissionState by appPermissionState.collectAsState()
        val shizukuPermission by runtime.shizukuRepository.permissionState.collectAsState()
        val connectionState by runtime.connectionState.collectAsState()
        val readiness = SetupReadiness(
            accessibilityEnabled = permissionState.volumeKeysGranted,
            shizukuPermission = shizukuPermission,
            volumeServiceConnection = connectionState
        )

        if (readiness.canShowDashboard(introductionSeen)) {
            // Show main app screen
            MainAppScreen()
        } else {
            // Show setup wizard
            val viewModel = remember {
                SetupViewModel(
                    shizukuRepository = runtime.shizukuRepository,
                    preferencesManager = runtime.preferencesManager,
                    context = this
                )
            }

            SetupWizardScreen(
                viewModel = viewModel,
                introductionSeen = introductionSeen,
                appPermissionState = permissionState,
                connectionState = connectionState,
                onAccessibilityClick = { openAccessibilitySettings() },
                onRetryConnection = { runtime.retryVolumeServiceConnection() },
                onSetupComplete = {
                    // Setup completed, will automatically switch to main screen
                }
            )
        }
    }

    @Composable
    fun MainAppScreen() {
        val permissionState by appPermissionState.collectAsState()
        SettingsDashboard(
            runtime = runtime,
            appPermissionState = permissionState,
            onAccessibilityClick = { openAccessibilitySettings() },
            onNotificationPolicyClick = { openNotificationPolicySettings() },
            onPhoneStateClick = { requestPhoneStatePermission() },
            onNotificationsClick = { requestNotificationPermission() }
        )
    }

    private fun refreshPermissionState() {
        val accessibilityManager = getSystemService(AccessibilityManager::class.java)
        val volumeKeysGranted = runCatching {
            accessibilityManager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { service ->
                    val serviceInfo = service.resolveInfo.serviceInfo
                    serviceInfo.packageName == packageName &&
                        serviceInfo.name == VolumeKeyService::class.java.name
                }
        }.getOrDefault(false)

        _appPermissionState.value = AppPermissionState(
            volumeKeysGranted = volumeKeysGranted,
            notificationPolicyGranted = getSystemService(NotificationManager::class.java)
                .isNotificationPolicyAccessGranted,
            phoneStateGranted = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED,
            notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }

    private fun openAccessibilitySettings() {
        val detailsIntent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
            putExtra(
                Intent.EXTRA_COMPONENT_NAME,
                android.content.ComponentName(this@MainActivity, VolumeKeyService::class.java)
            )
        }
        runCatching { startActivity(detailsIntent) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun openNotificationPolicySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestPhoneStatePermission() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }

}
