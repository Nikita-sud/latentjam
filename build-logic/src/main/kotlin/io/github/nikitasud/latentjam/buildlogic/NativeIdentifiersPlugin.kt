/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileTree
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTargetsContainer

/**
 * Registers `checkNativeIdentifiers` on a Kotlin Multiplatform module and makes `testAndroidHostTest`
 * and `check` depend on it: the host tests compile names that the iOS compile refuses, and nothing
 * compiles for iOS routinely.
 */
class NativeIdentifiersPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            val guard = project.tasks.register("checkNativeIdentifiers", CheckNativeIdentifiers::class.java) { task ->
                task.group = LifecycleBasePlugin.VERIFICATION_GROUP
                task.description = "Fails on a backticked name that Kotlin/Native refuses, without compiling for iOS."
                task.sources.from(nativeSources(project))
                task.rootDirectory.set(project.rootDir)
                task.report.set(project.layout.buildDirectory.file("reports/native-identifiers.txt"))
            }
            project.pluginManager.apply(LifecycleBasePlugin::class.java)
            project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { it.dependsOn(guard) }
            project.tasks.named { it == "testAndroidHostTest" }.configureEach { it.dependsOn(guard) }
        }
    }

    /**
     * The hand-written Kotlin sources of every source set a Kotlin/Native compilation includes, which
     * brings in commonMain and commonTest through the dependsOn chain. They come from the model rather
     * than a list of names, so a new native source set is covered as it appears. The iOS targets exist
     * in the model on every host, Linux included; there only their compile tasks are skipped.
     */
    private fun nativeSources(project: Project): FileTree {
        val kotlin = project.extensions.getByName("kotlin") as KotlinTargetsContainer
        val directories = project.provider {
            val generated = project.layout.buildDirectory.get().asFile
            kotlin.targets
                .filter { it.platformType == KotlinPlatformType.native }
                .flatMap { target -> target.compilations.flatMap { it.allKotlinSourceSets } }
                .flatMap { it.kotlin.srcDirs }
                .filterNot { it.startsWith(generated) }
                .distinct()
        }
        return project.files(directories).asFileTree.matching { it.include("**/*.kt") }
    }
}
