package com.agentkosticka.amply.tutorial

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.agentkosticka.amply.R
import com.agentkosticka.amply.audio.routing.VolumeBarModel
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.audio.session.AppVolumeControlState
import com.agentkosticka.amply.audio.session.AppVolumeTarget
import com.agentkosticka.amply.overlay.ui.OverlayAppPresentation
import com.agentkosticka.amply.overlay.ui.VolumeOverlay
import com.agentkosticka.amply.settings.model.OverlaySide
import com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState
import com.agentkosticka.amply.ui.theme.NothingColors

@Composable
internal fun TutorialWaitingScreen(
    overlayAttachFailed: Boolean,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NothingColors.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(NothingColors.Red),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                tint = NothingColors.White,
                modifier = Modifier.size(44.dp)
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.tutorial_try_it_title),
            style = MaterialTheme.typography.headlineMedium,
            color = NothingColors.White,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.tutorial_try_it_description),
            style = MaterialTheme.typography.bodyLarge,
            color = NothingColors.GreyMedium,
            textAlign = TextAlign.Center
        )
        if (overlayAttachFailed) {
            Spacer(Modifier.height(20.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF271717)),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    text = stringResource(R.string.tutorial_overlay_retry),
                    modifier = Modifier.padding(18.dp),
                    color = NothingColors.White,
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(stringResource(R.string.setup_waiting_for_volume_button))
        }
        TextButton(onClick = onSkip) {
            Text(stringResource(R.string.tutorial_skip), color = NothingColors.GreyMedium)
        }
    }
}

@Composable
internal fun TutorialOverlayDemo(
    stage: TutorialStage,
    coordinator: TutorialCoordinator
) {
    if (!stage.isOverlayDemo) return
    val stageIndex = TutorialStage.overlayStages.indexOf(stage)
    var mediaVolume by remember { mutableIntStateOf(10) }
    var ringVolume by remember { mutableIntStateOf(5) }
    var alarmVolume by remember { mutableIntStateOf(8) }
    var notificationVolume by remember { mutableIntStateOf(4) }
    var musicVolume by remember { mutableFloatStateOf(0.72f) }
    var podcastVolume by remember { mutableFloatStateOf(0.46f) }
    var selectedTarget by remember { mutableStateOf(VolumeTarget.MEDIA) }
    var expanded by remember { mutableStateOf(false) }
    val dotCount = 16

    val streams = listOf(
        demoBar(VolumeTarget.MEDIA, mediaVolume, 16, dotCount, selectedTarget == VolumeTarget.MEDIA),
        demoBar(VolumeTarget.RING, ringVolume, 7, dotCount, selectedTarget == VolumeTarget.RING),
        demoBar(VolumeTarget.ALARM, alarmVolume, 15, dotCount, selectedTarget == VolumeTarget.ALARM),
        demoBar(
            VolumeTarget.NOTIFICATION,
            notificationVolume,
            7,
            dotCount,
            selectedTarget == VolumeTarget.NOTIFICATION
        )
    )
    val apps = listOf(
        demoApp("tutorial.music", 10_001, "Music", musicVolume),
        demoApp("tutorial.podcast", 10_002, "Podcasts", podcastVolume)
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(NothingColors.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val availableWidth = maxWidth.value - 32f
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.tutorial_overlay_heading),
                modifier = Modifier.padding(start = 24.dp, top = 18.dp),
                color = NothingColors.GreyMedium,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (expanded) 760.dp else 500.dp)
                            .border(2.dp, NothingColors.Red, RoundedCornerShape(30.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        VolumeOverlay(
                            volumeBars = streams,
                            selectedTarget = selectedTarget,
                            visible = true,
                            apps = apps,
                            shizukuConnectionState = VolumeServiceConnectionState.CONNECTED,
                            showShizukuDisconnectedWarning = false,
                            overlaySide = OverlaySide.LEFT,
                            availableWidthDp = availableWidth,
                            expanded = expanded,
                            onStreamVolumeChange = { streamType, value ->
                                when (streamType) {
                                    VolumeTarget.MEDIA.streamType -> mediaVolume = value
                                    VolumeTarget.RING.streamType -> ringVolume = value
                                    VolumeTarget.ALARM.streamType -> alarmVolume = value
                                    VolumeTarget.NOTIFICATION.streamType -> notificationVolume = value
                                }
                            },
                            onStreamSelected = { selectedTarget = it },
                            onAppVolumeChange = { target, volume ->
                                if (target.packageName == "tutorial.music") musicVolume = volume
                                else podcastVolume = volume
                            },
                            onMuteToggle = { streamType ->
                                when (streamType) {
                                    VolumeTarget.MEDIA.streamType -> mediaVolume = if (mediaVolume == 0) 10 else 0
                                    VolumeTarget.RING.streamType -> ringVolume = if (ringVolume == 0) 5 else 0
                                    VolumeTarget.ALARM.streamType -> alarmVolume = if (alarmVolume == 0) 8 else 0
                                    VolumeTarget.NOTIFICATION.streamType -> {
                                        notificationVolume = if (notificationVolume == 0) 4 else 0
                                    }
                                }
                            },
                            onExpandedChange = { expanded = it },
                            onPauseAmply = {}
                        )
                    }
                }
            }
            TutorialCoachmarkCard(
                step = stageIndex + 1,
                total = TutorialStage.overlayStages.size,
                title = stringResource(stage.titleRes),
                body = stringResource(stage.bodyRes),
                canGoBack = stageIndex > 0,
                onBack = coordinator::backOverlay,
                onNext = coordinator::advanceOverlay,
                onSkip = coordinator::skip
            )
        }
    }
}

