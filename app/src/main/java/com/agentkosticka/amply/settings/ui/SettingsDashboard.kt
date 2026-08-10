package com.agentkosticka.amply.settings.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.agentkosticka.amply.AmplyRuntime
import com.agentkosticka.amply.audio.session.AudioSessionState
import com.agentkosticka.amply.audio.session.AppVolumeTarget
import com.agentkosticka.amply.permissions.AppPermissionState
import com.agentkosticka.amply.settings.data.PreferencesManager
import com.agentkosticka.amply.settings.model.AmplyPauseDuration
import com.agentkosticka.amply.settings.model.AppIdentity
import com.agentkosticka.amply.settings.model.AppSettings
import com.agentkosticka.amply.settings.model.AppSettingsStoreHealth
import com.agentkosticka.amply.settings.model.ImportMode
import com.agentkosticka.amply.settings.model.OverlayAppMode
import com.agentkosticka.amply.settings.model.OverlaySide
import com.agentkosticka.amply.settings.model.SettingsImportPreview
import com.agentkosticka.amply.settings.model.SettingsOperationResult
import com.agentkosticka.amply.settings.model.VolumeDotScaleConfig
import com.agentkosticka.amply.shizuku.client.ShizukuPermissionState
import com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState
import com.agentkosticka.amply.shizuku.protocol.VOLUME_PROTOCOL_VERSION
import com.agentkosticka.amply.tutorial.TutorialCoachmarkCard
import com.agentkosticka.amply.tutorial.TutorialStage
import com.agentkosticka.amply.ui.theme.NothingColors
import com.agentkosticka.amply.util.readAtMost
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

internal enum class AppListMode {
    DEFAULT,
    EXPANDED
}

internal enum class SettingsTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    ACCESS("Access", Icons.Default.Security),
    APPS("Apps", Icons.Default.Apps),
    PROFILES("Profiles", Icons.Default.Style),
    PILL("Pill", Icons.Default.Tune),
    STAND_DOWN("Stand-Down", Icons.AutoMirrored.Filled.VolumeUp),
    DIAGNOSTICS("Diagnostics", Icons.Default.BugReport)
}

private class UiJobHolder {
    var job: Job? = null
}

private class ReorderAutoScrollState {
    var job: Job? = null
    var direction: Float = 0f
    var velocityPxPerSecond: Float = 0f
}

private class ReorderMotionState {
    var distance: Float = 0f
    var initialOffset: Int = 0
    var itemSize: Int = 0
    var expectedLazyIndex: Int? = null
}

private data class AppReorderCallbacks(
    val onStart: () -> Unit,
    val onDrag: (Float) -> Unit,
    val onEnd: () -> Unit
)

private class AppReorderGestureHost {
    var listCoordinates: LayoutCoordinates? = null
    val handleBounds = mutableMapOf<AppIdentity, Rect>()
    val rowBounds = mutableMapOf<AppIdentity, Rect>()
    val callbacks = mutableMapOf<AppIdentity, AppReorderCallbacks>()
}

private fun Modifier.appReorderGestures(host: AppReorderGestureHost): Modifier =
    onGloballyPositioned { host.listCoordinates = it }
        .pointerInput(host) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial
                )
                val windowPosition = host.listCoordinates?.localToWindow(down.position)
                    ?: return@awaitEachGesture
                val identity = host.handleBounds.entries
                    .lastOrNull { (_, bounds) -> bounds.contains(windowPosition) }
                    ?.key
                    ?: return@awaitEachGesture
                if (host.callbacks[identity] == null) return@awaitEachGesture

                var accumulatedDrag = 0f
                var dragging = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) {
                        if (dragging) host.callbacks[identity]?.onEnd?.invoke()
                        break
                    }

                    val delta = change.position.y - change.previousPosition.y
                    if (!dragging) {
                        accumulatedDrag += delta
                        if (abs(accumulatedDrag) > viewConfiguration.touchSlop) {
                            dragging = true
                            host.callbacks[identity]?.onStart?.invoke()
                            val overSlop = accumulatedDrag -
                                kotlin.math.sign(accumulatedDrag) * viewConfiguration.touchSlop
                            if (overSlop != 0f) {
                                host.callbacks[identity]?.onDrag?.invoke(overSlop)
                            }
                            change.consume()
                        }
                    } else {
                        if (delta != 0f) host.callbacks[identity]?.onDrag?.invoke(delta)
                        change.consume()
                    }
                }
            }
        }

