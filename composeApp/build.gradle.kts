/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // AGP 9 forbids com.android.application together with the KMP plugin, and
    // ships no KMP *application* plugin yet — so the shared Compose UI lives in
    // this KMP LIBRARY, and the thin :androidApp shell hosts the launcher
    // activity (mirroring how the thin iosApp Swift shell hosts the framework).
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidLibrary {
        namespace = "io.github.nikitasud.latentjam.app.shared"
        compileSdk = 36
        minSdk = 24
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            // Consumed by the iosApp Xcode project via embedAndSignAppleFrameworkForXcode.
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: :androidApp consumes these types
            // (SimilarityEngine in App()'s signature, @Composable App itself).
            api(project(":core:smart"))
            api(project(":core:library"))
            api(project(":core:playback"))
            api(project(":core:history"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            implementation(compose.materialIconsExtended)
            implementation(libs.coil.compose)
        }
        androidMain.dependencies {
            // MainActivity lives here (see its KDoc for the AGP 9 rationale).
            api(libs.androidx.activity.compose)
        }
    }
}
