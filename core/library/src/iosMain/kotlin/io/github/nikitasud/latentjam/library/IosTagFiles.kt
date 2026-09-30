/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.library.tags.write.AtomicReplacer
import io.github.nikitasud.latentjam.library.tags.write.RecoveryDirectory
import io.github.nikitasud.latentjam.library.tags.write.StorageFullException
import io.github.nikitasud.latentjam.library.tags.write.TargetFile
import kotlin.concurrent.Volatile
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileCreationDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFilePosixPermissions
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.posix.EDQUOT
import platform.posix.EINTR
import platform.posix.EINVAL
import platform.posix.ENOENT
import platform.posix.ENOSPC
import platform.posix.ENOTDIR
import platform.posix.ENOTSUP
import platform.posix.ENOTTY
import platform.posix.F_FULLFSYNC
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_NOFOLLOW
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.O_TRUNC
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fstat
import platform.posix.fsync
import platform.posix.ftruncate
import platform.posix.lstat
import platform.posix.pread
import platform.posix.pwrite
import platform.posix.rename
import platform.posix.stat
import platform.posix.unlink
import platform.posix.close as posixClose
import platform.posix.open as posixOpen

/**
 * A POSIX file descriptor as a [TargetFile]. It does not buffer, and [force] uses `F_FULLFSYNC`:
 * on Apple platforms plain `fsync` hands the data to the drive without flushing the drive's cache.
 */
internal class PosixTargetFile(private var fd: Int) : TargetFile {
    override val length: Long
        get() = memScoped {
            val info = alloc<stat>()
            check(fstat(fd, info.ptr) == 0) { "fstat failed: errno $errno" }
            info.st_size
        }

    override fun read(offset: Long, count: Int): ByteArray? {
        if (offset < 0 || count < 0 || offset > length || count > length - offset) return null
        val buffer = ByteArray(count)
        if (count == 0) return buffer
        buffer.usePinned { pinned ->
            var done = 0
            while (done < count) {
                val n = pread(fd, pinned.addressOf(done), (count - done).toULong(), offset + done)
                when {
                    n > 0 -> done += n.toInt()
                    n < 0 && errno == EINTR -> continue
                    else -> return null
                }
            }
        }
        return buffer
    }

    override fun write(offset: Long, bytes: ByteArray, from: Int, count: Int) {
        if (count == 0) return
        require(from >= 0 && count > 0 && from + count <= bytes.size) { "range $from+$count of ${bytes.size}" }
        bytes.usePinned { pinned ->
            var done = 0
            while (done < count) {
                val n = pwrite(fd, pinned.addressOf(from + done), (count - done).toULong(), offset + done)
                if (n < 0) {
                    if (errno == EINTR) continue
                    throw failure("write")
                }
                done += n.toInt()
            }
        }
    }

    override fun setLength(length: Long) {
        while (ftruncate(fd, length) != 0) {
            if (errno != EINTR) throw failure("ftruncate")
        }
    }

    /** `F_FULLFSYNC`, and plain `fsync` only on a file system that does not support it. */
    override fun force() {
        while (fcntl(fd, F_FULLFSYNC) == -1) {
            when (errno) {
                EINTR -> continue
                ENOTSUP, EINVAL, ENOTTY -> {
                    while (fsync(fd) != 0) {
                        if (errno != EINTR) throw failure("fsync")
                    }
                    return
                }
                // An I/O error must not fall back: an fsync after it can succeed on nothing written.
                else -> throw failure("F_FULLFSYNC")
            }
        }
    }

    override fun close() {
        if (fd >= 0) posixClose(fd)
        fd = -1
    }

    private fun failure(call: String): Exception {
        val code = errno
        // With delayed allocation the disk-full error can surface at the sync, not at the write.
        return if (code == ENOSPC || code == EDQUOT) StorageFullException("$call: no space (errno $code)")
        else IllegalStateException("$call failed: errno $code")
    }
}

/** Opens [path] as a [PosixTargetFile]; never through a symlink in its last component. */
private fun openFile(path: String, flags: Int): PosixTargetFile? {
    val fd = posixOpen(path, flags or O_NOFOLLOW or O_CLOEXEC, 0x180) // 0600 when created
    return if (fd >= 0) PosixTargetFile(fd) else null
}

/** Like [openFile], but null only when there is no such file: any other failure is not an absence. */
private fun openExisting(path: String, flags: Int): PosixTargetFile? =
    openFile(path, flags) ?: if (errno == ENOENT) null else throw IllegalStateException("open failed: errno $errno")

/** Only names this app gives its files; anything else in a directory (`.DS_Store`) is not ours. */
private val NAME = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")

private fun childOf(root: String, name: String): String {
    require(NAME.matches(name)) { "unsafe file name $name" }
    return "$root/$name"
}

/**
 * The names this app could have given files in [root]. Empty only when [root] does not exist: a
 * listing that fails (EMFILE, ENOMEM, EIO) throws, since read as empty it would make every open
 * save look finished, and a sweep would delete its only way back.
 */
