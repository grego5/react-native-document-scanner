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
}
