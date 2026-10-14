/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    id("latentjam.native-identifiers")
}

kotlin {
    explicitApi()

    androidLibrary {
        namespace = "io.github.nikitasud.latentjam.playback"
        compileSdk = 36
        minSdk = 24
        // The media notification carries the SMART-shuffle glyph as a drawable
        // (Media3 CommandButton takes a resource id, not a Compose vector).
        androidResources.enable = true
        withHostTest {}
        withDeviceTest {
            applicationId = "io.github.nikitasud.latentjam.playback.test"
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // TrackDescriptor/TrackId are the shared vocabulary.
            api(project(":core:smart"))
            implementation(libs.kotlinx.coroutines.core)
            api(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(project(":core:library"))
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.session)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.runner)
            implementation(libs.junit4)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
