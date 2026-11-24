package com.margelo.nitro.documentscanner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TextLineReadingTest {
  @Test
  fun regionMapsIntoTheBufferForEachRotation() {
    val region = TextRegion(0.1, 0.4, 0.8, 0.2)
    assertArrayEquals(intArrayOf(110, 440, 190, 460), regionInBuffer(region, 0, 100, 400, 100, 100))
    // Portrait over a landscape buffer: the view's horizontal strip is a vertical one in the buffer.
    assertArrayEquals(intArrayOf(40, 10, 60, 90), regionInBuffer(region, 90, 0, 0, 100, 100))
    assertArrayEquals(intArrayOf(10, 40, 90, 60), regionInBuffer(region, 180, 0, 0, 100, 100))
    assertArrayEquals(intArrayOf(40, 10, 60, 90), regionInBuffer(region, 270, 0, 0, 100, 100))
  }

  @Test
  fun regionClampsToTheView() {
    assertEquals(TextRegion(0.5, 0.0, 0.5, 1.0), clampedRegion(TextRegion(0.5, -0.2, 0.9, 1.5)))
  }

  @Test
  fun regionCornersClampToTheView() {
    val corners = TextRegion(0.8, 0.1, 0.4, 0.2).corners()
    assertEquals(0.8, corners.topLeft.x, 1e-9)
    assertEquals(0.1, corners.topLeft.y, 1e-9)
    // The right edge clamps to the view.
    assertEquals(1.0, corners.bottomRight.x, 1e-9)
    assertEquals(0.3, corners.bottomRight.y, 1e-9)
  }
}
