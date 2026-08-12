/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import io.github.nikitasud.latentjam.history.LibraryListening
import io.github.nikitasud.latentjam.history.RegionListening
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorld
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorldNameSource
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorldSemanticTitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the pure helpers [MapTab] draws with: [screenPosition] (the zoom/pan transform shared by
 * the `Canvas` draw loop and hit-testing), [nearest] (hit-testing), and [largestRegionCentroids]
 * (label placement). Everything else in `MapTab.kt` is a `@Composable` and is not exercised here.
 */
class MapTabTest {

    private fun dot(trackId: String = "t", x: Float = 0.5f, y: Float = 0.5f, region: Int = 0) =
        MapDot(TrackId(trackId), x, y, region, plays = 0, skipRate = 0f)

    @Test
    fun `an interrupted cold map build returns to indexing`() {
        assertEquals(
            MapPageState.Indexing,
            MapPageState.Building.afterInterruptedMapBuild(),
        )
    }

    @Test
    fun `an interrupted warm map build keeps its page and clears the spinner`() {
        val ready = MapPageState.Ready(page(emptyList()), rebuilding = true)

        assertEquals(
            ready.copy(rebuilding = false),
            ready.afterInterruptedMapBuild(),
        )
    }

    @Test
    fun `starting a build keeps a warm page but uses a placeholder when cold`() {
        val warm = MapPageState.Ready(page(emptyList()))

        assertEquals(warm.copy(rebuilding = true), warm.duringMapBuild())
        assertEquals(MapPageState.Building, MapPageState.Indexing.duringMapBuild())
    }

    // Pins the one formula both the Canvas draw loop and `nearest` share: a dot at (0.25, 0.75) on
    // an 800x600 canvas, zoomed 2x and panned by (50, -20), lands at exactly (450, 880). If a future
    // change edits this formula, every caller moves together or this test catches the drift.
    @Test
    fun `screenPosition places a dot using the zoom and pan transform`() {
        val target = dot(x = 0.25f, y = 0.75f)

        val position = screenPosition(target, width = 800f, height = 600f, zoom = 2f, panX = 50f, panY = -20f)

        assertEquals(Offset(450f, 880f), position)
    }

    // Derives the expected tap point through screenPosition itself rather than re-deriving the
    // transform by hand, so this test cannot keep passing against a formula that has drifted from
    // the one screenPosition actually implements.
    @Test
    fun `nearest finds a dot under a zoomed and panned tap`() {
        val target = dot(trackId = "target", x = 0.5f, y = 0.5f)
        val decoy = dot(trackId = "decoy", x = 0.0f, y = 0.0f)
        val width = 800
        val height = 600
        val zoom = 2f
        val panX = 50f
        val panY = -20f

        val tap = screenPosition(target, width.toFloat(), height.toFloat(), zoom, panX, panY)

        val found = nearest(
            listOf(decoy, target),
            tap,
            width,
            height,
            zoom,
            panX,
            panY,
            hitRadiusPx = 24f,
        )
        assertEquals(target, found)
    }

    @Test
    fun `nearest returns the closer of two dots`() {
        val near = dot(trackId = "near", x = 0.1f, y = 0.1f)
        val far = dot(trackId = "far", x = 0.9f, y = 0.9f)

        val found = nearest(
            listOf(far, near),
            tap = Offset(100f, 100f),
            width = 1000,
            height = 1000,
            zoom = 1f,
            panX = 0f,
            panY = 0f,
            hitRadiusPx = 24f,
        )
        assertEquals(near, found)
    }

    // A generous hit target still has an edge. At zoom 1 with no pan, a dot sitting at the origin
    // has its screen centre at (0, 0); a tap 20px right and 10px down is 500 squared-px away
    // (inside a 24px radius, 576 squared-px), and a tap 20px right and 15px down is 625 squared-px
    // away (just outside it). The radius is a plain parameter here, independent of however the
    // caller (density-converted dp, in production) arrives at it.
    @Test
    fun `nearest respects the hit radius boundary`() {
        val target = dot(trackId = "origin", x = 0f, y = 0f)

        val inside = nearest(listOf(target), Offset(20f, 10f), 1, 1, 1f, 0f, 0f, hitRadiusPx = 24f)
        val outside = nearest(listOf(target), Offset(20f, 15f), 1, 1, 1f, 0f, 0f, hitRadiusPx = 24f)

        assertEquals(target, inside)
        assertNull(outside)
    }

