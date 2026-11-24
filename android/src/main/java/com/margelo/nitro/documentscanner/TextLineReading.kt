package com.margelo.nitro.documentscanner

import kotlin.math.roundToInt

/**
 * One recognized word: its text and horizontal extent in the upright crop's pixels, and [corners], its box as the
 * recognizer gives it, top left, top right, bottom right, bottom left as x, y pairs, turned with the text; null when
 * the recognizer gives none.
 */
internal data class RecognizedWord(val text: String, val left: Float, val right: Float, val corners: FloatArray? = null)

/**
 * One recognized line in the upright crop's pixels: its words, its vertical extent, [confidence], 0 when the
 * recognizer gives none, and [corners], its box turned with the text as for [RecognizedWord]; null when not given.
 */
internal data class RecognizedLine(
  val words: List<RecognizedWord>,
  val top: Float,
  val bottom: Float,
  val confidence: Float,
  val corners: FloatArray? = null,
)

/** Maps [region], fractions of the upright view, into an analysis buffer's pixels. The bound viewport crops every
 * use case to what the view shows, so the view is [cropRect] rotated clockwise by [rotationDegrees]. */
internal fun regionInBuffer(
  region: TextRegion, rotationDegrees: Int, cropLeft: Int, cropTop: Int, cropWidth: Int, cropHeight: Int,
): IntArray {
  val x0 = region.x.coerceIn(0.0, 1.0)
  val y0 = region.y.coerceIn(0.0, 1.0)
  val x1 = (region.x + region.width).coerceIn(x0, 1.0)
  val y1 = (region.y + region.height).coerceIn(y0, 1.0)
  // Upright (u, v) comes from buffer (bu, bv) rotated clockwise by the rotation.
  fun toBuffer(u: Double, v: Double): Pair<Double, Double> = when (rotationDegrees) {
    90 -> v to 1 - u
    180 -> 1 - u to 1 - v
    270 -> 1 - v to u
    else -> u to v
  }
  val (ax, ay) = toBuffer(x0, y0)
  val (bx, by) = toBuffer(x1, y1)
  val left = cropLeft + (minOf(ax, bx) * cropWidth).roundToInt()
  val top = cropTop + (minOf(ay, by) * cropHeight).roundToInt()
  val right = cropLeft + (maxOf(ax, bx) * cropWidth).roundToInt()
  val bottom = cropTop + (maxOf(ay, by) * cropHeight).roundToInt()
  return intArrayOf(left, top, right, bottom)
}

/** [this] region's corners, clamped to the view. */
internal fun TextRegion.corners(): DocumentCorners {
  val left = x.coerceIn(0.0, 1.0)
  val top = y.coerceIn(0.0, 1.0)
  val right = (x + width).coerceIn(left, 1.0)
  val bottom = (y + height).coerceIn(top, 1.0)
  return DocumentCorners(DocumentPoint(left, top), DocumentPoint(right, top), DocumentPoint(right, bottom), DocumentPoint(left, bottom))
}

/** [this] quadrilateral's area, in the units of its coordinates squared; its corners go around it in order. */
internal fun DocumentCorners.area(): Double {
  val p = arrayOf(topLeft, topRight, bottomRight, bottomLeft)
  var twice = 0.0
  for (i in p.indices) {
    val a = p[i]
    val b = p[(i + 1) % p.size]
    twice += a.x * b.y - b.x * a.y
  }
  return kotlin.math.abs(twice) / 2
}

internal fun clampedRegion(region: TextRegion): TextRegion {
  val x = region.x.coerceIn(0.0, 1.0)
  val y = region.y.coerceIn(0.0, 1.0)
  return TextRegion(x, y, region.width.coerceIn(0.0, 1.0 - x), region.height.coerceIn(0.0, 1.0 - y))
}
