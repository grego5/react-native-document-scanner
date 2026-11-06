package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.processing.NativeGeometry

/** Normalized upright TL/TR/BR/BL edits; Android retains gesture state. */
internal object CropQuadEditing {
  val FULL_FRAME = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)
  fun clampToFrame(quad: DoubleArray): DoubleArray = NativeGeometry.clamp(quad)
  fun moveCorner(quad: DoubleArray, index: Int, x: Double, y: Double): DoubleArray? =
    NativeGeometry.move(quad, index, x, y, edge = false)
  fun moveEdge(quad: DoubleArray, index: Int, dx: Double, dy: Double): DoubleArray? =
    NativeGeometry.move(quad, index, dx, dy, edge = true)
  fun isValid(quad: DoubleArray): Boolean = NativeGeometry.validCrop(quad)

  /** The upright rectangle bounding [quad], clamped to the frame. */
  fun boundingRectangle(quad: DoubleArray): DoubleArray {
    val xs = doubleArrayOf(quad[0], quad[2], quad[4], quad[6]).map { it.coerceIn(0.0, 1.0) }
    val ys = doubleArrayOf(quad[1], quad[3], quad[5], quad[7]).map { it.coerceIn(0.0, 1.0) }
    return rectangle(xs.min(), ys.min(), xs.max(), ys.max())
  }

  /**
   * Moves corner [index] of an upright rectangle to ([x], [y]), clamped to the frame, with the opposite corner fixed and
   * the other two following, so the crop stays an upright rectangle; null when it would turn inside out or narrower
   * than [MIN_RECTANGLE_SIDE].
   */
  fun moveRectangleCorner(quad: DoubleArray, index: Int, x: Double, y: Double): DoubleArray? {
    val opposite = (index + 2) % 4
    val cx = x.coerceIn(0.0, 1.0)
    val cy = y.coerceIn(0.0, 1.0)
    val ox = quad[opposite * 2]
    val oy = quad[opposite * 2 + 1]
    val leftSide = index == 0 || index == 3
    val topSide = index == 0 || index == 1
    val left = if (leftSide) cx else ox
    val right = if (leftSide) ox else cx
    val top = if (topSide) cy else oy
    val bottom = if (topSide) oy else cy
    if (right - left < MIN_RECTANGLE_SIDE || bottom - top < MIN_RECTANGLE_SIDE) return null
    return rectangle(left, top, right, bottom)
  }

  private fun rectangle(left: Double, top: Double, right: Double, bottom: Double) =
    doubleArrayOf(left, top, right, top, right, bottom, left, bottom)

  /** The narrowest a rectangle crop gets along either axis, of the photo's side. */
  const val MIN_RECTANGLE_SIDE = 0.02
}
