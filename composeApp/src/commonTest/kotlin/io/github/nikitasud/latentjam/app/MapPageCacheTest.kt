package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.TrackStats
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.StoredLibraryLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class MapPageCacheTest {
    private val a = TrackId("a")
    private val b = TrackId("b")
    private fun layout(fingerprint: Long = 10, ids: List<TrackId> = listOf(a, b)) =
        StoredLibraryLayout(ids.associateWith { floatArrayOf(0.2f, 0.4f) }, fingerprint)
    private val missing = StoredLibraryLayout(emptyMap(), null)
    private fun stats(plays: Int) = mapOf(a to TrackStats(plays, 0, 0, 0, 0))

    @Test fun `warm return reads neither disk nor history and retains render caches`() = runTest {
        val cache = MapPageCache()
        var loads = 0
        var reads = 0
        repeat(3) {
            val geometry = cache.layout(listOf(a, b), 10, { loads++; layout() }, { error("No recompute") })!!
            val page = cache.page(geometry, mapOf(a to 0, b to 1), listOf("A", "B"), 1) { reads++; stats(7) }
            val again = cache.page(geometry, mapOf(a to 0, b to 1), listOf("A", "B"), 1) { error("No read") }
            assertSame(page, again)
            assertSame(page.dotIndex, again.dotIndex)
            assertSame(page.centroids, again.centroids)
        }
        assertEquals(1, loads)
        assertEquals(1, reads)
    }

    @Test fun `history updates and clearing change facts without loading or computing geometry`() = runTest {
        val cache = MapPageCache()
        val geometry = cache.layout(listOf(a, b), 10, { layout() }, { error("No recompute") })!!
        val first = cache.page(geometry, mapOf(a to 0, b to 1), listOf("A", "B"), 1) { stats(7) }
        val second = cache.page(geometry, mapOf(a to 0, b to 1), listOf("A", "B"), 2) { stats(8) }
        assertEquals(8, second.dots.first { it.trackId == a }.plays)
        assertEquals(first.dots.map { it.x to it.y }, second.dots.map { it.x to it.y })
        val cleared = cache.page(geometry, mapOf(a to 0, b to 1), listOf("A", "B"), 3) { emptyMap() }
        assertEquals(2, cleared.listening.neverPlayed)
        assertEquals(0, cleared.listening.maxPlays)
        assertSame(geometry, cache.layout(listOf(a, b), 10, { error("No read") }, { error("No recompute") }))
    }

    @Test fun `new labels and region membership invalidate only the presentation`() = runTest {
        val cache = MapPageCache()
        val geometry = layout()
        val first = cache.page(geometry, mapOf(a to 0, b to 1), listOf("A", "B"), 1) { stats(4) }
        val localized = cache.page(geometry, mapOf(a to 0, b to 1), listOf("А", "Б"), 1) { stats(4) }
        assertNotSame(first, localized)
        assertEquals(listOf("А", "Б"), localized.regionNames)
        val regrouped = cache.page(geometry, mapOf(a to 1, b to 0), listOf("А", "Б"), 1) { stats(4) }
        assertEquals(1, regrouped.dots.first { it.trackId == a }.region)
        assertEquals(4, regrouped.listening.regions.first { it.region == 1 }.plays)
    }

    @Test fun `same ids with changed vectors recompute and then reuse new layout`() = runTest {
        val cache = MapPageCache()
        val old = cache.layout(listOf(a, b), 10, { layout() }, { error("No recompute") })!!
        var builds = 0
        val new = cache.layout(listOf(a, b), 11, { old }, { previous ->
            assertSame(old, previous)
            builds++
            layout(11)
        })!!
        assertEquals(11, new.fingerprint)
        assertSame(new, cache.layout(listOf(a, b), 11, { error("No read") }, { error("No recompute") }))
        assertEquals(1, builds)
    }

    @Test fun `deleted tracks do not survive in cached positions`() = runTest {
        val cache = MapPageCache()
        val old = cache.layout(listOf(a, b), 10, { layout() }, { error("No recompute") })!!
        val new = cache.layout(listOf(a), 12, { old }, { layout(12, listOf(a)) })!!
        val page = cache.page(new, mapOf(a to 0), listOf("A"), 1) { stats(2) }
        assertEquals(listOf(a), page.dots.map { it.trackId })
    }

    @Test fun `unclaimed dots and regions without dots retain their identities`() = runTest {
        val cache = MapPageCache()
        val page = cache.page(layout(), mapOf(a to 0, TrackId("not drawable") to 1), listOf("A", "B"), 1) { stats(1) }
        assertEquals(MapDot.NO_REGION, page.dots.first { it.trackId == b }.region)
        assertEquals(listOf(0, 1), page.listening.regions.map { it.region })
    }

    @Test fun `failed disk save still permits a warm in-memory return`() = runTest {
        val cache = MapPageCache()
        val computed = cache.layout(listOf(a, b), 10, { missing }, { layout() })!!
        assertSame(computed, cache.layout(listOf(a, b), 10, { error("Disk remains empty") }, { error("No recompute") }))
    }

    @Test fun `cancelled or mismatched builds never poison the cache`() = runTest {
        val cache = MapPageCache()
        assertFailsWith<CancellationException> {
            cache.layout(listOf(a, b), 10, { missing }, { throw CancellationException() })
        }
        assertNull(cache.layout(listOf(a, b), 10, { missing }, { layout(11) }))
        val built = cache.layout(listOf(a, b), 10, { missing }, { layout() })!!
        assertEquals(10, built.fingerprint)
    }

    @Test fun `a failed statistics read keeps the previous presentation retryable`() = runTest {
        val cache = MapPageCache()
        val geometry = layout()
        val old = cache.page(geometry, mapOf(a to 0), listOf("A"), 1) { stats(1) }
        assertFailsWith<IllegalStateException> {
            cache.page(geometry, mapOf(a to 0), listOf("A"), 2) { error("Read failed") }
        }
        assertSame(old, cache.page(geometry, mapOf(a to 0), listOf("A"), 1) { error("Cached") })
        assertEquals(2, cache.page(geometry, mapOf(a to 0), listOf("A"), 2) { stats(2) }.listening.maxPlays)
    }
}
