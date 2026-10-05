/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
// LatentJam's own ONNX Runtime operators as a native library (libljq4.so) for the ABIs the app ships.
// A plain Android library: the Kotlin Multiplatform Android plugin of :core:smart has no native build.
plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "io.github.nikitasud.latentjam.ortops"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 24
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
