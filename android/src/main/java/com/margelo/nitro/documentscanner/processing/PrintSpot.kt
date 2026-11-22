package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentCorners
import com.margelo.nitro.documentscanner.DocumentPoint
import java.nio.ByteBuffer
import kotlin.math.roundToInt

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
 * most gradient energy, which crisp print has and blank paper or creases have little of, so contrast autofocus finds edges
 * there. Cells within [SPOT_TIE] of the best prefer the page's centre, where the focus box
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
