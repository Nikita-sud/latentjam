/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    `java-gradle-plugin`
    // Plain Kotlin at the catalog's version rather than `kotlin-dsl`: the build downloads this compiler
    // anyway, while `kotlin-dsl` would add Gradle's embedded one to every clean build, F-Droid's included.
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    compilerOptions {
        // The plugin runs on the standard library Gradle embeds (2.3.0 under Gradle 9.4.1), not on the
        // catalog's, so a Kotlin bump must not let it call anything newer.
        apiVersion = KotlinVersion.fromVersion(embeddedKotlinVersion.substringBeforeLast('.'))
    }
}

dependencies {
    // Only the model interfaces, and compileOnly: every module that applies the plugin already has the
    // Kotlin Gradle plugin on its classpath.
    compileOnly(libs.kotlin.gradle.plugin.api)
    testImplementation(libs.kotlin.test)
}

gradlePlugin {
    plugins {
        register("nativeIdentifiers") {
            id = "latentjam.native-identifiers"
            implementationClass = "io.github.nikitasud.latentjam.buildlogic.NativeIdentifiersPlugin"
        }
    }
}
