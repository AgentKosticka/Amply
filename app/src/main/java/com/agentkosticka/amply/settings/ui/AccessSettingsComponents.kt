package com.agentkosticka.amply.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentkosticka.amply.audio.ringer.NotificationAlertMode
import com.agentkosticka.amply.audio.ringer.RingerExperimentMethod
import com.agentkosticka.amply.audio.ringer.RingerMethodTestResult
import com.agentkosticka.amply.ui.theme.NothingColors


@Composable
internal fun RingerExperimentPanel(
    selected: RingerExperimentMethod,
    results: Map<RingerExperimentMethod, RingerMethodTestResult>,
    progress: Map<RingerExperimentMethod, Float>,
    runningMethod: RingerExperimentMethod?,
    busy: Boolean,
    shizukuConnected: Boolean,
    notificationPolicyGranted: Boolean,
    onNotificationPolicyClick: () -> Unit,
    onSelect: (RingerExperimentMethod) -> Unit,
    onTest: (RingerExperimentMethod) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var explainedMethod by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsPanel(modifier = Modifier.clickable { expanded = !expanded }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("RINGER MODE COMPATIBILITY", color = NothingColors.White, fontWeight = FontWeight.Bold)
                    Text("Device-specific control checks", color = NothingColors.GreyMedium)
                }
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Hide ringer tests" else "Show ringer tests",
                    tint = NothingColors.GreyMedium,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f)
                )
            }
        }

        if (expanded) {
            CompatibilityPermissionRow(
                granted = notificationPolicyGranted,
                onClick = onNotificationPolicyClick
            )
            SettingsPanel {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Experimental controls warning",
                        tint = NothingColors.Red,
                        modifier = Modifier.size(32.dp)
                    )
                    Column {
                        Text(
                            "FOR TROUBLESHOOTING ONLY",
                            color = NothingColors.Red,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Run these checks only if silent, vibrate, or loud mode is not behaving correctly. Some methods can enable DND or interfere with volume keys; Amply will disable DND if a check turns it on.",
                            color = NothingColors.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "The selected method is used by the overlay’s ringer icon for this session.",
                            color = NothingColors.GreyMedium,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        if (expanded) RingerExperimentMethod.entries.forEach { method ->
            val enabled = !busy && (!method.requiresShizuku || shizukuConnected)
            val result = results[method]
            val methodProgress = progress[method] ?: 0f
            val isRunning = runningMethod == method
            SettingsPanel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier.size(34.dp).background(NothingColors.Red, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("E${method.number}", color = NothingColors.White, fontWeight = FontWeight.Bold)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(method.title.uppercase(), color = NothingColors.White, fontWeight = FontWeight.Bold)
                        if (method.requiresShizuku) {
                            Text("Requires Shizuku", color = NothingColors.GreyMedium, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    IconButton(
                        onClick = {
                            explainedMethod = if (explainedMethod == method.name) null else method.name
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "What E${method.number} tests",
                            tint = NothingColors.GreyMedium,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Button(
                        onClick = { onSelect(method) },
                        enabled = enabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected == method) NothingColors.Red else DarkControlBackground,
                            contentColor = NothingColors.White,
                            disabledContainerColor = DarkControlBackground.copy(alpha = 0.35f),
                            disabledContentColor = NothingColors.White.copy(alpha = 0.5f)
                        )
                    ) { Text(if (selected == method) "SELECTED" else "SELECT") }
                }
                if (explainedMethod == method.name) {
                    Spacer(Modifier.height(6.dp))
                    Text(method.description, color = NothingColors.GreyMedium, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onTest(method) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DarkControlBackground,
                        contentColor = NothingColors.White,
                        disabledContainerColor = DarkControlBackground.copy(alpha = 0.35f),
                        disabledContentColor = NothingColors.White.copy(alpha = 0.5f)
                    )
                ) { Text(if (isRunning) "TESTING…" else "TEST ALL TRANSITIONS") }
                if (isRunning) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { methodProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = NothingColors.Red,
                        trackColor = NothingColors.GreyDim
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Testing ${(methodProgress * 6).toInt().coerceIn(0, 6)} of 6 transitions",
                        color = NothingColors.GreyMedium,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (result != null) {
                    Spacer(Modifier.height(8.dp))
                    if (result.detail.isNotBlank()) {
                        Text(result.detail, color = NothingColors.Red, style = MaterialTheme.typography.bodySmall)
                    }
                    result.transitions.forEach { transition ->
                        Text(
                            "${transition.from.readableName()} → ${transition.to.readableName()}: ${transition.detail}",
                            color = if (transition.passed) NothingColors.GreyMedium else NothingColors.Red,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompatibilityPermissionRow(
    granted: Boolean,
    onClick: () -> Unit
) {
    SettingsPanel {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        if (granted) NothingColors.GreyMedium else NothingColors.Red,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = NothingColors.White,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "NOTIFICATION MODES",
                    color = NothingColors.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Only needed by alternate methods; E7 does not require it",
                    color = NothingColors.GreyMedium,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (granted) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Granted",
                    tint = NothingColors.GreyMedium,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

private fun NotificationAlertMode.readableName(): String = when (this) {
    NotificationAlertMode.MUTED -> "Muted"
    NotificationAlertMode.VIBRATIONS -> "Vibrate"
    NotificationAlertMode.LOUD -> "Loud"
}

