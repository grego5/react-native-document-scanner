package com.margelo.nitro.documentscanner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CropQuadEditingTest {
  private val quad = doubleArrayOf(0.1, 0.1, 0.9, 0.1, 0.9, 0.9, 0.1, 0.9)

  @Test
  fun edgeMoveIsShortenedAtTheBoundsAndKeepsTheEdgeVector() {
    val moved = CropQuadEditing.moveEdge(quad, 0, 0.3, -0.5)!!
    assertArrayEquals(doubleArrayOf(0.2, 0.0, 1.0, 0.0, 0.9, 0.9, 0.1, 0.9), moved, 1e-9)
  }

  @Test
  fun edgeMoveThatCrossesTheOppositeEdgeIsRejected() {
    assertNull(CropQuadEditing.moveEdge(quad, 0, 0.0, 0.9))
  }

  @Test
  fun cornerIsClampedIntoThePhoto() {
    val moved = CropQuadEditing.moveCorner(quad, 2, 1.4, 0.5)!!
    assertArrayEquals(doubleArrayOf(0.1, 0.1, 0.9, 0.1, 1.0, 0.5, 0.1, 0.9), moved, 1e-9)
  }

  @Test
  fun crossedConcaveAndDegenerateCornersAreRejected() {
    assertNull(CropQuadEditing.moveCorner(quad, 0, 0.95, 0.95))
    assertNull(CropQuadEditing.moveCorner(quad, 0, 0.6, 0.6))
    assertNull(CropQuadEditing.moveCorner(quad, 0, 0.5, 0.5))
    assertNotNull(CropQuadEditing.moveCorner(quad, 0, 0.3, 0.3))
  }

  @Test
  fun collapsedAndNonFiniteMovesAreRejectedWithoutChangingTheInput() {
    val original = quad.copyOf()
    assertNull(CropQuadEditing.moveCorner(quad, 0, Double.NaN, .2))
    assertNull(CropQuadEditing.moveEdge(quad, 0, Double.NaN, 0.0))
    assertNull(CropQuadEditing.moveEdge(quad, 0, 0.0, .8))
    assertArrayEquals(original, quad, 0.0)
  }

  @Test
  fun developerGeometryMustAlreadyBeValidAndInFrame() {
    org.junit.Assert.assertFalse(NativeQuadGeometry.editor(
      doubleArrayOf(-.2, .1, 1.3, 0.0, 1.0, 1.5, 0.0, 1.0),
    ))
    org.junit.Assert.assertTrue(NativeQuadGeometry.editor(CropQuadEditing.FULL_FRAME))
  }
}
