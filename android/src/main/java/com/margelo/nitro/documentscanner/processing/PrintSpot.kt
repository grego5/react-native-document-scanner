package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentCorners
import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.FocusAim
import java.nio.ByteBuffer
import kotlin.math.abs
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

/**
 * The buffer rectangle [left], [top] to [right], [bottom]'s sharpness: the mean squared luma gradient over a grid of
 * up to [SHARPNESS_SAMPLES] points spread by its shape. A smeared or soft frame loses the edges print has, so the same
 * view scores lower; the score means nothing across views, so frames are ranked only against others of one focus
 * ([com.margelo.nitro.documentscanner.FrameRing]).
 * Null for a rectangle too small to sample.
 */
internal fun regionSharpness(
  buffer: ByteBuffer, rowStride: Int, pixelStride: Int, left: Int, top: Int, right: Int, bottom: Int,
): Double? {
  val width = right - left - 2
  val height = bottom - top - 2
  if (width < 4 || height < 4) return null
  val columns = kotlin.math.sqrt(SHARPNESS_SAMPLES.toDouble() * width / height).roundToInt().coerceIn(2, width)
  val rows = (SHARPNESS_SAMPLES / columns).coerceIn(2, height)
  fun luma(x: Int, y: Int) = luma(buffer, y * rowStride + x * pixelStride, pixelStride)
  var energy = 0.0
  for (j in 0 until rows) {
    val y = top + 1 + ((j + .5) * height / rows).toInt()
    for (i in 0 until columns) {
      val x = left + 1 + ((i + .5) * width / columns).toInt()
      val gx = luma(x + 1, y) - luma(x - 1, y)
      val gy = luma(x, y + 1) - luma(x, y - 1)
      energy += (gx * gx + gy * gy).toDouble()
    }
  }
  return energy / (rows * columns)
}

/** Points sampled for [regionSharpness]: enough to cover a line of print, cheap enough for every frame. */
private const val SHARPNESS_SAMPLES = 4096

/**
 * The buffer rectangle [left], [top] to [right], [bottom] as a coarse [THUMB_COLUMNS] by [THUMB_ROWS] grid of mean
 * luma, each cell averaged over a few points, so frames of one view compare alike while a moved page or a new view
 * does not. Row by row in buffer order; null for a rectangle too small to sample.
 */
internal fun regionThumbnail(
  buffer: ByteBuffer, rowStride: Int, pixelStride: Int, left: Int, top: Int, right: Int, bottom: Int,
): FloatArray? {
  val width = right - left
  val height = bottom - top
  if (width < THUMB_COLUMNS * THUMB_SAMPLES || height < THUMB_ROWS * THUMB_SAMPLES) return null
  // The region is usually a wide strip; in the buffer it may stand on its side, so the grid follows its long axis.
  val wide = width >= height
  val columns = if (wide) THUMB_COLUMNS else THUMB_ROWS
  val rows = if (wide) THUMB_ROWS else THUMB_COLUMNS
  val thumb = FloatArray(columns * rows)
  for (j in 0 until rows) {
    for (i in 0 until columns) {
      var sum = 0
      for (sj in 0 until THUMB_SAMPLES) {
        val y = top + ((j + (sj + .5) / THUMB_SAMPLES) * height / rows).toInt()
        for (si in 0 until THUMB_SAMPLES) {
          val x = left + ((i + (si + .5) / THUMB_SAMPLES) * width / columns).toInt()
          sum += luma(buffer, y * rowStride + x * pixelStride, pixelStride)
        }
      }
      thumb[j * columns + i] = sum.toFloat() / (THUMB_SAMPLES * THUMB_SAMPLES)
    }
  }
  return thumb
}

/**
 * A frame's print edges: the [median] width in pixels over which the luma turns from paper to ink, the medians of the
 * edges met along buffer rows, [rows], and columns, [columns], which part under motion blur, and the [edges] measured.
 */
internal data class EdgeWidth(val median: Double, val rows: Double, val columns: Double, val edges: Int)

/**
 * The buffer rectangle [left], [top] to [right], [bottom]'s edge width: how far the luma takes to turn across its
 * print's edges. A sharp edge turns within a pixel or two and blur spreads it, whatever the print's size or contrast,
 * so unlike [regionSharpness] the width means the same in every view. Along up to [EDGE_LINES] rows and as many
 * columns, each local peak of the luma step of at least [EDGE_STEP] is followed both ways while the luma keeps
 * turning, up to [EDGE_REACH] pixels each side; one turning by less than [EDGE_RISE] in all is texture or noise, not
 * print. Its width is the distance between the points 10% and 90% of the way through its turn, so tails of a smear
 * count less than its slope. Null with fewer than [EDGE_MIN] edges.
 */
internal fun regionEdgeWidth(
  buffer: ByteBuffer, rowStride: Int, pixelStride: Int, left: Int, top: Int, right: Int, bottom: Int,
): EdgeWidth? {
  val width = right - left
  val height = bottom - top
  if (width < 8 || height < 8) return null
  val rows = ArrayList<Double>()
  val columns = ArrayList<Double>()
  val line = IntArray(maxOf(width, height))
  for (k in 0 until minOf(EDGE_LINES, height)) {
    val y = top + ((k + .5) * height / minOf(EDGE_LINES, height)).toInt()
    for (x in 0 until width) line[x] = luma(buffer, y * rowStride + (left + x) * pixelStride, pixelStride)
    edgeWidths(line, width, rows)
  }
  for (k in 0 until minOf(EDGE_LINES, width)) {
    val x = left + ((k + .5) * width / minOf(EDGE_LINES, width)).toInt()
    for (y in 0 until height) line[y] = luma(buffer, (top + y) * rowStride + x * pixelStride, pixelStride)
    edgeWidths(line, height, columns)
  }
  val all = rows + columns
  if (all.size < EDGE_MIN) return null
  return EdgeWidth(median(all), median(rows), median(columns), all.size)
}

