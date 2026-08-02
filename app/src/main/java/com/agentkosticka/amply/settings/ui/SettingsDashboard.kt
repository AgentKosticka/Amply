package com.agentkosticka.amply.settings.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentkosticka.amply.AmplyRuntime
import com.agentkosticka.amply.audio.session.AudioSessionState
import com.agentkosticka.amply.settings.model.AppSettings
import com.agentkosticka.amply.permissions.AppPermissionState
import com.agentkosticka.amply.settings.model.AppSettingsStoreHealth
import com.agentkosticka.amply.settings.model.SettingsImportPreview
import com.agentkosticka.amply.settings.model.AmplyPauseDuration
import com.agentkosticka.amply.settings.model.OverlayAppMode
import com.agentkosticka.amply.settings.model.OverlaySide
import com.agentkosticka.amply.settings.model.VolumeDotScaleConfig
import com.agentkosticka.amply.shizuku.client.ShizukuPermissionState
import com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState
import com.agentkosticka.amply.ui.theme.NothingColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

internal enum class AppListMode {
    DEFAULT,
    EXPANDED
}

private enum class SettingsTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    ACCESS("Access", Icons.Default.Security),
    APPS("Apps", Icons.Default.Apps),
    PILL("Pill", Icons.Default.Tune),
    STAND_DOWN("Stand-Down", Icons.AutoMirrored.Filled.VolumeUp)
}

internal data class InstalledAppEntry(
    val packageName: String,
    val appName: String,
    val uid: Int
)

internal val DarkControlBackground = Color(0xFF2A2A2A)

