package com.agentkosticka.amply.settings.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentkosticka.amply.AmplyRuntime
import com.agentkosticka.amply.audio.session.AudioSessionState
import com.agentkosticka.amply.settings.data.PreferencesManager
import com.agentkosticka.amply.settings.model.AppSettings
import com.agentkosticka.amply.permissions.AppPermissionState
import com.agentkosticka.amply.settings.model.AppSettingsStoreHealth
import com.agentkosticka.amply.settings.model.SettingsImportPreview
import com.agentkosticka.amply.settings.model.ImportMode
import com.agentkosticka.amply.settings.model.SettingsOperationResult
import com.agentkosticka.amply.settings.model.AmplyPauseDuration
import com.agentkosticka.amply.settings.model.OverlayAppMode
import com.agentkosticka.amply.settings.model.OverlaySide
import com.agentkosticka.amply.settings.model.VolumeDotScaleConfig
import com.agentkosticka.amply.shizuku.client.ShizukuPermissionState
import com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState
import com.agentkosticka.amply.shizuku.protocol.VOLUME_PROTOCOL_VERSION
import com.agentkosticka.amply.ui.theme.NothingColors
import com.agentkosticka.amply.tutorial.TutorialCoachmarkCard
import com.agentkosticka.amply.tutorial.TutorialStage
import com.agentkosticka.amply.util.readAtMost
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class AppListMode {
    DEFAULT,
    EXPANDED
}

internal enum class SettingsTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    ACCESS("Access", Icons.Default.Security),
    APPS("Apps", Icons.Default.Apps),
    PILL("Pill", Icons.Default.Tune),
    STAND_DOWN("Stand-Down", Icons.AutoMirrored.Filled.VolumeUp),
    DIAGNOSTICS("Diagnostics", Icons.Default.BugReport)
}

