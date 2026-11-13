package com.margelo.nitro.documentscanner

import org.junit.Assert.*
import org.junit.Test

class CropRectangleTest {
  private val rectangle = doubleArrayOf(.2, .1, .8, .1, .8, .9, .2, .9)

  @Test fun aCornerMovesWithItsNeighboursAndTheOppositeCornerStays() {
    // Top-left to (0.1, 0.3): the top-right follows its height, the bottom-left its width.
    assertArrayEquals(doubleArrayOf(.1, .3, .8, .3, .8, .9, .1, .9),
      CropCornerEditing.moveRectangleCorner(rectangle, 0, .1, .3), 1e-12)
    assertArrayEquals(doubleArrayOf(.2, .1, .7, .1, .7, .5, .2, .5),
      CropCornerEditing.moveRectangleCorner(rectangle, 2, .7, .5), 1e-12)
  }

  @Test fun movesClampToThePhotoAndRefuseInsideOutOrSliverRectangles() {
    assertArrayEquals(doubleArrayOf(0.0, 0.0, .8, 0.0, .8, .9, 0.0, .9),
      CropCornerEditing.moveRectangleCorner(rectangle, 0, -.5, -.5), 1e-12)
    assertNull(CropCornerEditing.moveRectangleCorner(rectangle, 0, .9, .3))
    assertNull(CropCornerEditing.moveRectangleCorner(rectangle, 1, .21, .3))
  }

  @Test fun aCornersSquaresToItsClampedBoundingRectangle() {
    assertArrayEquals(doubleArrayOf(.1, 0.0, .9, 0.0, .9, .8, .1, .8),
      CropCornerEditing.boundingRectangle(doubleArrayOf(.15, -.1, .9, .05, .85, .8, .1, .7)), 1e-12)
  }
}
