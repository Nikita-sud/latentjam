/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    explicitApi()

    androidLibrary {
        namespace = "io.github.nikitasud.latentjam.history"
        compileSdk = 36
        minSdk = 24
        withHostTest {}
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // TrackId is the shared vocabulary.
            api(project(":core:smart"))
            implementation(libs.kotlinx.coroutines.core)
            api(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
