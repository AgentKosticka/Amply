package com.agentkosticka.amply.settings.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.agentkosticka.amply.settings.model.AmplyPauseDuration
import com.agentkosticka.amply.ui.theme.NothingColors


internal fun loadLaunchableApps(context: Context): List<InstalledAppEntry> {
    val packageManager = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        .mapNotNull { resolveInfo ->
            val applicationInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
            val packageName = applicationInfo.packageName
            if (packageName == context.packageName) return@mapNotNull null
            InstalledAppEntry(
                packageName = packageName,
                appName = packageManager.getApplicationLabel(applicationInfo).toString(),
                uid = applicationInfo.uid
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.appName.lowercase() }
}

@Composable
internal fun VolumeKeyHandlingPanel(
    pauseDuration: AmplyPauseDuration,
    pausedUntilEpochMs: Long,
    currentTimeEpochMs: Long,
    pickerExpanded: Boolean,
    onDurationSelected: (AmplyPauseDuration) -> Unit,
    onRestoreNow: () -> Unit,
    onPickerToggle: () -> Unit
) {
    val isManualPause = pausedUntilEpochMs == Long.MAX_VALUE
    val remainingSeconds = if (isManualPause) 0L else
        ((pausedUntilEpochMs - currentTimeEpochMs).coerceAtLeast(0L) + 999L) / 1_000L
    val isPaused = isManualPause || remainingSeconds > 0

    SettingsPanel {
        if (isPaused) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(NothingColors.Red.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = NothingColors.Red
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("AMPLY IS PAUSED", color = NothingColors.White, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (isManualPause) {
                            "Until restored manually"
                        } else {
                            "${remainingSeconds / 60}:${(remainingSeconds % 60).toString().padStart(2, '0')} remaining"
                        },
                        color = NothingColors.GreyMedium,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Button(
                    onClick = onRestoreNow,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NothingColors.Red,
                        contentColor = NothingColors.White
                    )
                ) {
                    Text("RESTORE NOW", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            "EXPANDED-PILL PAUSE DURATION",
            color = NothingColors.GreyMedium,
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AmplyPauseDuration.entries.filter { it.minutes != null }.forEach { duration ->
                Button(
                    onClick = { onDurationSelected(duration) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (pauseDuration == duration) NothingColors.Red else DarkControlBackground,
                        contentColor = NothingColors.White
                    )
                ) {
                    Text("${duration.minutes}m", fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onDurationSelected(AmplyPauseDuration.MANUAL) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (pauseDuration == AmplyPauseDuration.MANUAL) NothingColors.Red else DarkControlBackground,
                contentColor = NothingColors.White
            )
        ) {
            Text(
                "TURN BACK ON ONLY MANUALLY",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Apps selected below receive volume-button presses directly. Amply handles every other app unless it is temporarily paused.",
            color = NothingColors.GreyMedium,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onPickerToggle,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = DarkControlBackground,
                contentColor = NothingColors.White
            )
        ) {
            Text(if (pickerExpanded) "HIDE APP LIST" else "CHOOSE STAND-DOWN APPS", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun StandDownAppRow(
    app: InstalledAppEntry,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    val icon = rememberApplicationIconBitmap(app.packageName, bitmapSizePx = 80)
    SettingsPanel {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEnabledChange(!enabled) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(Color(0xFF303030), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                icon?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                } ?: Icon(Icons.Default.MusicNote, contentDescription = null, tint = NothingColors.GreyMedium)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(app.appName, color = NothingColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    app.packageName,
                    color = NothingColors.GreyMedium,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Let app use volume buttons",
                    color = NothingColors.GreyMedium,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = NothingColors.White,
                    checkedTrackColor = NothingColors.Red,
                    uncheckedThumbColor = NothingColors.GreyMedium,
                    uncheckedTrackColor = Color(0xFF333333)
                )
            )
        }
    }
}

internal fun openShizukuApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
    if (intent != null) {
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    } else {
        openShizukuDownload(context)
    }
}

internal fun openShizukuDownload(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        data = "https://github.com/RikkaApps/Shizuku/releases".toUri()
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)
}

