package com.margelo.nitro.documentscanner

import org.junit.Assert.*
import org.junit.Test

class NativeQuadGeometryTest {
  private fun box(left: Double, top: Double, right: Double, bottom: Double) =
    doubleArrayOf(left, top, right, top, right, bottom, left, bottom)

  @Test fun fullFrameAndSharedPrimitives() {
    val quad = box(0.0, 0.0, 1.0, 1.0)
    assertTrue(NativeQuadGeometry.prediction(quad))
    assertTrue(NativeQuadGeometry.editor(quad))
    assertNull(NativeQuadGeometry.renderIssue(quad))
    assertEquals(1.0, NativeQuadGeometry.area(quad), 0.0)
    assertEquals(1.0, NativeQuadGeometry.turn(quad, 0, 1, 2), 0.0)
  }

  @Test fun rejectsCrossedConcaveCollinearAndReversedGeometry() {
    val invalid = listOf(
      doubleArrayOf(0.0, 0.0, 1.0, 1.0, 1.0, 0.0, 0.0, 1.0),
      doubleArrayOf(0.0, 0.0, 1.0, 0.0, .2, .2, 0.0, 1.0),
      doubleArrayOf(0.0, 0.0, .3, .3, .6, .6, 1.0, 1.0),
      doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0),
    )
    invalid.forEach {
      assertFalse(NativeQuadGeometry.prediction(it))
      assertFalse(NativeQuadGeometry.editor(it))
      assertNotNull(NativeQuadGeometry.renderIssue(it))
    }
  }

  @Test fun rejectsMalformedAndNonFiniteCoordinates() {
    val invalid = listOf(DoubleArray(0), DoubleArray(7), DoubleArray(9)) +
      listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).flatMap { value ->
        (0 until 8).map { index -> box(0.0, 0.0, 1.0, 1.0).apply { this[index] = value } }
      }
    invalid.forEach {
      assertFalse(NativeQuadGeometry.prediction(it))
      assertFalse(NativeQuadGeometry.editor(it))
      assertNotNull(NativeQuadGeometry.renderIssue(it))
    }
  }

  @Test fun predictionDoesNotInheritEditorMinimumArea() {
    val small = box(0.0, 0.0, .005, .005)
    assertTrue(NativeQuadGeometry.prediction(small))
    assertFalse(NativeQuadGeometry.editor(small))
    assertNotNull(NativeQuadGeometry.renderIssue(small))
    assertFalse(NativeQuadGeometry.prediction(box(0.0, 0.0, .0005, .0005)))
    assertTrue(NativeQuadGeometry.editor(box(0.0, 0.0, .01, .01)))
    assertNull(NativeQuadGeometry.renderIssue(box(0.0, 0.0, .01, .01)))
  }

  @Test fun onlyRenderingAllowsBoundedExtrapolation() {
    val extrapolated = box(-.8, 0.0, 1.0, 1.0)
    assertFalse(NativeQuadGeometry.prediction(extrapolated))
    assertFalse(NativeQuadGeometry.editor(extrapolated))
    assertNull(NativeQuadGeometry.renderIssue(extrapolated))
    assertNull(NativeQuadGeometry.renderIssue(box(0.0, 0.0, 1.8, 1.0)))
    assertNotNull(NativeQuadGeometry.renderIssue(box(-.800001, 0.0, 1.0, 1.0)))
    assertNotNull(NativeQuadGeometry.renderIssue(box(0.0, 0.0, 1.800001, 1.0)))
    assertNotNull(NativeQuadGeometry.renderIssue(box(-.8, -.8, 1.8, 1.8)))
  }
}
