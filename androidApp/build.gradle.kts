/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import org.gradle.work.DisableCachingByDefault

plugins {
    alias(libs.plugins.androidApplication)
}

// The Google Play upload key lives outside the repository; `keystore.properties` at the root
// (git-ignored) names it. It is used ONLY when a build asks for it with
// `-Platentjam.playSigning=true`: the GitHub releases keep the debug key, see buildTypes below.
val playSigningRequested = providers.gradleProperty("latentjam.playSigning").orNull == "true"
// `-Platentjam.fdroid=true` is what F-Droid's build server passes: one universal APK, no ABI
// splits (they would share a versionCode, and fdroidserver expects exactly one output), and
// no signing at all, because F-Droid signs with its own key after the build.
val fdroidBuild = providers.gradleProperty("latentjam.fdroid").orNull == "true"
val uploadKeystore: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }
if (playSigningRequested) {
    requireNotNull(uploadKeystore) {
        "latentjam.playSigning=true needs keystore.properties at the repository root"
    }
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
        versionCode = 12
        versionName = "0.7.1"
    }

    if (uploadKeystore != null) {
        signingConfigs.create("upload") {
            storeFile = file(uploadKeystore.getProperty("storeFile"))
            storePassword = uploadKeystore.getProperty("storePassword")
            keyAlias = uploadKeystore.getProperty("keyAlias")
            keyPassword = uploadKeystore.getProperty("keyPassword")
        }
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
            // Deliberately the debug key: every install out there (v0.1.0, v0.2.0 sideloads)
            // carries this signature, and switching keys would force uninstall — losing the
            // library index and listening history. A sideload-distributed app keeps its
            // upgrade path by keeping its key. Google Play is a separate channel with its own
            // signature (Play App Signing re-signs uploads anyway), so only a build that asks
            // for it — `bundleRelease -Platentjam.playSigning=true` — uses the upload key.
            signingConfig = when {
                playSigningRequested -> signingConfigs.getByName("upload")
                fdroidBuild -> null
                else -> signingConfigs.getByName("debug")
            }
        }
    }

    // One APK per ABI instead of one APK with four: libonnxruntime.so alone is 20-33 MB per
    // architecture. No universal APK on purpose — shipping it would quietly re-bloat the release
    // download. Splits apply to every build type, so x86_64 stays in the list for Intel-hosted
    // emulators — the release upload simply never includes that file. A Play bundle splits by
    // ABI on Google's side and AGP refuses to build one while these splits are on, so the
    // Play-signed build turns them off.
    packaging {
        // F-Droid serves one universal APK, so its download size matters more than the
        // install-time extraction that compressed native libraries cost. The other channels
        // keep AGP's default: uncompressed, page-aligned libraries loaded straight from the APK.
        jniLibs.useLegacyPackaging = fdroidBuild
    }

    splits {
        abi {
            isEnable = !playSigningRequested && !fdroidBuild
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
}

// Builds that turn the splits off package every ABI a dependency ships, x86 included. An ABI that has
// ONNX Runtime but not LatentJam's operators (:core:ort-ops) installs, then never loads the music encoder.
// Android Studio builds native code only for the connected device's ABI (android.injected.build.abi), so
// its builds are not held to this.
val deviceAbiOnly = providers.gradleProperty("android.injected.build.abi").isPresent
androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar(Char::uppercaseChar)
        val verify = tasks.register<CheckNativeLibraries>("check${variantName}NativeLibraries") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Fails when an ABI of this build packages ONNX Runtime without libljq4.so."
            nativeLibraries.set(variant.artifacts.get(SingleArtifact.MERGED_NATIVE_LIBS))
        }
        if (!deviceAbiOnly) {
            tasks.named { it == "assemble$variantName" || it == "bundle$variantName" }
                .configureEach { dependsOn(verify) }
        }
    }
}

/** Fails when an ABI directory of [nativeLibraries] holds libonnxruntime.so but not libljq4.so. */
@DisableCachingByDefault(because = "Listing the libraries again is as quick as a cache lookup")
abstract class CheckNativeLibraries : DefaultTask() {

    @get:InputFiles
    @get:SkipWhenEmpty
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeLibraries: DirectoryProperty

    @TaskAction
    fun check() {
        val abis = nativeLibraries.get().asFile.resolve("lib").listFiles().orEmpty().filter { it.isDirectory }
        val missing = abis
            .filter { it.resolve("libonnxruntime.so").isFile && !it.resolve("libljq4.so").isFile }
            .map { it.name }
            .sorted()
        if (missing.isNotEmpty()) {
            throw GradleException(
                "libljq4.so is missing for ${missing.joinToString()}, which packages ONNX Runtime: the music " +
                    "encoder would never load on such a device. Add the ABI to :core:ort-ops' abiFilters.",
            )
        }
    }
}

dependencies {
    implementation(project(":composeApp"))
}
