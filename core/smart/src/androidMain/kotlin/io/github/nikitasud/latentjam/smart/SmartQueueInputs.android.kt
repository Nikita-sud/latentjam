/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import android.content.Context
import io.github.nikitasud.latentjam.smart.chain.PredictorRuntime
import java.util.Calendar
import java.util.GregorianCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.module.Module
import org.koin.dsl.module

/** Reads the packed descriptors from assets; absent asset means "no descriptors", not an error. */
internal class AssetDescriptorSource(
    private val context: Context,
    private val assetPath: String = ASSET_PATH,
) : DescriptorSource {

    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { context.assets.open(assetPath).use { it.readBytes() } }.getOrNull()
    }

    companion object {
        const val ASSET_PATH = "ml/semantic_descriptors.bin"
    }
}

internal class SystemSmartClock : SmartClock {
    override fun timeFeatures(): FloatArray {
        val calendar = GregorianCalendar.getInstance()
        return PredictorRuntime.timeFeatures(
            hourOfDay = calendar.get(Calendar.HOUR_OF_DAY),
            // Calendar counts Sunday as 1; training used Monday = 0.
            dayOfWeek = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7,
        )
    }
}

public actual fun smartChainInputsModule(): Module = module {
    single<DescriptorSource> { AssetDescriptorSource(get()) }
    single<SmartClock> { SystemSmartClock() }
}
