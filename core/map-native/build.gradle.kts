// SPDX-License-Identifier: Apache-2.0
plugins { alias(libs.plugins.androidLibrary) }
android {
    namespace = "io.github.nikitasud.latentjam.mapnative"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 24
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
        consumerProguardFiles("consumer-rules.pro")
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

