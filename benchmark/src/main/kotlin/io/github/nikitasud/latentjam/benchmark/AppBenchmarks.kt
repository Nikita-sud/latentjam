// SPDX-License-Identifier: Apache-2.0
@file:OptIn(androidx.benchmark.macro.ExperimentalMetricApi::class)

package io.github.nikitasud.latentjam.benchmark

import android.os.Trace
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "io.github.nikitasud.latentjam.kmp.benchmark"
private const val WAIT_MS = 30_000L

private fun MacrobenchmarkScope.page(name: String) {
    repeat(9) {
        val tab = device.findObject(By.res("latentjam_page_$name"))
        if (tab != null && tab.visibleBounds.width() > 0) { tab.click(); return }
        val header = device.wait(Until.findObject(By.res("latentjam_browse_header")), WAIT_MS)
            ?: error("Browse navigation unavailable")
        val bounds = header.visibleBounds
        device.swipe(bounds.right - 30, bounds.centerY(), bounds.left + 30, bounds.centerY(), 20)
    }
    error("Enable $name in the fixture's visible pages")
}

private fun MacrobenchmarkScope.firstList() {
    page("tracks")
    check(device.wait(Until.hasObject(By.res("latentjam_track_row")), WAIT_MS)) {
        "A prepared library with at least one track is required; an empty app is not a valid profile."
    }
}

private fun traced(name: String, block: () -> Unit) {
    Trace.beginSection(name)
    try { block() } finally { Trace.endSection() }
}

private fun MacrobenchmarkScope.cache(clear: Boolean): Boolean {
    val action = if (clear) "CLEAR_MAP" else "HAS_MAP"
    val response = device.executeShellCommand("am broadcast --include-stopped-packages -a " +
        "io.github.nikitasud.latentjam.benchmark.$action -n " +
        "$PACKAGE/io.github.nikitasud.latentjam.benchmark.MapCacheControl")
    check(response.contains("result=0") || response.contains("result=1")) { response }
    return response.contains("result=1")
}

private fun MacrobenchmarkScope.browsePlayerAndReturn() {
    firstList()
    device.wait(Until.findObject(By.res("latentjam_search")), WAIT_MS)?.click()
        ?: error("Search unavailable")
    device.wait(Until.findObject(By.clazz("android.widget.EditText")), WAIT_MS)?.text = "benchmark"
    device.pressBack()
    device.pressBack()
    firstList()
    device.findObject(By.res("latentjam_track_row")).click()
    device.wait(Until.findObject(By.res("latentjam_mini_player")), WAIT_MS)?.click()
        ?: error("Mini player unavailable")
    check(device.wait(Until.hasObject(By.res("latentjam_full_player")), WAIT_MS))
    device.pressBack()
    pressHome()
    startActivityAndWait()
    firstList()
}

@RunWith(AndroidJUnit4::class)
class AppBenchmarks {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val compilation: CompilationMode
        get() = when (InstrumentationRegistry.getArguments().getString("profile", "none")) {
            "require" -> CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require)
            "none" -> CompilationMode.None()
            else -> error("profile must be none or require")
        }

    @Test fun coldStartAndFirstList() = benchmark.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric(),
            TraceSectionMetric("LatentJam.FirstList", TraceSectionMetric.Mode.First, targetPackageOnly = false)),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { pressHome() },
    ) {
        traced("LatentJam.FirstList") { startActivityAndWait(); firstList() }
    }

    @Test fun searchPlayerAndReturn() = benchmark.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation,
        iterations = 5,
        setupBlock = { pressHome(); startActivityAndWait() },
    ) { browsePlayerAndReturn() }

    @Test fun readyMap() = map(cold = false)
    @Test fun uncachedMap() = map(cold = true)

    private fun map(cold: Boolean) = benchmark.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric(), TraceSectionMetric("LatentJam.MapReady",
            TraceSectionMetric.Mode.First, targetPackageOnly = false)),
        compilationMode = compilation,
        iterations = 3,
        setupBlock = {
            killProcess()
            check(cache(clear = cold) != cold) {
                "Prepare a completed map for readyMap; only derived map data may be reset."
            }
            killProcess() // Also discard the previous in-memory layout/regions.
            pressHome()
        },
    ) {
        traced("LatentJam.MapReady") {
            startActivityAndWait()
            page("map")
            check(device.wait(Until.hasObject(By.res("latentjam_map_ready")), 600_000L)) {
                "Map did not complete; indexing must finish before either map benchmark."
            }
        }
    }

}

@RunWith(AndroidJUnit4::class)
class AppBaselineProfile {
    @get:Rule val profile = BaselineProfileRule()
    @Test fun startup() = profile.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome(); startActivityAndWait(); firstList()
    }
    @Test fun launchBrowseSearchPlayerReturn() = profile.collect(
        packageName = PACKAGE,
        includeInStartupProfile = false,
    ) {
        pressHome()
        startActivityAndWait()
        browsePlayerAndReturn()
    }
}

