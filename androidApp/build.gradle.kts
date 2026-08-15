/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
plugins {
    alias(libs.plugins.androidApplication)
}

// Deliberately a ZERO-SOURCE packaging shell: AGP 9 forbids pairing
// com.android.application with the KMP plugin AND with the classic
// org.jetbrains.kotlin.android plugin, so every line of Kotlin — including
// MainActivity — lives in :composeApp (androidMain), compiled by the proven
// KMP toolchain. This module contributes only the manifest, launcher
// resources, and packaging identity.
android {
    namespace = "io.github.nikitasud.latentjam.app.android"
    compileSdk = 36

    defaultConfig {
        // ".kmp" suffix lets this build coexist on-device with another
        // LatentJam build during development. Drop the suffix when this
        // becomes the only one.
        applicationId = "io.github.nikitasud.latentjam.kmp"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            // Compose, Media3 and the local model pipeline all benefit from ahead-of-time R8
            // optimization. Debug remains intentionally untouched for useful stack traces.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(project(":composeApp"))
}