    @Test
    fun `nearest returns null when every dot is out of reach`() {
        val target = dot(trackId = "far", x = 0.9f, y = 0.9f)
        val found = nearest(listOf(target), Offset(0f, 0f), 1000, 1000, 1f, 0f, 0f, hitRadiusPx = 24f)
        assertNull(found)
    }

    @Test
    fun `nearest returns null for an empty dot list`() {
        val found = nearest(emptyList(), Offset(100f, 100f), 1000, 1000, 1f, 0f, 0f, hitRadiusPx = 24f)
        assertNull(found)
    }

    // Closes the density finding directly: the same dp constant must resolve to more raw pixels on
    // a denser screen, or the hit target shrinks back to an unreachable sliver on high-density
    // phones exactly as it did when HIT_RADIUS was a raw px literal.
    @Test
    fun `HIT_RADIUS_DP converts to more raw pixels at higher density`() {
        val onePx = with(Density(density = 1f)) { HIT_RADIUS_DP.toPx() }
        val threePx = with(Density(density = 3f)) { HIT_RADIUS_DP.toPx() }

        assertEquals(24f, onePx)
        assertEquals(72f, threePx)
    }

    private fun page(dots: List<MapDot>) = MapPage(
        dots = dots,
        regionNames = List(dots.map { it.region }.distinct().size + 1) { "" },
        listening = LibraryListening(
            trackCount = dots.size,
            neverPlayed = 0,
            tracksForHalfOfPlays = 0,
            regions = emptyList(),
            darkestRegion = null,
            skippiestRegion = null,
            maxPlays = 0,
        ),
    )

    private fun region(index: Int) = RegionListening(
        region = index,
        trackCount = 1,
        neverPlayed = 1,
        plays = 0,
        skipRate = 0f,
    )

    @Test
    fun `region accessibility actions expose named real regions and update selection`() {
        val accessiblePage = MapPage(
            dots = listOf(dot("rock", region = 0), dot("ambient", region = 2)),
            regionNames = listOf("Rock", "", "Ambient", ""),
            listening = LibraryListening(
                trackCount = 2,
                neverPlayed = 2,
                tracksForHalfOfPlays = 0,
                // Duplicate, blank-named, and invalid entries model malformed/stale page data. None
                // should become an ambiguous TalkBack action.
                regions = listOf(region(0), region(2), region(2), region(3), region(-1)),
                darkestRegion = null,
                skippiestRegion = null,
                maxPlays = 0,
            ),
        )
        var selected = -1

        val actions = regionAccessibilityActions(accessiblePage) { selected = it }

        assertEquals(listOf("Rock", "Ambient"), actions.map { it.label })
        assertTrue(actions[1].action())
        assertEquals(2, selected)
    }

    @Test
    fun `largestRegionCentroids orders by region size and truncates to the limit`() {
        val dots = listOf(
            dot("a1", region = 0), dot("a2", region = 0), dot("a3", region = 0),
            dot("b1", region = 1),
            dot("c1", region = 2), dot("c2", region = 2),
        )
        val top = largestRegionCentroids(page(dots), limit = 2)
        assertEquals(listOf(0, 2), top.map { it.first })
    }

    // Deliberately picks coordinates exact in binary floating point (0, 1, 0.25, 0.75) so the mean
    // compares with assertEquals rather than needing a tolerance.
    @Test
    fun `largestRegionCentroids centres a region at the mean of its members`() {
        val dots = listOf(
            dot("a1", x = 0f, y = 0.25f, region = 0),
            dot("a2", x = 1f, y = 0.75f, region = 0),
        )
        val centroids = largestRegionCentroids(page(dots), limit = 5)
        assertEquals(1, centroids.size)
        assertEquals(0, centroids.single().first)
        assertEquals(Offset(0.5f, 0.5f), centroids.single().second)
    }

