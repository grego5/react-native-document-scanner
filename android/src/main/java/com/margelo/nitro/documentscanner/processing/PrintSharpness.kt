package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentCorners
import com.margelo.nitro.documentscanner.DocumentPoint
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/** The side of the square [printSharpness] measures, as a fraction of the crop's shorter edge: about a scan lock's
 * metering box. */
internal const val SHARPNESS_SIDE = .15

/** The pixel's luma: BT.601 weights over R, G and B for an RGBA buffer ([pixelStride] of 4), as a colour-to-grey
 * conversion takes it, so coloured print and a tinted light score by brightness rather than one channel; the byte
 * itself for a single-channel buffer. */
private fun luma(buffer: ByteBuffer, offset: Int, pixelStride: Int): Int {
  val first = buffer.get(offset).toInt() and 0xFF
  if (pixelStride < 3) return first
  val green = buffer.get(offset + 1).toInt() and 0xFF
  val blue = buffer.get(offset + 2).toInt() and 0xFF
  return (77 * first + 150 * green + 29 * blue) shr 8
}

/** Each thread's luma patch, reused across frames so a score allocates nothing once it has grown to the square. */
private val patches = ThreadLocal<IntArray>()

private fun sharpnessPatch(size: Int): IntArray =
  patches.get()?.takeIf { it.size >= size } ?: IntArray(size).also(patches::set)

/**
 * The variance of the luma Laplacian over a square centred on [point], a fraction of the upright crop, with a side of
 * [sideFraction] of the crop's shorter edge. Printed text gives crisp one-pixel edges and a high variance; defocus
 * spreads them and the variance drops, so scores of one patch of print compare its focus between frames. The crop is
 * [left], [top], [right], [bottom] in buffer pixels, and [rotation] turns it upright. Null when the square does not fit.
 */
internal fun printSharpness(
  buffer: ByteBuffer, rowStride: Int, pixelStride: Int, left: Int, top: Int, right: Int, bottom: Int, rotation: Int,
  point: DocumentPoint, sideFraction: Double,
): Double? {
  val width = right - left
  val height = bottom - top
  val u = point.x
  val v = point.y
  // The upright point in the buffer; the inverse of the upright mapping the flow estimator uses.
  val (fx, fy) = when (rotation) {
    90 -> v to 1 - u
    180 -> 1 - u to 1 - v
    270 -> 1 - v to u
    else -> u to v
  }
  val half = (sideFraction * min(width, height) / 2).roundToInt()
  if (half < 2) return null
  val cx = left + (fx * width).roundToInt()
  val cy = top + (fy * height).roundToInt()
  val x0 = cx - half
  val y0 = cy - half
  val x1 = cx + half
  val y1 = cy + half
  // One pixel of margin for the Laplacian's neighbours.
  if (x0 < left + 1 || y0 < top + 1 || x1 > right - 1 || y1 > bottom - 1) return null
  // The square with its one-pixel margin, converted once, so the Laplacian reads each pixel's luma once.
  val side = x1 - x0 + 2
  val rows = y1 - y0 + 2
  val patch = sharpnessPatch(side * rows)
  for (row in 0 until rows) {
    val rowOffset = (y0 - 1 + row) * rowStride
    for (column in 0 until side) patch[row * side + column] = luma(buffer, rowOffset + (x0 - 1 + column) * pixelStride, pixelStride)
  }
  var sum = 0.0
  var sumSquares = 0.0
  var count = 0
  for (row in 1 until rows - 1) {
    val at = row * side
    for (column in 1 until side - 1) {
      val i = at + column
      val laplacian = (4 * patch[i] - patch[i - 1] - patch[i + 1] - patch[i - side] - patch[i + side]).toDouble()
      sum += laplacian
      sumSquares += laplacian * laplacian
      count++
    }
  }
  val mean = sum / count
  return sumSquares / count - mean * mean
}

/** The point at page coordinates ([s], [t]), each 0 to 1 across and down the page, inside [corners]; bilinear, which a
 * tracked outline's small perspective allows. */
internal fun pagePoint(corners: DocumentCorners, s: Double, t: Double): DocumentPoint {
  val (a, b, c, d) = listOf(corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft)
  return DocumentPoint(
    (1 - s) * (1 - t) * a.x + s * (1 - t) * b.x + s * t * c.x + (1 - s) * t * d.x,
    (1 - s) * (1 - t) * a.y + s * (1 - t) * b.y + s * t * c.y + (1 - s) * t * d.y,
  )
}

/**
 * The page's best spot for focus, in page coordinates: the centre of the third-by-third cell whose sampled luma has the
 * most gradient energy, which crisp print has and blank paper or creases have little of, so contrast autofocus and the
 * sharpness check find edges there. Cells within [SPOT_TIE] of the best prefer the page's centre, where the focus box
 * fits without moving. The page's border is left out, since past an imprecise outline it is background. Null when no
 * cell could be sampled.
 */
internal fun pickPrintSpot(
  buffer: ByteBuffer, rowStride: Int, pixelStride: Int, left: Int, top: Int, right: Int, bottom: Int, rotation: Int,
  corners: DocumentCorners,
): DocumentPoint? {
  val width = right - left
  val height = bottom - top
  fun luma(x: Int, y: Int) = luma(buffer, y * rowStride + x * pixelStride, pixelStride)
  val scores = DoubleArray(9) { -1.0 }
  for (cell in 0 until 9) {
    var energy = 0.0
    var samples = 0
    for (j in 0 until SPOT_SAMPLES) {
      for (i in 0 until SPOT_SAMPLES) {
        val s = SPOT_BORDER + (1 - 2 * SPOT_BORDER) * (cell % 3 + (i + .5) / SPOT_SAMPLES) / 3
        val t = SPOT_BORDER + (1 - 2 * SPOT_BORDER) * (cell / 3 + (j + .5) / SPOT_SAMPLES) / 3
        val p = pagePoint(corners, s, t)
        val (fx, fy) = when (rotation) {
          90 -> p.y to 1 - p.x
          180 -> 1 - p.x to 1 - p.y
          270 -> 1 - p.y to p.x
          else -> p.x to p.y
        }
        val x = left + (fx * width).roundToInt()
        val y = top + (fy * height).roundToInt()
        if (x < left + 1 || y < top + 1 || x > right - 2 || y > bottom - 2) continue
        val gx = luma(x + 1, y) - luma(x - 1, y)
        val gy = luma(x, y + 1) - luma(x, y - 1)
        energy += (gx * gx + gy * gy).toDouble()
        samples++
      }
    }
    if (samples > 0) scores[cell] = energy / samples
  }
  val best = scores.max()
  if (best < 0) return null
  val chosen = (0 until 9).filter { scores[it] >= best * (1 - SPOT_TIE) }
    .minBy { (it % 3 - 1) * (it % 3 - 1) + (it / 3 - 1) * (it / 3 - 1) }
  return DocumentPoint(SPOT_BORDER + (1 - 2 * SPOT_BORDER) * (chosen % 3 + .5) / 3,
    SPOT_BORDER + (1 - 2 * SPOT_BORDER) * (chosen / 3 + .5) / 3)
}

/** Samples per cell side when picking the print spot. */
private const val SPOT_SAMPLES = 24
/** The page's border left out when picking, as a fraction of its width and height. */
private const val SPOT_BORDER = .06
/** Cells scoring within this fraction of the best count as tied. */
private const val SPOT_TIE = .15
