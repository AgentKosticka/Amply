package com.agentkosticka.amply.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import com.agentkosticka.amply.AmplyRuntime
import com.agentkosticka.amply.R
import com.agentkosticka.amply.data.AudioSession
import com.agentkosticka.amply.data.AudioSessionState
import com.agentkosticka.amply.data.AppSettings
import com.agentkosticka.amply.data.OverlaySide
import com.agentkosticka.amply.shizuku.ShizukuPermissionState
import com.agentkosticka.amply.shizuku.VolumeServiceConnectionState
import com.agentkosticka.amply.ui.theme.NothingColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class AppListMode {
    DEFAULT,
    EXPANDED
}

private data class InstalledAppEntry(
    val packageName: String,
    val appName: String,
    val uid: Int
)

@Composable
fun SettingsDashboard(
    runtime: AmplyRuntime,
    onOverlayPermissionClick: () -> Unit,
    onAccessibilityClick: () -> Unit
) {
    val context = LocalContext.current
    val preferencesManager = runtime.preferencesManager
    val shizukuRepository = runtime.shizukuRepository

    val overlaySide by preferencesManager.overlaySide.collectAsState(initial = OverlaySide.LEFT)
    val verticalFraction by preferencesManager.overlayVerticalFraction.collectAsState(initial = 0.5f)
    val appSettings by preferencesManager.appSettings.collectAsState(initial = emptyMap())
    val pauseDurationMinutes by preferencesManager.amplyPauseDurationMinutes.collectAsState(initial = 5)
    val pausedUntilEpochMs by preferencesManager.amplyPausedUntilEpochMs.collectAsState(initial = 0L)
    val shizukuState by shizukuRepository.permissionState.collectAsState(initial = ShizukuPermissionState.UNKNOWN)
    val connectionState by runtime.connectionState.collectAsState(
        initial = VolumeServiceConnectionState.WAITING_FOR_PERMISSION
    )
    val sessionState by runtime.sessionState.collectAsState(initial = AudioSessionState.empty())
    val scope = rememberCoroutineScope()
    val inactiveVolumeSaveJobs = remember { mutableMapOf<String, Job>() }
    var positionSaveJob by remember { mutableStateOf<Job?>(null) }
    var previewVerticalFraction by remember { mutableFloatStateOf(verticalFraction) }
    var appListMode by remember { mutableStateOf(AppListMode.DEFAULT) }
    var standDownPickerExpanded by remember { mutableStateOf(false) }
    var standDownSearch by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf<List<InstalledAppEntry>>(emptyList()) }
    var currentTimeEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var orderedPackageNames by remember(appListMode) { mutableStateOf<List<String>>(emptyList()) }
    val now = System.currentTimeMillis()
    val activeSessionsByPackage = sessionState.sessions.associateBy { it.packageName }
    val activePackageNames = activeSessionsByPackage.keys
    val allApps = mergeAppSettingsWithActiveSessions(
        appSettings = appSettings,
        activeSessions = sessionState.sessions
    )
    val candidateApps = if (appListMode == AppListMode.DEFAULT) {
        allApps.filter { it.packageName in activePackageNames }
    } else {
        allApps.filter { it.packageName in activePackageNames || it.isCustomized || now - it.lastSeenTimestamp <= 60_000L }
    }
    val candidatePackageNames = candidateApps.map { it.packageName }.toSet()
    val nextOrderedPackageNames = stablePackageOrder(
        currentOrder = orderedPackageNames,
        candidateApps = candidateApps
    )
    val appsByPackage = candidateApps.associateBy { it.packageName }
    val apps = nextOrderedPackageNames.mapNotNull { packageName ->
        appsByPackage[packageName]
    }
    val standDownApps = remember(installedApps, appSettings, standDownSearch) {
        val query = standDownSearch.trim().lowercase()
        installedApps
            .filter { query.isEmpty() || query in it.appName.lowercase() || query in it.packageName.lowercase() }
            .sortedWith(
                compareByDescending<InstalledAppEntry> { appSettings[it.packageName]?.passVolumeKeysToApp == true }
                    .thenBy { it.appName.lowercase() }
            )
    }

    LaunchedEffect(appListMode, candidatePackageNames) {
        if (orderedPackageNames != nextOrderedPackageNames) {
            orderedPackageNames = nextOrderedPackageNames
        }
    }

    LaunchedEffect(Unit) {
        shizukuRepository.checkPermissionState()
    }

    LaunchedEffect(verticalFraction) {
        previewVerticalFraction = verticalFraction
    }

    LaunchedEffect(standDownPickerExpanded) {
        if (standDownPickerExpanded && installedApps.isEmpty()) {
            installedApps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
        }
    }

    LaunchedEffect(pausedUntilEpochMs) {
        currentTimeEpochMs = System.currentTimeMillis()
        while (pausedUntilEpochMs > currentTimeEpochMs) {
            delay(1_000L)
            currentTimeEpochMs = System.currentTimeMillis()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            inactiveVolumeSaveJobs.values.forEach { it.cancel() }
            inactiveVolumeSaveJobs.clear()
            positionSaveJob?.cancel()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(NothingColors.Black)
            .padding(horizontal = 24.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            DashboardHeader()
        }

        if (shizukuState != ShizukuPermissionState.GRANTED ||
            connectionState != VolumeServiceConnectionState.CONNECTED
        ) {
            item {
                ShizukuWarningCard(
                    permissionState = shizukuState,
                    connectionState = connectionState,
                    onAction = {
                        when (shizukuState) {
                            ShizukuPermissionState.NOT_GRANTED,
                            ShizukuPermissionState.SHOULD_SHOW_RATIONALE,
                            ShizukuPermissionState.DENIED -> {
                                shizukuRepository.requestPermission()
                            }
                            ShizukuPermissionState.SHIZUKU_NOT_RUNNING -> {
                                openShizukuApp(context)
                            }
                            ShizukuPermissionState.SHIZUKU_NOT_INSTALLED -> {
                                openShizukuDownload(context)
                            }
                            ShizukuPermissionState.UNKNOWN -> {
                                shizukuRepository.checkPermissionState()
                            }
                            ShizukuPermissionState.GRANTED -> {
                                runtime.retryVolumeServiceConnection()
                            }
                        }
                    },
                    onRefresh = {
                        shizukuRepository.checkPermissionState()
                        if (shizukuState == ShizukuPermissionState.GRANTED) {
                            runtime.retryVolumeServiceConnection()
                        }
                    }
                )
            }
        }

        item {
            SectionTitle("PERMISSIONS")
            Spacer(modifier = Modifier.height(10.dp))
            PermissionButton(
                number = "1",
                title = "OVERLAY",
                description = "Grant overlay access",
                onClick = onOverlayPermissionClick
            )
            Spacer(modifier = Modifier.height(10.dp))
            PermissionButton(
                number = "2",
                title = "VOLUME KEYS",
                description = "Enable accessibility service",
                onClick = onAccessibilityClick
            )
        }

        item {
            SectionTitle("OVERLAY POSITION")
            Spacer(modifier = Modifier.height(10.dp))
            SettingsPanel {
                SideSelector(
                    selected = overlaySide,
                    onSelected = { side ->
                        scope.launch { preferencesManager.setOverlaySide(side) }
                    }
                )
                Spacer(modifier = Modifier.height(18.dp))
                PositionPreview(
                    side = overlaySide,
                    verticalFraction = previewVerticalFraction,
                    onFractionChange = { fraction ->
                        previewVerticalFraction = fraction
                        positionSaveJob?.cancel()
                        positionSaveJob = scope.launch {
                            delay(200L)
                            preferencesManager.setOverlayVerticalFraction(fraction)
                        }
                    }
                )
            }
        }

        item {
            SectionTitle("VOLUME KEY HANDLING")
            Spacer(modifier = Modifier.height(10.dp))
            VolumeKeyHandlingPanel(
                pauseDurationMinutes = pauseDurationMinutes,
                pausedUntilEpochMs = pausedUntilEpochMs,
                currentTimeEpochMs = currentTimeEpochMs,
                pickerExpanded = standDownPickerExpanded,
                onDurationSelected = { minutes ->
                    scope.launch { preferencesManager.setAmplyPauseDurationMinutes(minutes) }
                },
                onRestoreNow = {
                    scope.launch { preferencesManager.restoreAmplyNow() }
                },
                onPickerToggle = { standDownPickerExpanded = !standDownPickerExpanded }
            )
        }

        if (standDownPickerExpanded) {
            item {
                OutlinedTextField(
                    value = standDownSearch,
                    onValueChange = { standDownSearch = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search app or package") },
                    singleLine = true
                )
            }
            items(standDownApps, key = { "stand-down-${it.packageName}" }) { app ->
                StandDownAppRow(
                    app = app,
                    enabled = appSettings[app.packageName]?.passVolumeKeysToApp == true,
                    onEnabledChange = { enabled ->
                        scope.launch {
                            preferencesManager.setPassVolumeKeysToApp(
                                packageName = app.packageName,
                                appName = app.appName,
                                uid = app.uid,
                                enabled = enabled
                            )
                        }
                    }
                )
            }
        }

        item {
            SectionTitle("APP VOLUMES")
            Spacer(modifier = Modifier.height(10.dp))
            AppListModeSelector(
                selected = appListMode,
                onSelected = { appListMode = it }
            )
        }

        if (apps.isEmpty()) {
            item {
                SettingsPanel {
                    Text(
                        text = if (appSettings.isEmpty()) "NO APPS SEEN YET" else "NO APPS PLAYING RIGHT NOW",
                        style = MaterialTheme.typography.labelMedium,
                        color = NothingColors.GreyMedium
                    )
                }
            }
        } else {
            items(apps, key = { it.packageName }) { app ->
                AppSettingsRow(
                    app = app,
                    onVisibleChange = { visible ->
                        scope.launch {
                            preferencesManager.setAppHiddenInOverlay(app.packageName, !visible)
                        }
                    },
                    onVolumeChange = { volume ->
                        val activeSession = activeSessionsByPackage[app.packageName]
                        if (activeSession != null) {
                            runtime.audioSessionManager.setSessionVolume(
                                sessionId = activeSession.sessionId,
                                packageName = activeSession.packageName,
                                volume = volume
                            )
                        } else {
                            inactiveVolumeSaveJobs.remove(app.packageName)?.cancel()
                            inactiveVolumeSaveJobs[app.packageName] = scope.launch {
                                delay(300L)
                                preferencesManager.setAppDefaultVolume(app.packageName, volume)
                                inactiveVolumeSaveJobs.remove(app.packageName)
                            }
                        }
                    }
                )
            }
        }
    }
}

private fun loadLaunchableApps(context: Context): List<InstalledAppEntry> {
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
private fun VolumeKeyHandlingPanel(
    pauseDurationMinutes: Int,
    pausedUntilEpochMs: Long,
    currentTimeEpochMs: Long,
    pickerExpanded: Boolean,
    onDurationSelected: (Int) -> Unit,
    onRestoreNow: () -> Unit,
    onPickerToggle: () -> Unit
) {
    val remainingSeconds = ((pausedUntilEpochMs - currentTimeEpochMs).coerceAtLeast(0L) + 999L) / 1_000L
    val isPaused = remainingSeconds > 0

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
                        text = "${remainingSeconds / 60}:${(remainingSeconds % 60).toString().padStart(2, '0')} remaining",
                        color = NothingColors.GreyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
                Button(
                    onClick = onRestoreNow,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NothingColors.Red)
                ) {
                    Text("RESTORE NOW", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text("EXPANDED-PILL PAUSE DURATION", color = NothingColors.GreyMedium, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 5, 15, 30).forEach { minutes ->
                Button(
                    onClick = { onDurationSelected(minutes) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (pauseDurationMinutes == minutes) NothingColors.Red else Color(0xFF2A2A2A)
                    )
                ) {
                    Text("${minutes}m", fontWeight = FontWeight.Bold)
                }
            }
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
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A))
        ) {
            Text(if (pickerExpanded) "HIDE APP LIST" else "CHOOSE STAND-DOWN APPS", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StandDownAppRow(
    app: InstalledAppEntry,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val icon = remember(app.packageName) {
        runCatching { context.packageManager.getApplicationIcon(app.packageName) }.getOrNull()
    }
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
                        bitmap = it.toBitmap(80, 80).asImageBitmap(),
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
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text("Let app use volume buttons", color = NothingColors.GreyMedium, fontSize = 11.sp)
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

private fun openShizukuApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
    if (intent != null) {
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    } else {
        openShizukuDownload(context)
    }
}

private fun openShizukuDownload(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        data = "https://github.com/RikkaApps/Shizuku/releases".toUri()
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)
}

private fun stablePackageOrder(
    currentOrder: List<String>,
    candidateApps: List<AppSettings>
): List<String> {
    val candidatePackages = candidateApps.map { it.packageName }.toSet()
    val previousOrder = currentOrder.filter { it in candidatePackages }
    val previousPackages = previousOrder.toSet()
    val addedPackages = candidateApps
        .filter { it.packageName !in previousPackages }
        .sortedWith(compareByDescending<AppSettings> { it.lastSeenTimestamp }.thenBy { it.appName.lowercase() })
        .map { it.packageName }

    return previousOrder + addedPackages
}

private fun mergeAppSettingsWithActiveSessions(
    appSettings: Map<String, AppSettings>,
    activeSessions: List<AudioSession>
): List<AppSettings> {
    val merged = LinkedHashMap<String, AppSettings>()
    appSettings.values.forEach { setting ->
        merged[setting.packageName] = setting
    }

    activeSessions.forEach { session ->
        val existing = merged[session.packageName]
        merged[session.packageName] = AppSettings(
            packageName = session.packageName,
            appName = session.appName,
            uid = session.uid,
            defaultVolume = existing?.defaultVolume ?: session.volume,
            hiddenInOverlay = existing?.hiddenInOverlay ?: false,
            passVolumeKeysToApp = existing?.passVolumeKeysToApp ?: false,
            lastSeenTimestamp = maxOf(existing?.lastSeenTimestamp ?: 0L, session.lastSeenTimestamp)
        )
    }

    return merged.values.toList()
}

@Composable
private fun DashboardHeader() {
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
private fun ShizukuWarningCard(
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
        ShizukuPermissionState.GRANTED -> "Amply is restoring per-app volume control."
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
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = NothingColors.Red,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1C), RoundedCornerShape(18.dp))
            .padding(14.dp),
        content = content
    )
}