    @Test
    fun `largestRegionCentroids returns every region when the limit is not reached`() {
        val dots = listOf(dot("a", region = 0), dot("b", region = 1), dot("c", region = 2))
        val centroids = largestRegionCentroids(page(dots), limit = 5)
        assertEquals(3, centroids.size)
    }

    @Test
    fun `largestRegionCentroids returns nothing for an empty page`() {
        assertTrue(largestRegionCentroids(page(emptyList()), limit = 5).isEmpty())
    }

    // The unclaimed set is not a region and has no name to draw. Before this, its dots grouped into a
    // MapDot.NO_REGION "region" that could outrank real ones, take a label slot, then find
    // regionNames.getOrNull(-1) == null and silently draw nothing -- one of five slots wasted.
    @Test
    fun `largestRegionCentroids ignores dots no region claimed`() {
        val dots = listOf(
            dot("u1", region = MapDot.NO_REGION),
            dot("u2", region = MapDot.NO_REGION),
            dot("u3", region = MapDot.NO_REGION),
            dot("a1", region = 0),
        )

        val labelled = largestRegionCentroids(page(dots), limit = 5)

        assertEquals(listOf(0), labelled.map { it.first })
    }

    @Test
    fun `a dot is claimed only when a region took it`() {
        assertTrue(dot("a", region = 0).claimed)
        assertFalse(dot("u", region = MapDot.NO_REGION).claimed)
    }

    private fun world(
        name: String,
        source: LibraryWorldNameSource,
        semanticTitle: LibraryWorldSemanticTitle? = null,
    ) = LibraryWorld(
        name = name,
        tracks = listOf(track("t-$name")),
        nameSource = source,
        semanticTitle = semanticTitle,
    )

    private val semanticLabels = mapOf(
        LibraryWorldSemanticTitle.MEME_VIRAL_AUDIO to "Мемы и вирусные аудиозаписи",
    )

    // The defect this exists for: a real library produced three regions all named "Discovery mix",
    // which on the map meant three identical labels that could not be told apart. Numbering follows
    // the list order, which for the map is its stable size order.
    @Test
    fun `regionDisplayNames numbers every generic region`() {
        val regions = listOf(
            world("Discovery mix", LibraryWorldNameSource.GENERIC),
            world("Rock", LibraryWorldNameSource.GENRE),
            world("Discovery mix", LibraryWorldNameSource.GENERIC),
            world("Discovery mix", LibraryWorldNameSource.GENERIC),
        )

        val names = regionDisplayNames(regions, "Микс открытий", semanticLabels)

        assertEquals(
            listOf("Микс открытий 1", "Rock", "Микс открытий 2", "Микс открытий 3"),
            names,
        )
    }

    // One discovery region has nothing to be told apart from, so a bare "1" would be noise.
    @Test
    fun `regionDisplayNames leaves a lone generic region unnumbered`() {
        val regions = listOf(
            world("Rock", LibraryWorldNameSource.GENRE),
            world("Discovery mix", LibraryWorldNameSource.GENERIC),
        )

        val names = regionDisplayNames(regions, "Микс открытий", semanticLabels)

        assertEquals(listOf("Rock", "Микс открытий"), names)
    }

    // The map used to draw the generator's English "Meme & Viral Audio" while the For You tab beside
    // it showed the localized name for the very same region.
    @Test
    fun `regionDisplayNames localizes a semantic region`() {
        val regions = listOf(
            world(
                "Meme & Viral Audio",
                LibraryWorldNameSource.SEMANTIC,
                LibraryWorldSemanticTitle.MEME_VIRAL_AUDIO,
            ),
        )

        val names = regionDisplayNames(regions, "Микс открытий", semanticLabels)

        assertEquals(listOf("Мемы и вирусные аудиозаписи"), names)
    }

