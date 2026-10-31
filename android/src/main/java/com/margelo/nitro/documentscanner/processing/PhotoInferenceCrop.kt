package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentQuad

/** Pixel bounds in the decoded upright photo. Margin is 10% of the seed bounds on each side. */
internal data class PhotoInferenceCrop(val left: Int, val top: Int, val width: Int, val height: Int) {
  fun toPhoto(quad: DocumentQuad, photoWidth: Int, photoHeight: Int): DocumentQuad =
    NativeGeometry.toPhoto(quad, this, photoWidth, photoHeight)

  companion object {
    fun from(quad: DocumentQuad?, photoWidth: Int, photoHeight: Int): PhotoInferenceCrop? {
      if (quad == null) return null
      return NativeGeometry.inferenceCrop(quad, photoWidth, photoHeight)?.let {
        PhotoInferenceCrop(it[0], it[1], it[2], it[3])
      }
    }
  }
}