/** The widths of the edges along the first [n] values of [line], added to [out]. */
private fun edgeWidths(line: IntArray, n: Int, out: MutableList<Double>) {
  var i = 0
  while (i < n - 1) {
    val step = line[i + 1] - line[i]
    val size = kotlin.math.abs(step)
    // A peak of the step: at least its neighbours on either side.
    val before = if (i > 0) kotlin.math.abs(line[i] - line[i - 1]) else 0
    val after = if (i < n - 2) kotlin.math.abs(line[i + 2] - line[i + 1]) else 0
    if (size < EDGE_STEP || size < before || size < after) {
      i++
      continue
    }
    val sign = if (step > 0) 1 else -1
    var a = i
    while (a > 0 && i - a < EDGE_REACH && sign * (line[a] - line[a - 1]) > 0) a--
    var b = i + 1
    while (b < n - 1 && b - i - 1 < EDGE_REACH && sign * (line[b + 1] - line[b]) > 0) b++
    val low = line[a]
    val rise = line[b] - low
    if (kotlin.math.abs(rise) >= EDGE_RISE) out += crossing(line, a, b, low + .9 * rise) - crossing(line, a, b, low + .1 * rise)
    i = b
  }
}

/** Where [line] first passes [level] between [a] and [b], interpolated between pixels. */
private fun crossing(line: IntArray, a: Int, b: Int, level: Double): Double {
  for (k in a until b) {
    val p = line[k].toDouble()
    val q = line[k + 1].toDouble()
    if ((level - p) * (level - q) <= 0 && p != q) return k + (level - p) / (q - p)
  }
  return b.toDouble()
}

private fun median(values: List<Double>): Double {
  if (values.isEmpty()) return Double.NaN
  val sorted = values.sorted()
  return sorted[sorted.size / 2]
}

/** [regionEdgeWidth]'s lines each way, least step of an edge's peak, reach each side, least rise and edges. */
private const val EDGE_LINES = 48
private const val EDGE_STEP = 8
private const val EDGE_REACH = 16
private const val EDGE_RISE = 40
private const val EDGE_MIN = 20

/** [regionThumbnail]'s grid along and across the region, and the points averaged per cell along each axis. */
private const val THUMB_COLUMNS = 24
private const val THUMB_ROWS = 8
private const val THUMB_SAMPLES = 3

/** The static guide area around the frame's centre, in upright frame fractions: continuous autofocus's central box,
 * where the page being scanned usually lies before it is found. */
internal val GUIDE_AREA: DocumentCorners = (FocusAim.CENTRE - FocusAim.AREA / 2).toDouble().let { a ->
  val b = 1 - a
  DocumentCorners(DocumentPoint(a, a), DocumentPoint(b, a), DocumentPoint(b, b), DocumentPoint(a, b))
}

/** [pickPrintSpot] over [GUIDE_AREA], without a page: the guide's most textured spot, in upright frame fractions. A
 * blurred frame keeps more contrast over print than over blank paper, so the spot is found before focus is. */
internal fun pickGuideSpot(
  buffer: ByteBuffer, rowStride: Int, pixelStride: Int, left: Int, top: Int, right: Int, bottom: Int, rotation: Int,
): DocumentPoint? = pickPrintSpot(buffer, rowStride, pixelStride, left, top, right, bottom, rotation, GUIDE_AREA)
  ?.let { pagePoint(GUIDE_AREA, it.x, it.y) }

/** [point] lies inside the convex outline [corners]. */
internal fun insidePage(corners: DocumentCorners, point: DocumentPoint): Boolean {
  val outline = listOf(corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft)
  var sign = 0
  for (index in outline.indices) {
    val a = outline[index]
    val b = outline[(index + 1) % outline.size]
    val cross = (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)
    if (cross == 0.0) continue
    val side = if (cross > 0) 1 else -1
    if (sign == 0) sign = side else if (side != sign) return false
  }
  return true
}

/** The page coordinates of [point] inside [corners], the inverse of [pagePoint]; null when it does not converge. */
internal fun pageCoordinates(corners: DocumentCorners, point: DocumentPoint): DocumentPoint? {
  var s = .5
  var t = .5
  repeat(20) {
    val p = pagePoint(corners, s, t)
    val ex = p.x - point.x
    val ey = p.y - point.y
    if (abs(ex) < 1e-9 && abs(ey) < 1e-9) return DocumentPoint(s, t)
    // The bilinear map's derivatives along s and t.
    val (a, b, c, d) = listOf(corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft)
    val sx = (1 - t) * (b.x - a.x) + t * (c.x - d.x)
    val sy = (1 - t) * (b.y - a.y) + t * (c.y - d.y)
    val tx = (1 - s) * (d.x - a.x) + s * (c.x - b.x)
    val ty = (1 - s) * (d.y - a.y) + s * (c.y - b.y)
    val det = sx * ty - sy * tx
    if (abs(det) < 1e-12) return null
    s -= (ex * ty - ey * tx) / det
    t -= (sx * ey - sy * ex) / det
  }
  val p = pagePoint(corners, s, t)
  return DocumentPoint(s, t).takeIf { abs(p.x - point.x) < 1e-6 && abs(p.y - point.y) < 1e-6 }
}

/** Samples per cell side when picking the print spot. */
private const val SPOT_SAMPLES = 24
/** The page's border left out when picking, as a fraction of its width and height. */
private const val SPOT_BORDER = .06
/** Cells scoring within this fraction of the best count as tied. */
private const val SPOT_TIE = .15