@Composable
private fun SwipeAwareTabBar(
    pagerState: PagerState,
    onTabClick: (Int) -> Unit
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val tabWidth = 108.dp
    val tabSpacing = 4.dp
    val edgePadding = 8.dp
    val pagePosition by remember(pagerState) {
        derivedStateOf {
            pagerState.currentPage + pagerState.currentPageOffsetFraction
        }
    }

    Surface(color = Color(0xFF151515)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(76.dp)
        ) {
            val viewportWidthPx = with(density) { maxWidth.toPx() }
            val firstTabCenterPx = with(density) { (edgePadding + tabWidth / 2).toPx() }
            val tabStepPx = with(density) { (tabWidth + tabSpacing).toPx() }

            LaunchedEffect(pagerState, viewportWidthPx, tabStepPx) {
                snapshotFlow {
                    pagerState.isScrollInProgress to
                        (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                }.collect { (scrolling, position) ->
                    if (scrolling && scrollState.maxValue > 0) {
                        val target = (firstTabCenterPx + position * tabStepPx - viewportWidthPx / 2f)
                            .roundToInt()
                            .coerceIn(0, scrollState.maxValue)
                        scrollState.scrollTo(target)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .horizontalScroll(scrollState)
                    .padding(horizontal = edgePadding),
                horizontalArrangement = Arrangement.spacedBy(tabSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsTab.entries.forEachIndexed { index, tab ->
                    val selectionProgress = (1f - abs(pagePosition - index)).coerceIn(0f, 1f)
                    val background = lerp(Color.Transparent, DarkControlBackground, selectionProgress)
                    val iconColor = lerp(NothingColors.GreyMedium, NothingColors.Red, selectionProgress)
                    val textColor = lerp(NothingColors.GreyMedium, NothingColors.White, selectionProgress)

                    Column(
                        modifier = Modifier
                            .width(tabWidth)
                            .height(64.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(background)
                            .selectable(
                                selected = pagerState.settledPage == index,
                                role = Role.Tab,
                                onClick = { onTabClick(index) }
                            )
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = null,
                            tint = iconColor,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = tab.label,
                            color = textColor,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                        Box(
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .width(26.dp)
                                .height(2.dp)
                                .graphicsLayer {
                                    alpha = selectionProgress
                                    scaleX = 0.45f + selectionProgress * 0.55f
                                }
                                .background(NothingColors.Red, RoundedCornerShape(1.dp))
                        )
                    }
                }
            }
        }
    }
}

internal data class InstalledAppEntry(
    val packageName: String,
    val appName: String,
    val uid: Int
)

private fun buildSanitizedDiagnostics(
    context: android.content.Context,
    health: com.agentkosticka.amply.runtime.RuntimeHealth,
    permissions: AppPermissionState,
    ringerReport: String
): String {
    val packageInfo = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    val versionCode = packageInfo?.longVersionCode
    return buildString {
        appendLine("Amply diagnostics")
        appendLine("Version: ${packageInfo?.versionName ?: "unknown"} ($versionCode)")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Protocol: $VOLUME_PROTOCOL_VERSION")
        appendLine("Accessibility connected: ${health.accessibilityConnected}")
        appendLine("Foreground service: ${health.foregroundServiceRunning}")
        appendLine("Shizuku permission: ${health.shizukuPermission}")
        appendLine("Volume service: ${health.volumeServiceConnection}")
        appendLine("Paused: ${health.isPaused}")
        appendLine("Last operation: ${health.lastOperation}")
        appendLine("Recoverable error: ${health.recoverableError?.code ?: "none"}")
        appendLine("Phone-state permission: ${permissions.phoneStateGranted}")
        appendLine("Notification permission: ${permissions.notificationsGranted}")
        appendLine("Notification-policy access: ${permissions.notificationPolicyGranted}")
        appendLine()
        append(ringerReport)
    }
}

internal val DarkControlBackground = Color(0xFF2A2A2A)

@Composable
fun SettingsDashboard(
    runtime: AmplyRuntime,
    appPermissionState: AppPermissionState,
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
    val disableShizukuDisconnectedWarning by preferences.disableShizukuDisconnectedWarning.collectAsState(
        initial = false
    )
    val sessionState by runtime.sessionState.collectAsState(initial = AudioSessionState.empty())
    val shizukuState by shizukuRepository.permissionState.collectAsState(initial = ShizukuPermissionState.UNKNOWN)
    val connectionState by runtime.connectionState.collectAsState(initial = VolumeServiceConnectionState.WAITING_FOR_PERMISSION)
    val runtimeHealth by runtime.runtimeHealth.collectAsState()
    val tutorialStage by runtime.tutorialCoordinator.stage.collectAsState()
    val selectedExperiment by runtime.ringerExperimentExecutor.selectedMethod.collectAsState()
    val experimentResults by runtime.ringerExperimentExecutor.methodTestResults.collectAsState()
    val experimentProgress by runtime.ringerExperimentExecutor.methodTestProgress.collectAsState()
    val runningExperiment by runtime.ringerExperimentExecutor.runningMethod.collectAsState()
    val experimentBusy by runtime.ringerExperimentExecutor.busy.collectAsState()
    val scope = rememberCoroutineScope()
    val inactiveVolumeSaveJobs = remember { mutableMapOf<String, Job>() }
    var selectedTabName by rememberSaveable { mutableStateOf(SettingsTab.ACCESS.name) }
    val selectedTab = SettingsTab.entries.firstOrNull { it.name == selectedTabName } ?: SettingsTab.ACCESS
    val pagerState = rememberPagerState(
        initialPage = SettingsTab.entries.indexOf(selectedTab),
        pageCount = { SettingsTab.entries.size }
    )
    val appsListState = rememberLazyListState()
    val pillListState = rememberLazyListState()
    val keysListState = rememberLazyListState()
    val accessListState = rememberLazyListState()
    val diagnosticsListState = rememberLazyListState()
    val listStates = remember(
        appsListState,
        pillListState,
        keysListState,
        accessListState,
        diagnosticsListState
    ) {
        mapOf(
            SettingsTab.APPS to appsListState,
            SettingsTab.PILL to pillListState,
            SettingsTab.STAND_DOWN to keysListState,
            SettingsTab.ACCESS to accessListState,
            SettingsTab.DIAGNOSTICS to diagnosticsListState
        )
    }
    var appListMode by rememberSaveable { mutableStateOf(AppListMode.DEFAULT) }
    var appSearch by rememberSaveable { mutableStateOf("") }
    var previewVerticalFraction by remember { mutableFloatStateOf(verticalFraction) }
    var positionSaveJob by remember { mutableStateOf<Job?>(null) }
    var pickerExpanded by rememberSaveable { mutableStateOf(false) }
    var standDownSearch by rememberSaveable { mutableStateOf("") }
    var installedApps by remember { mutableStateOf<List<InstalledAppEntry>>(emptyList()) }
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var staleAppCount by remember { mutableIntStateOf(0) }
    var pendingImportRaw by remember { mutableStateOf<String?>(null) }
    var pendingImportPreview by remember { mutableStateOf<SettingsImportPreview?>(null) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    var showCleanupConfirmation by remember { mutableStateOf(false) }
    var showRepairConfirmation by remember { mutableStateOf(false) }
    var pendingRingerTest by remember { mutableStateOf<com.agentkosticka.amply.audio.ringer.RingerExperimentMethod?>(null) }

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
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readAtMost(PreferencesManager.MAX_IMPORT_BYTES + 1)
                        require(bytes.size <= PreferencesManager.MAX_IMPORT_BYTES) {
                            "Settings file exceeds 2 MiB"
                        }
                        val raw = bytes.toString(Charsets.UTF_8)
                        raw to preferences.previewImport(raw)
                    } ?: error("Could not open settings file")
                }
            }.onSuccess { (raw, preview) ->
                pendingImportRaw = raw
                pendingImportPreview = preview
            }.onFailure {
                Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val activeSessionsByIdentity = remember(sessionState.sessions) {
        sessionState.sessions.associateBy { it.identity }
    }
    val activeIdentities = remember(activeSessionsByIdentity) { activeSessionsByIdentity.keys }
    val knownApps = remember(
        appSettings,
        sessionState.sessions,
        activeIdentities,
        appSearch,
        appListMode
    ) {
        val query = appSearch.trim().lowercase()
        mergeAppSettingsWithActiveSessions(appSettings, sessionState.sessions)
            .filter { it.lastSeenTimestamp > 0L || it.identity in activeIdentities }
            .filter {
                query.isEmpty() || query in it.appName.lowercase() || query in it.packageName.lowercase()
            }
            .filter { appListMode == AppListMode.EXPANDED || it.identity in activeIdentities }
            .sortedWith(
                compareByDescending<AppSettings> { it.overlayMode == OverlayAppMode.PINNED }
                    .thenByDescending { it.identity in activeIdentities }
                    .thenBy { it.appName.lowercase() }
            )
    }
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
    LaunchedEffect(pagerState.settledPage) {
        val page = pagerState.settledPage.coerceIn(SettingsTab.entries.indices)
        selectedTabName = SettingsTab.entries[page].name
        if (tutorialStage.isAppTour) {
            val landedStage = SettingsTab.entries[page].tutorialStage
            val currentIndex = TutorialStage.appStages.indexOf(tutorialStage)
            val landedIndex = landedStage?.let(TutorialStage.appStages::indexOf) ?: -1
            if (landedStage != null && landedIndex >= 0 && kotlin.math.abs(landedIndex - currentIndex) <= 1) {
                if (landedStage != tutorialStage) runtime.tutorialCoordinator.goTo(landedStage)
            } else {
                pagerState.animateScrollToPage(tutorialStage.settingsTabIndex)
            }
        }
    }
    LaunchedEffect(tutorialStage) {
        if (tutorialStage.isAppTour && pagerState.settledPage != tutorialStage.settingsTabIndex) {
            pagerState.animateScrollToPage(tutorialStage.settingsTabIndex)
        } else if (tutorialStage == TutorialStage.COMPLETED && pagerState.settledPage != 0) {
            pagerState.animateScrollToPage(0)
        }
    }
    LaunchedEffect(selectedTab) {
        if (selectedTab == SettingsTab.DIAGNOSTICS) {
            while (true) {
                runtime.ringerExperimentExecutor.refresh()
                delay(1_000L.milliseconds)
            }
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

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = NothingColors.Black,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            SwipeAwareTabBar(pagerState = pagerState) { index ->
                if (tutorialStage.isAppTour && index == tutorialStage.settingsTabIndex) {
                    runtime.tutorialCoordinator.advanceAppTour()
                } else {
                    scope.launch { pagerState.animateScrollToPage(index) }
                }
            }
        }
    ) { scaffoldPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .background(NothingColors.Black),
            key = { SettingsTab.entries[it].name },
            beyondViewportPageCount = 1
        ) { page ->
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)) {
                    DashboardHeader()
                }

            when (SettingsTab.entries[page]) {
                SettingsTab.APPS -> LazyColumn(
                    modifier = Modifier.weight(1f),
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
                                enabled = appSettingsStoreHealth != AppSettingsStoreHealth.CORRUPT,
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
                            onRepair = { showRepairConfirmation = true },
                            onReset = { showResetConfirmation = true }
                        )
                    }
                }

                SettingsTab.PILL -> LazyColumn(
                    modifier = Modifier.weight(1f),
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
                    modifier = Modifier.weight(1f),
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
                    modifier = Modifier.weight(1f),
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
                        SettingsPanel {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            preferences.setDisableShizukuDisconnectedWarning(
                                                !disableShizukuDisconnectedWarning
                                            )
                                        }
                                    },
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "HIDE SHIZUKU DISCONNECTION WARNING",
                                        color = NothingColors.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "Don't show the connection warning in the volume overlay when Shizuku disconnects.",
                                        color = NothingColors.GreyMedium,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Switch(
                                    checked = disableShizukuDisconnectedWarning,
                                    onCheckedChange = { disabled ->
                                        scope.launch {
                                            preferences.setDisableShizukuDisconnectedWarning(disabled)
                                        }
                                    },
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
                    item {
                        SectionTitle("PERMISSIONS")
                        Spacer(Modifier.height(10.dp))
                        PermissionButton(
                            "1",
                            "VOLUME KEYS",
                            "Enable accessibility service",
                            appPermissionState.volumeKeysGranted,
                            onAccessibilityClick
                        )
                        Spacer(Modifier.height(10.dp))
                        PermissionButton(
                            "2",
                            "CALL ROUTING",
                            "Distinguish incoming and outgoing calls",
                            appPermissionState.phoneStateGranted,
                            onPhoneStateClick
                        )
                        Spacer(Modifier.height(10.dp))
                        PermissionButton(
                            "3",
                            "NOTIFICATIONS",
                            "Show Amply's foreground-service status",
                            appPermissionState.notificationsGranted,
                            onNotificationsClick
                        )
                    }
                }

                SettingsTab.DIAGNOSTICS -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = listStates.getValue(SettingsTab.DIAGNOSTICS),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        SectionTitle("ADVANCED DIAGNOSTICS")
                        Spacer(Modifier.height(10.dp))
                        SettingsPanel {
                            Text(
                                "SUPPORT REPORT",
                                color = NothingColors.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "The report contains build, protocol, permission, connection, and error states. App labels and package names are excluded.",
                                color = NothingColors.GreyMedium,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    val report = buildSanitizedDiagnostics(
                                        context = context,
                                        health = runtimeHealth,
                                        permissions = appPermissionState,
                                        ringerReport = runtime.ringerExperimentExecutor.report()
                                    )
                                    context.getSystemService(ClipboardManager::class.java)
                                        .setPrimaryClip(ClipData.newPlainText("Amply diagnostics", report))
                                    Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                                }) { Text("COPY DIAGNOSTICS", color = NothingColors.White) }
                                TextButton(onClick = {
                                    val report = buildSanitizedDiagnostics(
                                        context = context,
                                        health = runtimeHealth,
                                        permissions = appPermissionState,
                                        ringerReport = runtime.ringerExperimentExecutor.report()
                                    )
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_SUBJECT, "Amply diagnostics")
                                                putExtra(Intent.EXTRA_TEXT, report)
                                            },
                                            "Share Amply diagnostics"
                                        )
                                    )
                                }) { Text("SHARE", color = NothingColors.White) }
                            }
                        }
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
                            onTest = { pendingRingerTest = it }
                        )
                    }
                    item {
                        SectionTitle("GUIDED TOUR")
                        Spacer(Modifier.height(10.dp))
                        SettingsPanel {
                            Text(
                                stringResource(com.agentkosticka.amply.R.string.tutorial_replay_title),
                                color = NothingColors.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                stringResource(com.agentkosticka.amply.R.string.tutorial_replay_body),
                                color = NothingColors.GreyMedium,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(10.dp))
                            TextButton(onClick = runtime.tutorialCoordinator::replay) {
                                Text(
                                    stringResource(com.agentkosticka.amply.R.string.tutorial_replay_action),
                                    color = NothingColors.Red
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    }

        if (tutorialStage.isAppTour) {
            AppTabTourOverlay(
                stage = tutorialStage,
                onBack = runtime.tutorialCoordinator::backAppTour,
                onNext = runtime.tutorialCoordinator::advanceAppTour,
                onSkip = runtime.tutorialCoordinator::skip
            )
        }
    }

    pendingRingerTest?.let { method ->
        AlertDialog(
            onDismissRequest = { pendingRingerTest = null },
            title = { Text("RUN DEVICE-MODIFYING CHECK?") },
            text = {
                Text(
                    "Amply will temporarily change ringer mode and ring/notification volume, then restore the captured values. The check is blocked during calls, ringing, alarms, or Do Not Disturb."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRingerTest = null
                    scope.launch { runtime.ringerExperimentExecutor.testAllTransitions(method) }
                }) { Text("RUN CHECK", color = NothingColors.Red) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRingerTest = null }) {
                    Text("CANCEL", color = NothingColors.GreyMedium)
                }
            },
            containerColor = Color(0xFF1C1C1C),
            titleContentColor = NothingColors.White,
            textContentColor = NothingColors.GreyMedium
        )
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
                        "${preview.appCount} app records, ${preview.customizedAppCount} customized, and ${preview.standDownCount} stood-down packages. Both modes replace global settings; Merge keeps other app records and unions Stand-Down, while Replace removes local records not in the backup."
                    } else preview.error.orEmpty()
                )
            },
            confirmButton = {
                if (preview.valid) TextButton(onClick = {
                    val raw = pendingImportRaw ?: return@TextButton
                    scope.launch {
                        when (val result = preferences.importSettings(raw, ImportMode.MERGE)) {
                            SettingsOperationResult.Success ->
                                Toast.makeText(context, "Settings merged", Toast.LENGTH_SHORT).show()
                            SettingsOperationResult.StoreCorrupt ->
                                Toast.makeText(context, "Import blocked: current settings are corrupt", Toast.LENGTH_LONG).show()
                            is SettingsOperationResult.ValidationFailed ->
                                Toast.makeText(context, "Import failed: ${result.reason}", Toast.LENGTH_LONG).show()
                            is SettingsOperationResult.IoFailed ->
                                Toast.makeText(context, "Import failed: ${result.reason}", Toast.LENGTH_LONG).show()
                        }
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
                            when (val result = preferences.importSettings(raw, ImportMode.REPLACE)) {
                                SettingsOperationResult.Success ->
                                    Toast.makeText(context, "Settings replaced", Toast.LENGTH_SHORT).show()
                                SettingsOperationResult.StoreCorrupt ->
                                    Toast.makeText(context, "Import blocked: current settings are corrupt", Toast.LENGTH_LONG).show()
                                is SettingsOperationResult.ValidationFailed ->
                                    Toast.makeText(context, "Import failed: ${result.reason}", Toast.LENGTH_LONG).show()
                                is SettingsOperationResult.IoFailed ->
                                    Toast.makeText(context, "Import failed: ${result.reason}", Toast.LENGTH_LONG).show()
                            }
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

    if (showRepairConfirmation) {
        AlertDialog(
            onDismissRequest = { showRepairConfirmation = false },
            title = { Text("REPAIR APP SETTINGS?") },
            text = {
                Text(
                    "This removes only unreadable per-app records. Global layout, pause, and Stand-Down settings are preserved; export first if you need the corrupt raw data for support."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        when (preferences.repairAppSettingsStore()) {
                            SettingsOperationResult.Success ->
                                Toast.makeText(context, "App settings repaired", Toast.LENGTH_SHORT).show()
                            else -> Toast.makeText(context, "Repair failed", Toast.LENGTH_LONG).show()
                        }
                    }
                    showRepairConfirmation = false
                }) { Text("REPAIR", color = NothingColors.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showRepairConfirmation = false }) {
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

private val TutorialStage.settingsTabIndex: Int
    get() = when (this) {
        TutorialStage.APP_ACCESS -> SettingsTab.entries.indexOf(SettingsTab.ACCESS)
        TutorialStage.APP_APPS -> SettingsTab.entries.indexOf(SettingsTab.APPS)
        TutorialStage.APP_PILL -> SettingsTab.entries.indexOf(SettingsTab.PILL)
        TutorialStage.APP_STAND_DOWN -> SettingsTab.entries.indexOf(SettingsTab.STAND_DOWN)
        else -> SettingsTab.entries.indexOf(SettingsTab.ACCESS)
    }

private val SettingsTab.tutorialStage: TutorialStage?
    get() = when (this) {
        SettingsTab.ACCESS -> TutorialStage.APP_ACCESS
        SettingsTab.APPS -> TutorialStage.APP_APPS
        SettingsTab.PILL -> TutorialStage.APP_PILL
        SettingsTab.STAND_DOWN -> TutorialStage.APP_STAND_DOWN
        SettingsTab.DIAGNOSTICS -> null
    }

@Composable
private fun AppTabTourOverlay(
    stage: TutorialStage,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    val index = TutorialStage.appStages.indexOf(stage)
    var horizontalDrag by remember(stage) { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f))
            .pointerInput(stage) {
                detectHorizontalDragGestures(
                    onDragStart = { horizontalDrag = 0f },
                    onHorizontalDrag = { _, amount -> horizontalDrag += amount },
                    onDragEnd = {
                        when {
                            horizontalDrag < -72f -> onNext()
                            horizontalDrag > 72f && index > 0 -> onBack()
                        }
                        horizontalDrag = 0f
                    },
                    onDragCancel = { horizontalDrag = 0f }
                )
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        TutorialCoachmarkCard(
            step = index + 1,
            total = TutorialStage.appStages.size,
            title = stringResource(stage.appTourTitleRes),
            body = stringResource(stage.appTourBodyRes),
            canGoBack = index > 0,
            onBack = onBack,
            onNext = onNext,
            onSkip = onSkip,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = 76.dp)
        )
    }
}

private val TutorialStage.appTourTitleRes: Int
    get() = when (this) {
        TutorialStage.APP_ACCESS -> com.agentkosticka.amply.R.string.tutorial_access_title
        TutorialStage.APP_APPS -> com.agentkosticka.amply.R.string.tutorial_apps_title
        TutorialStage.APP_PILL -> com.agentkosticka.amply.R.string.tutorial_pill_title
        TutorialStage.APP_STAND_DOWN -> com.agentkosticka.amply.R.string.tutorial_stand_down_title
        else -> com.agentkosticka.amply.R.string.tutorial_access_title
    }

private val TutorialStage.appTourBodyRes: Int
    get() = when (this) {
        TutorialStage.APP_ACCESS -> com.agentkosticka.amply.R.string.tutorial_access_body
        TutorialStage.APP_APPS -> com.agentkosticka.amply.R.string.tutorial_apps_body
        TutorialStage.APP_PILL -> com.agentkosticka.amply.R.string.tutorial_pill_body
        TutorialStage.APP_STAND_DOWN -> com.agentkosticka.amply.R.string.tutorial_stand_down_body
        else -> com.agentkosticka.amply.R.string.tutorial_access_body
    }
