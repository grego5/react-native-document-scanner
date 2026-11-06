package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.NativeQuadGeometry
import com.margelo.nitro.documentscanner.DocumentQuad

/** Pixel bounds in the decoded upright photo. Margin is 10% of the seed bounds on each side. */
internal data class PhotoInferenceCrop(val left: Int, val top: Int, val width: Int, val height: Int) {
  fun toPhoto(quad: DocumentQuad, photoWidth: Int, photoHeight: Int): DocumentQuad? {
    if (photoWidth <= 0 || photoHeight <= 0 || left < 0 || top < 0 || width <= 0 || height <= 0 ||
      left.toLong() + width > photoWidth || top.toLong() + height > photoHeight ||
      !NativeQuadGeometry.prediction(NativeQuadGeometry.coordinates(quad))) return null
    return NativeGeometry.toPhoto(quad, this, photoWidth, photoHeight)
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
      return NativeGeometry.inferenceCrop(quad, photoWidth, photoHeight)?.let {
        PhotoInferenceCrop(it[0], it[1], it[2], it[3])
      }
    }
  }
}