private fun Modifier.lazyScrollProgressIndicator(state: LazyListState): Modifier =
    drawWithContent {
        drawContent()
        if (!state.canScrollBackward && !state.canScrollForward) return@drawWithContent

        val indicatorState = state.scrollIndicatorState ?: return@drawWithContent
        val maximumScrollOffset =
            (indicatorState.contentSize - indicatorState.viewportSize).coerceAtLeast(1)
        val progress = when {
            !state.canScrollBackward -> 0f
            !state.canScrollForward -> 1f
            else -> indicatorState.scrollOffset.toFloat() / maximumScrollOffset
        }.coerceIn(0f, 1f)

        val trackInset = 10.dp.toPx()
        val trackHeight = (size.height - trackInset * 2f).coerceAtLeast(1f)
        val thumbHeight = 48.dp.toPx().coerceAtMost(trackHeight)
        val thumbTop = trackInset + (trackHeight - thumbHeight) * progress
        val trackWidth = 3.dp.toPx()
        val thumbWidth = 6.dp.toPx()
        val centerX = size.width - 8.dp.toPx()

        drawRoundRect(
            color = NothingColors.White.copy(alpha = 0.22f),
            topLeft = Offset(centerX - trackWidth / 2f, trackInset),
            size = Size(trackWidth, trackHeight),
            cornerRadius = CornerRadius(trackWidth)
        )
        drawRoundRect(
            color = NothingColors.Red,
            topLeft = Offset(centerX - thumbWidth / 2f, thumbTop),
            size = Size(thumbWidth, thumbHeight),
            cornerRadius = CornerRadius(thumbWidth)
        )
    }

