package com.margelo.nitro.documentscanner

import kotlin.math.max
import kotlin.math.min

/**
 * Crop edits in normalized upright photo coordinates. A quad is TL, TR, BR, BL as x,y pairs; edge `i` runs from
 * corner `i` to corner `i + 1`. Every corner stays inside the photo.
 */
internal object CropQuadEditing {
  val FULL_FRAME = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)

  /** Moves corner [index] to ([x], [y]), kept inside the photo; null when the result is not a valid quad. */
  fun moveCorner(quad: DoubleArray, index: Int, x: Double, y: Double): DoubleArray? {
    val next = quad.copyOf()
    next[index * 2] = x.coerceIn(0.0, 1.0)
    next[index * 2 + 1] = y.coerceIn(0.0, 1.0)
    return next.takeIf(NativeQuadGeometry::editor)
  }

  /**
   * Moves both corners of edge [index] by the same displacement, shortened where either would leave the photo so the
   * edge keeps its direction and length; null when the result is not a valid quad.
   */
  fun moveEdge(quad: DoubleArray, index: Int, dx: Double, dy: Double): DoubleArray? {
    val a = index * 2
    val b = (index + 1) % 4 * 2
    val sx = dx.coerceIn(-min(quad[a], quad[b]), 1.0 - max(quad[a], quad[b]))
    val sy = dy.coerceIn(-min(quad[a + 1], quad[b + 1]), 1.0 - max(quad[a + 1], quad[b + 1]))
    val next = quad.copyOf()
    next[a] += sx
    next[a + 1] += sy
    next[b] += sx
    next[b + 1] += sy
    return next.takeIf(NativeQuadGeometry::editor)
  }
}
