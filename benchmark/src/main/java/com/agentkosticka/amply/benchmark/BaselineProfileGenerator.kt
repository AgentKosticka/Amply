package com.agentkosticka.amply.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = "com.agentkosticka.amply",
        includeInStartupProfile = false
    ) {
        pressHome()
        startActivityAndWait(
            Intent().apply {
                component = ComponentName(
                    "com.agentkosticka.amply",
                    "com.agentkosticka.amply.benchmarkhost.OverlayBenchmarkHostActivity"
                )
                putExtra("scenario", "worst")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        val device = androidx.test.uiautomator.UiDevice
            .getInstance(InstrumentationRegistry.getInstrumentation())
        repeat(12) {
            checkNotNull(device.wait(
                Until.findObject(By.desc(Pattern.compile("(Expand|Collapse) Amply"))),
                2_000
            )) { "Amply expand/collapse control was not rendered" }.click()
        }
    }
}
