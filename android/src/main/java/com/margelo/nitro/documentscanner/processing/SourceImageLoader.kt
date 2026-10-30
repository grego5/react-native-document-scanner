package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.InvalidInputException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.roundToInt

/** A JPEG's encoded size and EXIF orientation, read without decoding its pixels. */
internal class SourceImageHeader(val file: File, val encodedWidth: Int, val encodedHeight: Int, val exifOrientation: Int) {
  private val swapsDimensions = exifOrientation in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270
  val uprightWidth: Int get() = if (swapsDimensions) encodedHeight else encodedWidth
  val uprightHeight: Int get() = if (swapsDimensions) encodedWidth else encodedHeight
}

internal object SourceImageLoader {
  /** Loads a sampled, upright bitmap; the returned image owns its bitmap until closed. */
  fun load(uriString: String, maxDimension: Int): UprightSourceImage {
    require(maxDimension > 0) { "Requested decode size must be positive." }
    val header = inspect(uriString)
    val file = header.file
    val orientation = header.exifOrientation
    val sampleSize = calculateSampleSize(header.encodedWidth, header.encodedHeight, maxDimension)
    val decoded = BitmapFactory.decodeFile(
      file.absolutePath,
      BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inScaled = false
      },
    ) ?: throw IOException("Captured JPEG could not be decoded.")

    var upright: Bitmap? = null
    try {
      upright = applyOrientation(decoded, orientation)
      val transform = EncodedToUprightTransform(
        encodedWidth = header.encodedWidth,
        encodedHeight = header.encodedHeight,
        sampledWidth = decoded.width,
        sampledHeight = decoded.height,
        exifOrientation = orientation,
      )
      val result = UprightSourceImage(
        bitmap = upright,
        encodedWidth = header.encodedWidth,
        encodedHeight = header.encodedHeight,
        uprightWidth = upright.width,
        uprightHeight = upright.height,
        exifOrientation = orientation,
        encodedToUpright = transform,
      )
      if (decoded !== upright) decoded.recycle()
      return result
    } catch (error: Throwable) {
      if (upright != null && upright !== decoded && !upright.isRecycled) upright.recycle()
      if (!decoded.isRecycled) decoded.recycle()
      throw error
    }
  }

  /**
   * Loads an upright bitmap whose longer side is [longSide], or the image's own when smaller. It is sampled while
   * decoding, so a large file never decodes at full size; the caller owns the bitmap.
   */
  fun loadReduced(uriString: String, longSide: Int): Bitmap {
    require(longSide > 0) { "Requested decode size must be positive." }
    val header = inspect(uriString)
    val encodedLongSide = maxOf(header.encodedWidth, header.encodedHeight)
    // The coarsest sampling that still keeps the requested size.
    var sampleSize = 1
    while (encodedLongSide / (sampleSize * 2) >= longSide) sampleSize *= 2
    val sampled = load(uriString, ceilDiv(encodedLongSide, sampleSize)).bitmap
    val scale = longSide.toFloat() / maxOf(sampled.width, sampled.height)
    if (scale >= 1f) return sampled
    val width = (sampled.width * scale).roundToInt().coerceAtLeast(1)
    val height = (sampled.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(sampled, width, height, true).also { if (it !== sampled) sampled.recycle() }
  }

  /** Validates a local JPEG and reads its header. */
  fun inspect(uriString: String): SourceImageHeader {
    val uri = Uri.parse(uriString)
    if (uri.scheme != "file" || uri.path.isNullOrBlank()) {
      throw InvalidInputException("Unsupported image URI; expected a local file:// JPEG.")
    }

    val file = File(uri.path!!)
    if (!file.isFile || !file.canRead()) {
      throw FileNotFoundException("Captured image file was not found or is not readable.")
    }

    val storedOrientation = try {
      ExifInterface(file.absolutePath).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_UNDEFINED,
      )
    } catch (error: Exception) {
      throw IOException("Captured JPEG metadata could not be read.", error)
    }
    val orientation = if (storedOrientation == ExifInterface.ORIENTATION_UNDEFINED) {
      ExifInterface.ORIENTATION_NORMAL
    } else {
      storedOrientation
    }
    if (orientation !in ExifInterface.ORIENTATION_NORMAL..ExifInterface.ORIENTATION_ROTATE_270) {
      throw IOException("Captured JPEG has an unsupported EXIF orientation.")
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
      throw IOException("Captured file is not a decodable JPEG.")
    }
    return SourceImageHeader(file, bounds.outWidth, bounds.outHeight, orientation)
  }

  private fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    var sampleSize = 1
    while (ceilDiv(width, sampleSize) > maxDimension || ceilDiv(height, sampleSize) > maxDimension) {
      if (sampleSize > Int.MAX_VALUE / 2) return sampleSize
      sampleSize *= 2
    }
    return sampleSize
  }

  private fun ceilDiv(value: Int, divisor: Int): Int = (value.toLong() + divisor - 1L)
    .div(divisor)
    .coerceAtMost(Int.MAX_VALUE.toLong())
    .toInt()

  private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    if (orientation == ExifInterface.ORIENTATION_NORMAL) return bitmap
    val width = bitmap.width.toFloat()
    val height = bitmap.height.toFloat()
    val matrix = Matrix()
    val values = when (orientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> floatArrayOf(-1f, 0f, width - 1f, 0f, 1f, 0f, 0f, 0f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 -> floatArrayOf(-1f, 0f, width - 1f, 0f, -1f, height - 1f, 0f, 0f, 1f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> floatArrayOf(1f, 0f, 0f, 0f, -1f, height - 1f, 0f, 0f, 1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
      ExifInterface.ORIENTATION_ROTATE_90 -> floatArrayOf(0f, -1f, height - 1f, 1f, 0f, 0f, 0f, 0f, 1f)
      ExifInterface.ORIENTATION_TRANSVERSE -> floatArrayOf(0f, -1f, height - 1f, -1f, 0f, width - 1f, 0f, 0f, 1f)
      ExifInterface.ORIENTATION_ROTATE_270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, width - 1f, 0f, 0f, 1f)
      else -> throw IOException("Captured JPEG has an unsupported EXIF orientation.")
    }
    matrix.setValues(values)
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
  }
}
