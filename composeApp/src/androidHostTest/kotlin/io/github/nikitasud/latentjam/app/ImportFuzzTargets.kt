/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

public object BackupFuzz {
    @JvmStatic public fun fuzzerTestOneInput(data: ByteArray) {
        require(data.size <= 1024 * 1024)
        val snapshot = try {
            LocalBackupCodec.decode(data.decodeToString())
        } catch (_: LocalBackupFormatException) {
            return // This is the format's explicit refusal; other failures must escape.
        }
        val canonical = LocalBackupCodec.encode(snapshot)
        check(LocalBackupCodec.encode(LocalBackupCodec.decode(canonical)) == canonical)
    }
}
public object M3uFuzz {
    @JvmStatic public fun fuzzerTestOneInput(data: ByteArray) {
        require(data.size <= 1024 * 1024)
        val text = data.decodeToString()
        parseM3uName(text)
        val entries = parseM3u(text)
        check(entries == parseM3u(text))
        entries.forEach { m3uPathNames(it.path, decode = true) }
    }
}
class ImportFuzzCorpusTest {
    @Test fun replaySavedInputs() {
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "tools/fuzz/corpus").isDirectory }
        for ((name, run) in mapOf("backup" to BackupFuzz::fuzzerTestOneInput, "m3u" to M3uFuzz::fuzzerTestOneInput)) {
            val files = File(root, "tools/fuzz/corpus/$name").listFiles().orEmpty().filter { it.isFile }
            assertTrue(files.isNotEmpty(), "Missing corpus: $name")
            files.forEach { run(it.readBytes()) }
        }
    }
}

