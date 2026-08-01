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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import com.agentkosticka.amply.AmplyRuntime
import com.agentkosticka.amply.R
import com.agentkosticka.amply.data.AudioSession
import com.agentkosticka.amply.data.AudioSessionState
import com.agentkosticka.amply.data.AppSettings
import com.agentkosticka.amply.data.AppPermissionState
import com.agentkosticka.amply.data.AmplyPauseDuration
import com.agentkosticka.amply.data.OverlayAppMode
import com.agentkosticka.amply.data.OverlaySide
import com.agentkosticka.amply.audio.NotificationAlertMode
import com.agentkosticka.amply.audio.RingerExperimentMethod
import com.agentkosticka.amply.audio.RingerMethodTestResult
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

private enum class SettingsTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    ACCESS("Access", Icons.Default.Security),
    APPS("Apps", Icons.Default.Apps),
    PILL("Pill", Icons.Default.Tune),
    STAND_DOWN("Stand-Down", Icons.AutoMirrored.Filled.VolumeUp)
}

private data class InstalledAppEntry(
    val packageName: String,
    val appName: String,
    val uid: Int
)

private val DarkControlBackground = Color(0xFF2A2A2A)

@Composable
fun SettingsDashboard(
    runtime: AmplyRuntime,
    appPermissionState: AppPermissionState,
    onOverlayPermissionClick: () -> Unit,
    onAccessibilityClick: () -> Unit,
    onNotificationPolicyClick: () -> Unit
) {
    val context = LocalContext.current
    val preferences = runtime.preferencesManager
    val shizukuRepository = runtime.shizukuRepository
    val overlaySide by preferences.overlaySide.collectAsState(initial = OverlaySide.LEFT)
    val verticalFraction by preferences.overlayVerticalFraction.collectAsState(initial = 0.5f)
    val appSettings by preferences.appSettings.collectAsState(initial = emptyMap())
    val pauseDuration by preferences.amplyPauseDuration.collectAsState(initial = AmplyPauseDuration.FIVE_MINUTES)
    val pausedUntil by preferences.amplyPausedUntilEpochMs.collectAsState(initial = 0L)
    val sessionState by runtime.sessionState.collectAsState(initial = AudioSessionState.empty())
    val shizukuState by shizukuRepository.permissionState.collectAsState(initial = ShizukuPermissionState.UNKNOWN)
    val connectionState by runtime.connectionState.collectAsState(initial = VolumeServiceConnectionState.WAITING_FOR_PERMISSION)
    val selectedExperiment by runtime.ringerExperimentExecutor.selectedMethod.collectAsState()
    val experimentResults by runtime.ringerExperimentExecutor.methodTestResults.collectAsState()
    val experimentProgress by runtime.ringerExperimentExecutor.methodTestProgress.collectAsState()
    val runningExperiment by runtime.ringerExperimentExecutor.runningMethod.collectAsState()
    val experimentBusy by runtime.ringerExperimentExecutor.busy.collectAsState()
    val scope = rememberCoroutineScope()
    val inactiveVolumeSaveJobs = remember { mutableMapOf<String, Job>() }
    var selectedTabName by rememberSaveable { mutableStateOf(SettingsTab.ACCESS.name) }
    val selectedTab = SettingsTab.entries.firstOrNull { it.name == selectedTabName } ?: SettingsTab.ACCESS
    val appsListState = rememberLazyListState()
    val pillListState = rememberLazyListState()
    val keysListState = rememberLazyListState()
    val accessListState = rememberLazyListState()
    val listStates = mapOf(
        SettingsTab.APPS to appsListState,
        SettingsTab.PILL to pillListState,
        SettingsTab.STAND_DOWN to keysListState,
        SettingsTab.ACCESS to accessListState
    )
    var appListMode by rememberSaveable { mutableStateOf(AppListMode.DEFAULT) }
    var appSearch by rememberSaveable { mutableStateOf("") }
    var previewVerticalFraction by remember { mutableFloatStateOf(verticalFraction) }
    var positionSaveJob by remember { mutableStateOf<Job?>(null) }
    var pickerExpanded by rememberSaveable { mutableStateOf(false) }
    var standDownSearch by rememberSaveable { mutableStateOf("") }
    var installedApps by remember { mutableStateOf<List<InstalledAppEntry>>(emptyList()) }
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val activeSessionsByPackage = sessionState.sessions.associateBy { it.packageName }
    val activePackages = activeSessionsByPackage.keys
    val knownApps = mergeAppSettingsWithActiveSessions(appSettings, sessionState.sessions)
        .filter { it.lastSeenTimestamp > 0L || it.packageName in activePackages }
        .filter {
            val query = appSearch.trim().lowercase()
            query.isEmpty() || query in it.appName.lowercase() || query in it.packageName.lowercase()
        }
        .filter { appListMode == AppListMode.EXPANDED || it.packageName in activePackages }
        .sortedWith(
            compareByDescending<AppSettings> { it.overlayMode == OverlayAppMode.PINNED }
                .thenByDescending { it.packageName in activePackages }
                .thenBy { it.appName.lowercase() }
        )
    val standDownApps = remember(installedApps, appSettings, standDownSearch) {
        val query = standDownSearch.trim().lowercase()
        installedApps
            .filter { query.isEmpty() || query in it.appName.lowercase() || query in it.packageName.lowercase() }
            .sortedWith(
                compareByDescending<InstalledAppEntry> { appSettings[it.packageName]?.passVolumeKeysToApp == true }
                    .thenBy { it.appName.lowercase() }
            )
    }

    LaunchedEffect(Unit) { shizukuRepository.checkPermissionState() }
    LaunchedEffect(Unit) {
        while (true) {
            runtime.ringerExperimentExecutor.refresh()
            delay(1_000L)
        }
    }
    LaunchedEffect(verticalFraction) { previewVerticalFraction = verticalFraction }
    LaunchedEffect(pickerExpanded) {
        if (pickerExpanded && installedApps.isEmpty()) {
            installedApps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
        }
    }
    LaunchedEffect(pausedUntil) {
        currentTime = System.currentTimeMillis()
        while (pausedUntil > currentTime && pausedUntil != Long.MAX_VALUE) {
            delay(1_000L)
            currentTime = System.currentTimeMillis()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            inactiveVolumeSaveJobs.values.forEach(Job::cancel)
            positionSaveJob?.cancel()
        }
    }

    Scaffold(
        containerColor = NothingColors.Black,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF151515)) {
                SettingsTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTabName = tab.name },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NothingColors.White,
                            selectedTextColor = NothingColors.White,
                            indicatorColor = NothingColors.Red,
                            unselectedIconColor = NothingColors.GreyMedium,
                            unselectedTextColor = NothingColors.GreyMedium
                        )
                    )
                }
            }
        }
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .background(NothingColors.Black)
        ) {
            Box(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)) {
                DashboardHeader()
            }

            when (selectedTab) {
                SettingsTab.APPS -> LazyColumn(
                    state = listStates.getValue(SettingsTab.APPS),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        SectionTitle("PER-APP VOLUME")
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Pinned apps stay in the expanded overlay while Shizuku is connected.",
                            color = NothingColors.GreyMedium,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (connectionState != VolumeServiceConnectionState.CONNECTED) {
                        item {
                            SettingsPanel {
                                Text("SHIZUKU DISCONNECTED", color = NothingColors.Red, fontWeight = FontWeight.Bold)
                                Text("Pins are saved and will appear after reconnection.", color = NothingColors.GreyMedium)
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = appSearch,
                            onValueChange = { appSearch = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Search known audio apps") },
                            singleLine = true
                        )
                        Spacer(Modifier.height(10.dp))
                        AppListModeSelector(appListMode) { appListMode = it }
                    }
                    if (knownApps.isEmpty()) {
                        item {
                            SettingsPanel {
                                Text(
                                    if (appListMode == AppListMode.DEFAULT) "NO APPS PLAYING RIGHT NOW" else "NO AUDIO APPS SEEN YET",
                                    color = NothingColors.GreyMedium
                                )
                            }
                        }
                    } else {
                        items(knownApps, key = { "app-${it.packageName}" }) { app ->
                            AppSettingsRow(
                                app = app,
                                onOverlayModeChange = { mode ->
                                    scope.launch { preferences.setAppOverlayMode(app.packageName, mode) }
                                },
                                onVolumeChange = { volume ->
                                    val active = activeSessionsByPackage[app.packageName]
                                    if (active != null) {
                                        runtime.audioSessionManager.setAppVolume(app.packageName, volume)
                                    } else {
                                        inactiveVolumeSaveJobs.remove(app.packageName)?.cancel()
                                        inactiveVolumeSaveJobs[app.packageName] = scope.launch {
                                            delay(300L)
                                            preferences.setAppDefaultVolume(app.packageName, volume)
                                            inactiveVolumeSaveJobs.remove(app.packageName)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                SettingsTab.PILL -> LazyColumn(
                    state = listStates.getValue(SettingsTab.PILL),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp)
                ) {
                    item {
                        SectionTitle("PILL LAYOUT")
                        Spacer(Modifier.height(10.dp))
                        SettingsPanel {
                            SideSelector(overlaySide) { side -> scope.launch { preferences.setOverlaySide(side) } }
                            Spacer(Modifier.height(18.dp))
                            PositionPreview(overlaySide, previewVerticalFraction) { fraction ->
                                previewVerticalFraction = fraction
                                positionSaveJob?.cancel()
                                positionSaveJob = scope.launch {
                                    delay(200L)
                                    preferences.setOverlayVerticalFraction(fraction)
                                }
                            }
                        }
                    }
                }

                SettingsTab.STAND_DOWN -> LazyColumn(
                    state = listStates.getValue(SettingsTab.STAND_DOWN),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        SectionTitle("VOLUME KEY HANDLING")
                        Spacer(Modifier.height(10.dp))
                        VolumeKeyHandlingPanel(
                            pauseDuration = pauseDuration,
                            pausedUntilEpochMs = pausedUntil,
                            currentTimeEpochMs = currentTime,
                            pickerExpanded = pickerExpanded,
                            onDurationSelected = { duration -> scope.launch { preferences.setAmplyPauseDuration(duration) } },
                            onRestoreNow = { scope.launch { preferences.restoreAmplyNow() } },
                            onPickerToggle = { pickerExpanded = !pickerExpanded }
                        )
                    }
                    if (pickerExpanded) {
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
                                app,
                                appSettings[app.packageName]?.passVolumeKeysToApp == true
                            ) { enabled ->
                                scope.launch {
                                    preferences.setPassVolumeKeysToApp(app.packageName, app.appName, app.uid, enabled)
                                }
                            }
                        }
                    }
                }

                SettingsTab.ACCESS -> LazyColumn(
                    state = listStates.getValue(SettingsTab.ACCESS),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (shizukuState == ShizukuPermissionState.GRANTED && connectionState == VolumeServiceConnectionState.CONNECTED) {
                        item {
                            SettingsPanel {
                                Text("SHIZUKU CONNECTED", color = NothingColors.White, fontWeight = FontWeight.Bold)
                                Text("Privileged per-app volume control is ready.", color = NothingColors.GreyMedium)
                            }
                        }
                    }
                    if (shizukuState != ShizukuPermissionState.GRANTED || connectionState != VolumeServiceConnectionState.CONNECTED) {
                        item {
                            ShizukuWarningCard(
                                shizukuState,
                                connectionState,
                                onAction = {
                                    when (shizukuState) {
                                        ShizukuPermissionState.NOT_GRANTED,
                                        ShizukuPermissionState.SHOULD_SHOW_RATIONALE,
                                        ShizukuPermissionState.DENIED -> shizukuRepository.requestPermission()
                                        ShizukuPermissionState.SHIZUKU_NOT_RUNNING -> openShizukuApp(context)
                                        ShizukuPermissionState.SHIZUKU_NOT_INSTALLED -> openShizukuDownload(context)
                                        ShizukuPermissionState.UNKNOWN -> shizukuRepository.checkPermissionState()
                                        ShizukuPermissionState.GRANTED -> runtime.retryVolumeServiceConnection()
                                    }
                                },
                                onRefresh = {
                                    shizukuRepository.checkPermissionState()
                                    if (shizukuState == ShizukuPermissionState.GRANTED) runtime.retryVolumeServiceConnection()
                                }
                            )
                        }
                    }
                    item {
                        SectionTitle("PERMISSIONS")
                        Spacer(Modifier.height(10.dp))
                        PermissionButton(
                            "1",
                            "OVERLAY",
                            "Grant overlay access",
                            appPermissionState.overlayGranted,
                            onOverlayPermissionClick
                        )
                        Spacer(Modifier.height(10.dp))
                        PermissionButton(
                            "2",
                            "VOLUME KEYS",
                            "Enable accessibility service",
                            appPermissionState.volumeKeysGranted,
                            onAccessibilityClick
                        )
                        Spacer(Modifier.height(10.dp))
                        PermissionButton(
                            "3",
                            "NOTIFICATION MODES",
                            "Allow silent-mode changes",
                            appPermissionState.notificationPolicyGranted,
                            onNotificationPolicyClick
                        )
                    }
                    item {
                        RingerExperimentPanel(
                            selected = selectedExperiment,
                            results = experimentResults,
                            progress = experimentProgress,
                            runningMethod = runningExperiment,
                            busy = experimentBusy,
                            shizukuConnected = connectionState == VolumeServiceConnectionState.CONNECTED,
                            onSelect = runtime.ringerExperimentExecutor::select,
                            onTest = { method ->
                                scope.launch { runtime.ringerExperimentExecutor.testAllTransitions(method) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RingerExperimentPanel(
    selected: RingerExperimentMethod,
    results: Map<RingerExperimentMethod, RingerMethodTestResult>,
    progress: Map<RingerExperimentMethod, Float>,
    runningMethod: RingerExperimentMethod?,
    busy: Boolean,
    shizukuConnected: Boolean,
    onSelect: (RingerExperimentMethod) -> Unit,
    onTest: (RingerExperimentMethod) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var explainedMethod by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsPanel {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
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
                    ) { Text(if (selected == method) "ACTIVE" else "USE") }
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

private fun NotificationAlertMode.readableName(): String = when (this) {
    NotificationAlertMode.MUTED -> "Muted"
    NotificationAlertMode.VIBRATIONS -> "Vibrate"
    NotificationAlertMode.LOUD -> "Loud"
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
            overlayMode = existing?.overlayMode ?: OverlayAppMode.AUTO,
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
                Text(text = if (mode == AppListMode.DEFAULT) "PLAYING" else "ALL")
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
    onOverlayModeChange: (OverlayAppMode) -> Unit,
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
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$volumePercent%",
                    color = if (volumePercent > 80) NothingColors.Red else NothingColors.GreyMedium,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
            }

        }

        Spacer(modifier = Modifier.height(12.dp))

        OverlayModeSelector(
            selected = app.overlayMode,
            onSelected = onOverlayModeChange
        )

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
private fun OverlayModeSelector(
    selected: OverlayAppMode,
    onSelected: (OverlayAppMode) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        OverlayAppMode.entries.forEach { mode ->
            VisibilityButton(
                text = mode.name,
                active = selected == mode,
                onClick = { onSelected(mode) },
                modifier = Modifier.weight(1f)
            )
        }
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
            style = MaterialTheme.typography.labelSmall,
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
