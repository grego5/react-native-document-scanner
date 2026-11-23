package com.margelo.nitro.documentscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthOfFieldTest {
  // A phone main camera: 6.7 mm at f/1.8 on a sensor with a 12 mm diagonal, so c = 8 µm.
  private val lens = DepthOfField.of(6.7, 1.8, .008)!!

  @Test fun theToleranceIsTheSameInDioptersAtEveryDistance() {
    // N·c / f² = 1.8 × 0.008 / 6.7² per mm, about 0.32 diopters.
    assertEquals(.3208, lens.toleranceDiopters, 1e-4)
  }

  @Test fun nearerFocusAllowsASmallerChangeOfSize() {
    // 4 diopters is 25 cm, 8 diopters 12.5 cm.
    val far = lens.limit(4f)!!
    val near = lens.limit(8f)!!
    assertEquals(1 + .3208 / 4, far.grow, 1e-4)
    assertEquals(4 / (4 - .3208), far.shrink, 1e-4)
    assertTrue(near.grow < far.grow && near.shrink < far.shrink)
  }

  @Test fun zoomEnlargesTheBlurAndBeyondTheHyperfocalNothingShrinksOutOfFocus() {
    assertEquals(1 + .3208 / 2 / 8, lens.limit(8f, zoom = 2.0)!!.grow, 1e-4)
    assertEquals(Double.POSITIVE_INFINITY, lens.limit(.2f)!!.shrink, 0.0)
    assertNull(lens.limit(null))
    assertNull(lens.limit(0f))
  }

  @Test fun aLimitIsBoundedAndPassedEitherWay() {
    val limit = lens.limit(8f)!!.bounded(1.05, 1.25)
    assertEquals(1.05, limit.grow, 1e-9)
    assertFalse(limit.exceeded(1.04))
    assertTrue(limit.exceeded(1.05))
    assertFalse(limit.exceeded(1 / 1.04))
    assertTrue(limit.exceeded(1 / 1.06))
    assertEquals(1.25, lens.limit(.2f)!!.bounded(1.05, 1.25).shrink, 1e-9)
  }
}
