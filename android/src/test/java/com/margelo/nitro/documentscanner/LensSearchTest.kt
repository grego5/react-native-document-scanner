package com.margelo.nitro.documentscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LensSearchTest {
  /** A print sharpest at [peak] diopters, falling off linearly. */
  private fun run(start: Float, peak: Float, nearest: Float = 10f): Pair<LensSearch, Int> {
    val search = LensSearch(start, nearest)
    var positions = 0
    while (!search.done) {
      search.observe(1000.0 - 300 * abs(search.target - peak))
      positions++
    }
    return search to positions
  }

  @Test
  fun lensAtThePeakSettlesAfterThreePositions() {
    val (search, positions) = run(3.7f, 3.7f)
    assertEquals(3.7f, search.peak!!, 1e-4f)
    assertEquals(3, positions)
  }

  @Test
  fun climbsTowardTheSharperSide() {
    val (search, _) = run(3f, 4.5f)
    assertEquals(4.5f, search.peak!!, 1e-4f)
    val (back, _) = run(5f, 4f)
    assertEquals(4f, back.peak!!, 1e-4f)
  }

  @Test
  fun stopsAtTheNearEnd() {
    val (search, _) = run(9.5f, 12f, nearest = 10f)
    assertEquals(10f, search.peak!!, 1e-4f)
  }

  @Test
  fun flatScoresSettleWhereTheLensStarted() {
    val search = LensSearch(4.17f, 10f)
    var positions = 0
    while (!search.done) {
      search.observe(6.0 + (positions % 2) * .1)
      positions++
    }
    assertEquals(4.17f, search.peak!!, 1e-4f)
    assertEquals(3, positions)
  }

  @Test
  fun givesUpOnAPeakTooFar() {
    val search = LensSearch(5f, 10f)
    var score = 0.0
    while (!search.done) search.observe(++score)
    assertTrue(search.failed)
    assertFalse(search.peak != null)
  }
}
