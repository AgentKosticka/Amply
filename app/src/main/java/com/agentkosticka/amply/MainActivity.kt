package com.agentkosticka.amply

import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.agentkosticka.amply.ui.settings.SettingsDashboard
import com.agentkosticka.amply.data.AppPermissionState
import com.agentkosticka.amply.service.VolumeKeyService
import com.agentkosticka.amply.ui.setup.SetupViewModel
import com.agentkosticka.amply.ui.setup.SetupWizardScreen
import com.agentkosticka.amply.ui.theme.AmplyTheme
import com.agentkosticka.amply.ui.theme.NothingColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {

    private lateinit var runtime: AmplyRuntime
    private val _appPermissionState = MutableStateFlow(AppPermissionState())
    private val appPermissionState = _appPermissionState.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen BEFORE super.onCreate()
        installSplashScreen()
        
        super.onCreate(savedInstanceState)

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
        val isSetupCompleted by runtime.preferencesManager.isSetupCompleted.collectAsState(initial = false)

        if (isSetupCompleted) {
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
            onOverlayPermissionClick = { requestOverlayPermission() },
            onAccessibilityClick = { openAccessibilitySettings() }
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
            overlayGranted = Settings.canDrawOverlays(this),
            volumeKeysGranted = volumeKeysGranted
        )
    }

    @Composable
    fun SetupStep(
        number: String,
        title: String,
        description: String,
        onClick: () -> Unit
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = NothingColors.GreyContainer,
                contentColor = NothingColors.White
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Red number badge
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(NothingColors.Red, RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = number,
                        style = MaterialTheme.typography.titleMedium,
                        color = NothingColors.White
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = NothingColors.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = NothingColors.GreyMedium
                    )
                }
            }
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            startActivity(intent)
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

}
