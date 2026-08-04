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

android {
    namespace = "com.agentkosticka.amply"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.agentkosticka.amply"
        minSdk = 29
        targetSdk = amplyTargetSdk
        versionCode = 31
        versionName = "1.2.13"

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
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
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