private fun namesIn(root: String): List<String> {
    val manager = NSFileManager.defaultManager
    val names = manager.contentsOfDirectoryAtPath(root, null)
        ?: if (manager.fileExistsAtPath(root)) throw IllegalStateException("cannot list $root") else return emptyList()
    return names.filterIsInstance<String>().filter(NAME::matches).sorted()
}

private fun deleteFile(path: String) {
    if (unlink(path) != 0 && errno != ENOENT) throw IllegalStateException("unlink failed: errno $errno")
}

/** Creates [path] and makes its entry in the parent durable, once; retried after a failure. */
private class DirectoryOnce(private val path: String) {
    @Volatile
    private var ready = false

    fun ensure() {
        if (ready) return
        check(NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)) { "cannot create $path" }
        syncDirectory(path.substringBeforeLast('/'))
        ready = true
    }
}

/** The recovery store as a plain directory, its files opened with [PosixTargetFile]. */
internal class IosRecoveryDirectory(private val root: String) : RecoveryDirectory {
    private val directory = DirectoryOnce(root)

    /** Creates the store and syncs it into its parent: a record inside is only as durable as that entry. */
    fun prepare() {
        directory.ensure()
    }

    override fun create(name: String): TargetFile {
        val path = childOf(root, name)
        prepare()
        return openFile(path, O_RDWR or O_CREAT or O_TRUNC) ?: throw IllegalStateException("create failed: errno $errno")
    }

    override fun open(name: String): TargetFile? = openExisting(childOf(root, name), O_RDWR)

    override fun delete(name: String) {
        deleteFile(childOf(root, name))
    }

    override fun names(): List<String> = namesIn(root)

    override fun sync() {
        prepare()
        syncDirectory(root)
    }

    override fun freeBytes(): Long = freeBytesAt(root) ?: freeBytesAt(root.substringBeforeLast('/')) ?: 0L
}

/**
 * App-private files beside the recovery store: a save request's keys and replacement cover. Each is
 * written to a `.partial` file and renamed, so a killed process never leaves a torn one under its
 * name. [names] lists leftover partial files too, so a prune deletes them.
 */
public class IosPrivateFiles internal constructor(private val root: String) {
    private val directory = DirectoryOnce(root)

    public fun write(name: String, bytes: ByteArray) {
        val path = childOf(root, name)
        val partial = childOf(root, "$name$PARTIAL")
        directory.ensure()
        val file = openFile(partial, O_RDWR or O_CREAT or O_TRUNC) ?: throw IllegalStateException("create failed: errno $errno")
        file.use { it.write(0, bytes) }
        if (rename(partial, path) != 0) {
            val code = errno
            deleteFile(partial)
            throw IllegalStateException("rename failed: errno $code")
        }
    }

    public fun read(name: String): ByteArray? = openExisting(childOf(root, name), O_RDONLY)?.use { file ->
        check(file.length <= Int.MAX_VALUE) { "$name is too large" }
        file.read(0, file.length.toInt()) ?: throw IllegalStateException("short read of $name")
    }

    public fun delete(name: String) {
        deleteFile(childOf(root, name))
        deleteFile(childOf(root, "$name$PARTIAL"))
    }

    public fun names(): List<String> = namesIn(root)

    private companion object {
        const val PARTIAL = ".partial"
    }
}

/** `F_FULLFSYNC` of a directory, so the entries created, renamed and deleted in it survive a power cut. */
internal fun syncDirectory(path: String) {
    val fd = posixOpen(path, O_RDONLY or O_CLOEXEC)
    check(fd >= 0) { "cannot open $path: errno $errno" }
    PosixTargetFile(fd).use { it.force() }
}

private fun freeBytesAt(path: String): Long? =
    (NSFileManager.defaultManager.attributesOfFileSystemForPath(path, null)?.get(NSFileSystemFreeSize) as? NSNumber)
        ?.longLongValue

/** Syntactically an imported track: a relative path under Documents, never a Music-library id, `..` or `.`. */
private fun isImportedKey(key: String): Boolean =
    key.isNotBlank() && !key.startsWith('/') && !key.startsWith(IosTagFiles.MUSIC_LIBRARY_PREFIX) && '\u0000' !in key &&
        key.split('/').none { it == ".." || it == "." || it.isEmpty() }

/**
 * Resolves an imported track's stable id (its path relative to Documents) to a real file inside
 * Documents — never a Music-library id, an absolute path, `..`, or a symlink leading out. The open
 * that follows refuses a symlink in the last component, so what was checked is what is written.
 */
internal fun writableIosTrackPath(documents: String, key: String): String? {
    if (!isImportedKey(key)) return null
    val root = NSURL.fileURLWithPath(documents).URLByResolvingSymlinksInPath?.path?.trimEnd('/') ?: return null
    val path = NSURL.fileURLWithPath("$root/$key").URLByResolvingSymlinksInPath?.path ?: return null
    return path.takeIf { it.startsWith("$root/") }
}

/** The imported track [key] under [documents], opened for writing; null when it cannot be. */
internal fun openImportedTrack(documents: String, key: String): TargetFile? =
    writableIosTrackPath(documents, key)?.let { openFile(it, O_RDWR) }

