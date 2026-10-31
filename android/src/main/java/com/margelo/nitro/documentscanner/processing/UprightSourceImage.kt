package com.margelo.nitro.documentscanner.processing

import android.graphics.Bitmap
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Owns [bitmap] until [close] is called. Its dimensions describe the sampled upright bitmap. */
internal class UprightSourceImage internal constructor(
  val bitmap: Bitmap,
  val encodedWidth: Int,
  val encodedHeight: Int,
  val uprightWidth: Int,
  val uprightHeight: Int,
  val exifOrientation: Int,
  val encodedToUpright: EncodedToUprightTransform,
) : Closeable {
  private val closed = AtomicBoolean(false)

  override fun close() {
    if (closed.compareAndSet(false, true) && !bitmap.isRecycled) bitmap.recycle()
  }
}

/** Maps image-edge coordinates from the encoded source into the sampled upright bitmap. */
internal class EncodedToUprightTransform internal constructor(
  private val encodedWidth: Int,
  private val encodedHeight: Int,
  private val sampledWidth: Int,
  private val sampledHeight: Int,
  val exifOrientation: Int,
) {
  private val uprightSize = NativeGeometry.uprightSize(sampledWidth, sampledHeight, exifOrientation)
  val uprightWidth: Int = uprightSize[0]
  val uprightHeight: Int = uprightSize[1]

  init {
    require(encodedWidth > 0 && encodedHeight > 0) { "Image dimensions must be positive." }
  }

  fun encodedToUpright(point: ImagePoint): ImagePoint = NativeGeometry.sourcePoint(
    point, encodedWidth, encodedHeight, sampledWidth, sampledHeight, exifOrientation, inverse = false,
  )

  fun uprightToEncoded(point: ImagePoint): ImagePoint = NativeGeometry.sourcePoint(
    point, encodedWidth, encodedHeight, sampledWidth, sampledHeight, exifOrientation, inverse = true,
  )
}
