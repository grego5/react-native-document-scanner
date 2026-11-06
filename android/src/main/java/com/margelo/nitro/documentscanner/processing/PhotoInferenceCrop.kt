package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.NativeCornerGeometry
import com.margelo.nitro.documentscanner.DocumentCorners

/** Pixel bounds in the decoded upright photo. Margin is 10% of the seed bounds on each side. */
internal data class PhotoInferenceCrop(val left: Int, val top: Int, val width: Int, val height: Int) {
  fun toPhoto(corners: DocumentCorners, photoWidth: Int, photoHeight: Int): DocumentCorners? {
    if (photoWidth <= 0 || photoHeight <= 0 || left < 0 || top < 0 || width <= 0 || height <= 0 ||
      left.toLong() + width > photoWidth || top.toLong() + height > photoHeight ||
      !NativeCornerGeometry.prediction(NativeCornerGeometry.coordinates(corners))) return null
    return NativeGeometry.toPhoto(corners, this, photoWidth, photoHeight)
      .takeIf { NativeCornerGeometry.prediction(NativeCornerGeometry.coordinates(it)) }
  }

  /** The inverse of [toPhoto]: a photo corners relative to this region, or null when it leaves the region. */
  fun fromPhoto(corners: DocumentCorners, photoWidth: Int, photoHeight: Int): DocumentCorners? {
    fun point(p: DocumentPoint) = DocumentPoint((p.x * photoWidth - left) / width, (p.y * photoHeight - top) / height)
    return DocumentCorners(point(corners.topLeft), point(corners.topRight), point(corners.bottomRight), point(corners.bottomLeft))
      .takeIf { NativeCornerGeometry.prediction(NativeCornerGeometry.coordinates(it)) }
  }

  companion object {
    fun from(corners: DocumentCorners?, photoWidth: Int, photoHeight: Int): PhotoInferenceCrop? {
      if (corners == null || photoWidth <= 0 || photoHeight <= 0) return null
      return NativeGeometry.inferenceCrop(corners, photoWidth, photoHeight)?.let {
        PhotoInferenceCrop(it[0], it[1], it[2], it[3])
      }
    }
  }
}
