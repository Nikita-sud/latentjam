/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import java.io.File
import java.io.FileOutputStream

/** Writes and syncs a sibling first, then atomically replaces the live private file. */
internal fun File.atomicReplaceText(contents: String) {
    parentFile?.mkdirs()
    val temporary = File(parentFile, ".$name.tmp")
    try {
        FileOutputStream(temporary).use { stream ->
            val writer = stream.writer(Charsets.UTF_8)
            writer.write(contents)
            writer.flush()
            stream.fd.sync()
        }
        check(temporary.renameTo(this)) { "Could not atomically replace $name" }
    } finally {
        if (temporary.exists()) temporary.delete()
    }
}
