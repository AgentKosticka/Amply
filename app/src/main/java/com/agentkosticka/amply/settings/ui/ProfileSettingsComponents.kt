package com.agentkosticka.amply.settings.ui

import android.os.Process
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RingVolume
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.agentkosticka.amply.AmplyRuntime
import com.agentkosticka.amply.audio.ringer.NotificationAlertMode
import com.agentkosticka.amply.audio.routing.VolumeBarModel
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.overlay.ui.HorizontalDraggableDotSlider
import com.agentkosticka.amply.profiles.AudioProfile
import com.agentkosticka.amply.profiles.AudioProfileSnapshot
import com.agentkosticka.amply.profiles.KnownOutputDevice
import com.agentkosticka.amply.profiles.OutputKind
import com.agentkosticka.amply.profiles.PROFILE_SYSTEM_TARGETS
import com.agentkosticka.amply.profiles.ProfileSaveMode
import com.agentkosticka.amply.profiles.normalizedVolume
import com.agentkosticka.amply.profiles.volumeIndex
import com.agentkosticka.amply.settings.model.AppIdentity
import com.agentkosticka.amply.settings.model.AppSettings
import com.agentkosticka.amply.settings.model.VolumeDotScaleConfig
import com.agentkosticka.amply.settings.model.appDisplayName
import com.agentkosticka.amply.settings.model.appProfileFallbackLabel
import com.agentkosticka.amply.ui.theme.NothingColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal class ProfileEditorNavigationGuard {
    var editing by mutableStateOf(false)
        private set
    private var exitHandler: (((() -> Unit)) -> Unit)? = null

    fun attach(handler: ((() -> Unit)) -> Unit) {
        editing = true
        exitHandler = handler
    }

    fun detach() {
        editing = false
        exitHandler = null
    }

    fun requestExit(afterExit: () -> Unit = {}) {
        exitHandler?.invoke(afterExit) ?: afterExit()
    }
}

