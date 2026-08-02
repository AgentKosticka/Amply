package com.agentkosticka.amply.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class OverlayAnimationBenchmark {
    @get:Rule val benchmarkRule = MacrobenchmarkRule()

    @Test fun noApps() = measure("empty")
    @Test fun activeApps() = measure("apps")
    @Test fun disconnected() = measure("disconnected")
    @Test fun optionalStreams() = measure("optional")

    private fun measure(scenario: String) {
        benchmarkRule.measureRepeated(
            packageName = "com.agentkosticka.amply",
            metrics = listOf(FrameTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.WARM,
            setupBlock = {
                val intent = Intent().apply {
                    component = ComponentName(
                        "com.agentkosticka.amply",
                        "com.agentkosticka.amply.benchmark.BenchmarkOverlayActivity"
                    )
                    putExtra("scenario", scenario)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivityAndWait(intent)
            }
        ) {
            val device = androidx.test.uiautomator.UiDevice
                .getInstance(InstrumentationRegistry.getInstrumentation())
            repeat(20) {
                device.findObject(By.desc(Pattern.compile("(Expand|Collapse) Amply")))?.click()
            }
        }
    }
}
