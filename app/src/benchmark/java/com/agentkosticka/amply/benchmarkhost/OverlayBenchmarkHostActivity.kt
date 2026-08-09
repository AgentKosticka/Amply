package com.agentkosticka.amply.benchmarkhost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.agentkosticka.amply.audio.routing.StreamIcon
import com.agentkosticka.amply.audio.routing.VolumeBarModel
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.audio.session.AppVolumeControlState
import com.agentkosticka.amply.audio.session.AppVolumeTarget
import com.agentkosticka.amply.overlay.ui.OverlayAppPresentation
import com.agentkosticka.amply.settings.model.OverlaySide
import com.agentkosticka.amply.shizuku.client.VolumeServiceConnectionState
import com.agentkosticka.amply.overlay.ui.VolumeOverlay
import com.agentkosticka.amply.ui.theme.AmplyTheme

/** Benchmark-only host for deterministic overlay animation measurements. */
class OverlayBenchmarkHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = intent.getStringExtra("scenario").orEmpty()
        val disconnected = scenario == "disconnected"
        val optional = scenario == "optional"
        val worstCase = scenario == "worst"
        val appCount = when {
            worstCase -> 8
            scenario == "apps" -> 4
            else -> 0
        }
        val targets = if (optional || worstCase) VolumeTarget.entries.filter { it != VolumeTarget.ENFORCED_AUDIBLE }
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
        val sampleAppNames = listOf(
            "Music",
            "Podcasts",
            "Game",
            "Video",
            "Radio",
            "Audiobooks",
            "Browser",
            "Calls"
        )
        val apps = List(appCount) { index ->
            OverlayAppPresentation(
                target = AppVolumeTarget(
                    packageName = "benchmark.app.$index",
                    uid = 10_000 + index,
                    appName = sampleAppNames[index]
                ),
                icon = null,
                volume = 0.4f + index * 0.1f,
                isPlaying = true,
                controlState = AppVolumeControlState.ACTIVE
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
