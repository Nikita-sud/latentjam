// SPDX-License-Identifier: Apache-2.0
@file:OptIn(androidx.benchmark.macro.ExperimentalMetricApi::class)

package io.github.nikitasud.latentjam.benchmark

import android.os.Trace
import android.os.SystemClock
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import java.util.regex.Pattern
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

private fun MacrobenchmarkScope.chooseTrackSort(name: String) {
    if (device.hasObject(By.res("latentjam_song_order_${name}_ASCENDING"))) return
    device.wait(Until.findObject(By.res("latentjam_song_sort")), WAIT_MS)?.click()
        ?: error("Track sort menu unavailable")
    // DropdownMenu is a separate Compose window; root testTagsAsResourceId does not
    // propagate to it. Match its visible translated labels, configurable for the fixture.
    val defaultLabel = when (name) {
        "TITLE" -> "Title|Название"
        "ARTIST" -> "Artist|Исполнитель"
        else -> error("Unsupported benchmark sort: $name")
    }
    val label = InstrumentationRegistry.getArguments().getString("sort${name}Label", defaultLabel)
    device.wait(Until.findObject(By.text(Pattern.compile(label))), WAIT_MS)?.click()
        ?: error("Sort choice $name unavailable; check the fixture locale's label argument")
    check(device.wait(Until.hasObject(By.res("latentjam_song_order_${name}_ASCENDING")), WAIT_MS)) {
        "The requested order was not published"
    }
    device.waitForIdle(WAIT_MS)
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
            "full" -> CompilationMode.Full()
            else -> error("profile must be none, require or full")
        }

    private val uiIterations: Int
        get() = InstrumentationRegistry.getArguments().getString("iterations", "5").toInt()
            .also { require(it in 1..30) { "iterations must be in 1..30" } }

    /** Real artwork requests and lazy-list reuse, with the same gestures in both directions. */
    @Test fun scrollTracks() = benchmark.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation,
        iterations = uiIterations,
        setupBlock = {
            killProcess(); pressHome(); startActivityAndWait(); firstList()
            chooseTrackSort("ARTIST"); chooseTrackSort("TITLE")
            device.waitForIdle(WAIT_MS)
        },
    ) {
        val list = device.wait(Until.findObject(By.res("latentjam_track_list")), WAIT_MS)
            ?: error("The target needs benchmark list tags")
        // The LazyColumn draws behind the mini player/navigation bar. Start above both,
        // otherwise a fling can hit the overlay even though the list has a large viewport.
        val bounds = list.visibleBounds
        val x = bounds.centerX()
        val top = bounds.top + device.displayHeight / 12
        val bottom = bounds.bottom - device.displayHeight / 4
        check(bottom > top) { "Not enough unobstructed list space for the gesture" }
        // UiObject2.fling waits 5 seconds for a scroll accessibility event on some Compose
        // versions. Inject the same bounded physical swipe without that event dependency.
        val steps = ((bottom - top) / 3_500f * 1_000 / 5).toInt().coerceAtLeast(5)
        fun swipe(down: Boolean) {
            check(device.swipe(x, if (down) bottom else top, x, if (down) top else bottom, steps))
            SystemClock.sleep(700) // Let inertia draw frames before the next gesture.
        }
        fun firstVisibleRowText(): String = device.findObjects(By.res("latentjam_track_row"))
            .firstOrNull()?.findObjects(By.clazz("android.widget.TextView"))
            ?.joinToString("\n") { it.text.orEmpty() }.orEmpty()
        val before = firstVisibleRowText()
        require(before.isNotEmpty()) { "Fixture row semantics unavailable" }
        swipe(down = true)
        device.waitForIdle(WAIT_MS)
        check(firstVisibleRowText() != before) { "The gesture did not move the track list" }
        repeat(7) { swipe(down = true) }
        repeat(8) { swipe(down = false) }
        device.waitForIdle(WAIT_MS)
    }

    /** Waits for the order actually displayed, not just the selected sort-menu label. */
    @Test fun changeTrackSort() = benchmark.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric(), TraceSectionMetric("LatentJam.SortRoundTrip",
            TraceSectionMetric.Mode.Sum, targetPackageOnly = false)),
        compilationMode = compilation,
        iterations = uiIterations,
        setupBlock = {
            killProcess(); pressHome(); startActivityAndWait(); firstList()
            chooseTrackSort("ARTIST"); chooseTrackSort("TITLE")
            device.waitForIdle(WAIT_MS)
        },
    ) {
        repeat(3) {
            traced("LatentJam.SortRoundTrip") {
                chooseTrackSort("ARTIST")
                chooseTrackSort("TITLE")
            }
        }
    }

    /** Separates player transitions from search and from continuous audio rendering. */
    @Test fun openClosePlayer() = benchmark.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation,
        iterations = uiIterations,
        setupBlock = {
            killProcess(); pressHome(); startActivityAndWait(); firstList()
            device.findObject(By.res("latentjam_track_row")).click()
            val pause = InstrumentationRegistry.getArguments()
                .getString("pauseDescription", "Pause|Пауза")
            device.wait(Until.findObject(By.desc(Pattern.compile(pause))), WAIT_MS)?.click()
                ?: error("A playable fixture and matching pauseDescription are required")
            device.waitForIdle(WAIT_MS)
        },
    ) {
        repeat(5) {
            device.wait(Until.findObject(By.res("latentjam_mini_player")), WAIT_MS)?.click()
                ?: error("Mini player unavailable")
            check(device.wait(Until.hasObject(By.res("latentjam_full_player")), WAIT_MS))
            device.waitForIdle(WAIT_MS)
            device.pressBack()
            check(device.wait(Until.gone(By.res("latentjam_full_player")), WAIT_MS))
            check(device.wait(Until.hasObject(By.res("latentjam_track_list")), WAIT_MS))
            device.waitForIdle(WAIT_MS)
        }
    }

    @Test fun coldStartAndFirstList() = benchmark.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric(),
            TraceSectionMetric("LatentJam.FirstList", TraceSectionMetric.Mode.First, targetPackageOnly = false)),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = uiIterations,
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

