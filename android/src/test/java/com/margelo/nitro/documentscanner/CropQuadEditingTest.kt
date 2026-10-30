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
  fun clampToFramePullsCornersInside() {
    val clamped = CropQuadEditing.clampToFrame(doubleArrayOf(-0.2, 0.1, 1.3, 0.0, 1.0, 1.5, 0.0, 1.0))
    assertArrayEquals(doubleArrayOf(0.0, 0.1, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0), clamped, 0.0)
  }
}
