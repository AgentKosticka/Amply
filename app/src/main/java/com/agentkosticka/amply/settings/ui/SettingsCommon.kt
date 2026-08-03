package com.agentkosticka.amply.settings.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentkosticka.amply.R
import com.agentkosticka.amply.settings.model.AppSettingsStoreHealth
import com.agentkosticka.amply.shizuku.client.ShizukuPermissionState
import com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState
import com.agentkosticka.amply.ui.theme.NothingColors


@Composable
internal fun DashboardHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Image(
            painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_amply_logo),
            contentDescription = "Amply logo",
            modifier = Modifier.size(54.dp)
        )
        Column {
            Text(
                text = "AMPLY",
                style = MaterialTheme.typography.displayMedium,
                color = NothingColors.White
            )
            Text(
                text = "CONTROL CENTER",
                style = MaterialTheme.typography.labelSmall,
                color = NothingColors.GreyMedium
            )
        }
    }
}
@Composable
internal fun ShizukuWarningCard(
    permissionState: ShizukuPermissionState,
    connectionState: VolumeServiceConnectionState,
    onAction: () -> Unit,
    onRefresh: () -> Unit
) {
    val title = when (permissionState) {
        ShizukuPermissionState.SHIZUKU_NOT_INSTALLED -> "SHIZUKU NOT INSTALLED"
        ShizukuPermissionState.SHIZUKU_NOT_RUNNING -> "SHIZUKU NOT RUNNING"
        ShizukuPermissionState.DENIED -> "SHIZUKU PERMISSION DENIED"
        ShizukuPermissionState.UNKNOWN -> "CHECKING SHIZUKU"
        ShizukuPermissionState.GRANTED -> when (connectionState) {
            VolumeServiceConnectionState.BINDING -> "RECONNECTING TO SHIZUKU"
            VolumeServiceConnectionState.PROTOCOL_MISMATCH -> "SHIZUKU SERVICE NEEDS AN UPGRADE"
            else -> "SHIZUKU CONNECTION LOST"
        }
        else -> "SHIZUKU NOT CONNECTED"
    }
    val description = when (permissionState) {
        ShizukuPermissionState.SHIZUKU_NOT_INSTALLED -> "Install Shizuku to enable per-app volume control."
        ShizukuPermissionState.SHIZUKU_NOT_RUNNING -> "Start Shizuku, then return here to reconnect Amply."
        ShizukuPermissionState.NOT_GRANTED,
        ShizukuPermissionState.SHOULD_SHOW_RATIONALE,
        ShizukuPermissionState.DENIED -> "Shizuku is running. Grant Amply access to restore per-app volume control."
        ShizukuPermissionState.UNKNOWN -> "Refresh the Shizuku connection state."
        ShizukuPermissionState.GRANTED -> if (
            connectionState == VolumeServiceConnectionState.PROTOCOL_MISMATCH
        ) {
            "Amply and its privileged service use different protocols. Update Amply, then retry."
        } else {
            "Amply is restoring per-app volume control."
        }
    }
    val actionText = when (permissionState) {
        ShizukuPermissionState.SHIZUKU_NOT_INSTALLED -> "INSTALL"
        ShizukuPermissionState.SHIZUKU_NOT_RUNNING -> "OPEN SHIZUKU"
        ShizukuPermissionState.UNKNOWN -> "CHECK"
        ShizukuPermissionState.GRANTED -> "RETRY"
        else -> "REQUEST"
    }

    SettingsPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(NothingColors.Red.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Shizuku warning",
                    tint = NothingColors.Red,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = NothingColors.White
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NothingColors.GreyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onAction,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NothingColors.Red,
                    contentColor = NothingColors.White
                )
            ) {
                Text(text = actionText, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onRefresh,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2A2A2A),
                    contentColor = NothingColors.White
                )
            ) {
                Text(text = "REFRESH", fontWeight = FontWeight.Bold)
            }
        }
    }
}
@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = NothingColors.Red,
        fontWeight = FontWeight.Bold
    )
}

@Composable
internal fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1C), RoundedCornerShape(18.dp))
            .padding(14.dp),
        content = content
    )
}

@Composable
internal fun DataRecoveryPanel(
    health: AppSettingsStoreHealth,
    staleAppCount: Int,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onCleanup: () -> Unit,
    onRepair: () -> Unit,
    onReset: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SettingsPanel {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("SETTINGS STORAGE", color = NothingColors.White, fontWeight = FontWeight.Bold)
                Text(
                    text = when (health) {
                        AppSettingsStoreHealth.HEALTHY -> "Export, import, clean up, or reset"
                        AppSettingsStoreHealth.RECOVERED_FROM_BACKUP -> "Recovered from a previous-good backup"
                        AppSettingsStoreHealth.CORRUPT -> "App settings need recovery"
                    },
                    color = if (health == AppSettingsStoreHealth.CORRUPT) NothingColors.Red else NothingColors.GreyMedium,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide data tools" else "Show data tools",
                tint = NothingColors.GreyMedium,
                modifier = Modifier.rotate(if (expanded) 180f else 0f)
            )
        }
        if (expanded) {
            Spacer(Modifier.height(14.dp))
            MaintenanceButton("EXPORT SETTINGS", onClick = onExport)
            Spacer(Modifier.height(8.dp))
            MaintenanceButton("IMPORT SETTINGS", onClick = onImport)
            Spacer(Modifier.height(8.dp))
            MaintenanceButton("CLEAN STALE APPS · $staleAppCount", onClick = onCleanup)
            Spacer(Modifier.height(8.dp))
            if (health == AppSettingsStoreHealth.CORRUPT) {
                MaintenanceButton("REPAIR APP SETTINGS", destructive = true, onClick = onRepair)
                Spacer(Modifier.height(8.dp))
            }
            MaintenanceButton("RESET AMPLY", destructive = true, onClick = onReset)
        }
    }
}

@Composable
private fun MaintenanceButton(
    text: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(42.dp),
        shape = RoundedCornerShape(13.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = DarkControlBackground,
            contentColor = if (destructive) NothingColors.Red else NothingColors.White
        )
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun PermissionButton(
    number: String,
    title: String,
    description: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(74.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = NothingColors.GreyContainer,
            contentColor = NothingColors.White
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(if (granted) NothingColors.GreyMedium else NothingColors.Red, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = number,
                    color = NothingColors.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.labelLarge)
                Text(text = description, style = MaterialTheme.typography.bodyMedium, color = NothingColors.GreyMedium)
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
