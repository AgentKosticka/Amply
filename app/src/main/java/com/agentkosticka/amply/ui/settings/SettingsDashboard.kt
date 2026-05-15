package com.agentkosticka.amply.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.agentkosticka.amply.R
import com.agentkosticka.amply.data.AppSettings
import com.agentkosticka.amply.data.OverlaySide
import com.agentkosticka.amply.data.PreferencesManager
import com.agentkosticka.amply.ui.theme.NothingColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsDashboard(
    preferencesManager: PreferencesManager,
    onOverlayPermissionClick: () -> Unit,
    onAccessibilityClick: () -> Unit
) {
    val overlaySide by preferencesManager.overlaySide.collectAsState(initial = OverlaySide.LEFT)
    val verticalFraction by preferencesManager.overlayVerticalFraction.collectAsState(initial = 0.5f)
    val appSettings by preferencesManager.appSettings.collectAsState(initial = emptyMap())
    val scope = rememberCoroutineScope()

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
                    verticalFraction = verticalFraction,
                    onFractionChange = { fraction ->
                        scope.launch { preferencesManager.setOverlayVerticalFraction(fraction) }
                    }
                )
            }
        }

        item {
            SectionTitle("APP VOLUMES")
        }

        val apps = appSettings.values
            .sortedWith(compareByDescending<AppSettings> { it.lastSeenTimestamp }.thenBy { it.appName.lowercase() })

        if (apps.isEmpty()) {
            item {
                SettingsPanel {
                    Text(
                        text = "NO APPS SEEN YET",
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
                        scope.launch {
                            preferencesManager.setAppDefaultVolume(app.packageName, volume)
                        }
                    }
                )
            }
        }
    }
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
    val icon = remember(app.packageName) {
        try {
            context.packageManager.getApplicationIcon(app.packageName)
        } catch (e: Exception) {
            null
        }
    }
    val volumePercent = (app.defaultVolume * 100).roundToInt()

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

            Switch(
                checked = !app.hiddenInOverlay,
                onCheckedChange = onVisibleChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = NothingColors.White,
                    checkedTrackColor = NothingColors.Red,
                    uncheckedThumbColor = NothingColors.GreyMedium,
                    uncheckedTrackColor = Color(0xFF333333)
                )
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        DotVolumeSlider(
            volume = app.defaultVolume,
            onVolumeChange = onVolumeChange,
            modifier = Modifier.fillMaxWidth()
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
