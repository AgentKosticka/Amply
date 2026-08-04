package com.agentkosticka.amply

import android.Manifest
import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.agentkosticka.amply.settings.ui.SettingsDashboard
import com.agentkosticka.amply.permissions.AppPermissionState
import com.agentkosticka.amply.service.VolumeKeyService
import com.agentkosticka.amply.setup.SetupViewModel
import com.agentkosticka.amply.setup.SetupWizardScreen
import com.agentkosticka.amply.setup.SetupReadiness
import com.agentkosticka.amply.ui.theme.AmplyTheme
import com.agentkosticka.amply.runtime.RuntimeErrorCode
import com.agentkosticka.amply.tutorial.TutorialOverlayDemo
import com.agentkosticka.amply.tutorial.TutorialStage
import com.agentkosticka.amply.tutorial.TutorialWaitingScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.core.net.toUri

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
            lifecycleScope.launch { runtime.updateChecker.checkIfDue() }
        }
    }

    @Composable
    fun MainContent() {
        val introductionSeen by runtime.preferencesManager.isSetupIntroductionSeen.collectAsState(initial = false)
        val permissionState by appPermissionState.collectAsState()
        val shizukuPermission by runtime.shizukuRepository.permissionState.collectAsState()
        val connectionState by runtime.connectionState.collectAsState()
        val tutorialStage by runtime.tutorialCoordinator.stage.collectAsState()
        val runtimeHealth by runtime.runtimeHealth.collectAsState()
        val availableUpdate by runtime.updateChecker.availableUpdate.collectAsState()
        val readiness = SetupReadiness(
            accessibilityEnabled = permissionState.volumeKeysGranted,
            shizukuPermission = shizukuPermission,
            volumeServiceConnection = connectionState
        )

        Box(modifier = Modifier.fillMaxSize()) {
            if (readiness.canShowDashboard(introductionSeen)) {
                MainAppScreen()
            } else {
                val viewModel = remember {
                    SetupViewModel(
                        shizukuRepository = runtime.shizukuRepository,
                        context = this@MainActivity
                    )
                }

                SetupWizardScreen(
                    viewModel = viewModel,
                    introductionSeen = introductionSeen,
                    appPermissionState = permissionState,
                    connectionState = connectionState,
                    tutorialStage = tutorialStage,
                    overlayAttachFailed = runtimeHealth.recoverableError?.code ==
                        RuntimeErrorCode.OVERLAY_ATTACH_FAILED,
                    showRestrictedSettingsHelp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                    onAccessibilityClick = { openAccessibilitySettings() },
                    onRestrictedSettingsClick = { openAppDetailsSettings() },
                    onPhoneStateClick = { requestPhoneStatePermission() },
                    onNotificationsClick = { requestNotificationPermission() },
                    onRetryConnection = { runtime.retryVolumeServiceConnection() },
                    onArmTutorial = runtime.tutorialCoordinator::arm,
                    onTryItVisibilityChanged = runtime.tutorialCoordinator::setTryItScreenVisible,
                    onSkipTutorial = runtime.tutorialCoordinator::skip,
                    onSetupComplete = {}
                )
            }

            if (
                introductionSeen && readiness.requiredServicesReady &&
                tutorialStage == TutorialStage.WAITING_FOR_VOLUME_KEY
            ) {
                DisposableEffect(tutorialStage) {
                    runtime.tutorialCoordinator.setTryItScreenVisible(true)
                    onDispose { runtime.tutorialCoordinator.setTryItScreenVisible(false) }
                }
                TutorialWaitingScreen(
                    overlayAttachFailed = runtimeHealth.recoverableError?.code ==
                        RuntimeErrorCode.OVERLAY_ATTACH_FAILED,
                    onSkip = runtime.tutorialCoordinator::skip
                )
            }

            if (tutorialStage.isOverlayDemo && readiness.requiredServicesReady) {
                TutorialOverlayDemo(
                    stage = tutorialStage,
                    coordinator = runtime.tutorialCoordinator
                )
            }

            availableUpdate?.let { update ->
                if (
                    readiness.canShowDashboard(introductionSeen) &&
                    tutorialStage == TutorialStage.COMPLETED
                ) {
                    AlertDialog(
                        onDismissRequest = runtime.updateChecker::dismissAvailableUpdate,
                        title = {
                            Column {
                                Text(
                                    text = stringResource(R.string.update_available_eyebrow),
                                    color = com.agentkosticka.amply.ui.theme.NothingColors.Red,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = stringResource(
                                        R.string.update_available_title,
                                        update.version.toString()
                                    ),
                                    color = com.agentkosticka.amply.ui.theme.NothingColors.White,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        text = {
                            Text(
                                stringResource(R.string.update_available_message),
                                color = com.agentkosticka.amply.ui.theme.NothingColors.GreyMedium,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                runtime.updateChecker.dismissAvailableUpdate()
                                openReleasePage(update.releaseUrl)
                            }) {
                                Text(
                                    stringResource(R.string.update_view_release),
                                    color = com.agentkosticka.amply.ui.theme.NothingColors.Red,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = runtime.updateChecker::dismissAvailableUpdate) {
                                Text(
                                    stringResource(R.string.update_later),
                                    color = com.agentkosticka.amply.ui.theme.NothingColors.GreyMedium,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                        containerColor = Color(0xFF151515),
                        tonalElevation = 0.dp,
                        titleContentColor = com.agentkosticka.amply.ui.theme.NothingColors.White,
                        textContentColor = com.agentkosticka.amply.ui.theme.NothingColors.GreyMedium
                    )
                }
            }
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

    private fun openAppDetailsSettings() {
        val detailsIntent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        runCatching { startActivity(detailsIntent) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS)) }
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

    private fun openReleasePage(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

}
