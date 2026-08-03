package com.agentkosticka.amply.settings.ui

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.agentkosticka.amply.settings.model.OverlaySide
import com.agentkosticka.amply.settings.model.VolumeDotScaleConfig
import com.agentkosticka.amply.settings.model.VolumeDotScaleMode
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.ui.theme.NothingColors
import kotlin.math.roundToInt


@Composable
internal fun SideSelector(
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
                modifier = Modifier.weight(1f).semantics { this.selected = selected == side },
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
internal fun VolumeDotScalePanel(
    config: VolumeDotScaleConfig,
    deviceReferenceMax: Int,
    onChange: (VolumeDotScaleConfig) -> Unit
) {
    Text("VOLUME DOTS", color = NothingColors.White, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
    Text(
        if (deviceReferenceMax <= 30) {
            "Auto follows this device's 0–$deviceReferenceMax volume scale."
        } else {
            "This device has 0–$deviceReferenceMax steps; Auto maps them cleanly onto 30 dots."
        },
        color = NothingColors.GreyMedium,
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        VolumeDotModeButton(
            text = "AUTO · ${deviceReferenceMax.coerceIn(1, 30)}",
            active = config.mode == VolumeDotScaleMode.AUTO,
            onClick = { onChange(config.copy(mode = VolumeDotScaleMode.AUTO)) },
            modifier = Modifier.weight(1f)
        )
        VolumeDotModeButton(
            text = "CUSTOM",
            active = config.mode == VolumeDotScaleMode.CUSTOM,
            onClick = { onChange(config.copy(mode = VolumeDotScaleMode.CUSTOM)) },
            modifier = Modifier.weight(1f)
        )
    }
    if (config.mode == VolumeDotScaleMode.CUSTOM) {
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            VolumeDotModeButton(
                text = "−",
                active = false,
                onClick = { onChange(config.copy(customDotCount = (config.customDotCount - 1).coerceAtLeast(4))) }
            )
            Text(
                "${config.customDotCount.coerceIn(4, 60)} DOTS",
                color = NothingColors.White,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace
            )
            VolumeDotModeButton(
                text = "+",
                active = false,
                onClick = { onChange(config.copy(customDotCount = (config.customDotCount + 1).coerceAtMost(60))) }
            )
        }
    }
}

@Composable
private fun VolumeDotModeButton(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(13.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) NothingColors.Red else DarkControlBackground,
            contentColor = NothingColors.White
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

internal fun deviceVolumeReference(context: Context): Int {
    val manager = context.getSystemService(AudioManager::class.java)
    return VolumeTarget.entries.asSequence()
        .filter { it.userAdjustable }
        .mapNotNull { target -> runCatching { manager.getStreamMaxVolume(target.streamType) }.getOrNull() }
        .maxOrNull()
        ?.coerceAtLeast(1)
        ?: 16
}
@Composable
internal fun AppListModeSelector(
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
internal fun PositionPreview(
    side: OverlaySide,
    verticalFraction: Float,
    onFractionChange: (Float) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .semantics {
                contentDescription = "Overlay vertical position"
                stateDescription = "${(verticalFraction.coerceIn(0f, 1f) * 100).roundToInt()} percent"
                progressBarRangeInfo = ProgressBarRangeInfo(
                    verticalFraction.coerceIn(0f, 1f),
                    0f..1f,
                    99
                )
                setProgress { requested ->
                    onFractionChange(requested.coerceIn(0f, 1f))
                    true
                }
            }
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
                .align(
                    if (side == OverlaySide.LEFT) {
                        androidx.compose.ui.AbsoluteAlignment.TopLeft
                    } else {
                        androidx.compose.ui.AbsoluteAlignment.TopRight
                    }
                )
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
