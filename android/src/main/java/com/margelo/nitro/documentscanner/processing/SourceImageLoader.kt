package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.InvalidInputException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.roundToInt

/** A JPEG's encoded size and EXIF orientation, read without decoding its pixels. */
internal class SourceImageHeader(val file: File, val encodedWidth: Int, val encodedHeight: Int, val exifOrientation: Int) {
  private val uprightSize = NativeGeometry.uprightSize(encodedWidth, encodedHeight, exifOrientation)
  val uprightWidth: Int get() = uprightSize[0]
  val uprightHeight: Int get() = uprightSize[1]
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

  /**
   * Decodes the upright [region] of [header]'s photo, reduced by [sampleSize] (a power of two) while decoding and
   * reading only that part of the JPEG; the caller owns the bitmap.
   */
  @Suppress("DEPRECATION")
  fun loadRegion(header: SourceImageHeader, region: PhotoInferenceCrop, sampleSize: Int = 1): Bitmap {
    val encoded = encodedRect(header, region)
    val decoder = BitmapRegionDecoder.newInstance(header.file.absolutePath, false)
      ?: throw IOException("Captured JPEG could not be opened for region decoding.")
    val decoded = try {
      decoder.decodeRegion(encoded, BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inSampleSize = sampleSize
      })
        ?: throw IOException("Captured JPEG region could not be decoded.")
    } finally {
      decoder.recycle()
    }
    return try {
      applyOrientation(decoded, header.exifOrientation).also { if (it !== decoded) decoded.recycle() }
    } catch (error: Throwable) {
      decoded.recycle()
      throw error
    }
  }

  /** The power-of-two reduction that keeps [header]'s photo within [maxDimension] on each side. */
  fun sampleSize(header: SourceImageHeader, maxDimension: Int): Int =
    calculateSampleSize(header.encodedWidth, header.encodedHeight, maxDimension)

  /** The encoded-pixel rectangle that [applyOrientation] turns into the upright [region]. */
  internal fun encodedRect(header: SourceImageHeader, region: PhotoInferenceCrop): Rect {
    val w = header.encodedWidth
    val h = header.encodedHeight
    val l = region.left
    val t = region.top
    val r = region.left + region.width
    val b = region.top + region.height
    return when (header.exifOrientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Rect(w - r, t, w - l, b)
      ExifInterface.ORIENTATION_ROTATE_180 -> Rect(w - r, h - b, w - l, h - t)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> Rect(l, h - b, r, h - t)
      ExifInterface.ORIENTATION_TRANSPOSE -> Rect(t, l, b, r)
      ExifInterface.ORIENTATION_ROTATE_90 -> Rect(t, h - r, b, h - l)
      ExifInterface.ORIENTATION_TRANSVERSE -> Rect(w - b, h - r, w - t, h - l)
      ExifInterface.ORIENTATION_ROTATE_270 -> Rect(w - b, l, w - t, r)
      else -> Rect(l, t, r, b)
    }
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

  /** [bitmap] turned upright for EXIF [orientation]; the same bitmap when already upright. */
  internal fun orient(bitmap: Bitmap, orientation: Int): Bitmap = applyOrientation(bitmap, orientation)

  private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    if (orientation == ExifInterface.ORIENTATION_NORMAL) return bitmap
    val matrix = Matrix().apply {
      setValues(NativeGeometry.orientationMatrix(bitmap.width, bitmap.height, orientation))
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
  }
}
