package com.agentkosticka.amply.setup

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.agentkosticka.amply.R
import com.agentkosticka.amply.permissions.AppPermissionState
import com.agentkosticka.amply.shizuku.client.ShizukuPermissionState
import com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState
import com.agentkosticka.amply.ui.theme.NothingColors

private const val WELCOME_STEP = 0
private const val ACCESSIBILITY_STEP = 1
private const val SHIZUKU_STEP = 2
private const val SETUP_STEP_COUNT = 3

@Composable
fun SetupWizardScreen(
    viewModel: SetupViewModel,
    introductionSeen: Boolean,
    appPermissionState: AppPermissionState,
    connectionState: VolumeServiceConnectionState,
    onAccessibilityClick: () -> Unit,
    onRetryConnection: () -> Unit,
    onSetupComplete: () -> Unit
) {
    val shizukuState by viewModel.permissionState.collectAsState()
    val externalError by viewModel.externalActionError.collectAsState()
    val shizukuReady = shizukuState == ShizukuPermissionState.GRANTED &&
        connectionState == VolumeServiceConnectionState.CONNECTED
    var step by rememberSaveable {
        mutableIntStateOf(if (introductionSeen) ACCESSIBILITY_STEP else WELCOME_STEP)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NothingColors.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        SetupHeader(step)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (step) {
                WELCOME_STEP -> WelcomeStep()
                ACCESSIBILITY_STEP -> AccessibilityStep(
                    enabled = appPermissionState.volumeKeysGranted
                )
                else -> ShizukuStep(
                    state = shizukuState,
                    connected = shizukuReady,
                    onAction = {
                        when (shizukuState) {
                            ShizukuPermissionState.SHIZUKU_NOT_INSTALLED -> viewModel.openShizukuDownload()
                            ShizukuPermissionState.SHIZUKU_NOT_RUNNING -> viewModel.openShizukuApp()
                            ShizukuPermissionState.GRANTED -> onRetryConnection()
                            else -> viewModel.requestShizukuPermission()
                        }
                    },
                    onRefresh = viewModel::checkShizukuState
                )
            }

            externalError?.let { message ->
                Spacer(Modifier.height(18.dp))
                Text(message, color = NothingColors.Red, textAlign = TextAlign.Center)
                OutlinedButton(onClick = viewModel::clearExternalActionError) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        }

        Button(
            onClick = {
                when (step) {
                    WELCOME_STEP -> step = ACCESSIBILITY_STEP
                    ACCESSIBILITY_STEP -> {
                        if (appPermissionState.volumeKeysGranted) step = SHIZUKU_STEP
                        else onAccessibilityClick()
                    }
                    else -> {
                        viewModel.completeSetup(appPermissionState.volumeKeysGranted)
                        onSetupComplete()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(30.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = NothingColors.Red,
                contentColor = NothingColors.White
            )
        ) {
            Text(
                text = when (step) {
                    WELCOME_STEP -> stringResource(R.string.setup_get_started)
                    ACCESSIBILITY_STEP -> if (appPermissionState.volumeKeysGranted) {
                        stringResource(R.string.setup_next)
                    } else {
                        stringResource(R.string.setup_enable_amply)
                    }
                    else -> if (shizukuReady) {
                        stringResource(R.string.setup_enter_amply)
                    } else {
                        stringResource(R.string.setup_continue_without_shizuku)
                    }
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SetupHeader(step: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.ic_amply_logo),
            contentDescription = null,
            modifier = Modifier.size(42.dp)
        )
        Text(
            text = stringResource(R.string.setup_title),
            style = MaterialTheme.typography.titleLarge,
            color = NothingColors.White,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.setup_step_progress, step + 1, SETUP_STEP_COUNT),
            color = NothingColors.GreyMedium,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun WelcomeStep() {
    Spacer(Modifier.height(16.dp))
    Image(
        painter = painterResource(R.drawable.ic_amply_logo),
        contentDescription = null,
        modifier = Modifier.size(112.dp)
    )
    Spacer(Modifier.height(28.dp))
    Text(
        text = stringResource(R.string.setup_welcome_title),
        style = MaterialTheme.typography.displaySmall,
        color = NothingColors.White,
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.setup_welcome_body),
        style = MaterialTheme.typography.bodyLarge,
        color = NothingColors.GreyMedium,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun AccessibilityStep(enabled: Boolean) {
    StatusDot(complete = enabled)
    Spacer(Modifier.height(22.dp))
    Text(
        text = if (enabled) {
            stringResource(R.string.setup_accessibility_ready_title)
        } else {
            stringResource(R.string.setup_accessibility_welcome_title)
        },
        style = MaterialTheme.typography.headlineMedium,
        color = NothingColors.White,
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.setup_accessibility_consumer_description),
        style = MaterialTheme.typography.bodyLarge,
        color = NothingColors.GreyMedium,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(20.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF171717)),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.setup_accessibility_privacy),
            modifier = Modifier.padding(18.dp),
            color = NothingColors.White,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ShizukuStep(
    state: ShizukuPermissionState,
    connected: Boolean,
    onAction: () -> Unit,
    onRefresh: () -> Unit
) {
    StatusDot(complete = connected)
    Spacer(Modifier.height(22.dp))
    Text(
        text = stringResource(R.string.setup_shizuku_optional_welcome_title),
        style = MaterialTheme.typography.headlineMedium,
        color = NothingColors.White,
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.setup_shizuku_consumer_description),
        style = MaterialTheme.typography.bodyLarge,
        color = NothingColors.GreyMedium,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(20.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF171717)),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = if (connected) {
                    stringResource(R.string.setup_shizuku_ready_simple)
                } else {
                    stringResource(R.string.setup_shizuku_without_warning)
                },
                color = if (connected) NothingColors.White else NothingColors.GreyMedium,
                style = MaterialTheme.typography.bodyMedium
            )
            if (!connected) {
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when (state) {
                            ShizukuPermissionState.SHIZUKU_NOT_INSTALLED -> stringResource(R.string.setup_install_shizuku)
                            ShizukuPermissionState.SHIZUKU_NOT_RUNNING -> stringResource(R.string.setup_open_shizuku)
                            ShizukuPermissionState.GRANTED -> stringResource(R.string.setup_retry_connection)
                            else -> stringResource(R.string.setup_connect_shizuku)
                        }
                    )
                }
                OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setup_refresh))
                }
            }
        }
    }
}

@Composable
private fun StatusDot(complete: Boolean) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(if (complete) NothingColors.White else NothingColors.Red, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (complete) "✓" else "(1)",
            color = NothingColors.Black,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}