@Composable
internal fun ProfilesSettingsPage(
    runtime: AmplyRuntime,
    nearbyDevicesGranted: Boolean,
    notificationPolicyGranted: Boolean,
    onNearbyDevicesClick: () -> Unit,
    onNotificationPolicyClick: () -> Unit,
    navigationGuard: ProfileEditorNavigationGuard,
    modifier: Modifier = Modifier
) {
    val state by runtime.profileCoordinator.state.collectAsState()
    val appSettings by runtime.preferencesManager.appSettings.collectAsState(initial = emptyMap())
    val hideProfileIdentity by runtime.preferencesManager.hideAppProfileIdentity.collectAsState(initial = true)
    val dynamicStreams by runtime.dynamicStreamState.collectAsState()
    val dotConfig by runtime.preferencesManager.volumeDotScaleConfig.collectAsState(initial = VolumeDotScaleConfig())
    val scope = rememberCoroutineScope()
    val profilesListState = rememberLazyListState()
    var creating by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingId?.let(state.store.profiles::get)

    if (editing != null) {
        ProfileEditor(
            profile = editing,
            appSettings = appSettings,
            devices = state.store.devices.values.toList(),
            systemBars = runtime.profileCoordinator.systemVolumeBars(dotConfig),
            notificationPolicyGranted = notificationPolicyGranted,
            ringNotificationLinked = dynamicStreams.topology.aliasesTogether(
                VolumeTarget.RING,
                VolumeTarget.NOTIFICATION
            ),
            hideProfileIdentity = hideProfileIdentity,
            navigationGuard = navigationGuard,
            modifier = modifier,
            onNotificationPolicyClick = onNotificationPolicyClick,
            onDiscard = { editingId = null },
            onSave = { name, snapshot, assignedKeys, afterSave ->
                scope.launch {
                    if (runtime.profileCoordinator.updateProfile(editing.id, name, snapshot)) {
                        state.store.devices.keys.forEach { key ->
                            val current = state.store.devices[key]
                            if (key in assignedKeys) {
                                runtime.profileCoordinator.assignDevice(key, editing.id)
                            } else if (current?.assignedProfileId == editing.id) {
                                runtime.profileCoordinator.assignDevice(key, null)
                            }
                        }
                        editingId = null
                        afterSave()
                    }
                }
            }
        )
        return
    }

    DisposableEffect(Unit) {
        navigationGuard.detach()
        onDispose { navigationGuard.detach() }
    }

    LazyColumn(
        state = profilesListState,
        modifier = modifier
            .fillMaxWidth()
            .lazyScrollProgressIndicator(profilesListState),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionTitle("CURRENT PROFILE")
            Spacer(Modifier.height(10.dp))
            SettingsPanel {
                Text(
                    state.activeProfile?.name ?: "No active profile",
                    color = NothingColors.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    listOfNotNull(
                        state.currentOutput?.displayName,
                        when {
                            state.activeProfile?.saveMode == ProfileSaveMode.AUTO_DEVICE -> "Auto-saving"
                            state.dirty -> "Unsaved changes"
                            state.activeProfile != null -> "Saved"
                            else -> null
                        }
                    ).joinToString(" · "),
                    color = if (state.dirty) NothingColors.Red else NothingColors.GreyMedium
                )
                if (state.dirty) {
                    Spacer(Modifier.height(10.dp))
                    NothingButton("SAVE CURRENT SETTINGS", Icons.Default.Save) {
                        scope.launch { runtime.profileCoordinator.saveCurrentProfile() }
                    }
                }
                if (state.lastApplyWarnings.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.lastApplyWarnings.joinToString("\n"), color = NothingColors.Red)
                }
            }
        }

        if (!nearbyDevicesGranted) {
            item {
                SettingsPanel {
                    Text("IMPROVE BLUETOOTH MATCHING", color = NothingColors.White, fontWeight = FontWeight.Bold)
                    Text(
                        "Nearby Devices access helps Amply recognize paired Bluetooth outputs. Profiles still work without it.",
                        color = NothingColors.GreyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    NothingButton("ALLOW NEARBY DEVICES", onClick = onNearbyDevicesClick)
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("NAMED PROFILES")
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { creating = true }) {
                    Icon(Icons.Default.Add, "Create profile", tint = NothingColors.Red)
                }
            }
        }
        val named = state.store.profiles.values.filter { it.saveMode == ProfileSaveMode.EXPLICIT }
        if (named.isEmpty()) {
            item { Text("No named profiles yet. Create one from your current settings.", color = NothingColors.GreyMedium) }
        } else {
            items(named.sortedBy { it.createdAtEpochMs }, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    active = profile.id == state.store.activeProfileId,
                    onActivate = { scope.launch { runtime.profileCoordinator.activateProfile(profile.id) } },
                    onEdit = { editingId = profile.id },
                    onDelete = { deletingId = profile.id }
                )
            }
        }

        item { SectionTitle("OUTPUT DEVICES") }
        items(state.store.devices.values.sortedBy { it.descriptor.displayName }, key = { it.descriptor.key }) { device ->
            var menu by remember { mutableStateOf(false) }
            SettingsPanel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(outputIcon(device.descriptor.kind), null, tint = NothingColors.Red)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(device.descriptor.displayName, color = NothingColors.White, fontWeight = FontWeight.Bold)
                        Text(
                            device.assignedProfileId?.let(state.store.profiles::get)?.name ?: "No automatic profile",
                            color = if (device.explicitlyUnassigned) NothingColors.Red else NothingColors.GreyMedium,
                            maxLines = 2
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { menu = true }) { Text("ASSIGN", color = NothingColors.Red) }
                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        containerColor = Color(0xFF1C1C1C)
                    ) {
                        DropdownMenuItem(
                            leadingIcon = {
                                if (device.explicitlyUnassigned) Icon(Icons.Default.Check, null, tint = NothingColors.Red)
                            },
                            text = { Text("No automatic profile", color = NothingColors.White) },
                            onClick = {
                                menu = false
                                scope.launch { runtime.profileCoordinator.assignDevice(device.descriptor.key, null) }
                            }
                        )
                        state.store.profiles.values.sortedBy { it.name }.forEach { profile ->
                            DropdownMenuItem(
                                leadingIcon = {
                                    if (device.assignedProfileId == profile.id) {
                                        Icon(Icons.Default.Check, null, tint = NothingColors.Red)
                                    }
                                },
                                text = { Text(profile.name, color = NothingColors.White) },
                                onClick = {
                                    menu = false
                                    scope.launch { runtime.profileCoordinator.assignDevice(device.descriptor.key, profile.id) }
                                }
                            )
                        }
                    }
                    device.assignedProfileId?.let { profileId ->
                        IconButton(onClick = { editingId = profileId }) {
                            Icon(Icons.Default.Edit, "Edit output profile", tint = NothingColors.GreyMedium)
                        }
                    }
                    IconButton(onClick = { scope.launch { runtime.profileCoordinator.forgetDevice(device.descriptor.key) } }) {
                        Icon(Icons.Default.Delete, "Forget output", tint = NothingColors.GreyMedium)
                    }
                }
            }
        }

        val deviceProfiles = state.store.profiles.values.filter { it.saveMode == ProfileSaveMode.AUTO_DEVICE }
        if (deviceProfiles.isNotEmpty()) {
            item { SectionTitle("DEVICE PROFILES") }
            items(deviceProfiles.sortedBy { it.createdAtEpochMs }, key = { "device-${it.id}" }) { profile ->
                ProfileCard(
                    profile = profile,
                    active = profile.id == state.store.activeProfileId,
                    onActivate = { scope.launch { runtime.profileCoordinator.activateProfile(profile.id) } },
                    onEdit = { editingId = profile.id },
                    onDelete = { deletingId = profile.id }
                )
            }
        }
    }

    if (creating) {
        CreateProfileDialog(
            onDismiss = { creating = false },
            onCreate = { name ->
                scope.launch {
                    runtime.profileCoordinator.createNamedProfile(name)?.let { editingId = it.id }
                    creating = false
                }
            }
        )
    }
    deletingId?.let { id ->
        NothingDialog(
            title = "Delete profile?",
            body = "Current phone settings will stay unchanged. Device assignments using this profile will be cleared.",
            confirmLabel = "DELETE",
            onConfirm = {
                deletingId = null
                scope.launch { runtime.profileCoordinator.deleteProfile(id) }
            },
            dismissLabel = "CANCEL",
            onDismiss = { deletingId = null }
        )
    }
}

