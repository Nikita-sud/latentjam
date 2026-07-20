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
        withDeviceTest {
            applicationId = "io.github.nikitasud.latentjam.smart.test"
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    // iOS inference is injected by the application shell through IosInferenceProvider.
    // The shell owns the CocoaPods ONNX Runtime framework, so :core:smart must not
    // bind or link a second native runtime into the static Kotlin framework.
    iosArm64()
    // iosX64 (Intel simulators) deliberately omitted; add it if Intel hosts return.
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            // api, not implementation: org.koin.core.module.Module appears in
            // this module's public API (smartEngineModule).
            api(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.onnxruntime.android)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.annotation)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.runner)
            implementation(libs.junit4)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.koin.test)
        }
        // An iOS test target exists so the native ports can be gated on the same
        // fixtures the JVM ones are. Without it the iOS actuals — the tokenizer's
        // Unicode predicates especially — are unverified, and a mismatch there
        // shifts vectors silently rather than failing anything.
        iosTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