@Composable
private fun PermissionButton(
    number: String,
    title: String,
    description: String,
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
                    .background(NothingColors.Red, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = number,
                    color = NothingColors.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Column {
                Text(text = title, style = MaterialTheme.typography.labelLarge)
                Text(text = description, style = MaterialTheme.typography.bodyMedium, color = NothingColors.GreyMedium)
            }
        }
    }
}

@Composable
private fun SideSelector(
    selected: OverlaySide,
    onSelected: (OverlaySide) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OverlaySide.entries.forEach { side ->
            Button(
                onClick = { onSelected(side) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected == side) NothingColors.Red else Color(0xFF2A2A2A),
                    contentColor = NothingColors.White
                )
            ) {
                Text(text = side.name)
            }
        }
    }
}

@Composable
private fun AppListModeSelector(
    selected: AppListMode,
    onSelected: (AppListMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AppListMode.entries.forEach { mode ->
            Button(
                onClick = { onSelected(mode) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected == mode) NothingColors.Red else Color(0xFF2A2A2A),
                    contentColor = NothingColors.White
                )
            ) {
                Text(text = mode.name)
            }
        }
    }
}

@Composable
private fun PositionPreview(
    side: OverlaySide,
    verticalFraction: Float,
    onFractionChange: (Float) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(Color(0xFF101010), RoundedCornerShape(18.dp))
            .pointerInput(Unit) {
                fun update(y: Float) {
                    onFractionChange((y / size.height.toFloat()).coerceIn(0f, 1f))
                }
                detectTapGestures { offset -> update(offset.y) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onFractionChange((change.position.y / size.height.toFloat()).coerceIn(0f, 1f))
                }
            }
            .padding(14.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val dotRadius = 2.dp.toPx()
            val step = size.height / 8f
            for (i in 0..8) {
                drawCircle(
                    color = Color(0xFF333333),
                    radius = dotRadius,
                    center = Offset(size.width / 2f, i * step)
                )
            }
        }

        Box(
            modifier = Modifier
                .align(if (side == OverlaySide.LEFT) Alignment.TopStart else Alignment.TopEnd)
                .offset {
                    IntOffset(
                        x = 0,
                        y = ((220.dp.roundToPx() - 64.dp.roundToPx()) * verticalFraction).roundToInt()
                    )
                }
                .size(width = 42.dp, height = 64.dp)
                .background(Color(0xFF1C1C1C), RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = "Overlay preview",
                tint = NothingColors.Red,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun AppSettingsRow(
    app: AppSettings,
    onVisibleChange: (Boolean) -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    val context = LocalContext.current
    var displayedVolume by remember(app.packageName) { mutableFloatStateOf(app.defaultVolume) }
    LaunchedEffect(app.defaultVolume) {
        displayedVolume = app.defaultVolume
    }
    val icon = remember(app.packageName) {
        try {
            context.packageManager.getApplicationIcon(app.packageName)
        } catch (_: Exception) {
            null
        }
    }
    val volumePercent = (displayedVolume * 100).roundToInt()

    SettingsPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Color(0xFF303030), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                icon?.let {
                    Image(
                        bitmap = it.toBitmap(72, 72).asImageBitmap(),
                        contentDescription = app.appName,
                        modifier = Modifier.size(28.dp)
                    )
                } ?: Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = app.appName,
                    tint = NothingColors.GreyMedium
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    color = NothingColors.White,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$volumePercent%",
                    color = if (volumePercent > 80) NothingColors.Red else NothingColors.GreyMedium,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            VisibilitySelector(
                visible = !app.hiddenInOverlay,
                onVisibleChange = onVisibleChange
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        DotVolumeSlider(
            volume = displayedVolume,
            onVolumeChange = { volume ->
                displayedVolume = volume
                onVolumeChange(volume)
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun VisibilitySelector(
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.width(118.dp)
    ) {
        VisibilityButton(
            text = "SHOW",
            active = visible,
            onClick = { onVisibleChange(true) },
            modifier = Modifier.weight(1f)
        )
        VisibilityButton(
            text = "HIDE",
            active = !visible,
            onClick = { onVisibleChange(false) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun VisibilityButton(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) NothingColors.Red else Color(0xFF2A2A2A),
            contentColor = NothingColors.White
        )
    ) {
        Text(
            text = text,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DotVolumeSlider(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val dotCount = 18
    Canvas(
        modifier = modifier
            .height(24.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onVolumeChange((change.position.x / size.width.toFloat()).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onVolumeChange((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                }
            }
    ) {
        val spacing = size.width / (dotCount - 1)
        val filledDots = (volume * dotCount).roundToInt()
        for (i in 0 until dotCount) {
            val dotPercentage = i.toFloat() / (dotCount - 1)
            drawCircle(
                color = when {
                    i < filledDots && dotPercentage > 0.75f -> NothingColors.Red
                    i < filledDots -> NothingColors.White
                    else -> Color(0xFF444444)
                },
                radius = 3.dp.toPx(),
                center = Offset(i * spacing, size.height / 2f)
            )
        }
    }
}
