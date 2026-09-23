package io.github.nikitasud.latentjam.app

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapInteractionTest {
    @Test fun `one finger never claims the map even after a long scroll`() {
        val session = MapGestureSession()
        repeat(20) { assertEquals(MapGestureAction.PASS, session.update(1)) }
        assertEquals(MapGestureAction.PASS, session.update(0))
    }

    @Test fun `pinch owns the gesture until both fingers lift`() {
        val session = MapGestureSession()
        assertEquals(MapGestureAction.PASS, session.update(1))
        assertEquals(MapGestureAction.TRANSFORM, session.update(2))
        assertEquals(MapGestureAction.CONSUME, session.update(1))
        assertEquals(MapGestureAction.TRANSFORM, session.update(2))
        assertEquals(MapGestureAction.CONSUME, session.update(0))
        // A new single-finger gesture scrolls; ownership never leaks to the next gesture.
        assertEquals(MapGestureAction.PASS, MapGestureSession().update(1))
    }

    @Test fun `adding a finger during an established scroll does not steal it`() {
        val session = MapGestureSession()
        session.update(1, consumedMovement = true)
        assertEquals(MapGestureAction.PASS, session.update(2))
        assertEquals(MapGestureAction.PASS, session.update(1))
        assertEquals(MapGestureAction.PASS, session.update(0))
    }

    @Test fun `landscape phones use separate controls but narrow windows still scroll`() {
        assertTrue(mapUsesSidePanel(840f, 230f))
        assertTrue(mapUsesSidePanel(760f, 240f))
        assertTrue(mapUsesSidePanel(640f, 180f))
        assertTrue(mapUsesSidePanel(1024f, 600f))
        assertFalse(mapUsesSidePanel(390f, 650f))
        assertFalse(mapUsesSidePanel(600f, 220f))
        assertFalse(mapUsesSidePanel(800f, 950f))
        assertTrue(720f - mapSidePanelWidth(720f, 2f) >= 320f)
    }

    @Test fun `portrait plot remains usable from small phones to large tablets`() {
        assertEquals(260f, mapPortraitPlotHeight(280f))
        assertTrue(mapPortraitPlotHeight(390f) in 340f..360f)
        assertEquals(500f, mapPortraitPlotHeight(1000f))
    }

    @Test fun `normalized pan preserves map position when rotated at any zoom`() {
        for (zoom in listOf(1f, 2f, 6f)) {
            val normalizedX = (1f - zoom) * 0.3f
            val normalizedY = (1f - zoom) * 0.6f
            for ((width, height) in listOf(360f to 350f, 480f to 220f, 720f to 500f)) {
                val current = MapViewport(zoom, normalizedX * width, normalizedY * height)
                val transformed = transformMapViewport(current, Offset.Zero, Offset.Zero, 1f, width, height)
                assertEquals(normalizedX, transformed.panX / width, 0.00001f)
                assertEquals(normalizedY, transformed.panY / height, 0.00001f)
            }
        }
    }
}
