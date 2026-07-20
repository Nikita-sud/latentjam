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

    // ONNX Runtime ships one static slice per platform; each iOS target binds the
    // matching one. The framework is fetched by tools/fetch_onnxruntime.sh rather
    // than vendored — see that script for why, and for the version pinning rule.
    val ortRoot = rootProject.file("third_party/onnxruntime")
    // Absent on a fresh clone until tools/fetch_onnxruntime.sh has run. Skipping the
    // binding rather than failing keeps `git clone && ./gradlew build` working for
    // anyone who does not need the iOS inference path.
    val ortPresent = File(ortRoot, "Headers/onnxruntime_c_api.h").exists()
    listOf(
        iosArm64() to "ios-arm64",
        // iosX64 (Intel simulators) deliberately omitted; add the one-liner if ever needed.
        iosSimulatorArm64() to "ios-arm64_x86_64-simulator",
    ).forEach { (target, slice) ->
        if (!ortPresent) return@forEach
        target.compilations.getByName("main").cinterops.create("onnxruntime") {
            defFile(project.file("src/nativeInterop/cinterop/onnxruntime.def"))
            includeDirs(File(ortRoot, "Headers"))
        }
        // The Kotlin framework is static, so these symbols are resolved when Xcode
        // links the app rather than here; the search path still has to be declared
        // for the compiler to accept the references.
        target.binaries.all {
            linkerOpts(
                "-F", File(ortRoot, "onnxruntime.xcframework/$slice").absolutePath,
                "-framework", "onnxruntime",
            )
        }
    }

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