    // A semantic title with no label supplied must still name its region rather than blank it, and an
    // artist or genre name is a proper noun or tag text that no locale file translates.
    @Test
    fun `regionDisplayNames passes through names it cannot improve on`() {
        val regions = listOf(
            world("Ke\$ha • Mix", LibraryWorldNameSource.ARTIST),
            world(
                "Spoken Audio",
                LibraryWorldNameSource.SEMANTIC,
                LibraryWorldSemanticTitle.SPOKEN_AUDIO,
            ),
        )

        val names = regionDisplayNames(regions, "Микс открытий", semanticLabels)

        assertEquals(listOf("Ke\$ha • Mix", "Spoken Audio"), names)
    }

    // A region whose centroid sits near an edge used to have its name drawn centred on that
    // centroid regardless, which put half the text outside the canvas: on a real library the
    // right-hand region's label read "Discovery mi" with the rest cut off by the viewport. The
    // label is a region's only identity on the map (colour cannot carry it), so a half-drawn one
    // fails at the one job it has.
    @Test
    fun `labelTopLeft keeps a label at the right edge fully inside the canvas`() {
        val topLeft = labelTopLeft(
            centre = Offset(1f, 0.5f),
            labelWidth = 200f,
            labelHeight = 40f,
            width = 800f,
            height = 600f,
            zoom = 1f,
            panX = 0f,
            panY = 0f,
            marginPx = 0f,
        )

        assertEquals(Offset(600f, 280f), topLeft)
        assertTrue(topLeft.x + 200f <= 800f)
    }

    // The margin is only spent on labels the clamp actually moved, so it is measured here on the
    // same right-edge label: without it the name ends flush against the screen border and reads as
    // cut off even though it is whole.
    @Test
    fun `labelTopLeft holds a clamped label clear of the edge by the margin`() {
        val topLeft = labelTopLeft(
            centre = Offset(1f, 0.5f),
            labelWidth = 200f,
            labelHeight = 40f,
            width = 800f,
            height = 600f,
            zoom = 1f,
            panX = 0f,
            panY = 0f,
            marginPx = 18f,
        )

        assertEquals(Offset(582f, 280f), topLeft)
    }

    @Test
    fun `labelTopLeft clamps a label past the left and top edges to the corner`() {
        val topLeft = labelTopLeft(
            centre = Offset(0f, 0f),
            labelWidth = 200f,
            labelHeight = 40f,
            width = 800f,
            height = 600f,
            zoom = 1f,
            panX = 0f,
            panY = 0f,
            marginPx = 0f,
        )

        assertEquals(Offset.Zero, topLeft)
    }

    // Clamping must not become a general nudge inward: a label with room to spare stays centred on
    // its own centroid, or every interior label would drift away from the cluster it names.
    @Test
    fun `labelTopLeft leaves an interior label centred on its centroid`() {
        val topLeft = labelTopLeft(
            centre = Offset(0.5f, 0.5f),
            labelWidth = 200f,
            labelHeight = 40f,
            width = 800f,
            height = 600f,
            zoom = 1f,
            panX = 0f,
            panY = 0f,
            marginPx = 0f,
        )

        assertEquals(Offset(300f, 280f), topLeft)
    }

    // Zoom and pan move the centroid exactly as they move a dot, so this derives the expectation
    // from screenPosition rather than re-deriving the transform by hand -- the same guard the
    // `nearest` test above uses, for the same reason.
    @Test
    fun `labelTopLeft follows the zoom and pan transform`() {
        val centre = Offset(0.5f, 0.5f)
        val centreOnScreen = screenPosition(
            dot(x = centre.x, y = centre.y),
            width = 800f,
            height = 600f,
            zoom = 2f,
            panX = -400f,
            panY = -200f,
        )

        val topLeft = labelTopLeft(
            centre = centre,
            labelWidth = 100f,
            labelHeight = 20f,
            width = 800f,
            height = 600f,
            zoom = 2f,
            panX = -400f,
            panY = -200f,
            marginPx = 0f,
        )

        assertEquals(Offset(centreOnScreen.x - 50f, centreOnScreen.y - 10f), topLeft)
    }