@Composable
private fun ProfileCard(
    profile: AudioProfile,
    active: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    SettingsPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile.name, color = NothingColors.White, fontWeight = FontWeight.Bold)
                Text(if (active) "Active" else "Saved profile", color = if (active) NothingColors.Red else NothingColors.GreyMedium)
            }
            TextButton(onClick = onActivate, enabled = !active) {
                Text(if (active) "ACTIVE" else "ACTIVATE", color = if (active) NothingColors.GreyMedium else NothingColors.Red)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit profile", tint = NothingColors.GreyMedium) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete profile", tint = NothingColors.GreyMedium) }
        }
    }
}

@Composable
private fun CreateProfileDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF1C1C1C))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("CREATE PROFILE", color = NothingColors.White, fontWeight = FontWeight.Bold)
            NothingTextField(name, { name = it.take(40) }, "Profile name")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("CANCEL", color = NothingColors.GreyMedium) }
                TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) {
                    Text("CREATE", color = if (name.isNotBlank()) NothingColors.Red else NothingColors.GreyMedium)
                }
            }
        }
    }
}

@Composable
private fun ProfileEditor(
    profile: AudioProfile,
    appSettings: Map<AppIdentity, AppSettings>,
    devices: List<KnownOutputDevice>,
    systemBars: Map<VolumeTarget, VolumeBarModel>,
    notificationPolicyGranted: Boolean,
    ringNotificationLinked: Boolean,
    hideProfileIdentity: Boolean,
    navigationGuard: ProfileEditorNavigationGuard,
    modifier: Modifier = Modifier,
    onNotificationPolicyClick: () -> Unit,
    onDiscard: () -> Unit,
    onSave: (String, AudioProfileSnapshot, Set<String>, () -> Unit) -> Unit
) {
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
    var snapshot by remember(profile.id) { mutableStateOf(profile.snapshot) }
    var search by rememberSaveable(profile.id) { mutableStateOf("") }
    val originalAssigned = remember(profile.id, devices) {
        devices.filter { it.assignedProfileId == profile.id }.mapTo(linkedSetOf()) { it.descriptor.key }
    }
    var assigned by remember(profile.id) { mutableStateOf<Set<String>>(originalAssigned) }
    val dirty = name != profile.name || snapshot != profile.snapshot || assigned != originalAssigned
    var showExitPrompt by remember { mutableStateOf(false) }
    var pendingExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    val editorListState = rememberLazyListState()
    val personalUserId = Process.myUid() / 100_000
    val appPresentations = remember(appSettings, personalUserId, hideProfileIdentity) {
        appSettings.entries.map { (identity, setting) ->
            val base = appDisplayName(
                setting.appName,
                identity.userId,
                personalUserId,
                hideProfileIdentity
            )
            val suffix = appProfileFallbackLabel(
                setting.appName,
                identity.userId,
                personalUserId,
                hideProfileIdentity
            )
            ProfileEditorApp(identity, setting, base, suffix)
        }.sortedBy { it.displayName }
    }
    var adjustedApps by remember(profile.id) { mutableStateOf<Set<AppIdentity>>(emptySet()) }
    var searchFocused by remember { mutableStateOf(false) }
    var imeWasVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val shownApps = appPresentations.filter { presentation ->
        val effectiveVolume = snapshot.appVolumes[presentation.identity] ?: presentation.setting.defaultVolume
        shouldShowProfileApp(
            search = search,
            displayName = presentation.searchText,
            volume = effectiveVolume,
            alreadyRevealed = presentation.identity in adjustedApps
        )
    }
    LaunchedEffect(searchFocused, imeVisible) {
        if (!searchFocused) return@LaunchedEffect
        val searchIndex = editorListState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == "profile-app-search" }
            ?.index
            ?: return@LaunchedEffect
        if (imeVisible) {
            editorListState.scrollToItem(searchIndex)
        } else {
            editorListState.animateScrollToItem(searchIndex)
        }
    }
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            imeWasVisible = true
        } else if (imeWasVisible) {
            focusManager.clearFocus(force = true)
            searchFocused = false
            imeWasVisible = false
        }
    }

    DisposableEffect(profile.id, dirty) {
        navigationGuard.attach { afterExit ->
            if (dirty) {
                pendingExit = afterExit
                showExitPrompt = true
            } else {
                onDiscard()
                afterExit()
            }
        }
        onDispose { navigationGuard.detach() }
    }
    BackHandler { navigationGuard.requestExit() }

    LazyColumn(
        state = editorListState,
        modifier = modifier
            .imePadding()
            .lazyScrollProgressIndicator(editorListState),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "profile-editor-header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "EDIT PROFILE",
                    color = NothingColors.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { navigationGuard.requestExit() }) {
                    Text(
                        "BACK",
                        color = NothingColors.Red,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        item { NothingTextField(name, { name = it.take(40) }, "Profile name") }
        item { SectionTitle("SYSTEM VOLUMES") }
        PROFILE_SYSTEM_TARGETS.forEach { target ->
            item(target.name) {
                val bar = systemBars.getValue(target)
                val fraction = snapshot.systemVolumes[target] ?: 1f
                ProfileSystemVolumeRow(
                    target = target,
                    fraction = fraction,
                    bar = bar,
                    onChange = { changed ->
                        var volumes = snapshot.systemVolumes + (target to changed)
                        if (ringNotificationLinked &&
                            (target == VolumeTarget.RING || target == VolumeTarget.NOTIFICATION)
                        ) {
                            volumes = volumes +
                                (VolumeTarget.RING to changed) +
                                (VolumeTarget.NOTIFICATION to changed)
                        }
                        snapshot = snapshot.copy(systemVolumes = volumes)
                    }
                )
            }
        }
        item {
            SectionTitle("RINGER & DND")
            Spacer(Modifier.height(8.dp))
            SettingsPanel {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NotificationAlertMode.entries.forEach { mode ->
                        ChoicePill(mode.name, snapshot.ringerMode == mode) {
                            snapshot = snapshot.copy(ringerMode = mode)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (notificationPolicyGranted) {
                    CheckRow(
                        label = "Do Not Disturb",
                        checked = snapshot.dndEnabled == true,
                        onCheckedChange = { snapshot = snapshot.copy(dndEnabled = it) }
                    )
                } else {
                    TextButton(onClick = onNotificationPolicyClick) { Text("GRANT DND ACCESS", color = NothingColors.Red) }
                }
            }
        }
        if (devices.isNotEmpty()) {
            item { SectionTitle("DEFAULT OUTPUTS") }
            items(devices, key = { it.descriptor.key }) { device ->
                CheckRow(
                    label = device.descriptor.displayName,
                    checked = device.descriptor.key in assigned,
                    onCheckedChange = { checked ->
                        assigned = if (checked) assigned + device.descriptor.key else assigned - device.descriptor.key
                    }
                )
            }
        }
        item(key = "profile-app-heading") {
            SectionTitle("APP VOLUMES")
        }
        item(key = "profile-app-search") {
            NothingTextField(
                value = search,
                onValueChange = { nextSearch ->
                    adjustedApps = retainedProfileAppsAfterSearchChange(
                        currentSearch = search,
                        nextSearch = nextSearch,
                        adjustedApps = adjustedApps
                    )
                    search = nextSearch
                },
                label = "Search apps",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier
                    .onFocusChanged { searchFocused = it.isFocused }
            )
            if (search.isBlank() && shownApps.isEmpty()) {
                Text(
                    "Apps at 100% are hidden. Search to add or edit one.",
                    color = NothingColors.GreyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        item(key = "profile-app-results-holder") {
            val holderModifier = if (searchFocused) {
                Modifier.fillParentMaxHeight(0.82f)
            } else {
                Modifier
            }
            Column(
                modifier = holderModifier
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                shownApps.forEach { presentation ->
                    key(presentation.identity.storageKey) {
                        val value = snapshot.appVolumes[presentation.identity]
                            ?: presentation.setting.defaultVolume
                        ProfileAppVolumeRow(
                            packageName = presentation.identity.packageName,
                            displayName = presentation.displayName,
                            profileLabel = presentation.profileLabel,
                            fraction = value,
                            onChange = { changed ->
                                adjustedApps = adjustedApps + presentation.identity
                                snapshot = snapshot.copy(
                                    appVolumes = snapshot.appVolumes + (presentation.identity to changed)
                                )
                            }
                        )
                    }
                }
            }
        }
        item {
            Button(
                onClick = { onSave(name, snapshot, assigned) {} },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = NothingColors.Red),
                shape = RoundedCornerShape(18.dp)
            ) { Text("SAVE PROFILE") }
        }
    }

    if (showExitPrompt) {
        NothingDialog(
            title = "Keep changes?",
            body = "Save the changes to ${profile.name} before leaving the editor?",
            confirmLabel = "KEEP CHANGES",
            onConfirm = {
                val after = pendingExit ?: {}
                showExitPrompt = false
                pendingExit = null
                onSave(name, snapshot, assigned, after)
            },
            dismissLabel = "DISCARD CHANGES",
            onDismiss = {
                val after = pendingExit ?: {}
                showExitPrompt = false
                pendingExit = null
                onDiscard()
                after()
            }
        )
    }
}

@Composable
private fun ProfileSystemVolumeRow(
    target: VolumeTarget,
    fraction: Float,
    bar: VolumeBarModel,
    onChange: (Float) -> Unit
) {
    val current = volumeIndex(fraction, bar.minVolume, bar.maxVolume)
    val displayedPercentage = if (bar.maxVolume > 0) {
        ((current.toFloat() / bar.maxVolume.toFloat()) * 100f)
            .roundToInt()
            .coerceIn(0, 100)
    } else {
        0
    }
    SettingsPanel {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    systemIcon(target),
                    null,
                    tint = NothingColors.Red,
                    modifier = Modifier.size(30.dp)
                )
                Text(
                    target.label,
                    color = NothingColors.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 12.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "$displayedPercentage%",
                    color = if (displayedPercentage > 75) NothingColors.Red else NothingColors.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            HorizontalDraggableDotSlider(
                currentVolume = current,
                minVolume = bar.minVolume,
                maxVolume = bar.maxVolume,
                referenceMaxVolume = bar.referenceMaxVolume,
                dotCount = bar.dotCount,
                enabled = bar.enabled,
                onVolumeChange = { onChange(normalizedVolume(it, bar.minVolume, bar.maxVolume)) },
                accessibilityLabel = "${target.label} profile volume",
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp)
            )
            if (bar.minVolume > 0 || bar.maxVolume < bar.referenceMaxVolume) {
                Text(
                    "Unavailable limits stay dimmed",
                    color = NothingColors.GreyMedium,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun ProfileAppVolumeRow(
    packageName: String,
    displayName: String,
    profileLabel: String?,
    fraction: Float,
    onChange: (Float) -> Unit
) {
    val icon = rememberApplicationIconBitmap(packageName, 72)
    val percent = (fraction * 100).roundToInt()
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1C), RoundedCornerShape(27.dp))
            .padding(horizontal = 16.dp, vertical = 15.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(Color(0xFF303030), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Image(icon, displayName, Modifier.size(32.dp))
                } else {
                    Icon(Icons.Default.MusicNote, displayName, tint = NothingColors.GreyMedium)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    displayName,
                    color = NothingColors.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                profileLabel?.let {
                    Text(
                        it,
                        color = NothingColors.GreyMedium,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Text(
                "$percent%",
                color = if (percent > 75) NothingColors.Red else NothingColors.White,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(14.dp))
        AppVolumeRail(
            volume = fraction,
            enabled = true,
            onVolumeChange = onChange,
            accessibilityLabel = "$displayName profile volume",
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun NothingTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = keyboardOptions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = NothingColors.White,
            unfocusedTextColor = NothingColors.White,
            focusedBorderColor = NothingColors.Red,
            unfocusedBorderColor = Color(0xFF444444),
            focusedLabelColor = NothingColors.Red,
            unfocusedLabelColor = NothingColors.GreyMedium,
            cursorColor = NothingColors.Red,
            focusedContainerColor = Color(0xFF151515),
            unfocusedContainerColor = Color(0xFF151515)
        )
    )
}

@Composable
private fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) NothingColors.Red else Color(0xFF2A2A2A))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = NothingColors.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (checked) NothingColors.Red else Color.Transparent)
                .border(1.dp, if (checked) NothingColors.Red else Color(0xFF555555), RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Icon(Icons.Default.Check, null, tint = NothingColors.White, modifier = Modifier.size(17.dp))
        }
        Text(label, color = NothingColors.White, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun NothingButton(label: String, icon: ImageVector? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = NothingColors.Red),
        shape = RoundedCornerShape(18.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(label)
    }
}

@Composable
private fun NothingDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = {}) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF1C1C1C))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title.uppercase(), color = NothingColors.White, fontWeight = FontWeight.Bold)
            Text(body, color = NothingColors.GreyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(dismissLabel, color = NothingColors.GreyMedium) }
                TextButton(onClick = onConfirm) { Text(confirmLabel, color = NothingColors.Red) }
            }
        }
    }
}

