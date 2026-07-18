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
        // Host tests are OPT-IN on this plugin; without it commonTest never runs.
        withHostTest {}
        // So is the Android resource/asset pipeline. Compose Multiplatform ships
        // its strings as ASSETS (assets/composeResources/…), and with this off the
        // module builds and links fine but packages nothing — every
        // stringResource() would miss at runtime. Verified by inspecting the APK.
        androidResources {
            enable = true
        }
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
            // Compose Multiplatform resources: the generated `Res` accessors plus
            // stringResource(). Strings live in commonMain/composeResources/values,
            // translations in values-<lang>. NOT Android res/ — the plugin packages
            // them as assets and resolves the locale itself.
            implementation(compose.components.resources)
            implementation(libs.coil.compose)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            // MainActivity lives here (see its KDoc for the AGP 9 rationale).
            api(libs.androidx.activity.compose)
        }
    }
}

compose.resources {
    // The root project sets no `group`, so the default package for the generated
    // Res class ("{group}.{module}.generated.resources") would be rootless and
    // would change the day a group is added. Pin it instead.
    packageOfResClass = "io.github.nikitasud.latentjam.app.generated.resources"
    publicResClass = true
}