@Composable
fun SettingsDashboard(
    runtime: AmplyRuntime,
    appPermissionState: AppPermissionState,
    onOverlayPermissionClick: () -> Unit,
    onAccessibilityClick: () -> Unit,
    onNotificationPolicyClick: () -> Unit,
    onPhoneStateClick: () -> Unit,
    onNotificationsClick: () -> Unit
) {
    val context = LocalContext.current
    val preferences = runtime.preferencesManager
    val shizukuRepository = runtime.shizukuRepository
    val overlaySide by preferences.overlaySide.collectAsState(initial = OverlaySide.LEFT)
    val verticalFraction by preferences.overlayVerticalFraction.collectAsState(initial = 0.5f)
    val dotScaleConfig by preferences.volumeDotScaleConfig.collectAsState(initial = VolumeDotScaleConfig())
    val appSettings by preferences.appSettings.collectAsState(initial = emptyMap())
    val appSettingsStoreHealth by preferences.appSettingsStoreHealth.collectAsState(
        initial = AppSettingsStoreHealth.HEALTHY
    )
    val passThroughPackages by preferences.volumeKeyPassThroughPackages.collectAsState(initial = emptySet())
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
    var staleAppCount by remember { mutableStateOf(0) }
    var pendingImportRaw by remember { mutableStateOf<String?>(null) }
    var pendingImportPreview by remember { mutableStateOf<SettingsImportPreview?>(null) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    var showCleanupConfirmation by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val raw = withContext(Dispatchers.IO) { preferences.exportSettings() }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(raw) }
                        ?: error("Could not open destination")
                }
            }.onSuccess {
                Toast.makeText(context, "Settings exported", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Could not open settings file")
                }
            }.onSuccess { raw ->
                pendingImportRaw = raw
                pendingImportPreview = preferences.previewImport(raw)
            }.onFailure {
                Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val activeSessionsByIdentity = sessionState.sessions.associateBy { it.identity }
    val activeIdentities = activeSessionsByIdentity.keys
    val knownApps = mergeAppSettingsWithActiveSessions(appSettings, sessionState.sessions)
        .filter { it.lastSeenTimestamp > 0L || it.identity in activeIdentities }
        .filter {
            val query = appSearch.trim().lowercase()
            query.isEmpty() || query in it.appName.lowercase() || query in it.packageName.lowercase()
        }
        .filter { appListMode == AppListMode.EXPANDED || it.identity in activeIdentities }
        .sortedWith(
            compareByDescending<AppSettings> { it.overlayMode == OverlayAppMode.PINNED }
                .thenByDescending { it.identity in activeIdentities }
                .thenBy { it.appName.lowercase() }
        )
    val standDownApps = remember(installedApps, passThroughPackages, standDownSearch) {
        val query = standDownSearch.trim().lowercase()
        installedApps
            .filter { query.isEmpty() || query in it.appName.lowercase() || query in it.packageName.lowercase() }
            .sortedWith(
                compareByDescending<InstalledAppEntry> { it.packageName in passThroughPackages }
                    .thenBy { it.appName.lowercase() }
            )
    }

    LaunchedEffect(Unit) { shizukuRepository.checkPermissionState() }
    LaunchedEffect(Unit) {
        while (true) {
            runtime.ringerExperimentExecutor.refresh()
            delay(1_000L.milliseconds)
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
            delay(1_000L.milliseconds)
            currentTime = System.currentTimeMillis()
        }
    }
    LaunchedEffect(appSettings) {
        staleAppCount = withContext(Dispatchers.IO) { preferences.findStaleApps().size }
    }
    DisposableEffect(Unit) {
        onDispose {
            inactiveVolumeSaveJobs.values.forEach(Job::cancel)
            positionSaveJob?.cancel()
        }
    }

    Scaffold(
        containerColor = NothingColors.Black,
        contentWindowInsets = WindowInsets.safeDrawing,
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
                        items(knownApps, key = { "app-${it.identity.storageKey}" }) { app ->
                            AppSettingsRow(
                                app = app,
                                isActive = app.identity in activeIdentities,
                                onReset = { scope.launch { preferences.resetApp(app.identity) } },
                                onOverlayModeChange = { mode ->
                                    scope.launch { preferences.setAppOverlayMode(app.packageName, mode, app.uid) }
                                },
                                onVolumeChange = { volume ->
                                    val active = activeSessionsByIdentity[app.identity]
                                    if (active != null) {
                                        runtime.audioSessionManager.setAppVolume(app.packageName, volume)
                                    } else {
                                        val saveKey = app.identity.storageKey
                                        inactiveVolumeSaveJobs.remove(saveKey)?.cancel()
                                        inactiveVolumeSaveJobs[saveKey] = scope.launch {
                                            delay(300L.milliseconds)
                                            preferences.setAppDefaultVolume(app.packageName, volume, app.uid)
                                            inactiveVolumeSaveJobs.remove(saveKey)
                                        }
                                    }
                                }
                            )
                        }
                    }
                    item {
                        SectionTitle("APP DATA & RECOVERY")
                        Spacer(Modifier.height(10.dp))
                        DataRecoveryPanel(
                            health = appSettingsStoreHealth,
                            staleAppCount = staleAppCount,
                            onExport = { exportLauncher.launch("amply-settings.json") },
                            onImport = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                            onCleanup = { if (staleAppCount > 0) showCleanupConfirmation = true },
                            onReset = { showResetConfirmation = true }
                        )
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
                                    delay(200L.milliseconds)
                                    preferences.setOverlayVerticalFraction(fraction)
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                            VolumeDotScalePanel(
                                config = dotScaleConfig,
                                deviceReferenceMax = remember(context) { deviceVolumeReference(context) },
                                onChange = { config ->
                                    scope.launch { preferences.setVolumeDotScale(config) }
                                }
                            )
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
                                app.packageName in passThroughPackages
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
                            "CALL ROUTING",
                            "Distinguish incoming and outgoing calls",
                            appPermissionState.phoneStateGranted,
                            onPhoneStateClick
                        )
                        Spacer(Modifier.height(10.dp))
                        PermissionButton(
                            "4",
                            "NOTIFICATIONS",
                            "Show Amply's foreground-service status",
                            appPermissionState.notificationsGranted,
                            onNotificationsClick
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
                            notificationPolicyGranted = appPermissionState.notificationPolicyGranted,
                            onNotificationPolicyClick = onNotificationPolicyClick,
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

    pendingImportPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = {
                pendingImportRaw = null
                pendingImportPreview = null
            },
            title = { Text(if (preview.valid) "IMPORT SETTINGS" else "INVALID SETTINGS FILE") },
            text = {
                Text(
                    if (preview.valid) {
                        "${preview.appCount} app records, ${preview.customizedAppCount} customized. Merge keeps other local app records; Replace removes them."
                    } else preview.error.orEmpty()
                )
            },
            confirmButton = {
                if (preview.valid) TextButton(onClick = {
                    val raw = pendingImportRaw ?: return@TextButton
                    scope.launch {
                        runCatching { preferences.importSettings(raw, replace = false) }
                            .onSuccess { Toast.makeText(context, "Settings merged", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
                    }
                    pendingImportRaw = null
                    pendingImportPreview = null
                }) { Text("MERGE", color = NothingColors.White) }
            },
            dismissButton = {
                Row {
                    if (preview.valid) TextButton(onClick = {
                        val raw = pendingImportRaw ?: return@TextButton
                        scope.launch {
                            runCatching { preferences.importSettings(raw, replace = true) }
                                .onSuccess { Toast.makeText(context, "Settings replaced", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                        pendingImportRaw = null
                        pendingImportPreview = null
                    }) { Text("REPLACE", color = NothingColors.Red) }
                    TextButton(onClick = {
                        pendingImportRaw = null
                        pendingImportPreview = null
                    }) { Text("CANCEL", color = NothingColors.GreyMedium) }
                }
            },
            containerColor = Color(0xFF1C1C1C),
            titleContentColor = NothingColors.White,
            textContentColor = NothingColors.GreyMedium
        )
    }

    if (showCleanupConfirmation) {
        AlertDialog(
            onDismissRequest = { showCleanupConfirmation = false },
            title = { Text("REMOVE STALE APP DATA?") },
            text = { Text("This removes $staleAppCount uninstalled app records, including customized records shown in this count.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        staleAppCount = withContext(Dispatchers.IO) {
                            preferences.pruneStaleApps(automatic = false)
                            preferences.findStaleApps().size
                        }
                    }
                    showCleanupConfirmation = false
                }) { Text("REMOVE", color = NothingColors.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupConfirmation = false }) {
                    Text("CANCEL", color = NothingColors.GreyMedium)
                }
            },
            containerColor = Color(0xFF1C1C1C),
            titleContentColor = NothingColors.White,
            textContentColor = NothingColors.GreyMedium
        )
    }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("RESET AMPLY?") },
            text = { Text("Layout, volume behavior, app records, pins, stand-down choices, and pause settings return to defaults. Android permissions are not revoked.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { preferences.resetAllUserSettings() }
                    showResetConfirmation = false
                }) { Text("RESET", color = NothingColors.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text("CANCEL", color = NothingColors.GreyMedium)
                }
            },
            containerColor = Color(0xFF1C1C1C),
            titleContentColor = NothingColors.White,
            textContentColor = NothingColors.GreyMedium
        )
    }
}