private fun systemIcon(target: VolumeTarget): ImageVector = when (target) {
    VolumeTarget.MEDIA -> Icons.Default.MusicNote
    VolumeTarget.RING -> Icons.Default.RingVolume
    VolumeTarget.NOTIFICATION -> Icons.Default.Notifications
    VolumeTarget.ALARM -> Icons.Default.Alarm
    VolumeTarget.CALL -> Icons.Default.Call
    else -> Icons.Default.MusicNote
}

private fun outputIcon(kind: OutputKind): ImageVector = when (kind) {
    OutputKind.SPEAKER -> Icons.Default.PhoneAndroid
    OutputKind.WIRED -> Icons.Default.Headphones
    OutputKind.BLUETOOTH -> Icons.Default.Bluetooth
    OutputKind.CAST -> Icons.Default.Cast
}

internal fun shouldShowProfileApp(
    search: String,
    displayName: String,
    volume: Float,
    alreadyRevealed: Boolean = false
): Boolean = alreadyRevealed ||
    if (search.isNotBlank()) {
        displayName.contains(search, ignoreCase = true)
    } else {
        volume < 0.999f
    }

internal fun retainedProfileAppsAfterSearchChange(
    currentSearch: String,
    nextSearch: String,
    adjustedApps: Set<AppIdentity>
): Set<AppIdentity> = if (currentSearch == nextSearch) adjustedApps else emptySet()

private data class ProfileEditorApp(
    val identity: AppIdentity,
    val setting: AppSettings,
    val displayName: String,
    val profileLabel: String?
) {
    val searchText: String get() = listOfNotNull(displayName, profileLabel).joinToString(" ")
}
