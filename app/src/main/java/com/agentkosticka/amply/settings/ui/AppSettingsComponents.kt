package com.agentkosticka.amply.settings.ui

import android.os.Process
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentkosticka.amply.audio.session.AudioSession
import com.agentkosticka.amply.settings.model.AppSettings
import com.agentkosticka.amply.settings.model.AppIdentity
import com.agentkosticka.amply.settings.model.OverlayAppMode
import com.agentkosticka.amply.settings.model.appDisplayName
import com.agentkosticka.amply.settings.model.appProfileFallbackLabel
import com.agentkosticka.amply.ui.theme.NothingColors
import kotlin.math.roundToInt


internal fun mergeAppSettingsWithActiveSessions(
    appSettings: Map<AppIdentity, AppSettings>,
    activeSessions: List<AudioSession>
): List<AppSettings> {
    val merged = LinkedHashMap<AppIdentity, AppSettings>()
    appSettings.values.forEach { setting ->
        merged[setting.identity] = setting
    }

    activeSessions.forEach { session ->
        val existing = merged[session.identity]
        merged[session.identity] = AppSettings(
            packageName = session.packageName,
            appName = session.appName,
            uid = session.uid,
            userId = session.identity.userId,
            defaultVolume = existing?.defaultVolume ?: session.volume,
            overlayMode = existing?.overlayMode ?: OverlayAppMode.AUTO,
            lastSeenTimestamp = maxOf(existing?.lastSeenTimestamp ?: 0L, session.lastSeenTimestamp)
        )
    }

    return merged.values.toList()
}

@Composable
internal fun AppSettingsRow(
    app: AppSettings,
    isActive: Boolean,
    hideProfileIdentity: Boolean = true,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    reorderEnabled: Boolean = true,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onReorderHandleBoundsChanged: (Rect) -> Unit = {},
    onReset: () -> Unit,
    onOverlayModeChange: (OverlayAppMode) -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    var displayedVolume by remember(app.identity) { mutableFloatStateOf(app.defaultVolume) }
    LaunchedEffect(app.defaultVolume) {
        displayedVolume = app.defaultVolume
    }
    val icon = rememberApplicationIconBitmap(app.packageName, bitmapSizePx = 72)
    val volumePercent = (displayedVolume * 100).roundToInt()
    val personalUserId = Process.myUid() / 100_000
    val displayName = appDisplayName(
        appName = app.appName,
        userId = app.userId,
        personalUserId = personalUserId,
        hideProfileIdentity = hideProfileIdentity
    )
    val profileFallbackLabel = appProfileFallbackLabel(
        appName = app.appName,
        userId = app.userId,
        personalUserId = personalUserId,
        hideProfileIdentity = hideProfileIdentity
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .background(Color(0xFF1C1C1C), RoundedCornerShape(27.dp))
            .padding(horizontal = 16.dp, vertical = 15.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFF303030), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                icon?.let {
                    Image(
                        bitmap = it,
                        contentDescription = displayName,
                        modifier = Modifier.size(32.dp)
                    )
                } ?: Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = displayName,
                    tint = NothingColors.GreyMedium
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        color = NothingColors.White,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isActive) {
                        Spacer(Modifier.width(7.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(NothingColors.Red, CircleShape)
                        )
                    }
                }
                profileFallbackLabel?.let { label ->
                    Text(
                        text = label,
                        color = NothingColors.GreyMedium,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = null,
                tint = if (reorderEnabled && enabled) NothingColors.GreyMedium else Color(0xFF555555),
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = "Move $displayName"
                        customActions = buildList {
                            if (canMoveUp) add(CustomAccessibilityAction("Move up") {
                                onMoveUp()
                                true
                            })
                            if (canMoveDown) add(CustomAccessibilityAction("Move down") {
                                onMoveDown()
                                true
                            })
                        }
                    }
                    .onGloballyPositioned { coordinates ->
                        onReorderHandleBoundsChanged(coordinates.boundsInWindow())
                    }
                    .padding(12.dp)
            )

            Text(
                text = "$volumePercent%",
                color = if (volumePercent > 75) NothingColors.Red else NothingColors.White,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )

            if (app.isCustomized) {
                TextButton(
                    onClick = onReset,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Text("RESET", color = NothingColors.GreyMedium, style = MaterialTheme.typography.labelSmall)
                }
            }

        }

        Spacer(modifier = Modifier.height(14.dp))

        AppVolumeRail(
            volume = displayedVolume,
            enabled = enabled,
            accessibilityLabel = "$displayName default volume",
            onVolumeChange = { volume ->
                displayedVolume = volume
                onVolumeChange(volume)
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(14.dp))

        OverlayModeSelector(
            selected = app.overlayMode,
            enabled = enabled,
            onSelected = onOverlayModeChange
        )
    }
}

internal fun shouldShowAppProfilePrivacy(
    accessibleProfileCount: Int,
    knownIdentities: Collection<AppIdentity>,
    personalUserId: Int
): Boolean = accessibleProfileCount > 1 || knownIdentities.any { it.userId != personalUserId }

@Composable
private fun OverlayModeSelector(
    selected: OverlayAppMode,
    enabled: Boolean,
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
                enabled = enabled,
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
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
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
internal fun AppVolumeRail(
    volume: Float,
    enabled: Boolean,
    onVolumeChange: (Float) -> Unit,
    accessibilityLabel: String,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = accessibilityLabel
                stateDescription = "${(volume.coerceIn(0f, 1f) * 100).roundToInt()} percent"
                progressBarRangeInfo = ProgressBarRangeInfo(volume.coerceIn(0f, 1f), 0f..1f, 99)
                if (enabled) {
                    setProgress { requested ->
                        onVolumeChange(requested.coerceIn(0f, 1f))
                        true
                    }
                } else {
                    disabled()
                }
            }
            .then(
                if (enabled) {
                    Modifier
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { change, _ ->
                                change.consume()
                                val inset = 5.dp.toPx()
                                val usableWidth = (size.width - inset * 2f).coerceAtLeast(1f)
                                onVolumeChange(((change.position.x - inset) / usableWidth).coerceIn(0f, 1f))
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val inset = 5.dp.toPx()
                                val usableWidth = (size.width - inset * 2f).coerceAtLeast(1f)
                                onVolumeChange(((offset.x - inset) / usableWidth).coerceIn(0f, 1f))
                            }
                        }
                } else Modifier
            )
    ) {
        val level = volume.coerceIn(0f, 1f)
        val thumbRadius = 5.dp.toPx()
        val trackWidth = 4.dp.toPx()
        val endX = size.width - thumbRadius
        val usableWidth = (endX - thumbRadius).coerceAtLeast(1f)
        val valueX = thumbRadius + usableWidth * level
        val warningX = thumbRadius + usableWidth * 0.75f
        val centerY = size.height / 2f

        drawLine(
            color = Color(0xFF3A3A3A),
            start = Offset(thumbRadius, centerY),
            end = Offset(endX, centerY),
            strokeWidth = trackWidth,
            cap = StrokeCap.Round
        )
        if (valueX > thumbRadius) {
            drawLine(
                color = NothingColors.White,
                start = Offset(thumbRadius, centerY),
                end = Offset(valueX.coerceAtMost(warningX), centerY),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round
            )
        }
        if (level > 0.75f) {
            drawLine(
                color = NothingColors.Red,
                start = Offset(warningX, centerY),
                end = Offset(valueX, centerY),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round
            )
        }
        drawCircle(
            color = if (level > 0.75f) NothingColors.Red else NothingColors.White,
            radius = thumbRadius,
            center = Offset(valueX, centerY)
        )
    }
}