    // A label longer than the canvas is wide has no position that fits, and the naive clamp
    // (coerceIn(margin, width - labelWidth - margin)) throws IllegalArgumentException on an upper
    // bound below its lower one rather than drawing anything. Pinning to the leading margin shows as
    // much of the name as there is room for, which is what a reader can use.
    @Test
    fun `labelTopLeft pins a label wider than the canvas to the leading edge`() {
        val topLeft = labelTopLeft(
            centre = Offset(0.5f, 0.5f),
            labelWidth = 200f,
            labelHeight = 40f,
            width = 100f,
            height = 600f,
            zoom = 1f,
            panX = 0f,
            panY = 0f,
            marginPx = 12f,
        )

        assertEquals(Offset(12f, 280f), topLeft)
    }

    // Once zoom/pan has moved a region itself out of view, clamping its name is no longer keeping a
    // visible label readable: it manufactures an edge label for something that is not there. Worse,
    // every offscreen centroid in the same quadrant lands at this exact corner and the names pile up.
    @Test
    fun `visibleLabelTopLeft omits offscreen centroids instead of piling them at an edge`() {
        val common = listOf(Offset(0.1f, 0.1f), Offset(0.2f, 0.2f))

        val legacyClamped = common.map { centre ->
            labelTopLeft(
                centre = centre,
                labelWidth = 100f,
                labelHeight = 20f,
                width = 800f,
                height = 600f,
                zoom = 2f,
                panX = -800f,
                panY = -600f,
                marginPx = 0f,
            )
        }
        val visible = common.map { centre ->
            visibleLabelTopLeft(
                centre = centre,
                labelWidth = 100f,
                labelHeight = 20f,
                width = 800f,
                height = 600f,
                zoom = 2f,
                panX = -800f,
                panY = -600f,
                marginPx = 0f,
            )
        }

        assertEquals(listOf(Offset.Zero, Offset.Zero), legacyClamped)
        visible.forEach(::assertNull)
    }

    @Test
    fun `visibleLabelTopLeft still clamps a visible edge centroid into the viewport`() {
        assertEquals(
            Offset(600f, 280f),
            visibleLabelTopLeft(
                centre = Offset(1f, 0.5f),
                labelWidth = 200f,
                labelHeight = 40f,
                width = 800f,
                height = 600f,
                zoom = 1f,
                panX = 0f,
                panY = 0f,
                marginPx = 0f,
            ),
        )
    }

    private fun track(id: String) = TrackDescriptor(id = TrackId(id))

    // Finding (Important 3) of the final review, spec section 8 row 3: a library too small for
    // TrackClustering to form even one region must fall back to a single unnamed region rather
    // than leave the Map with nothing to key dots against.
    @Test
    fun `mapFallbackRegions covers every laid out track in one region`() {
        val library = listOf(track("a"), track("b"), track("c"))
        val laidOutIds = listOf(TrackId("a"), TrackId("b"), TrackId("c"))

        val regions = mapFallbackRegions(library, laidOutIds)

        assertEquals(1, regions.size)
        assertEquals(laidOutIds, regions.single().tracks.map { it.id })
        assertEquals(LibraryWorldNameSource.GENERIC, regions.single().nameSource)
    }

    // A laid-out id with no matching library track (should not happen in practice, but the
    // function must not crash on it) is simply dropped rather than crashing on a missing lookup.
    @Test
    fun `mapFallbackRegions drops laid out ids with no matching track`() {
        val library = listOf(track("a"))
        val laidOutIds = listOf(TrackId("a"), TrackId("ghost"))

        val regions = mapFallbackRegions(library, laidOutIds)

        assertEquals(listOf(TrackId("a")), regions.single().tracks.map { it.id })
    }

