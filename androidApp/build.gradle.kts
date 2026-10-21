/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
import com.android.build.api.artifact.SingleArtifact
import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.inject.Inject
import org.gradle.process.ExecOperations
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
        versionCode = 17
        versionName = "0.9.2-smart-test"
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
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            // Generate readable rules first, then measure the same optimized build with/without them.
            if (providers.gradleProperty("latentjam.profileGeneration").orNull == "true") {
                isMinifyEnabled = false
                isShrinkResources = false
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

    // The artist knowledge pack and the entity index are read in place, a few bytes per lookup, so
    // the app maps them from the APK instead of copying 12 MB onto a Java heap that is 48 MB in all
    // on the smallest devices (core/smart's assetBytes). Only an uncompressed entry can be mapped;
    // storing these two costs the APK about 1.1 MB.
    androidResources {
        noCompress += listOf("music_entities_250k.bin", "artist_knowledge.bin", "artist_colisten.bin", "artist_lfm.bin", "artist_known.bin", "audio_tagger.bin", "artist_pop.bin", "artist_family.bin", "artist_family_l2.bin")
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
// Device-targeted builds compile the first supported ABI in Studio's preference list. Validate it instead of
// disabling the gate for the very APK used in release device checks.
val buildOnlyTargetAbi = providers.gradleProperty("android.buildOnlyTargetAbi").orNull != "false"
val supportedDeviceAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
// A plain list, read now: a provider lambda would capture this script, which the configuration cache
// cannot store, and every release build would then fail.
val deviceAbis: List<String> = providers.gradleProperty("android.injected.build.abi").orNull
    ?.takeIf { buildOnlyTargetAbi }
    ?.split(',')?.map(String::trim)
    ?.firstOrNull { it in supportedDeviceAbis }
    ?.let { listOf(it) }
    .orEmpty()
androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar(Char::uppercaseChar)
        val verify = tasks.register<CheckNativeLibraries>("check${variantName}NativeLibraries") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Fails when an ABI of this build lacks LatentJam's native libraries."
            nativeLibraries.set(variant.artifacts.get(SingleArtifact.MERGED_NATIVE_LIBS))
            requestedAbis.set(deviceAbis)
        }
        tasks.named { it == "assemble$variantName" || it == "bundle$variantName" }
            .configureEach { dependsOn(verify) }
        // R8 mis-encodes a method that needs more than 256 registers, and the verifier then rejects
        // its class at launch: the 0.8.1 candidate fe24e12c crashed on start in every release build.
        // Every release channel, including universal and device-targeted APKs, runs the gate.
        if (variant.buildType == "release") {
            val registers = tasks.register<CheckDexRegisters>("check${variantName}DexRegisters") {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Fails when a method of this build's APKs needs more than 255 registers."
                apks.set(variant.artifacts.get(SingleArtifact.APK))
                mapping.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
                script.set(rootProject.layout.projectDirectory.file("tools/release/check-dex-registers.py"))
                report.set(layout.buildDirectory.file("reports/dex-registers/${variant.name}.txt"))
            }
            tasks.named { it == "assemble$variantName" }.configureEach { dependsOn(registers) }
        }
    }
}

/**
 * Runs tools/release/check-dex-registers.py over the APKs in [apks]: fails when a method needs more
 * than 255 registers or the check cannot run (for example no python3 or no SDK dexdump).
 */
@DisableCachingByDefault(because = "Reading the DEX again is as quick as a cache lookup")
abstract class CheckDexRegisters : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val apks: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mapping: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val script: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun check() {
        val files = apks.get().asFile.listFiles().orEmpty().filter { it.name.endsWith(".apk") }.sortedBy { it.name }
        val output = ByteArrayOutputStream()
        val exitValue = try {
            execOperations.exec {
                commandLine(
                    listOf("python3", script.get().asFile.path, "--mapping", mapping.get().asFile.path) +
                        files.map { it.path },
                )
                standardOutput = output
                errorOutput = output
                isIgnoreExitValue = true
            }.exitValue
        } catch (failure: GradleException) {
            output.write("${failure.message}\n".toByteArray())
            2
        }
        val text = output.toString(Charsets.UTF_8)
        report.get().asFile.writeText(text)
        when (exitValue) {
            0 -> logger.info(text)
            1 -> throw GradleException("A method needs more than 255 registers; R8 mis-encodes it:\n$text")
            else -> throw GradleException(
                "Could not check the DEX register counts (exit $exitValue); " +
                    "release verification is incomplete. Install python3 and Android SDK build-tools " +
                    "and rerun checkReleaseDexRegisters:\n$text",
            )
        }
    }
}

/** Checks every native library required by a packaged runtime, including targeted device builds. */
@DisableCachingByDefault(because = "Listing the libraries again is as quick as a cache lookup")
abstract class CheckNativeLibraries : DefaultTask() {

    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeLibraries: DirectoryProperty

    @get:Input
    abstract val requestedAbis: ListProperty<String>

    @TaskAction
    fun check() {
        val abis = nativeLibraries.get().asFile.resolve("lib").listFiles().orEmpty().filter { it.isDirectory }
        val requested = requestedAbis.get().toSet()
        val selected = abis.filter { requested.isEmpty() || it.name in requested }
        val absentAbis = requested - selected.map { it.name }.toSet()
        val missing = selected.flatMap { abi ->
            listOf("libonnxruntime.so", "libonnxruntime4j_jni.so", "libljq4.so", "libljmap.so", "liblj_equalizer.so")
                .filterNot { abi.resolve(it).isFile }.map { "${abi.name}/$it" }
        }
            .sorted()
        if (selected.isEmpty() || absentAbis.isNotEmpty() || missing.isNotEmpty()) {
            throw GradleException(
                "Native packaging is incomplete. Missing ABIs: $absentAbis; libraries: $missing. " +
                    "Every packaged ABI needs the runtime, music operators, map and audio DSP libraries.",
            )
        }
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
