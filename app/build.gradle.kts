import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
}

val amplyTargetSdk = providers.gradleProperty("amplyTargetSdk")
    .orNull
    ?.toIntOrNull()
    ?.also { require(it == 36 || it == 37) { "amplyTargetSdk must be 36 or 37" } }
    ?: 36

val signingEnvironment = listOf(
    "AMPLY_KEYSTORE_PATH",
    "AMPLY_KEYSTORE_PASSWORD",
    "AMPLY_KEY_ALIAS",
    "AMPLY_KEY_PASSWORD"
).associateWith { providers.environmentVariable(it).orNull }
val releaseSigningConfigured = signingEnvironment.values.none { value -> value.isNullOrBlank() }

val versionedReleaseRequested = gradle.startParameter.taskNames.any { requestedTask ->
    requestedTask.substringAfterLast(':') == "versionedRelease"
}
val versionCodeLine = Regex("""(?m)^(\s*versionCode\s*=\s*)(\d+)(\s*)$""")
val versionNameLine = Regex("""(?m)^(\s*versionName\s*=\s*)"(\d+)\.(\d+)\.(\d+)"(\s*)$""")
val appBuildScriptFile = project.buildFile
val declaredBuildScript = appBuildScriptFile.readText()
val declaredVersionCode = versionCodeLine.find(declaredBuildScript)?.groupValues?.get(2)?.toInt()
    ?: error("Could not read versionCode from app/build.gradle.kts")
val declaredVersionMatch = versionNameLine.find(declaredBuildScript)
    ?: error("Could not read semantic versionName from app/build.gradle.kts")
val declaredVersionName = declaredVersionMatch.groupValues.let { "${it[2]}.${it[3]}.${it[4]}" }
val nextVersionCode = declaredVersionCode + 1
val nextVersionName = declaredVersionMatch.groupValues.let {
    "${it[2]}.${it[3]}.${it[4].toInt() + 1}"
}

android {
    namespace = "com.agentkosticka.amply"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.agentkosticka.amply"
        minSdk = 29
        targetSdk = amplyTargetSdk
        versionCode = 79
        versionName = "1.4.3"
        if (versionedReleaseRequested) {
            versionCode = nextVersionCode
            versionName = nextVersionName
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    val releaseSigningConfig = if (releaseSigningConfigured) {
        signingConfigs.create("release") {
            storeFile = file(signingEnvironment.getValue("AMPLY_KEYSTORE_PATH")!!)
            storePassword = signingEnvironment.getValue("AMPLY_KEYSTORE_PASSWORD")
            keyAlias = signingEnvironment.getValue("AMPLY_KEY_ALIAS")
            keyPassword = signingEnvironment.getValue("AMPLY_KEY_PASSWORD")
        }
    } else null

    buildTypes {
        debug {
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = releaseSigningConfig
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        compose = true
        // Disabled BuildConfig due to JDK 24 jlink issues
        buildConfig = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.register("versionedRelease") {
    group = "build"
    description = "Builds release with the next code and patch version, then persists that version."
    dependsOn("assembleRelease")
    inputs.property("buildScriptPath", appBuildScriptFile.absolutePath)
    inputs.property("declaredVersionCode", declaredVersionCode)
    inputs.property("declaredVersionName", declaredVersionName)
    inputs.property("nextVersionCode", nextVersionCode)
    inputs.property("nextVersionName", nextVersionName)
    doNotTrackState("This task intentionally updates app/build.gradle.kts after a successful build.")
    doLast {
        val taskInputs = inputs.properties
        val buildScriptFile = File(checkNotNull(taskInputs["buildScriptPath"]).toString())
        val expectedVersionCode = checkNotNull(taskInputs["declaredVersionCode"]).toString().toInt()
        val expectedVersionName = checkNotNull(taskInputs["declaredVersionName"]).toString()
        val releaseVersionCode = checkNotNull(taskInputs["nextVersionCode"]).toString().toInt()
        val releaseVersionName = checkNotNull(taskInputs["nextVersionName"]).toString()
        val codeLine = Regex("""(?m)^(\s*versionCode\s*=\s*)(\d+)(\s*)$""")
        val nameLine = Regex("""(?m)^(\s*versionName\s*=\s*)"(\d+)\.(\d+)\.(\d+)"(\s*)$""")
        val currentText = buildScriptFile.readText()
        val currentCode = codeLine.find(currentText)?.groupValues?.get(2)?.toInt()
        val currentNameMatch = nameLine.find(currentText)
        val currentName = currentNameMatch?.groupValues?.let { "${it[2]}.${it[3]}.${it[4]}" }
        check(currentCode == expectedVersionCode && currentName == expectedVersionName) {
            "Version changed while the release was building; refusing to overwrite app/build.gradle.kts."
        }

        val currentCodeMatch = checkNotNull(codeLine.find(currentText))
        val withNextCode = currentText.replaceRange(
            currentCodeMatch.range,
            "${currentCodeMatch.groupValues[1]}$releaseVersionCode${currentCodeMatch.groupValues[3]}"
        )
        val currentNameRange = checkNotNull(nameLine.find(withNextCode))
        val withNextVersion = withNextCode.replaceRange(
            currentNameRange.range,
            "${currentNameRange.groupValues[1]}\"$releaseVersionName\"${currentNameRange.groupValues[5]}"
        )
        val temporaryFile = buildScriptFile.resolveSibling("${buildScriptFile.name}.version-update")
        temporaryFile.writeText(withNextVersion)
        Files.move(
            temporaryFile.toPath(),
            buildScriptFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
        logger.lifecycle(
            "Built and recorded Amply version $releaseVersionName (versionCode $releaseVersionCode)."
        )
    }
}

dependencies {
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hidden.api.bypass)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.core.splashscreen)
    testImplementation(libs.junit4)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    baselineProfile(project(":benchmark"))
}

baselineProfile {
    dexLayoutOptimization = true
    saveInSrc = true
}
