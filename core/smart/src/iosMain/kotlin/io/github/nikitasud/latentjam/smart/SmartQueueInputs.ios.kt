/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.chain.PredictorRuntime
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitWeekday
import platform.Foundation.NSDate
import org.koin.core.module.Module
import org.koin.dsl.module

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
    single<SmartClock> { SystemSmartClock() }
}
