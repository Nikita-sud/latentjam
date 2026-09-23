/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithURL
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceShouldCache
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
import platform.UIKit.UIPresentationController
import platform.UIKit.presentationController
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypeJPEG
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberPlaylistCoverPicker(
    onResult: (PlaylistCoverPickResult) -> Unit,
): () -> Unit {
    val host = LocalUIViewController.current
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    var busy by remember { mutableStateOf(false) }
    val delegate = remember(scope) {
        PlaylistCoverDelegate { result ->
            var delivered = false
            val delivery = scope.launch {
                busy = false
                currentOnResult(result)
                delivered = true
            }
            // If the owning composition disappears during the system's asynchronous load,
            // discard the new unclaimed file rather than leave an orphan in private storage.
            delivery.invokeOnCompletion {
                if (!delivered && result is PlaylistCoverPickResult.Selected) {
                    removeCoverFile(result.reference)
                }
            }
        }
    }
    return remember(host, delegate) {
        {
            if (!busy) {
                val root = host.view.window?.rootViewController
                if (root == null) {
                    currentOnResult(PlaylistCoverPickResult.Failed)
                } else {
                    busy = true
                    try {
                        var presenter = checkNotNull(root)
                        while (presenter.presentedViewController != null) {
                            presenter = checkNotNull(presenter.presentedViewController)
                        }
                        delegate.reset()
                        val configuration = PHPickerConfiguration().apply {
                            selectionLimit = 1L
                            filter = PHPickerFilter.imagesFilter
                        }
                        val picker = PHPickerViewController(configuration).apply {
                            this.delegate = delegate
                        }
                        presenter.presentViewController(picker, true, null)
                        picker.presentationController?.delegate = delegate
                    } catch (_: Exception) {
                        busy = false
                        currentOnResult(PlaylistCoverPickResult.Failed)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class PlaylistCoverDelegate(
    private val onResult: (PlaylistCoverPickResult) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol, UIAdaptivePresentationControllerDelegateProtocol {
    private var finished = false

    fun reset() { finished = false }

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        if (finished) return
        finished = true
        picker.dismissViewControllerAnimated(true, null)
        val chosen = didFinishPicking.firstOrNull() as? PHPickerResult
        if (chosen == null) {
            onResult(PlaylistCoverPickResult.Cancelled)
            return
        }
        // NSItemProvider invokes this completion on its internal queue. Its temporary file
        // expires when the block returns, so downsample directly here, never through UIImage
        // or a full-size NSData, and deliver only the small durable output back to the UI.
        chosen.itemProvider.loadFileRepresentationForTypeIdentifier(UTTypeImage.identifier) { url, error ->
            val reference = if (url != null && error == null) {
                runCatching { importPlaylistCover(url) }.getOrNull()
            } else {
                null
            }
            onResult(reference?.let(PlaylistCoverPickResult::Selected) ?: PlaylistCoverPickResult.Failed)
        }
    }

    override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
        if (!finished) {
            finished = true
            onResult(PlaylistCoverPickResult.Cancelled)
        }
    }
}

private val coverDirectory: String? by lazy {
    (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String)?.let { "$it/$PLAYLIST_COVER_DIRECTORY" }
}

internal actual fun playlistCoverUri(reference: String?): String? {
    if (!isPlaylistCoverReference(reference)) return null
    return coverDirectory?.let { NSURL.fileURLWithPath("$it/$reference").absoluteString }
}

internal actual suspend fun deletePlaylistCover(reference: String): Boolean =
    withContext(Dispatchers.Default) { removeCoverFile(reference) }

@OptIn(ExperimentalForeignApi::class)
private fun removeCoverFile(reference: String): Boolean {
    if (!isPlaylistCoverReference(reference)) return false
    val path = coverDirectory?.let { "$it/$reference" } ?: return false
    val manager = NSFileManager.defaultManager
    return !manager.fileExistsAtPath(path) || manager.removeItemAtPath(path, null)
}

@OptIn(ExperimentalForeignApi::class)
private fun importPlaylistCover(url: NSURL): String? {
    val manager = NSFileManager.defaultManager
    val sourcePath = url.path ?: return null
    val bytes = (manager.attributesOfItemAtPath(sourcePath, null)?.get(NSFileSize) as? NSNumber)
        ?.unsignedLongLongValue
    if (bytes != null && (bytes == 0uL || bytes > 64uL * 1024uL * 1024uL)) return null
    val directory = coverDirectory ?: return null
    if (!manager.createDirectoryAtPath(directory, true, null, null)) return null
    if (!NSURL.fileURLWithPath(directory, true).setResourceValue(true, NSURLIsExcludedFromBackupKey, null)) {
        return null
    }

    val sourceUrl = CFBridgingRetain(url) ?: return null
    val options = CFDictionaryCreateMutable(
        null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
    ) ?: run { CFRelease(sourceUrl); return null }
    try {
        CFDictionarySetValue(options, kCGImageSourceShouldCache, kCFBooleanFalse)
        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
        val dimension = memScoped {
            val value = alloc<IntVar> { this.value = PLAYLIST_COVER_MAX_EDGE }
            CFNumberCreate(null, kCFNumberIntType, value.ptr)
        } ?: return null
        CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, dimension)
        CFRelease(dimension)
        val source = CGImageSourceCreateWithURL(sourceUrl.reinterpret(), options) ?: return null
        try {
            val thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0u, options) ?: return null
            try {
                val width = CGImageGetWidth(thumbnail)
                val height = CGImageGetHeight(thumbnail)
                if (width == 0uL || height == 0uL || maxOf(width, height) > PLAYLIST_COVER_MAX_EDGE.toULong()) {
                    return null
                }
                val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
                val context = CGBitmapContextCreate(
                    null, width, height, 8u, width * 4u, colorSpace,
                    CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value,
                )
                CGColorSpaceRelease(colorSpace)
                if (context == null) return null
                try {
                    val rect = CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble())
                    CGContextSetRGBFillColor(context, 1.0, 1.0, 1.0, 1.0)
                    CGContextFillRect(context, rect)
                    CGContextDrawImage(context, rect, thumbnail)
                    val flattened = CGBitmapContextCreateImage(context) ?: return null
                    try {
                        val reference = "${NSUUID().UUIDString.lowercase()}.jpg"
                        val finalPath = "$directory/$reference"
                        val pendingPath = "$finalPath.tmp"
                        val outputUrl = CFBridgingRetain(NSURL.fileURLWithPath(pendingPath)) ?: return null
                        val jpegType = CFBridgingRetain(UTTypeJPEG.identifier)
                        try {
                            val destination = CGImageDestinationCreateWithURL(
                                outputUrl.reinterpret(), jpegType?.reinterpret(), 1u, null,
                            ) ?: return null
                            try {
                                CGImageDestinationAddImage(destination, flattened, null)
                                if (!CGImageDestinationFinalize(destination)) return null
                            } finally {
                                CFRelease(destination)
                            }
                            if (!manager.moveItemAtPath(pendingPath, finalPath, null)) return null
                            return reference
                        } finally {
                            manager.removeItemAtPath(pendingPath, null)
                            if (jpegType != null) CFRelease(jpegType)
                            CFRelease(outputUrl)
                        }
                    } finally {
                        CGImageRelease(flattened)
                    }
                } finally {
                    CGContextRelease(context)
                }
            } finally {
                CGImageRelease(thumbnail)
            }
        } finally {
            CFRelease(source)
        }
    } finally {
        CFRelease(options)
        CFRelease(sourceUrl)
    }
}
