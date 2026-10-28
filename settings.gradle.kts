/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
pluginManagement {
    // The repository's own Gradle plugin (checkNativeIdentifiers).
    includeBuild("build-logic")
    repositories {
        // Content filters keep non-Google artifacts (kotlinx, koin, …) from ever
        // being requested from dl.google.com — resolution stays correct even when
        // that host is briefly unreachable.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

// The reduced ARM64 ONNX Runtime (tools/runtime/README.md, "Every channel"): release builds take it from the local
// Maven repository tools/runtime/ort_source_build.sh writes (build/reduced-ort/maven unless latentjam.reducedOrtRepo
// says otherwise), after checking that it was built for exactly the shipped models, the committed operator list and
// the app's ORT version. Debug and test builds keep Maven Central's full runtime, whose outputs the reduced one
// matches bitwise (the parity probe). androidApp's checkReleaseOnnxRuntime fails a release that would package the
// full ARM64 runtime, unless -Platentjam.stockOrt=true asks for it.
val stockOrt = providers.gradleProperty("latentjam.stockOrt").orNull == "true"
val reducedOrtRepo: File = (providers.gradleProperty("latentjam.reducedOrtRepo").orNull ?: "build/reduced-ort/maven")
    .let { File(it).takeIf(File::isAbsolute) ?: File(settingsDir, it) }
val reducedOrt: Map<String, String>? = run {
    val manifestFile = File(reducedOrtRepo, "manifest.json")
    if (stockOrt || !manifestFile.isFile) return@run null
    @Suppress("UNCHECKED_CAST")
    val manifest = groovy.json.JsonSlurper().parse(manifestFile) as Map<String, Any?>
    fun sha256(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    val stockVersion = Regex("(?m)^onnxruntime\\s*=\\s*\"([^\"]+)\"")
        .find(File(settingsDir, "gradle/libs.versions.toml").readText())?.groupValues?.get(1)
    if (manifest["ort_version"] != stockVersion) {
        throw GradleException("$reducedOrtRepo: built for ORT ${manifest["ort_version"]}, the app uses $stockVersion; rebuild it")
    }
    val models = File(settingsDir, "androidApp/src/main/assets/ml").listFiles().orEmpty()
        .filter { it.name.endsWith(".onnx") }.associate { it.name to sha256(it.readBytes()) }
    // The exact model set, removals included, is part of the runtime's identity.
    if (models.isEmpty() || models != manifest["models"]) {
        throw GradleException("$reducedOrtRepo: built for other models; regenerate tools/runtime/required-operators.config " +
            "with build_reduced_ort.py, rebuild with ort_source_build.sh and rerun the parity probe")
    }
    val operators = File(settingsDir, "tools/runtime/required-operators.config")
    if (sha256(operators.readBytes()) != manifest["operators_sha256"]) {
        throw GradleException("$reducedOrtRepo: built from another operator list than tools/runtime/required-operators.config")
    }
    val version = manifest["candidate_version"] as String
    if (version != "$stockVersion-latentjam-arm64") throw GradleException("$reducedOrtRepo: unexpected version $version")
    val aar = File(reducedOrtRepo, "com/microsoft/onnxruntime/onnxruntime-android/$version/onnxruntime-android-$version.aar")
    if (!aar.isFile || sha256(aar.readBytes()) != manifest["candidate_aar_sha256"]) {
        throw GradleException("$reducedOrtRepo: the AAR is missing or differs from its manifest")
    }
    val library = java.util.zip.ZipFile(aar).use { zip ->
        zip.getEntry("jni/arm64-v8a/libonnxruntime.so")?.let { zip.getInputStream(it).readBytes() }
    } ?: throw GradleException("$reducedOrtRepo: the AAR has no ARM64 runtime")
    mapOf("stock" to stockVersion!!, "candidate" to version, "librarySha256" to sha256(library))
}
if (reducedOrt != null) {
    dependencyResolutionManagement.repositories.maven {
        url = reducedOrtRepo.toURI()
        content { includeModule("com.microsoft.onnxruntime", "onnxruntime-android") }
    }
    gradle.beforeProject {
        configurations.configureEach {
            if (name.contains("release", ignoreCase = true)) {
                resolutionStrategy.dependencySubstitution {
                    substitute(module("com.microsoft.onnxruntime:onnxruntime-android:${reducedOrt.getValue("stock")}"))
                        .using(module("com.microsoft.onnxruntime:onnxruntime-android:${reducedOrt.getValue("candidate")}"))
                }
            }
        }
    }
}
// Read by androidApp's checkReleaseOnnxRuntime: the reduced runtime's ARM64 library hash, empty when none is in use.
gradle.extensions.extraProperties["latentjam.reducedOrtLibrarySha256"] = reducedOrt?.get("librarySha256").orEmpty()
gradle.extensions.extraProperties["latentjam.stockOrt"] = stockOrt

rootProject.name = "latentjam-monorepo"

include(":core:smart")
include(":core:ort-ops")
include(":core:map-native")
include(":core:audio-dsp")
include(":core:library")
include(":core:playback")
include(":core:history")
include(":composeApp")
include(":androidApp")
// Device tooling is opt-in; normal/F-Droid builds do not configure a benchmark APK.
if (providers.gradleProperty("latentjam.benchmarks").orNull == "true") include(":benchmark")
