/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Fails on a backticked name that Kotlin/Native refuses ([NATIVE_REFUSED_CHARACTERS]) in the sources
 * it compiles. It reads those sources instead of compiling them, so a Linux host, which cannot build
 * the iOS targets at all, gets the same answer as a Mac.
 */
@DisableCachingByDefault(because = "Reading the sources again is as quick as a cache lookup")
abstract class CheckNativeIdentifiers : DefaultTask() {

    @get:InputFiles
    @get:SkipWhenEmpty
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Reported paths are relative to this: the repository root. */
    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    /** The refused names, one per line; empty when there are none. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val root = rootDirectory.get().asFile
        val refused = sources.files.sortedBy { it.path }.flatMap { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            // Worded like the compiler's own error: Name contains illegal characters: ",".
            refusedNames(file.readText()).map { "$path:${it.line}: `${it.name}` contains \"${it.refused}\"" }
        }
        report.get().asFile.writeText(refused.joinToString("") { "$it\n" })
        if (refused.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Kotlin/Native refuses these backticked names, so the iOS compile fails on them:")
                    refused.forEach { appendLine("  $it") }
                    append("Kotlin/Native refuses ${NATIVE_REFUSED_CHARACTERS.toList().joinToString(" ")} in a name. ")
                    append("The JVM accepts most of them, so the Android host tests pass either way. ")
                    append("Reword each name without them.")
                },
            )
        }
    }
}