@Composable
internal fun TutorialCoachmarkCard(
    step: Int,
    total: Int,
    title: String,
    body: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1C)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.tutorial_progress, step, total),
                color = NothingColors.Red,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                color = NothingColors.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(text = body, color = NothingColors.GreyMedium)
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.tutorial_skip), color = NothingColors.GreyMedium)
                }
                Spacer(Modifier.weight(1f))
                if (canGoBack) {
                    OutlinedButton(onClick = onBack) {
                        Text(stringResource(R.string.tutorial_back))
                    }
                }
                Button(
                    onClick = onNext,
                    colors = ButtonDefaults.buttonColors(containerColor = NothingColors.Red)
                ) {
                    Text(stringResource(R.string.setup_next))
                }
            }
        }
    }
}

private fun demoBar(
    target: VolumeTarget,
    current: Int,
    max: Int,
    dotCount: Int,
    active: Boolean = false
) = VolumeBarModel(
    target = target,
    aliases = setOf(target.streamType),
    label = target.label,
    currentVolume = current,
    minVolume = 0,
    maxVolume = max,
    active = active,
    enabled = true,
    referenceMaxVolume = 16,
    dotCount = dotCount
)

private fun demoApp(
    packageName: String,
    uid: Int,
    label: String,
    volume: Float
) = OverlayAppPresentation(
    target = AppVolumeTarget(packageName, uid, label),
    icon = null,
    volume = volume,
    isPlaying = true,
    controlState = AppVolumeControlState.ACTIVE
)

private val TutorialStage.titleRes: Int
    @StringRes get() = when (this) {
        TutorialStage.OVERLAY_LEVEL -> R.string.tutorial_overlay_level_title
        TutorialStage.OVERLAY_MUTE -> R.string.tutorial_overlay_mute_title
        TutorialStage.OVERLAY_EXPAND -> R.string.tutorial_overlay_expand_title
        TutorialStage.OVERLAY_STREAMS -> R.string.tutorial_overlay_streams_title
        TutorialStage.OVERLAY_APPS -> R.string.tutorial_overlay_apps_title
        TutorialStage.OVERLAY_PAUSE -> R.string.tutorial_overlay_pause_title
        else -> R.string.tutorial_try_it_title
    }

private val TutorialStage.bodyRes: Int
    @StringRes get() = when (this) {
        TutorialStage.OVERLAY_LEVEL -> R.string.tutorial_overlay_level_body
        TutorialStage.OVERLAY_MUTE -> R.string.tutorial_overlay_mute_body
        TutorialStage.OVERLAY_EXPAND -> R.string.tutorial_overlay_expand_body
        TutorialStage.OVERLAY_STREAMS -> R.string.tutorial_overlay_streams_body
        TutorialStage.OVERLAY_APPS -> R.string.tutorial_overlay_apps_body
        TutorialStage.OVERLAY_PAUSE -> R.string.tutorial_overlay_pause_body
        else -> R.string.tutorial_try_it_description
    }
