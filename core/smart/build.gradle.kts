/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    // Library hygiene: every public declaration must state its visibility and
    // return type explicitly, so the API surface is always deliberate.
    explicitApi()

    androidLibrary {
        namespace = "io.github.nikitasud.latentjam.smart"
        compileSdk = 36
        minSdk = 24
        // Host tests are OPT-IN on com.android.kotlin.multiplatform.library.
        // Without this block, commonTest silently never runs on the JVM.
        withHostTest {}
    }

    iosArm64()
    // iosX64 (Intel simulators) deliberately omitted; add the one-liner if ever needed.
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            // api, not implementation: org.koin.core.module.Module appears in
            // this module's public API (smartEngineModule).
            api(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.koin.test)
        }
    }
}
