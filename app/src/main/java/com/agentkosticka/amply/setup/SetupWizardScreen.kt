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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.agentkosticka.amply.tutorial.TutorialStage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp

private const val WELCOME_STEP = 0
private const val ACCESSIBILITY_STEP = 1
private const val SHIZUKU_STEP = 2
private const val CALLS_STEP = 3
private const val NOTIFICATIONS_STEP = 4
private const val TRY_IT_STEP = 5
private const val SETUP_STEP_COUNT = 6

@Composable
internal fun SetupWizardScreen(
    viewModel: SetupViewModel,
    introductionSeen: Boolean,
    appPermissionState: AppPermissionState,
    connectionState: VolumeServiceConnectionState,
    tutorialStage: TutorialStage,
    overlayAttachFailed: Boolean,
    showRestrictedSettingsHelp: Boolean,
    onAccessibilityClick: () -> Unit,
    onRestrictedSettingsClick: () -> Unit,
    onPhoneStateClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onRetryConnection: () -> Unit,
    onArmTutorial: () -> Unit,
    onTryItVisibilityChanged: (Boolean) -> Unit,
    onSkipTutorial: () -> Unit,
    onSetupComplete: () -> Unit
) {
    val shizukuState by viewModel.permissionState.collectAsState()
    val externalError by viewModel.externalActionError.collectAsState()
    val shizukuReady = shizukuState == ShizukuPermissionState.GRANTED &&
        connectionState == VolumeServiceConnectionState.CONNECTED
    var step by rememberSaveable {
        mutableIntStateOf(
            if (
                tutorialStage != TutorialStage.NOT_STARTED &&
                !appPermissionState.volumeKeysGranted
            ) {
                ACCESSIBILITY_STEP
            } else if (introductionSeen) {
                ACCESSIBILITY_STEP
            } else if (tutorialStage == TutorialStage.WAITING_FOR_VOLUME_KEY || tutorialStage.isOverlayDemo) {
                TRY_IT_STEP
            } else {
                WELCOME_STEP
            }
        )
    }

    LaunchedEffect(step, tutorialStage) {
        if (step == TRY_IT_STEP && tutorialStage == TutorialStage.NOT_STARTED) {
            onArmTutorial()
        }
    }
    LaunchedEffect(tutorialStage, appPermissionState.volumeKeysGranted, introductionSeen) {
        if (
            !introductionSeen && appPermissionState.volumeKeysGranted &&
            (tutorialStage == TutorialStage.WAITING_FOR_VOLUME_KEY || tutorialStage.isOverlayDemo)
        ) {
            step = TRY_IT_STEP
        } else if (
            tutorialStage != TutorialStage.NOT_STARTED &&
            !appPermissionState.volumeKeysGranted
        ) {
            step = ACCESSIBILITY_STEP
        }
    }
    DisposableEffect(step) {
        val visible = step == TRY_IT_STEP
        onTryItVisibilityChanged(visible)
        onDispose { if (visible) onTryItVisibilityChanged(false) }
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
                    enabled = appPermissionState.volumeKeysGranted,
                    showRestrictedSettingsHelp = showRestrictedSettingsHelp,
                    onRestrictedSettingsClick = onRestrictedSettingsClick
                )
                SHIZUKU_STEP -> ShizukuStep(
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
                CALLS_STEP -> OptionalPermissionStep(
                    granted = appPermissionState.phoneStateGranted,
                    title = stringResource(R.string.setup_calls_welcome_title),
                    readyTitle = stringResource(R.string.setup_calls_ready_title),
                    description = stringResource(R.string.setup_calls_consumer_description),
                    privacy = stringResource(R.string.setup_calls_privacy)
                )
                NOTIFICATIONS_STEP -> OptionalPermissionStep(
                    granted = appPermissionState.notificationsGranted,
                    title = stringResource(R.string.setup_notifications_welcome_title),
                    readyTitle = stringResource(R.string.setup_notifications_ready_title),
                    description = stringResource(R.string.setup_notifications_consumer_description),
                    privacy = stringResource(R.string.setup_notifications_privacy)
                )
                TRY_IT_STEP -> TryItStep(overlayAttachFailed = overlayAttachFailed)
            }

            externalError?.let { message ->
                Spacer(Modifier.height(18.dp))
                Text(message, color = NothingColors.Red, textAlign = TextAlign.Center)
                OutlinedButton(onClick = viewModel::clearExternalActionError) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        }

        val optionalPermissionMissing =
            (step == CALLS_STEP && !appPermissionState.phoneStateGranted) ||
                (step == NOTIFICATIONS_STEP && !appPermissionState.notificationsGranted)

        Button(
            onClick = {
                when (step) {
                    WELCOME_STEP -> step = ACCESSIBILITY_STEP
                    ACCESSIBILITY_STEP -> {
                        if (appPermissionState.volumeKeysGranted) step = SHIZUKU_STEP
                        else onAccessibilityClick()
                    }
                    SHIZUKU_STEP -> step = CALLS_STEP
                    CALLS_STEP -> {
                        if (appPermissionState.phoneStateGranted) step = NOTIFICATIONS_STEP
                        else onPhoneStateClick()
                    }
                    NOTIFICATIONS_STEP -> {
                        if (!appPermissionState.notificationsGranted) {
                            onNotificationsClick()
                            return@Button
                        }
                        step = TRY_IT_STEP
                        onArmTutorial()
                    }
                    TRY_IT_STEP -> Unit
                }
            },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            enabled = step != TRY_IT_STEP,
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
                    SHIZUKU_STEP -> if (shizukuReady) {
                        stringResource(R.string.setup_next)
                    } else {
                        stringResource(R.string.setup_continue_without_shizuku)
                    }
                    CALLS_STEP -> if (appPermissionState.phoneStateGranted) {
                        stringResource(R.string.setup_next)
                    } else {
                        stringResource(R.string.setup_allow_calls)
                    }
                    NOTIFICATIONS_STEP -> if (appPermissionState.notificationsGranted) {
                        stringResource(R.string.setup_next)
                    } else {
                        stringResource(R.string.setup_allow_notifications)
                    }
                    else -> stringResource(R.string.setup_waiting_for_volume_button)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        if (optionalPermissionMissing) {
            TextButton(
                onClick = {
                    if (step == CALLS_STEP) {
                        step = NOTIFICATIONS_STEP
                    } else {
                        step = TRY_IT_STEP
                        onArmTutorial()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.setup_not_now),
                    color = NothingColors.GreyMedium
                )
            }
        }
        if (step == TRY_IT_STEP) {
            TextButton(
                onClick = {
                    onTryItVisibilityChanged(false)
                    onSkipTutorial()
                    onSetupComplete()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.tutorial_skip),
                    color = NothingColors.GreyMedium
                )
            }
        }
    }
}

