/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.chain.PredictorRuntime
import platform.Foundation.NSBundle
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitWeekday
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.koin.core.module.Module
import org.koin.dsl.module

/** Reads the packed descriptors from the app bundle. */
internal class BundleDescriptorSource(
    private val resource: String = "semantic_descriptors",
    private val extension: String = "bin",
) : DescriptorSource {

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun read(): ByteArray? {
        val path = NSBundle.mainBundle.pathForResource(resource, extension) ?: return null
        val data: NSData = NSData.dataWithContentsOfFile(path) ?: return null
        val length = data.length.toInt()
        if (length == 0) return null
        return ByteArray(length).also { bytes ->
            bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        }
    }
}

internal class SystemSmartClock : SmartClock {
    override fun timeFeatures(): FloatArray {
        val calendar = NSCalendar.currentCalendar
        val components = calendar.components(
            NSCalendarUnitHour or NSCalendarUnitWeekday,
            fromDate = NSDate(),
        )
        // NSCalendar counts Sunday as 1; training used Monday = 0.
        return PredictorRuntime.timeFeatures(
            hourOfDay = components.hour.toInt(),
            dayOfWeek = (components.weekday.toInt() + 5) % 7,
        )
    }
}

public actual fun smartChainInputsModule(): Module = module {
    single<DescriptorSource> { BundleDescriptorSource() }
    single<SmartClock> { SystemSmartClock() }
}
