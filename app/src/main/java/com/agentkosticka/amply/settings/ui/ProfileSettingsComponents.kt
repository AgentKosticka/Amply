package com.agentkosticka.amply.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.os.Process
import com.agentkosticka.amply.AmplyRuntime
import com.agentkosticka.amply.audio.ringer.NotificationAlertMode
import com.agentkosticka.amply.profiles.AudioProfile
import com.agentkosticka.amply.profiles.AudioProfileSnapshot
import com.agentkosticka.amply.profiles.OutputKind
import com.agentkosticka.amply.profiles.PROFILE_SYSTEM_TARGETS
import com.agentkosticka.amply.profiles.ProfileSaveMode
import com.agentkosticka.amply.settings.model.AppIdentity
import com.agentkosticka.amply.settings.model.AppSettings
import com.agentkosticka.amply.settings.model.appDisplayName
import com.agentkosticka.amply.settings.model.appProfileFallbackLabel
import com.agentkosticka.amply.ui.theme.NothingColors
import kotlinx.coroutines.launch

@Composable
internal fun ProfilesSettingsPage(
    runtime: AmplyRuntime,
    nearbyDevicesGranted: Boolean,
    notificationPolicyGranted: Boolean,
    onNearbyDevicesClick: () -> Unit,
    onNotificationPolicyClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by runtime.profileCoordinator.state.collectAsState()
    val appSettings by runtime.preferencesManager.appSettings.collectAsState(initial = emptyMap())
    val hideProfileIdentity by runtime.preferencesManager.hideAppProfileIdentity.collectAsState(initial = true)
    val dynamicStreams by runtime.dynamicStreamState.collectAsState()
    val scope = rememberCoroutineScope()
    var creating by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingId?.let(state.store.profiles::get)

    if (editing != null) {
        ProfileEditor(
            profile = editing,
            appSettings = appSettings,
            devices = state.store.devices.values.toList(),
            notificationPolicyGranted = notificationPolicyGranted,
            ringNotificationLinked = !dynamicStreams.hasIndependentRinger,
            hideProfileIdentity = hideProfileIdentity,
            onNotificationPolicyClick = onNotificationPolicyClick,
            onBack = { editingId = null },
            onSave = { name, snapshot, assignedKeys ->
                scope.launch {
                    if (runtime.profileCoordinator.updateProfile(editing.id, name, snapshot)) {
                        state.store.devices.keys.forEach { key ->
                            val current = state.store.devices[key]
                            if (key in assignedKeys) runtime.profileCoordinator.assignDevice(key, editing.id)
                            else if (current?.assignedProfileId == editing.id) runtime.profileCoordinator.assignDevice(key, null)
                        }
                        editingId = null
                    }
                }
            }
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
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
                    Button(onClick = { scope.launch { runtime.profileCoordinator.saveCurrentProfile() } }) {
                        Icon(Icons.Default.Save, null)
                        Text(" SAVE CURRENT SETTINGS")
                    }
                }
                state.lastApplyWarnings.takeIf { it.isNotEmpty() }?.let { warnings ->
                    Spacer(Modifier.height(8.dp))
                    Text(warnings.joinToString("\n"), color = NothingColors.Red)
                }
            }
        }

        if (!nearbyDevicesGranted) {
            item {
                SettingsPanel {
                    Text("IMPROVE BLUETOOTH MATCHING", color = NothingColors.White, fontWeight = FontWeight.Bold)
                    Text(
                        "Nearby Devices access helps Amply recognize paired Bluetooth outputs. Profiles still work with best-effort matching without it.",
                        color = NothingColors.GreyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onNearbyDevicesClick,
                        colors = ButtonDefaults.buttonColors(containerColor = NothingColors.Red)
                    ) { Text("ALLOW NEARBY DEVICES") }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("NAMED PRESETS")
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { creating = true }) {
                    Icon(Icons.Default.Add, "Create preset", tint = NothingColors.Red)
                }
            }
        }
        val named = state.store.profiles.values.filter { it.saveMode == ProfileSaveMode.EXPLICIT }
        if (named.isEmpty()) {
            item { Text("Create Night, Commute, Gaming, Work, or your own preset from current settings.", color = NothingColors.GreyMedium) }
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
                            color = NothingColors.GreyMedium,
                            maxLines = 2
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { menu = true }) { Text("ASSIGN") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("No automatic profile") },
                            onClick = {
                                menu = false
                                scope.launch { runtime.profileCoordinator.assignDevice(device.descriptor.key, null) }
                            }
                        )
                        state.store.profiles.values.sortedBy { it.name }.forEach { profile ->
                            DropdownMenuItem(
                                text = { Text(profile.name) },
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
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text("Delete profile?") },
            text = { Text("Current phone settings will not be changed. Device assignments using this profile will be cleared.") },
            confirmButton = {
                TextButton(onClick = {
                    deletingId = null
                    scope.launch { runtime.profileCoordinator.deleteProfile(id) }
                }) { Text("DELETE", color = NothingColors.Red) }
            },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text("CANCEL") } }
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
                Text(if (active) "Active" else "Saved preset", color = if (active) NothingColors.Red else NothingColors.GreyMedium)
            }
            TextButton(onClick = onActivate, enabled = !active) { Text(if (active) "ACTIVE" else "ACTIVATE") }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit profile", tint = NothingColors.GreyMedium) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete profile", tint = NothingColors.GreyMedium) }
        }
    }
}

