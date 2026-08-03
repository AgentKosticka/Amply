package com.agentkosticka.amply.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
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
    @Test fun worstCaseWithoutCompilation() = measure(
        scenario = "worst",
        compilationMode = CompilationMode.None()
    )
    @Test fun worstCaseWithBaselineProfile() = measure(
        scenario = "worst",
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require)
    )

    private fun measure(
        scenario: String,
        compilationMode: CompilationMode = CompilationMode.DEFAULT
    ) {
        benchmarkRule.measureRepeated(
            packageName = "com.agentkosticka.amply",
            metrics = listOf(FrameTimingMetric()),
            iterations = 5,
            compilationMode = compilationMode,
            startupMode = StartupMode.WARM,
            setupBlock = {
                val intent = Intent().apply {
                    component = ComponentName(
                        "com.agentkosticka.amply",
                        "com.agentkosticka.amply.benchmarkhost.OverlayBenchmarkHostActivity"
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
                checkNotNull(
                    device.wait(
                        Until.findObject(By.desc(Pattern.compile("(Expand|Collapse) Amply"))),
                        2_000
                    )
                ) { "Amply expand/collapse control was not rendered for '$scenario'" }.click()
            }
        }
    }
}
