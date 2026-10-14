/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    id("latentjam.native-identifiers")
}

val eqSource = rootProject.file("core/audio-dsp/src/main/cpp")
val eqBuilder = rootProject.file("tools/release/build-native-eq.py")
val hostEqOutput = layout.buildDirectory.dir("nativeEq/host")
val hostEqLibrary = hostEqOutput.map { it.file(if (System.getProperty("os.name").contains("Mac")) "liblj_equalizer.dylib" else "liblj_equalizer.so") }
val buildHostEqualizer = tasks.register<Exec>("buildHostEqualizer") {
    inputs.dir(eqSource); inputs.file(eqBuilder)
    outputs.file(hostEqLibrary)
    commandLine("python3", eqBuilder, "--platform", "host", "--source-dir", eqSource,
        "--output-dir", hostEqOutput.get().asFile, "--java-home", System.getProperty("java.home"))
}
tasks.withType<Test>().configureEach {
    dependsOn(buildHostEqualizer)
    systemProperty("latentjam.eq.hostLibrary", hostEqLibrary.get().asFile.absolutePath)
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

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        val nativeOutput = layout.buildDirectory.dir("nativeEq/${target.name}")
        val buildNative = tasks.register<Exec>("buildEqualizer${target.name.replaceFirstChar(Char::uppercaseChar)}") {
            onlyIf { System.getProperty("os.name").contains("Mac") }
            inputs.dir(eqSource); inputs.file(eqBuilder)
            outputs.file(nativeOutput.map { it.file("liblj_equalizer.a") })
            commandLine("python3", eqBuilder, "--platform", target.name, "--source-dir", eqSource,
                "--output-dir", nativeOutput.get().asFile)
        }
        val interop = target.compilations.getByName("main").cinterops.create("nativeEq") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/nativeEq.def"))
            includeDirs(eqSource)
            extraOpts("-libraryPath", nativeOutput.get().asFile.absolutePath, "-staticLibrary", "liblj_equalizer.a")
        }
        tasks.named(interop.interopProcessingTaskName) { dependsOn(buildNative) }
    }

    sourceSets {
        commonMain.dependencies {
            // TrackDescriptor/TrackId are the shared vocabulary.
            api(project(":core:smart"))
            implementation(libs.kotlinx.coroutines.core)
            api(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(project(":core:audio-dsp"))
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
