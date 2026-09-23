plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.example.childsafetyimpl"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.childsafetyimpl"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work.runtime.ktx)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    // Bundled on-device image labelling. Ships inside the APK, so classification works
    // offline and no image ever leaves the device.
    implementation(libs.mlkit.image.labeling)
    // Runs a content classifier of our own, bundled as a TFLite asset, through the same on-device
    // runtime. The slot is wired and empty: see PurposeTrainedLabeler.
    implementation(libs.mlkit.image.labeling.custom)
    // Face detection gives the bounding boxes that make region blur possible at all; the
    // labeller only ever produced a whole-frame verdict.
    implementation(libs.mlkit.face.detection)
    implementation(libs.mlkit.segmentation.selfie)
    // The browser image-search path's two models: SafeScreen's NSFW classifier and MobileCLIP-S0.
    // Plain interpreters rather than ML Kit, because neither model carries ML Kit's metadata.
    implementation(libs.litert)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // For the browser image-search tests ported from SafeScreen, which exercise Rect and
    // AccessibilityNodeInfo as the device has them.
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