@Composable
private fun CreateProfileDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create preset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Profile name") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Night", "Commute", "Gaming", "Work").forEach { suggestion ->
                        Text(
                            suggestion,
                            color = NothingColors.Red,
                            modifier = Modifier.clickable { name = suggestion }.padding(4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("CREATE") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}

@Composable
private fun ProfileEditor(
    profile: AudioProfile,
    appSettings: Map<AppIdentity, AppSettings>,
    devices: List<com.agentkosticka.amply.profiles.KnownOutputDevice>,
    notificationPolicyGranted: Boolean,
    ringNotificationLinked: Boolean,
    hideProfileIdentity: Boolean,
    onNotificationPolicyClick: () -> Unit,
    onBack: () -> Unit,
    onSave: (String, AudioProfileSnapshot, Set<String>) -> Unit
) {
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
    var snapshot by remember(profile.id) { mutableStateOf(profile.snapshot) }
    var search by rememberSaveable(profile.id) { mutableStateOf("") }
    var assigned by remember(profile.id) {
        mutableStateOf<Set<String>>(
            devices.filter { it.assignedProfileId == profile.id }.mapTo(linkedSetOf()) { it.descriptor.key }
        )
    }
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("BACK") }
                Text("EDIT PROFILE", color = NothingColors.White, style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Profile name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        item { SectionTitle("SYSTEM VOLUMES") }
        PROFILE_SYSTEM_TARGETS.forEach { target ->
            item(target.name) {
                val value = snapshot.systemVolumes[target] ?: 1f
                SettingsPanel {
                    Text("${target.label} · ${(value * 100).toInt()}%", color = NothingColors.White)
                    Slider(
                        value = value,
                        onValueChange = { changed ->
                            var volumes = snapshot.systemVolumes + (target to changed)
                            if (ringNotificationLinked &&
                                (target == com.agentkosticka.amply.audio.routing.VolumeTarget.RING ||
                                    target == com.agentkosticka.amply.audio.routing.VolumeTarget.NOTIFICATION)
                            ) {
                                volumes = volumes +
                                    (com.agentkosticka.amply.audio.routing.VolumeTarget.RING to changed) +
                                    (com.agentkosticka.amply.audio.routing.VolumeTarget.NOTIFICATION to changed)
                            }
                            snapshot = snapshot.copy(systemVolumes = volumes)
                        }
                    )
                }
            }
        }
        item {
            SectionTitle("RINGER & DND")
            Spacer(Modifier.height(8.dp))
            SettingsPanel {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NotificationAlertMode.entries.forEach { mode ->
                        TextButton(onClick = { snapshot = snapshot.copy(ringerMode = mode) }) {
                            Text(mode.name, color = if (snapshot.ringerMode == mode) NothingColors.Red else NothingColors.White)
                        }
                    }
                }
                if (notificationPolicyGranted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Do Not Disturb", color = NothingColors.White, modifier = Modifier.weight(1f))
                        Checkbox(
                            checked = snapshot.dndEnabled == true,
                            onCheckedChange = { snapshot = snapshot.copy(dndEnabled = it) }
                        )
                    }
                } else {
                    TextButton(onClick = onNotificationPolicyClick) { Text("GRANT DND ACCESS") }
                }
            }
        }
        if (devices.isNotEmpty()) {
            item { SectionTitle("DEFAULT OUTPUTS") }
            items(devices, key = { it.descriptor.key }) { device ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = device.descriptor.key in assigned,
                        onCheckedChange = { checked ->
                            assigned = if (checked) assigned + device.descriptor.key else assigned - device.descriptor.key
                        }
                    )
                    Text(device.descriptor.displayName, color = NothingColors.White)
                }
            }
        }
        item {
            SectionTitle("APP VOLUMES")
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search apps") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
        }
        val personalUserId = Process.myUid() / 100_000
        val shownApps = appSettings.entries.map { (identity, setting) ->
            val base = appDisplayName(setting.appName, identity.userId, personalUserId, hideProfileIdentity)
            val suffix = appProfileFallbackLabel(
                setting.appName,
                identity.userId,
                personalUserId,
                hideProfileIdentity
            ).orEmpty()
            Triple(identity, setting, listOf(base, suffix).filter(String::isNotBlank).joinToString(" "))
        }.filter { it.third.contains(search, true) }.sortedBy { it.third }
        items(shownApps, key = { it.first.storageKey }) { (identity, setting, displayName) ->
            val value = snapshot.appVolumes[identity] ?: setting.defaultVolume
            SettingsPanel {
                Text("$displayName · ${(value * 100).toInt()}%", color = NothingColors.White)
                Slider(
                    value = value,
                    onValueChange = { snapshot = snapshot.copy(appVolumes = snapshot.appVolumes + (identity to it)) }
                )
            }
        }
        item {
            Button(
                onClick = { onSave(name, snapshot, assigned) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = NothingColors.Red)
            ) { Text("SAVE PROFILE") }
        }
    }
}

private fun outputIcon(kind: OutputKind): ImageVector = when (kind) {
    OutputKind.SPEAKER -> Icons.Default.PhoneAndroid
    OutputKind.WIRED -> Icons.Default.Headphones
    OutputKind.BLUETOOTH -> Icons.Default.Bluetooth
    OutputKind.CAST -> Icons.Default.Cast
}
