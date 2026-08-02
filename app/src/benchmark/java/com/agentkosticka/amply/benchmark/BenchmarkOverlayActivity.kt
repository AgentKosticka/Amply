package com.agentkosticka.amply.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.agentkosticka.amply.audio.StreamIcon
import com.agentkosticka.amply.audio.VolumeBarModel
import com.agentkosticka.amply.audio.VolumeTarget
import com.agentkosticka.amply.data.OverlayAppEntry
import com.agentkosticka.amply.data.OverlaySide
import com.agentkosticka.amply.shizuku.VolumeServiceConnectionState
import com.agentkosticka.amply.ui.overlay.VolumeOverlay
import com.agentkosticka.amply.ui.theme.AmplyTheme

/** Benchmark-only host for deterministic overlay animation measurements. */
class BenchmarkOverlayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = intent.getStringExtra("scenario").orEmpty()
        val disconnected = scenario == "disconnected"
        val optional = scenario == "optional"
        val appCount = if (scenario == "apps") 4 else 0
        val targets = if (optional) VolumeTarget.entries.filter { it != VolumeTarget.ENFORCED_AUDIBLE }
        else listOf(VolumeTarget.MEDIA, VolumeTarget.ALARM, VolumeTarget.NOTIFICATION, VolumeTarget.CALL)
        val bars = targets.mapIndexed { index, target ->
            VolumeBarModel(
                target = target,
                aliases = setOf(target.streamType),
                label = target.label,
                currentVolume = 8,
                minVolume = if (target == VolumeTarget.ALARM || target == VolumeTarget.CALL) 1 else 0,
                maxVolume = if (target == VolumeTarget.CALL) 14 else 16,
                active = index == 0,
                enabled = target.userAdjustable,
                referenceMaxVolume = 16,
                dotCount = 16
            )
        }
        val apps = List(appCount) { index ->
            OverlayAppEntry(
                packageName = "benchmark.app.$index",
                uid = 10_000 + index,
                appName = "Benchmark app ${index + 1}",
                appIcon = null,
                volume = 0.4f + index * 0.1f,
                isPlaying = true
            )
        }
        setContent {
            AmplyTheme {
                VolumeOverlay(
                    volumeBars = bars,
                    selectedTarget = VolumeTarget.MEDIA,
                    apps = apps,
                    shizukuConnectionState = if (disconnected) {
                        VolumeServiceConnectionState.DISCONNECTED
                    } else VolumeServiceConnectionState.CONNECTED,
                    overlaySide = OverlaySide.LEFT,
                    availableWidthDp = 720f
                )
            }
        }
    }
}
