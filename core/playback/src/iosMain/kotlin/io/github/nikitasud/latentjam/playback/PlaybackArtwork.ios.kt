/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.Foundation.NSFileManager
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceShouldCache
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import platform.UIKit.UIImage

/** Called on a worker: never decode/cache the original embedded cover just for the lock screen. */
@OptIn(ExperimentalForeignApi::class)
internal fun loadPlaybackArtwork(uri: String?): UIImage? {
    val url = uri?.let(NSURL::URLWithString)?.takeIf { it.isFileURL() } ?: return null
    val path = url.path ?: return null
    val bytes = (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
        ?.get(NSFileSize) as? NSNumber)?.unsignedLongLongValue
    if (bytes != null && (bytes == 0uL || bytes > 64uL * 1024uL * 1024uL)) return null
    val retainedUrl = CFBridgingRetain(url) ?: return null
    val options = CFDictionaryCreateMutable(
        null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
    ) ?: run { CFRelease(retainedUrl); return null }
    try {
        CFDictionarySetValue(options, kCGImageSourceShouldCache, kCFBooleanFalse)
        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
        val dimension = memScoped {
            val value = alloc<IntVar> { this.value = PLAYBACK_ARTWORK_EDGE }
            CFNumberCreate(null, kCFNumberIntType, value.ptr)
        } ?: return null
        CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, dimension)
        CFRelease(dimension)
        val source = CGImageSourceCreateWithURL(retainedUrl.reinterpret(), options) ?: return null
        try {
            val image = CGImageSourceCreateThumbnailAtIndex(source, 0u, options) ?: return null
            try {
                val width = CGImageGetWidth(image)
                val height = CGImageGetHeight(image)
                if (width == 0uL || height == 0uL || maxOf(width, height) > PLAYBACK_ARTWORK_EDGE.toULong()) {
                    return null
                }
                return UIImage.imageWithCGImage(image)
            } finally {
                CGImageRelease(image)
            }
        } finally {
            CFRelease(source)
        }
    } finally {
        CFRelease(options)
        CFRelease(retainedUrl)
    }
}

private const val PLAYBACK_ARTWORK_EDGE = 512
