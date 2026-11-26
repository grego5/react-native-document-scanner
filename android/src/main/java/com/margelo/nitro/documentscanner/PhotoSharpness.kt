package com.margelo.nitro.documentscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import com.margelo.nitro.documentscanner.processing.EdgeWidth
import com.margelo.nitro.documentscanner.processing.regionEdgeWidth
import java.io.File
import java.nio.ByteBuffer

/**
 * The saved photo's own edge width ([regionEdgeWidth]) over a full-resolution square of up to [PHOTO_SHARPNESS_SIDE]
 * pixels around [centre], the page's print spot in upright photo fractions: the page is flagged soft by it
 * ([photoSoft]), and the limits judged on analysis frames are calibrated against it. [orientation] is the file's EXIF
 * orientation, which maps the upright point to the stored pixels the decoder reads. Null when the file cannot be read or the square
 * has too few print edges.
 */
internal fun photoSharpness(file: File, orientation: Int, centre: DocumentPoint): EdgeWidth? {
  @Suppress("DEPRECATION")
  val decoder = runCatching { BitmapRegionDecoder.newInstance(file.absolutePath, false) }.getOrNull() ?: return null
  try {
    val width = decoder.width
    val height = decoder.height
    val (x, y) = when (orientation) {
      ExifInterface.ORIENTATION_ROTATE_90 -> centre.y to 1 - centre.x
      ExifInterface.ORIENTATION_ROTATE_180 -> 1 - centre.x to 1 - centre.y
      ExifInterface.ORIENTATION_ROTATE_270 -> 1 - centre.y to centre.x
      else -> centre.x to centre.y
    }
    val side = minOf(PHOTO_SHARPNESS_SIDE, width, height)
    val left = (x * width - side / 2).toInt().coerceIn(0, width - side)
    val top = (y * height - side / 2).toInt().coerceIn(0, height - side)
    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
    val bitmap = decoder.decodeRegion(Rect(left, top, left + side, top + side), options) ?: return null
    try {
      val pixels = IntArray(bitmap.width * bitmap.height)
      bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
      val luma = ByteArray(pixels.size) { i ->
        val p = pixels[i]
        ((p shr 16 and 0xff) * 77 + (p shr 8 and 0xff) * 150 + (p and 0xff) * 29 shr 8).toByte()
      }
      return regionEdgeWidth(ByteBuffer.wrap(luma), bitmap.width, 1, 0, 0, bitmap.width, bitmap.height)
    } finally {
      bitmap.recycle()
    }
  } finally {
    decoder.recycle()
  }
}

/**
 * Where a camera page's photo is measured ([photoSharpness]): [spot] in upright photo fractions, fixed at the shutter,
 * and the file's EXIF [orientation]. [measured], for diagnostics, receives the edges found, the verdict and the time
 * the measure took in milliseconds, on the session's worker.
 */
internal class PhotoSample(
  val spot: DocumentPoint, val orientation: Int, val measured: ((EdgeWidth?, Boolean, Long) -> Unit)? = null,
)

/**
 * The photo reads soft: its print edges' median turn is wider than [PHOTO_SOFT_WIDTH] over dense print
 * ([PHOTO_DENSE_EDGES] edges or more), or than [PHOTO_SPARSE_SOFT_WIDTH] over fewer. Blur itself thins the edges
 * found, since a spread edge's steps fall below the detector's least step, so a low count cannot excuse a wide
 * median; sparse print only measures somewhat wider while sharp. Motion smears only the edges it crosses, so over
 * dense print either direction's median wider than [PHOTO_AXIS_SOFT_WIDTH] is soft too, however sharp the other
 * direction keeps the overall median. Under [PHOTO_MIN_EDGES] the square holds too little print to judge.
 */
internal fun photoSoft(edges: EdgeWidth): Boolean {
  if (edges.edges < PHOTO_MIN_EDGES) return false
  if (edges.edges < PHOTO_DENSE_EDGES) return edges.median > PHOTO_SPARSE_SOFT_WIDTH
  val axis = maxOf(edges.rows ?: 0.0, edges.columns ?: 0.0)
  return edges.median > PHOTO_SOFT_WIDTH || axis > PHOTO_AXIS_SOFT_WIDTH
}

/** The side of [photoSharpness]'s square, in photo pixels: a quarter of a 4000-wide photo, a few lines of print. */
private const val PHOTO_SHARPNESS_SIDE = 1024
/** In photo pixels: about 1.5 times the median. On the SM-G998U, 14 photos over dense print, passive and locked, lit
 * and dark, measured 1.55 to 2.09 (median 1.67); squares of sparse print measured up to 3.0 for their content. A
 * starting setting from one phone. */
private const val PHOTO_SOFT_WIDTH = 2.6
/** In photo pixels, over sparse print: sharp sparse squares measured up to 3.0 on the SM-G998U, and photos that live
 * frames passed but motion reached during the still measured 3.3 to 7.1 with 130 to 850 edges. */
private const val PHOTO_SPARSE_SOFT_WIDTH = 3.2
/** In photo pixels, either direction over dense print: sharp dense photos measured up to 2.34 one way, and one smeared
 * sideways measured 7.12 one way while its median read 2.36. */
private const val PHOTO_AXIS_SOFT_WIDTH = 3.5
/** Print edges that make a square dense: the dense squares had 1000 to 11000. */
private const val PHOTO_DENSE_EDGES = 1000
/** Print edges a square needs at all for its width to judge the photo. */
private const val PHOTO_MIN_EDGES = 100
