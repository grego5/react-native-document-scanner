package com.margelo.nitro.documentscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LensSearchTest {
  @Test
  fun refinesABracketedTextPeakBetweenSampledPositions() {
    val search = LensSearch(7.25f, 10f, refinePeak = true)
    assertEquals(7.25f, search.target, 0f)
    search.observe(839.0)
    assertEquals(7.50f, search.target, 0f)
    search.observe(836.0)
    assertEquals(7.00f, search.target, 0f)
    search.observe(63.0)
    assertEquals(7.37f, search.peak!!, 0.01f)
  }

  @Test
  fun aNeighbourWithinTheMarginPullsTheEstimateWithoutLeading() {
    val search = LensSearch(7.25f, 10f, refinePeak = true)
    search.observe(800.0)
    // 7.50 scores higher, but not by the margin, so 7.25 still leads; the peak lies between them.
    search.observe(820.0)
    search.observe(300.0)
    val peak = search.peak!!
    assertTrue(peak > 7.25f && peak <= 7.38f)
  }

  @Test
  fun refinementFindsTheNeighboursOfAnOffGridStart() {
    // A remembered, refined lock opens off the step grid; its neighbours are still found.
    val search = LensSearch(7.37f, 10f, refinePeak = true)
    search.observe(900.0)
    search.observe(500.0)
    search.observe(700.0)
    assertTrue(search.peak!! < 7.37f)
  }

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
