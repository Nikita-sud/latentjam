/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.chain.PredictorRuntime
import java.util.Calendar
import java.util.GregorianCalendar
import org.koin.core.module.Module
import org.koin.dsl.module

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
    single<SmartClock> { SystemSmartClock() }
}
