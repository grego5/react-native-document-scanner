package com.margelo.nitro.documentscanner

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageZoomTest {
  // A 100x200 image fits a 200x200 view as 100x200, centered between 50 px bars.
  private fun zoom() = ImageZoom(5f).apply { layout(200f, 200f, 100f, 200f) }

  @Test
  fun doubleTapZoomsAroundTheTappedPointAndBackToFit() {
    val zoom = zoom()
    val target = zoom.doubleTapTarget(100f, 50f, 2.5f)
    assertEquals(ZoomState(2.5f, 0f, 75f), target)
    zoom.set(target)
    assertEquals(ZoomState.FIT, zoom.doubleTapTarget(100f, 50f, 2.5f))
  }

  @Test
  fun panStopsAtTheImageEdgesAndCentersTheNarrowerAxis() {
    val zoom = zoom()
    zoom.set(ZoomState(1.5f, 0f, 0f))
    zoom.panBy(500f, -500f)
    // 150 px wide stays centered in 200; 300 px tall pans 50 px either way.
    assertEquals(ZoomState(1.5f, 0f, -50f), zoom.state)
    assertEquals(25f, zoom.left)
    assertEquals(-100f, zoom.top)
  }

  @Test
  fun pinchKeepsTheFocusPointAndClampsTheScale() {
    val zoom = zoom()
    zoom.scaleAround(2f, 100f, 150f)
    assertEquals(ZoomState(2f, 0f, -50f), zoom.state)
    zoom.scaleAround(10f, 100f, 100f)
    assertEquals(5f, zoom.state.zoom)
    zoom.scaleAround(0.01f, 100f, 100f)
    assertEquals(ZoomState.FIT, zoom.state)
  }
}
