package com.margelo.nitro.documentscanner.processing

import androidx.camera.core.ImageProxy

/** An analysis frame's crop in sensor orientation, RGBA, [rotation] degrees clockwise from upright. */
internal class LiveFrame(val pixels: ByteArray, val width: Int, val height: Int, val rotation: Int)

/** Copies [image]'s crop, or null unless it is a single RGBA plane. */
internal fun copyCrop(image: ImageProxy): LiveFrame? {
  val plane = image.planes.singleOrNull() ?: return null
  if (plane.pixelStride != 4) return null
  val crop = image.cropRect
  val rowBytes = crop.width() * 4
  val pixels = ByteArray(rowBytes * crop.height())
  // A duplicate keeps the plane's own position, which the preprocessor reads.
  val buffer = plane.buffer.duplicate()
  val start = buffer.position()
  for (row in 0 until crop.height()) {
    buffer.position(start + (crop.top + row) * plane.rowStride + crop.left * 4)
    buffer.get(pixels, row * rowBytes, rowBytes)
  }
  return LiveFrame(pixels, crop.width(), crop.height(), image.imageInfo.rotationDegrees)
}