@Composable
private fun OverlayPreferenceToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = NothingColors.White, fontWeight = FontWeight.Bold)
            Text(
                description,
                color = NothingColors.GreyMedium,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = NothingColors.White,
                checkedTrackColor = NothingColors.Red,
                uncheckedThumbColor = NothingColors.GreyMedium,
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }
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
                        val delta = target - scrollState.value
                        if (delta != 0) scrollState.dispatchRawDelta(delta.toFloat())
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
                    val interactionSource = remember(tab) { MutableInteractionSource() }
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
                                interactionSource = interactionSource,
                                indication = null,
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
    onNotificationsClick: () -> Unit,
    onNearbyDevicesClick: () -> Unit
) {
    val context = LocalContext.current
    val preferences = runtime.preferencesManager
    val shizukuRepository = runtime.shizukuRepository
    val overlaySide by preferences.overlaySide.collectAsState(initial = OverlaySide.LEFT)
    val verticalFraction by preferences.overlayVerticalFraction.collectAsState(initial = 0.5f)
    val dotScaleConfig by preferences.volumeDotScaleConfig.collectAsState(initial = VolumeDotScaleConfig())
    val baseAppSettings by preferences.appSettings.collectAsState(initial = emptyMap())
    val profileAppVolumes by runtime.profileCoordinator.effectiveAppVolumes.collectAsState()
    val appSettings = remember(baseAppSettings, profileAppVolumes) {
        baseAppSettings.mapValues { (identity, setting) ->
            setting.copy(defaultVolume = profileAppVolumes[identity] ?: setting.defaultVolume)
        }
    }
    val appOverlayOrder by preferences.appOverlayOrder.collectAsState(initial = emptyList())
    val appSettingsStoreHealth by preferences.appSettingsStoreHealth.collectAsState(
        initial = AppSettingsStoreHealth.HEALTHY
    )
    val passThroughPackages by preferences.volumeKeyPassThroughPackages.collectAsState(initial = emptySet())
    val pauseDuration by preferences.amplyPauseDuration.collectAsState(initial = AmplyPauseDuration.FIVE_MINUTES)
    val pausedUntil by preferences.amplyPausedUntilEpochMs.collectAsState(initial = 0L)
    val disableShizukuDisconnectedWarning by preferences.disableShizukuDisconnectedWarning.collectAsState(
        initial = false
    )
    val hidePerAppVolumeControl by preferences.hidePerAppVolumeControl.collectAsState(initial = false)
    val hideAppProfileIdentity by preferences.hideAppProfileIdentity.collectAsState(initial = true)
    val hideStandDownButton by preferences.hideStandDownButton.collectAsState(initial = false)
    val showDndButton by preferences.showDndButton.collectAsState(initial = false)
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
    val hapticFeedback = LocalHapticFeedback.current
    val dashboardDensity = LocalDensity.current
    val inactiveVolumeSaveJobs = remember { mutableMapOf<String, Job>() }
    var selectedTabName by rememberSaveable { mutableStateOf(SettingsTab.ACCESS.name) }
    val selectedTab = SettingsTab.entries.firstOrNull { it.name == selectedTabName } ?: SettingsTab.ACCESS
    val pagerState = rememberPagerState(
        initialPage = SettingsTab.entries.indexOf(selectedTab),
        pageCount = { SettingsTab.entries.size }
    )
    val profileEditorNavigation = remember { ProfileEditorNavigationGuard() }
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
    var accessibleProfileCount by remember { mutableIntStateOf(1) }
    var staleAppCount by remember { mutableIntStateOf(0) }
    var pendingImportRaw by remember { mutableStateOf<String?>(null) }
    var pendingImportPreview by remember { mutableStateOf<SettingsImportPreview?>(null) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    var showCleanupConfirmation by remember { mutableStateOf(false) }
    var showRepairConfirmation by remember { mutableStateOf(false) }
    var showDndPermissionFallback by remember { mutableStateOf(false) }
    var waitingForDndPermission by remember { mutableStateOf(false) }
    var wentToSettingsForDndPermission by remember { mutableStateOf(false) }

    val currentAppPermissionState by rememberUpdatedState(appPermissionState)
    val currentShowDndButton by rememberUpdatedState(showDndButton)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    if (waitingForDndPermission) {
                        wentToSettingsForDndPermission = true
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    val hasPolicyAccess = runtime.dndController.hasPolicyAccess() || currentAppPermissionState.notificationPolicyGranted
                    if (currentShowDndButton && !hasPolicyAccess) {
                        if (waitingForDndPermission && wentToSettingsForDndPermission) {
                            showDndPermissionFallback = true
                            waitingForDndPermission = false
                            wentToSettingsForDndPermission = false
                        }
                    } else if (hasPolicyAccess) {
                        showDndPermissionFallback = false
                        waitingForDndPermission = false
                        wentToSettingsForDndPermission = false
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    var pendingRingerTest by remember { mutableStateOf<com.agentkosticka.amply.audio.ringer.RingerExperimentMethod?>(null) }
    var draggedAppIdentity by remember { mutableStateOf<AppIdentity?>(null) }
    var draggedOverlayTopWindow by remember { mutableFloatStateOf(0f) }
    var draggedRowBounds by remember { mutableStateOf<Rect?>(null) }
    var transientAppOrder by remember { mutableStateOf<List<AppIdentity>?>(null) }
    val reorderAutoScroll = remember { ReorderAutoScrollState() }
    val reorderMotion = remember { ReorderMotionState() }
    val reorderGestureHost = remember { AppReorderGestureHost() }
    val tabNavigation = remember { UiJobHolder() }

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
    val showProfileIdentityToggle = remember(
        accessibleProfileCount,
        appSettings,
        sessionState.sessions
    ) {
        shouldShowAppProfilePrivacy(
            accessibleProfileCount = accessibleProfileCount,
            knownIdentities = appSettings.keys + sessionState.sessions.map { it.identity },
            personalUserId = Process.myUid() / 100_000
        )
    }
    val knownApps = remember(
        appSettings,
        appOverlayOrder,
        sessionState.sessions,
        activeIdentities,
        appSearch,
        appListMode
    ) {
        val query = appSearch.trim().lowercase()
        val orderRank = appOverlayOrder.withIndex().associate { (index, identity) -> identity to index }
        mergeAppSettingsWithActiveSessions(appSettings, sessionState.sessions)
            .filter { it.lastSeenTimestamp > 0L || it.identity in activeIdentities }
            .filter {
                query.isEmpty() || query in it.appName.lowercase() || query in it.packageName.lowercase()
            }
            .filter { appListMode == AppListMode.EXPANDED || it.identity in activeIdentities }
            .sortedWith(
                compareBy<AppSettings> { orderRank[it.identity] ?: Int.MAX_VALUE }
                    .thenByDescending { it.overlayMode == OverlayAppMode.PINNED }
                    .thenByDescending { it.identity in activeIdentities }
                    .thenBy { it.appName.lowercase() }
            )
    }
    val displayedKnownApps = remember(knownApps, transientAppOrder) {
        val transientOrder = transientAppOrder ?: return@remember knownApps
        val appsByIdentity = knownApps.associateBy { it.identity }
        buildList {
            transientOrder.mapNotNullTo(this) { appsByIdentity[it] }
            knownApps.filterTo(this) { candidate -> none { it.identity == candidate.identity } }
        }
    }
    val allKnownAppIdentities = remember(appOverlayOrder, appSettings, sessionState.sessions) {
        buildList {
            addAll(appOverlayOrder)
            addAll(appSettings.keys)
            addAll(sessionState.sessions.map { it.identity })
        }.distinct()
    }
    val persistVisibleAppOrder: (List<AppIdentity>) -> Unit = { visibleOrder ->
        val completeOrder = mergeVisibleAppOrder(
            existingOrder = appOverlayOrder,
            allKnownApps = allKnownAppIdentities,
            reorderedVisibleApps = visibleOrder
        )
        transientAppOrder = visibleOrder
        scope.launch { preferences.setAppOverlayOrder(completeOrder) }
    }
    fun updateDraggedAppPosition() {
        val draggedIdentity = draggedAppIdentity ?: return
        val layoutInfo = appsListState.layoutInfo
        val draggedInfo = layoutInfo.visibleItemsInfo.firstOrNull {
            it.key == "app-${draggedIdentity.storageKey}"
        } ?: return
        if (
            reorderMotion.expectedLazyIndex != null &&
            draggedInfo.index != reorderMotion.expectedLazyIndex
        ) return

        val currentOrder = transientAppOrder ?: knownApps.map { it.identity }
        val fromIndex = currentOrder.indexOf(draggedIdentity)
        if (fromIndex < 0) return
        val visualTop = clampDraggedAppTop(
            rawTop = reorderMotion.initialOffset + reorderMotion.distance,
            itemSize = draggedInfo.size,
            viewportStart = layoutInfo.viewportStartOffset,
            viewportEnd = layoutInfo.viewportEndOffset
        )
        val visualCenter = visualTop + draggedInfo.size / 2f
        fun itemInfoAt(orderIndex: Int) = currentOrder.getOrNull(orderIndex)?.let { identity ->
            layoutInfo.visibleItemsInfo.firstOrNull {
                it.key == "app-${identity.storageKey}"
            }
        }
        val previousInfo = itemInfoAt(fromIndex - 1)
        val nextInfo = itemInfoAt(fromIndex + 1)
        val toIndex = adjacentReorderTarget(
            currentIndex = fromIndex,
            lastIndex = currentOrder.lastIndex,
            draggedCenter = visualCenter,
            previousCenter = previousInfo?.let { it.offset + it.size / 2f },
            nextCenter = nextInfo?.let { it.offset + it.size / 2f }
        )
        if (toIndex != fromIndex) {
            val targetInfo = (if (toIndex < fromIndex) previousInfo else nextInfo)
                ?: return
            transientAppOrder = moveAppIdentity(currentOrder, fromIndex, toIndex)
            reorderMotion.expectedLazyIndex = targetInfo.index
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    fun updateDraggedOverlayPosition() {
        val draggedIdentity = draggedAppIdentity ?: return
        val layoutInfo = appsListState.layoutInfo
        val draggedInfo = layoutInfo.visibleItemsInfo.firstOrNull {
            it.key == "app-${draggedIdentity.storageKey}"
        }
        val itemSize = draggedInfo?.size
            ?: reorderMotion.itemSize.takeIf { it > 0 }
            ?: draggedRowBounds?.height?.roundToInt()
            ?: return
        val visualTop = clampDraggedAppTop(
            rawTop = reorderMotion.initialOffset + reorderMotion.distance,
            itemSize = itemSize,
            viewportStart = layoutInfo.viewportStartOffset,
            viewportEnd = layoutInfo.viewportEndOffset
        )
        draggedOverlayTopWindow = reorderGestureHost.listCoordinates
            ?.localToWindow(Offset(0f, visualTop))
            ?.y
            ?: draggedOverlayTopWindow
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
    LaunchedEffect(context) {
        accessibleProfileCount = withContext(Dispatchers.IO) {
            runCatching {
                context.getSystemService(LauncherApps::class.java).profiles.distinct().size
            }.getOrDefault(1).coerceAtLeast(1)
        }
    }
    LaunchedEffect(pagerState) {
        // Exercise the programmatic pager path after all pages have been precomposed. The offset is
        // roughly one physical pixel, avoiding a visible transition while removing first-use JIT
        // and measure work from the user's first tab press.
        withFrameNanos { _ -> }
        withFrameNanos { _ -> }
        if (!pagerState.isScrollInProgress && tabNavigation.job?.isActive != true) {
            val page = pagerState.currentPage
            pagerState.animateScrollToPage(
                page = page,
                pageOffsetFraction = 0.001f,
                animationSpec = tween(durationMillis = 32, easing = LinearEasing)
            )
            pagerState.scrollToPage(page)
        }
    }
    LaunchedEffect(pagerState.settledPage) {
        val page = pagerState.settledPage.coerceIn(SettingsTab.entries.indices)
        selectedTabName = SettingsTab.entries[page].name
        if (tutorialStage.isAppTour) {
            val landedStage = SettingsTab.entries[page].tutorialStage
            val currentIndex = TutorialStage.appStages.indexOf(tutorialStage)
            val landedIndex = landedStage?.let(TutorialStage.appStages::indexOf) ?: -1
            if (landedStage != null && landedIndex >= 0 && abs(landedIndex - currentIndex) <= 1) {
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
    LaunchedEffect(appOverlayOrder) {
        if (draggedAppIdentity == null) transientAppOrder = null
    }
    DisposableEffect(Unit) {
        onDispose {
            inactiveVolumeSaveJobs.values.forEach(Job::cancel)
            positionSaveJob?.cancel()
            reorderAutoScroll.job?.cancel()
            tabNavigation.job?.cancel()
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
                    val navigate = {
                        tabNavigation.job?.cancel()
                        tabNavigation.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            val pageDistance = abs(index - pagerState.currentPage)
                            pagerState.animateScrollToPage(
                                page = index,
                                animationSpec = tween(
                                    durationMillis = 330 + (pageDistance - 1).coerceAtLeast(0) * 45,
                                    easing = FastOutSlowInEasing
                                )
                            )
                        }
                    }
                    if (pagerState.currentPage == SettingsTab.PROFILES.ordinal &&
                        index != pagerState.currentPage && profileEditorNavigation.editing
                    ) {
                        profileEditorNavigation.requestExit(navigate)
                    } else {
                        navigate()
                    }
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
            beyondViewportPageCount = SettingsTab.entries.lastIndex,
            userScrollEnabled = !profileEditorNavigation.editing
        ) { page ->
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)) {
                    DashboardHeader()
                }

            when (SettingsTab.entries[page]) {
                SettingsTab.APPS -> LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .lazyScrollProgressIndicator(listStates.getValue(SettingsTab.APPS))
                        .appReorderGestures(reorderGestureHost),
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
                    if (showProfileIdentityToggle) {
                        item {
                            SettingsPanel {
                                OverlayPreferenceToggle(
                                    title = "Hide personal/work app identity",
                                    description = "Show only the app name and hide profile labels such as Work or Personal.",
                                    checked = hideAppProfileIdentity,
                                    onCheckedChange = { hidden ->
                                        scope.launch { preferences.setHideAppProfileIdentity(hidden) }
                                    }
                                )
                            }
                        }
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
                    if (displayedKnownApps.isEmpty()) {
                        item {
                            SettingsPanel {
                                Text(
                                    if (appListMode == AppListMode.DEFAULT) "NO APPS PLAYING RIGHT NOW" else "NO AUDIO APPS SEEN YET",
                                    color = NothingColors.GreyMedium
                                )
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = displayedKnownApps,
                            key = { _, app -> "app-${app.identity.storageKey}" },
                            contentType = { _, _ -> "app-settings-row" }
                        ) { index, app ->
                            val isDragged = draggedAppIdentity == app.identity
                            val startReorder: () -> Unit = {
                                val itemInfo = appsListState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.key == "app-${app.identity.storageKey}" }
                                draggedAppIdentity = app.identity
                                reorderMotion.distance = 0f
                                reorderMotion.initialOffset = itemInfo?.offset ?: 0
                                reorderMotion.itemSize = itemInfo?.size
                                    ?: reorderGestureHost.rowBounds[app.identity]?.height?.roundToInt()
                                    ?: with(dashboardDensity) { 220.dp.roundToPx() }
                                reorderMotion.expectedLazyIndex = itemInfo?.index
                                draggedRowBounds = reorderGestureHost.rowBounds[app.identity]
                                draggedOverlayTopWindow = draggedRowBounds?.top ?: 0f
                                transientAppOrder = displayedKnownApps.map { it.identity }
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            val dragReorder: (Float) -> Unit = { dragAmount ->
                                if (draggedAppIdentity == app.identity) {
                                    reorderMotion.distance += dragAmount
                                    val itemInfo = appsListState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.key == "app-${app.identity.storageKey}" }
                                    val layoutInfo = appsListState.layoutInfo
                                    updateDraggedAppPosition()
                                    updateDraggedOverlayPosition()
                                    val rawVisualTop = reorderMotion.initialOffset + reorderMotion.distance
                                    val edge = with(dashboardDensity) { 72.dp.toPx() }
                                    val itemSize = itemInfo?.size ?: reorderMotion.itemSize
                                    val topEdgeDepth = (
                                        layoutInfo.viewportStartOffset + edge - rawVisualTop
                                        ).coerceAtLeast(0f)
                                    val bottomEdgeDepth = (
                                        rawVisualTop + itemSize -
                                            (layoutInfo.viewportEndOffset - edge)
                                        ).coerceAtLeast(0f)
                                    val requestedScrollDirection = when {
                                        topEdgeDepth > 0f -> -1f
                                        bottomEdgeDepth > 0f -> 1f
                                        else -> 0f
                                    }
                                    val edgeDepth = if (requestedScrollDirection < 0f) {
                                        topEdgeDepth
                                    } else {
                                        bottomEdgeDepth
                                    }
                                    val edgeIntensity = (edgeDepth / edge).coerceIn(0f, 1f)
                                    val speedDpPerSecond =
                                        180f + 720f * edgeIntensity * edgeIntensity
                                    reorderAutoScroll.direction = when {
                                        requestedScrollDirection < 0f -> -1f
                                        requestedScrollDirection > 0f -> 1f
                                        else -> 0f
                                    }
                                    reorderAutoScroll.velocityPxPerSecond =
                                        reorderAutoScroll.direction *
                                            with(dashboardDensity) { speedDpPerSecond.dp.toPx() }
                                    if (
                                        reorderAutoScroll.direction != 0f &&
                                        reorderAutoScroll.job?.isActive != true
                                    ) {
                                        reorderAutoScroll.job = scope.launch {
                                            appsListState.scroll(MutatePriority.PreventUserInput) {
                                                var previousFrameNanos = withFrameNanos { it }
                                                while (
                                                    draggedAppIdentity == app.identity &&
                                                    reorderAutoScroll.direction != 0f
                                                ) {
                                                    val frameNanos = withFrameNanos { it }
                                                    val elapsedSeconds = (
                                                        (frameNanos - previousFrameNanos) /
                                                            1_000_000_000f
                                                        ).coerceIn(1f / 240f, 1f / 30f)
                                                    previousFrameNanos = frameNanos
                                                    val currentOrder = transientAppOrder
                                                        ?: knownApps.map { it.identity }
                                                    val currentIndex = currentOrder.indexOf(app.identity)
                                                    val currentLayout = appsListState.layoutInfo
                                                    val currentInfo = currentLayout.visibleItemsInfo
                                                        .firstOrNull {
                                                            it.key == "app-${app.identity.storageKey}"
                                                        }
                                                    var frameScroll =
                                                        reorderAutoScroll.velocityPxPerSecond *
                                                            elapsedSeconds
                                                    frameScroll = capReorderEdgeScrollDelta(
                                                        requestedDelta = frameScroll,
                                                        draggedOrderIndex = currentIndex,
                                                        lastOrderIndex = currentOrder.lastIndex,
                                                        renderedLazyIndex = currentInfo?.index,
                                                        expectedLazyIndex =
                                                            reorderMotion.expectedLazyIndex,
                                                        itemOffset = currentInfo?.offset,
                                                        itemSize = currentInfo?.size
                                                            ?: reorderMotion.itemSize,
                                                        viewportStart =
                                                            currentLayout.viewportStartOffset,
                                                        viewportEnd =
                                                            currentLayout.viewportEndOffset
                                                    )
                                                    if (frameScroll == 0f) {
                                                        reorderAutoScroll.direction = 0f
                                                        reorderAutoScroll.velocityPxPerSecond = 0f
                                                        break
                                                    }

                                                    val consumed = scrollBy(frameScroll)
                                                    updateDraggedAppPosition()
                                                    updateDraggedOverlayPosition()
                                                    if (abs(consumed) < 0.5f) {
                                                        reorderAutoScroll.direction = 0f
                                                        reorderAutoScroll.velocityPxPerSecond = 0f
                                                        break
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            val endReorder: () -> Unit = {
                                if (draggedAppIdentity == app.identity) {
                                    val reordered = transientAppOrder
                                        ?: displayedKnownApps.map { it.identity }
                                    reorderAutoScroll.job?.cancel()
                                    reorderAutoScroll.job = null
                                    reorderAutoScroll.direction = 0f
                                    reorderAutoScroll.velocityPxPerSecond = 0f
                                    persistVisibleAppOrder(reordered)
                                    scope.launch {
                                        withFrameNanos { _ -> }
                                        withFrameNanos { _ -> }
                                        val targetInfo = appsListState.layoutInfo.visibleItemsInfo
                                            .firstOrNull {
                                                it.key == "app-${app.identity.storageKey}"
                                            }
                                        val targetTop = targetInfo?.let { info ->
                                            reorderGestureHost.listCoordinates
                                                ?.localToWindow(
                                                    Offset(
                                                        0f,
                                                        info.offset.toFloat()
                                                    )
                                                )
                                                ?.y
                                        } ?: reorderGestureHost.rowBounds[app.identity]?.top
                                            ?: draggedOverlayTopWindow
                                        Animatable(draggedOverlayTopWindow).animateTo(
                                            targetValue = targetTop,
                                            animationSpec = tween(
                                                durationMillis = 140,
                                                easing = FastOutSlowInEasing
                                            )
                                        ) {
                                            draggedOverlayTopWindow = value
                                        }
                                        draggedAppIdentity = null
                                        reorderMotion.distance = 0f
                                        reorderMotion.itemSize = 0
                                        reorderMotion.expectedLazyIndex = null
                                        draggedRowBounds = null
                                    }
                                }
                            }
                            SideEffect {
                                reorderGestureHost.callbacks[app.identity] = AppReorderCallbacks(
                                    onStart = startReorder,
                                    onDrag = dragReorder,
                                    onEnd = endReorder
                                )
                            }
                            DisposableEffect(app.identity) {
                                onDispose {
                                    reorderGestureHost.handleBounds.remove(app.identity)
                                    reorderGestureHost.rowBounds.remove(app.identity)
                                }
                            }
                            if (isDragged) {
                                val placeholderHeight = reorderMotion.itemSize
                                    .takeIf { it > 0 }
                                    ?: with(dashboardDensity) { 220.dp.roundToPx() }
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(with(dashboardDensity) { placeholderHeight.toDp() })
                                )
                            } else AppSettingsRow(
                                app = app,
                                isActive = app.identity in activeIdentities,
                                hideProfileIdentity = hideAppProfileIdentity,
                                enabled = appSettingsStoreHealth != AppSettingsStoreHealth.CORRUPT,
                                modifier = Modifier
                                    .animateItem(
                                        fadeInSpec = null,
                                        placementSpec = tween(
                                            durationMillis = 110,
                                            easing = FastOutSlowInEasing
                                        ),
                                        fadeOutSpec = null
                                    )
                                    .graphicsLayer {
                                        shape = RoundedCornerShape(27.dp)
                                        alpha = if (isDragged) 0f else 1f
                                    }
                                    .onGloballyPositioned { coordinates ->
                                        reorderGestureHost.rowBounds[app.identity] =
                                            coordinates.boundsInWindow()
                                    },
                                reorderEnabled = displayedKnownApps.size > 1,
                                canMoveUp = index > 0,
                                canMoveDown = index < displayedKnownApps.lastIndex,
                                onMoveUp = {
                                    val reordered = moveAppIdentity(
                                        displayedKnownApps.map { it.identity },
                                        index,
                                        index - 1
                                    )
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    persistVisibleAppOrder(reordered)
                                },
                                onMoveDown = {
                                    val reordered = moveAppIdentity(
                                        displayedKnownApps.map { it.identity },
                                        index,
                                        index + 1
                                    )
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    persistVisibleAppOrder(reordered)
                                },
                                onReorderHandleBoundsChanged = { bounds ->
                                    reorderGestureHost.handleBounds[app.identity] = bounds
                                },
                                onReset = { scope.launch { preferences.resetApp(app.identity) } },
                                onOverlayModeChange = { mode ->
                                    scope.launch { preferences.setAppOverlayMode(app.packageName, mode, app.uid) }
                                },
                                onVolumeChange = { volume ->
                                    val target = AppVolumeTarget(
                                        packageName = app.packageName,
                                        uid = app.uid,
                                        appName = app.appName
                                    )
                                    val active = activeSessionsByIdentity[app.identity]
                                    if (active != null) {
                                        runtime.audioSessionManager.setAppVolume(target, volume)
                                    } else {
                                        val saveKey = app.identity.storageKey
                                        inactiveVolumeSaveJobs.remove(saveKey)?.cancel()
                                        inactiveVolumeSaveJobs[saveKey] = scope.launch {
                                            delay(300L.milliseconds)
                                            runtime.audioSessionManager.setAppVolume(target, volume)
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

                SettingsTab.PROFILES -> ProfilesSettingsPage(
                    runtime = runtime,
                    nearbyDevicesGranted = appPermissionState.nearbyDevicesGranted,
                    notificationPolicyGranted = appPermissionState.notificationPolicyGranted,
                    onNearbyDevicesClick = onNearbyDevicesClick,
                    onNotificationPolicyClick = onNotificationPolicyClick,
                    navigationGuard = profileEditorNavigation,
                    modifier = Modifier.weight(1f)
                )

                SettingsTab.PILL -> LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .lazyScrollProgressIndicator(listStates.getValue(SettingsTab.PILL)),
                    state = listStates.getValue(SettingsTab.PILL),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
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
                    item {
                        SectionTitle(
                            stringResource(com.agentkosticka.amply.R.string.pill_optional_controls)
                        )
                    }
                    item {
                        SettingsPanel {
                            OverlayPreferenceToggle(
                                title = stringResource(com.agentkosticka.amply.R.string.pill_hide_shizuku_warning),
                                description = stringResource(com.agentkosticka.amply.R.string.pill_hide_shizuku_warning_description),
                                checked = disableShizukuDisconnectedWarning,
                                onCheckedChange = { disabled ->
                                    scope.launch {
                                        preferences.setDisableShizukuDisconnectedWarning(disabled)
                                    }
                                }
                            )
                        }
                    }
                    item {
                        SettingsPanel {
                            OverlayPreferenceToggle(
                                title = stringResource(com.agentkosticka.amply.R.string.pill_hide_per_app_control),
                                description = stringResource(com.agentkosticka.amply.R.string.pill_hide_per_app_control_description),
                                checked = hidePerAppVolumeControl,
                                onCheckedChange = { hidden ->
                                    scope.launch { preferences.setHidePerAppVolumeControl(hidden) }
                                }
                            )
                        }
                    }
                    item {
                        SettingsPanel {
                            OverlayPreferenceToggle(
                                title = stringResource(com.agentkosticka.amply.R.string.pill_show_dnd),
                                description = stringResource(com.agentkosticka.amply.R.string.pill_show_dnd_description),
                                checked = showDndButton,
                                onCheckedChange = { show ->
                                    scope.launch { preferences.setShowDndButton(show) }
                                    if (show && !runtime.dndController.hasPolicyAccess()) {
                                        waitingForDndPermission = true
                                        wentToSettingsForDndPermission = false
                                        showDndPermissionFallback = false
                                        onNotificationPolicyClick()
                                    }
                                }
                            )
                        }
                    }
                    item {
                        SettingsPanel {
                            OverlayPreferenceToggle(
                                title = stringResource(com.agentkosticka.amply.R.string.pill_hide_stand_down),
                                description = stringResource(com.agentkosticka.amply.R.string.pill_hide_stand_down_description),
                                checked = hideStandDownButton,
                                onCheckedChange = { hidden ->
                                    scope.launch { preferences.setHideStandDownButton(hidden) }
                                }
                            )
                        }
                    }
                }

                SettingsTab.STAND_DOWN -> LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .lazyScrollProgressIndicator(listStates.getValue(SettingsTab.STAND_DOWN)),
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
                        items(
                            items = standDownApps,
                            key = { "stand-down-${it.packageName}" },
                            contentType = { "stand-down-app-row" }
                        ) { app ->
                            StandDownAppRow(
                                app,
                                app.packageName in passThroughPackages
                            ) { enabled ->
                                scope.launch {
                                    preferences.setPassVolumeKeysToApp(app.packageName, enabled)
                                }
                            }
                        }
                    }
                }

                SettingsTab.ACCESS -> LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .lazyScrollProgressIndicator(listStates.getValue(SettingsTab.ACCESS)),
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
                    modifier = Modifier
                        .weight(1f)
                        .lazyScrollProgressIndicator(listStates.getValue(SettingsTab.DIAGNOSTICS)),
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

        val draggedPreviewApp = draggedAppIdentity?.let { identity ->
            displayedKnownApps.firstOrNull { it.identity == identity }
                ?: knownApps.firstOrNull { it.identity == identity }
        }
        val previewBounds = draggedRowBounds
        if (draggedPreviewApp != null && previewBounds != null) {
            AppSettingsRow(
                app = draggedPreviewApp,
                isActive = draggedPreviewApp.identity in activeIdentities,
                enabled = appSettingsStoreHealth != AppSettingsStoreHealth.CORRUPT,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = previewBounds.left.roundToInt(),
                            y = draggedOverlayTopWindow.roundToInt()
                        )
                    }
                    .width(with(dashboardDensity) { previewBounds.width.toDp() })
                    .graphicsLayer {
                        scaleX = 1.015f
                        scaleY = 1.015f
                        shadowElevation = with(dashboardDensity) { 10.dp.toPx() }
                        shape = RoundedCornerShape(27.dp)
                    },
                reorderEnabled = true,
                onReset = {},
                onOverlayModeChange = {},
                onVolumeChange = {}
            )
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (preview.valid) Arrangement.SpaceBetween else Arrangement.End
                ) {
                    if (preview.valid) {
                        TextButton(
                            onClick = {
                                val raw = pendingImportRaw ?: return@TextButton
                                scope.launch {
                                    when (val result = preferences.importSettings(raw, ImportMode.MERGE)) {
                                        SettingsOperationResult.Success -> {
                                            Toast.makeText(context, "Settings merged", Toast.LENGTH_SHORT).show()
                                            if (preferences.showDndButton.first() && !runtime.dndController.hasPolicyAccess()) {
                                                showDndPermissionFallback = true
                                            }
                                        }
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
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) { Text("MERGE", color = NothingColors.White, maxLines = 1, softWrap = false) }
                        TextButton(
                            onClick = {
                                val raw = pendingImportRaw ?: return@TextButton
                                scope.launch {
                                    when (val result = preferences.importSettings(raw, ImportMode.REPLACE)) {
                                        SettingsOperationResult.Success -> {
                                            Toast.makeText(context, "Settings replaced", Toast.LENGTH_SHORT).show()
                                            if (preferences.showDndButton.first() && !runtime.dndController.hasPolicyAccess()) {
                                                showDndPermissionFallback = true
                                            }
                                        }
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
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) { Text("REPLACE", color = NothingColors.Red, maxLines = 1, softWrap = false) }
                    }
                    TextButton(
                        onClick = {
                            pendingImportRaw = null
                            pendingImportPreview = null
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) { Text("CANCEL", color = NothingColors.GreyMedium, maxLines = 1, softWrap = false) }
                }
            },
            containerColor = Color(0xFF1C1C1C),
            titleContentColor = NothingColors.White,
            textContentColor = NothingColors.GreyMedium
        )
    }

    if (showDndPermissionFallback) {
        AlertDialog(
            onDismissRequest = {
                showDndPermissionFallback = false
                waitingForDndPermission = false
                wentToSettingsForDndPermission = false
                scope.launch { preferences.setShowDndButton(false) }
            },
            title = { Text("CAN'T ENABLE DND BUTTON WITHOUT PERMISSIONS") },
            text = {
                Text(
                    "Amply requires Do Not Disturb (Notification Policy) access to show the DND button. Please grant permissions or disable the button."
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = {
                            waitingForDndPermission = true
                            wentToSettingsForDndPermission = false
                            showDndPermissionFallback = false
                            onNotificationPolicyClick()
                        }
                    ) {
                        Text("GIVE PERMISSIONS", color = NothingColors.White, maxLines = 1, softWrap = false)
                    }
                    TextButton(
                        onClick = {
                            showDndPermissionFallback = false
                            waitingForDndPermission = false
                            wentToSettingsForDndPermission = false
                            scope.launch { preferences.setShowDndButton(false) }
                        }
                    ) {
                        Text("DISABLE DND BUTTON", color = NothingColors.Red, maxLines = 1, softWrap = false)
                    }
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
        SettingsTab.PROFILES -> null
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