/**
 * True only when the imported track [key] is certainly not there: Documents exists, and nothing at
 * all (not even a dangling symlink) is at the key's path. Any doubt is false, so the caller reports
 * a failure rather than a missing file.
 */
internal fun importedTrackIsGone(documents: String, key: String): Boolean {
    if (!isImportedKey(key)) return false
    return memScoped {
        val info = alloc<stat>()
        if (lstat(documents, info.ptr) != 0 || (info.st_mode.toInt() and S_IFMT) != S_IFDIR) return@memScoped false
        lstat("${documents.trimEnd('/')}/$key", info.ptr) != 0 && (errno == ENOENT || errno == ENOTDIR)
    }
}

/**
 * Replaces an imported track with a staged file in the store by one `rename`, which is atomic
 * within a volume. The track keeps its creation date (the library's "date added"), permissions and
 * data protection class; both directories are synced before the result is reopened.
 */
internal fun iosAtomicReplacer(documents: String, storeRoot: String): AtomicReplacer = AtomicReplacer { key, stagedName ->
    val path = writableIosTrackPath(documents, key) ?: throw IllegalStateException("not an imported track")
    val staged = childOf(storeRoot, stagedName)
    val manager = NSFileManager.defaultManager
    val kept = manager.attributesOfItemAtPath(path, null)?.filterKeys { it in KEPT_ATTRIBUTES }
        ?: throw IllegalStateException("the track's attributes could not be read")
    check(manager.setAttributes(kept, ofItemAtPath = staged, error = null)) { "the track's attributes could not be kept" }
    check(rename(staged, path) == 0) { "rename failed: errno $errno" }
    syncDirectory(path.substringBeforeLast('/'))
    syncDirectory(storeRoot)
    val replaced = openFile(path, O_RDWR) ?: throw IllegalStateException("reopen failed: errno $errno")
    try {
        replaced.force() // The attributes set on the staged file are metadata a directory sync may not cover.
    } catch (e: Exception) {
        replaced.close()
        throw e
    }
    replaced
}

private val KEPT_ATTRIBUTES = setOf<Any?>(NSFileCreationDate, NSFilePosixPermissions, NSFileProtectionKey)

/** The device id of [path]'s volume, or null when it cannot be read. */
private fun volumeOf(path: String): Int? = memScoped {
    val info = alloc<stat>()
    if (platform.posix.stat(path, info.ptr) == 0) info.st_dev else null
}

/**
 * The app's door to writing imported tracks. Imported files live in Documents, and the app owns
 * them: no consent is needed, and a rename replaces one atomically. Music-library items cannot be
 * written by any app.
 */
public object IosTagFiles {
    /** The prefix of Music-library track ids; see [IosMusicLibrary]. */
    public const val MUSIC_LIBRARY_PREFIX: String = "ios-media:"

    private fun storeRoot(): String? = IosPaths.appSupport()?.let { "$it/tag-write" }

    /** One instance per process, like the one coordinator over it. */
    private val store: IosRecoveryDirectory? by lazy { storeRoot()?.let(::IosRecoveryDirectory) }

    public fun isMusicLibraryTrack(key: String): Boolean = key.startsWith(MUSIC_LIBRARY_PREFIX)

    /** The recovery store under Application Support, or null when the sandbox is malformed. */
    public fun store(): RecoveryDirectory? = store

    /** Creates the store and syncs it into its parent; throws when it cannot. */
    public fun prepareStore() {
        (store ?: throw IllegalStateException("no Application Support")).prepare()
    }

    /** The files of save request bookkeeping of [kind] (`keys`, `covers`), never inside the store. */
    public fun requestFiles(kind: String): IosPrivateFiles? {
        require(NAME.matches(kind)) { "unsafe kind $kind" }
        return IosPaths.appSupport()?.let { IosPrivateFiles("$it/tag-write-requests/$kind") }
    }

    /** The imported track [key] (a path relative to Documents) opened for writing; null for Music-library ids, escapes and missing files. */
    public fun open(key: String): TargetFile? {
        val documents = IosPaths.documents() ?: return null
        return openImportedTrack(documents, key)
    }

    /** True only when the imported track [key] is certainly gone; see [importedTrackIsGone]. */
    public fun isGone(key: String): Boolean {
        val documents = IosPaths.documents() ?: return false
        return importedTrackIsGone(documents, key)
    }

    public fun freeBytes(): Long? = IosPaths.documents()?.let(::freeBytesAt)

    /**
     * A rename within the app container. Null when Documents and the store are not on one volume,
     * where a rename cannot be atomic; the writer then copies over a verified backup instead.
     */
    public fun replacer(): AtomicReplacer? {
        val documents = IosPaths.documents() ?: return null
        val support = IosPaths.appSupport() ?: return null
        val root = storeRoot() ?: return null
        val volume = volumeOf(documents) ?: return null
        if (volumeOf(support) != volume) return null
        return iosAtomicReplacer(documents, root)
    }
}
