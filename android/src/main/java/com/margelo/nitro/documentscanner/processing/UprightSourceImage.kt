package com.margelo.nitro.documentscanner.processing

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
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
  val uprightWidth: Int = if (swapsDimensions(exifOrientation)) sampledHeight else sampledWidth
  val uprightHeight: Int = if (swapsDimensions(exifOrientation)) sampledWidth else sampledHeight

  init {
    require(encodedWidth > 0 && encodedHeight > 0 && sampledWidth > 0 && sampledHeight > 0) {
      "Image dimensions must be positive."
    }
    require(exifOrientation in ExifInterface.ORIENTATION_NORMAL..ExifInterface.ORIENTATION_ROTATE_270 ||
      exifOrientation == ExifInterface.ORIENTATION_TRANSPOSE ||
      exifOrientation == ExifInterface.ORIENTATION_TRANSVERSE
    ) { "EXIF orientation must be between 1 and 8." }
  }

  fun encodedToUpright(point: ImagePoint): ImagePoint {
    val x = point.x * sampledWidth / encodedWidth
    val y = point.y * sampledHeight / encodedHeight
    return when (exifOrientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ImagePoint(sampledWidth - x, y)
      ExifInterface.ORIENTATION_ROTATE_180 -> ImagePoint(sampledWidth - x, sampledHeight - y)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> ImagePoint(x, sampledHeight - y)
      ExifInterface.ORIENTATION_TRANSPOSE -> ImagePoint(y, x)
      ExifInterface.ORIENTATION_ROTATE_90 -> ImagePoint(sampledHeight - y, x)
      ExifInterface.ORIENTATION_TRANSVERSE -> ImagePoint(sampledHeight - y, sampledWidth - x)
      ExifInterface.ORIENTATION_ROTATE_270 -> ImagePoint(y, sampledWidth - x)
      else -> ImagePoint(x, y)
    }
  }

  fun uprightToEncoded(point: ImagePoint): ImagePoint {
    val scaled = when (exifOrientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ImagePoint(sampledWidth - point.x, point.y)
      ExifInterface.ORIENTATION_ROTATE_180 -> ImagePoint(sampledWidth - point.x, sampledHeight - point.y)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> ImagePoint(point.x, sampledHeight - point.y)
      ExifInterface.ORIENTATION_TRANSPOSE -> ImagePoint(point.y, point.x)
      ExifInterface.ORIENTATION_ROTATE_90 -> ImagePoint(point.y, sampledHeight - point.x)
      ExifInterface.ORIENTATION_TRANSVERSE -> ImagePoint(sampledWidth - point.y, sampledHeight - point.x)
      ExifInterface.ORIENTATION_ROTATE_270 -> ImagePoint(sampledWidth - point.y, point.x)
      else -> point
    }
    return ImagePoint(
      scaled.x * encodedWidth / sampledWidth,
      scaled.y * encodedHeight / sampledHeight,
    )
  }

  private fun swapsDimensions(orientation: Int): Boolean = orientation in
    ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270
}
