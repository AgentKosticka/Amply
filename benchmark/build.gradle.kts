plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

val amplyTargetSdk = providers.gradleProperty("amplyTargetSdk")
    .orNull
    ?.toIntOrNull()
    ?.also { require(it == 36 || it == 37) { "amplyTargetSdk must be 36 or 37" } }
    ?: 36

android {
    namespace = "com.agentkosticka.amply.benchmark"
    compileSdk = 37
    targetProjectPath = ":app"

    defaultConfig {
        minSdk = 29
        targetSdk = amplyTargetSdk
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }

    experimentalProperties["android.experimental.self-instrumenting"] = true
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "benchmark"
    }
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
