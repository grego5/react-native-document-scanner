package com.margelo.nitro.documentscanner

import org.junit.Assert.*
import org.junit.Test

class NativeCornerGeometryTest {
  private fun box(left: Double, top: Double, right: Double, bottom: Double) =
    doubleArrayOf(left, top, right, top, right, bottom, left, bottom)

  @Test fun fullFrameAndSharedPrimitives() {
    val corners = box(0.0, 0.0, 1.0, 1.0)
    assertTrue(NativeCornerGeometry.prediction(corners))
    assertTrue(NativeCornerGeometry.editor(corners))
    assertNull(NativeCornerGeometry.renderIssue(corners))
    assertEquals(1.0, NativeCornerGeometry.area(corners), 0.0)
    assertEquals(1.0, NativeCornerGeometry.turn(corners, 0, 1, 2), 0.0)
  }

  @Test fun rejectsCrossedConcaveCollinearAndReversedGeometry() {
    val invalid = listOf(
      doubleArrayOf(0.0, 0.0, 1.0, 1.0, 1.0, 0.0, 0.0, 1.0),
      doubleArrayOf(0.0, 0.0, 1.0, 0.0, .2, .2, 0.0, 1.0),
      doubleArrayOf(0.0, 0.0, .3, .3, .6, .6, 1.0, 1.0),
      doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0),
    )
    invalid.forEach {
      assertFalse(NativeCornerGeometry.prediction(it))
      assertFalse(NativeCornerGeometry.editor(it))
      assertNotNull(NativeCornerGeometry.renderIssue(it))
    }
  }

  @Test fun rejectsMalformedAndNonFiniteCoordinates() {
    val invalid = listOf(DoubleArray(0), DoubleArray(7), DoubleArray(9)) +
      listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).flatMap { value ->
        (0 until 8).map { index -> box(0.0, 0.0, 1.0, 1.0).apply { this[index] = value } }
      }
    invalid.forEach {
      assertFalse(NativeCornerGeometry.prediction(it))
      assertFalse(NativeCornerGeometry.editor(it))
      assertNotNull(NativeCornerGeometry.renderIssue(it))
    }
  }

  @Test fun predictionDoesNotInheritEditorMinimumArea() {
    val small = box(0.0, 0.0, .005, .005)
    assertTrue(NativeCornerGeometry.prediction(small))
    assertFalse(NativeCornerGeometry.editor(small))
    assertNotNull(NativeCornerGeometry.renderIssue(small))
    assertFalse(NativeCornerGeometry.prediction(box(0.0, 0.0, .0005, .0005)))
    assertTrue(NativeCornerGeometry.editor(box(0.0, 0.0, .01, .01)))
    assertNull(NativeCornerGeometry.renderIssue(box(0.0, 0.0, .01, .01)))
  }

  @Test fun onlyRenderingAllowsBoundedExtrapolation() {
    val extrapolated = box(-.8, 0.0, 1.0, 1.0)
    assertFalse(NativeCornerGeometry.prediction(extrapolated))
    assertFalse(NativeCornerGeometry.editor(extrapolated))
    assertNull(NativeCornerGeometry.renderIssue(extrapolated))
    assertNull(NativeCornerGeometry.renderIssue(box(0.0, 0.0, 1.8, 1.0)))
    assertNotNull(NativeCornerGeometry.renderIssue(box(-.800001, 0.0, 1.0, 1.0)))
    assertNotNull(NativeCornerGeometry.renderIssue(box(0.0, 0.0, 1.800001, 1.0)))
    assertNotNull(NativeCornerGeometry.renderIssue(box(-.8, -.8, 1.8, 1.8)))
  }
}