@Composable
private fun TryItStep(overlayAttachFailed: Boolean) {
    Box(
        modifier = Modifier
            .size(88.dp)
            .background(NothingColors.Red, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            tint = NothingColors.White,
            modifier = Modifier.size(44.dp)
        )
    }
    Spacer(Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.tutorial_try_it_title),
        style = MaterialTheme.typography.headlineMedium,
        color = NothingColors.White,
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.tutorial_try_it_description),
        style = MaterialTheme.typography.bodyLarge,
        color = NothingColors.GreyMedium,
        textAlign = TextAlign.Center
    )
    if (overlayAttachFailed) {
        Spacer(Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF271717)),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.tutorial_overlay_retry),
                modifier = Modifier.padding(18.dp),
                color = NothingColors.White,
                textAlign = TextAlign.Center
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
private fun AccessibilityStep(
    enabled: Boolean,
    showRestrictedSettingsHelp: Boolean,
    onRestrictedSettingsClick: () -> Unit
) {
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
    if (!enabled && showRestrictedSettingsHelp) {
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.setup_restricted_settings_title),
            color = NothingColors.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.setup_restricted_settings_description),
            color = NothingColors.GreyMedium,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onRestrictedSettingsClick) {
            Text(stringResource(R.string.setup_open_app_info))
        }
    }
}

@Composable
private fun OptionalPermissionStep(
    granted: Boolean,
    title: String,
    readyTitle: String,
    description: String,
    privacy: String
) {
    StatusDot(complete = granted)
    Spacer(Modifier.height(22.dp))
    Text(
        text = if (granted) readyTitle else title,
        style = MaterialTheme.typography.headlineMedium,
        color = NothingColors.White,
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = description,
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
            text = privacy,
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