    // An empty overlap between the library and the laid-out ids means there is genuinely nothing
    // to show yet -- the caller (App.kt) must be able to tell this apart from "clustering merely
    // has not run" and keep its existing state instead of treating this as ready.
    @Test
    fun `mapFallbackRegions returns nothing when there is no overlap at all`() {
        val library = listOf(track("a"))
        val laidOutIds = listOf(TrackId("ghost"))

        assertTrue(mapFallbackRegions(library, laidOutIds).isEmpty())
    }

    @Test
    fun `mapFallbackRegions returns nothing for an empty library`() {
        assertTrue(mapFallbackRegions(emptyList(), emptyList()).isEmpty())
    }

    // Final review, IMPORTANT finding: App.kt used to substitute mapFallbackRegions on bare
    // `worlds.isEmpty()`, which is also true in the first seconds after launch -- before
    // LibraryWorlds.discover has ever reported on this library -- not only when it reported and
    // found nothing. This is the exact timing window: `worlds` is empty and `worldLibraryIds` is
    // still whatever it was before this library loaded (its own initial `emptyList()`, or a stale
    // id set from *Rebuild analysis*/*Backup restored*), so it cannot possibly equal `loadedIds`
    // yet. A naive `worlds.isEmpty()` predicate returns true here -- wrongly claiming the fallback
    // applies -- which is precisely what this test proves the real predicate must not do.
    @Test
    fun `mapFallbackShouldApply is false before discovery has reported on this library`() {
        val loadedIds = listOf(TrackId("a"), TrackId("b"))

        val applies = mapFallbackShouldApply(
            worlds = emptyList(),
            worldLibraryIds = emptyList(),
            loadedIds = loadedIds,
        )

        assertFalse(applies, "the naive worlds.isEmpty() check this replaces would wrongly say true")
    }

    // The same "not yet" window reopens after *Rebuild analysis* or *Backup restored*, which reset
    // `worldLibraryIds` to `emptyList()` right alongside `worlds` -- so a library that was already
    // loaded before the reset must still wait for discovery to catch up rather than borrow the
    // fallback using its old (now stale) worldLibraryIds.
    @Test
    fun `mapFallbackShouldApply is false immediately after a rebuild resets worldLibraryIds`() {
        val loadedIds = listOf(TrackId("a"), TrackId("b"), TrackId("c"))

        val applies = mapFallbackShouldApply(
            worlds = emptyList(),
            worldLibraryIds = emptyList(),
            loadedIds = loadedIds,
        )

        assertFalse(applies)
    }

    // Discovery stale for a *different* library (an id set that does not match the currently
    // loaded one -- e.g. a track was added since the last discovery run) must also not borrow the
    // fallback: it is still "not yet", just for a subtler reason than a bare reset.
    @Test
    fun `mapFallbackShouldApply is false when worldLibraryIds names a different library`() {
        val applies = mapFallbackShouldApply(
            worlds = emptyList(),
            worldLibraryIds = listOf(TrackId("a"), TrackId("b")),
            loadedIds = listOf(TrackId("a"), TrackId("b"), TrackId("c")),
        )

        assertFalse(applies)
    }

    // The one case the fallback actually exists for: discovery reported back on exactly this
    // library's id set, and came back with nothing.
    @Test
    fun `mapFallbackShouldApply is true once discovery has genuinely found nothing for this library`() {
        val loadedIds = listOf(TrackId("a"), TrackId("b"))

        val applies = mapFallbackShouldApply(
            worlds = emptyList(),
            worldLibraryIds = loadedIds,
            loadedIds = loadedIds,
        )

        assertTrue(applies)
    }

    // A non-empty `worlds` never needs the fallback, regardless of whether worldLibraryIds happens
    // to match -- real regions always win.
    @Test
    fun `mapFallbackShouldApply is false when worlds are not empty`() {
        val loadedIds = listOf(TrackId("a"))
        val worlds = mapFallbackRegions(listOf(track("a")), loadedIds)

        val applies = mapFallbackShouldApply(worlds, worldLibraryIds = loadedIds, loadedIds = loadedIds)

        assertFalse(applies)
    }
}
