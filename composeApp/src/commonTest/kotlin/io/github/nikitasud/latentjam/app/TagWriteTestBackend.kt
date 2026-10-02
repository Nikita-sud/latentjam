/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.Id3Tags
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.write.DurableWriter
import io.github.nikitasud.latentjam.library.tags.write.TagRecovery
import io.github.nikitasud.latentjam.library.tags.write.TargetFile

/** An MPEG-1 Layer III frame header, so an untagged file may be given a tag. */
internal val TEST_MPEG_FRAME = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64)

/** An MP3 whose fresh ID3v2.3 tag (16 KiB padding) holds [title], so title edits patch in place. */
internal fun testMp3(title: String = "Old"): ByteArray =
    Id3Tags.updateTag(TEST_MPEG_FRAME + ByteArray(1024), TagEdits(title = title))!!

internal class TestTagWriteBackend(override val strategy: TagWriteStrategy) : TagWriteBackend<String> {
    val files = MemoryWriteFiles()
    var permission = false
    val granted = HashSet<String>()
    val consentBatches = ArrayList<List<String>>()
    val rescans = ArrayList<List<String>>()
    val stashed = HashMap<String, ByteArray>()
    val savedKeys = HashMap<Long, List<String>>()
    var keyReads = 0
    var permissionError = false
    var keysError = false

    /** Runs as a file's open begins; a test suspends here to hold that save in flight. */
    var gate: suspend (String) -> Unit = {}

    /** Runs before a batch consent is built; a test suspends here to act while it is asked for. */
    var consentGate: suspend () -> Unit = {}

    /** Files there that cannot be opened at all, as a row MediaStore has lost can be. */
    val unopenable = HashSet<String>()

    /** Where each file is, as the platform reports it with an open handle. */
    val paths = HashMap<String, String>()

    /** Files whose handle's force() fails, as a process killed mid-save leaves them. */
    val forceFails = HashSet<String>()

    /** The keys each read-only look before a consent was asked about. */
    val probes = ArrayList<List<String>>()

    /** False for a backend that cannot look at all (iOS), or a test whose keys name no files. */
    var looks = true

    /** Files a look cannot tell about, as a provider error leaves them. */
    val cannotTell = HashSet<String>()

    /** Files whose open has begun and whose handle is not yet closed. */
    var inFlight = 0
    private var ids = 0
    override val writer = DurableWriter(files.directory, { "w${++ids}" })
    override val recovery = TagRecovery(files.directory)

    override fun hasWritePermission(): Boolean {
        if (permissionError) throw IllegalStateException("the permission service is gone")
        return permission
    }
    override suspend fun batchConsent(keys: List<String>): String {
        consentGate()
        consentBatches += keys
        return "batch:${keys.size}"
    }
    override suspend fun look(keys: List<String>, paths: Map<String, String>): Map<String, FileLook>? {
        probes += keys
        if (!looks) return null
        return keys.associateWith { key ->
            when {
                key in cannotTell -> FileLook.UNKNOWN
                !files.has(key) -> FileLook.MISSING
                key in unopenable -> FileLook.UNOPENABLE
                else -> FileLook.PRESENT
            }
        }
    }
    override suspend fun open(key: String): WriteOpen<String> {
        inFlight++
        gate(key)
        val opened = openAllowed(key)
        if (opened !is WriteOpen.Opened) inFlight--
        return opened
    }
    private fun openAllowed(key: String): WriteOpen<String> {
        if (!files.has(key)) return WriteOpen.Missing
        if (key in unopenable) return WriteOpen.Failed
        val allowed = when (strategy) {
            TagWriteStrategy.NO_CONSENT -> true
            TagWriteStrategy.WRITE_PERMISSION -> permission
            TagWriteStrategy.SYSTEM_WRITE_REQUEST, TagWriteStrategy.RECOVERABLE_CONSENT -> key in granted
        }
        return when {
            allowed -> WriteOpen.Opened(
                object : TargetFile by files.track(key) {
                    override fun force() {
                        if (key in forceFails) throw IllegalStateException("process died")
                        files.track(key).force()
                    }

                    override fun close() {
                        inFlight--
                    }
                },
                freeBytes = null,
                path = paths[key],
            )
            strategy == TagWriteStrategy.RECOVERABLE_CONSENT -> WriteOpen.NeedsConsent("file:$key")
            else -> WriteOpen.Denied
        }
    }
    override suspend fun rescan(keys: List<String>) {
        rescans += keys
    }
    override fun stash(name: String, bytes: ByteArray) {
        stashed[name] = bytes
    }
    override fun unstash(name: String): ByteArray? = stashed[name]
    override fun drop(name: String) {
        stashed.remove(name)
    }
    override fun stashNames(): List<String> = stashed.keys.toList()
    override fun saveKeys(id: Long, keys: List<String>) {
        savedKeys[id] = keys.toList()
    }
    override fun loadKeys(id: Long): List<String>? {
        keyReads++
        if (keysError) throw IllegalStateException("the key file could not be read")
        return savedKeys[id]
    }
    override fun dropKeys(id: Long) {
        savedKeys.remove(id)
    }
    override fun savedKeyIds(): List<Long> = savedKeys.keys.toList()
}
