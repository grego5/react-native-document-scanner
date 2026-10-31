package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.NativeQuadGeometry
import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pixel bounds in the upright photo. Margin is at least 10% of the seed bounds on each side; the shorter side then
 * grows toward a square as far as the photo allows, so the model's square input stretches the region least.
 */
internal data class PhotoInferenceCrop(val left: Int, val top: Int, val width: Int, val height: Int) {
  fun toPhoto(quad: DocumentQuad, photoWidth: Int, photoHeight: Int): DocumentQuad? {
    if (photoWidth <= 0 || photoHeight <= 0) return null
    fun point(p: DocumentPoint) = DocumentPoint(
      (left + p.x * width) / photoWidth, (top + p.y * height) / photoHeight,
    )
    return DocumentQuad(point(quad.topLeft), point(quad.topRight), point(quad.bottomRight), point(quad.bottomLeft))
      .takeIf { NativeQuadGeometry.prediction(NativeQuadGeometry.coordinates(it)) }
  }

  /** The inverse of [toPhoto]: a photo quad relative to this region, or null when it leaves the region. */
  fun fromPhoto(quad: DocumentQuad, photoWidth: Int, photoHeight: Int): DocumentQuad? {
    fun point(p: DocumentPoint) = DocumentPoint((p.x * photoWidth - left) / width, (p.y * photoHeight - top) / height)
    return DocumentQuad(point(quad.topLeft), point(quad.topRight), point(quad.bottomRight), point(quad.bottomLeft))
      .takeIf { NativeQuadGeometry.prediction(NativeQuadGeometry.coordinates(it)) }
  }

  companion object {
    fun from(quad: DocumentQuad?, photoWidth: Int, photoHeight: Int): PhotoInferenceCrop? {
      if (quad == null || photoWidth <= 0 || photoHeight <= 0) return null
      val points = DocCornerSession.corners(quad)
      if (!NativeQuadGeometry.prediction(NativeQuadGeometry.coordinates(quad))) return null
      val x0 = points.minOf { it.x } * photoWidth
      val x1 = points.maxOf { it.x } * photoWidth
      val y0 = points.minOf { it.y } * photoHeight
      val y1 = points.maxOf { it.y } * photoHeight
      val minX = max(0.0, x0 - (x1 - x0) * .10)
      val maxX = min(photoWidth.toDouble(), x1 + (x1 - x0) * .10)
      val minY = max(0.0, y0 - (y1 - y0) * .10)
      val maxY = min(photoHeight.toDouble(), y1 + (y1 - y0) * .10)
      // Expand, never shrink the margin, toward 1:1. Shift at edges.
      val side = max(maxX - minX, maxY - minY)
      val w = side.coerceAtMost(photoWidth.toDouble())
      val h = side.coerceAtMost(photoHeight.toDouble())
      val x = ((minX + maxX - w) / 2).coerceIn(0.0, photoWidth - w)
      val y = ((minY + maxY - h) / 2).coerceIn(0.0, photoHeight - h)
      val left = floor(x).toInt()
      val top = floor(y).toInt()
      return PhotoInferenceCrop(left, top,
        (ceil(x + w).toInt() - left).coerceIn(1, photoWidth - left),
        (ceil(y + h).toInt() - top).coerceIn(1, photoHeight - top))
    }
  }
}
