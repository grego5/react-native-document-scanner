package com.margelo.nitro.documentscanner.processing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.documentscanner.ReactNativeDocumentScannerOnLoad
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * The page's own edges around a rectified quad, for a page that bends: held in a hand, curled at a corner or fresh
 * from a printer. Each side is an outward offset in output pixels, t(1 - t)(a + bt + ct²) along that side, so the
 * corners stay where the quad put them; a side with no edge found stays straight. [remap] bends each output side onto
 * its edge with a Coons patch, which leaves a straight side and the page between untouched.
 */
@DoNotStrip
internal class PageEdges private constructor(
  private val width: Int,
  private val height: Int,
  private val top: Curve?,
  private val right: Curve?,
  private val bottom: Curve?,
  private val left: Curve?,
) {
  /** Outward offset t(1 - t)(a + bt + ct²) from [coefficients] a, b and c. */
  private class Curve(private val coefficients: DoubleArray) {
    fun at(t: Double) = t * (1 - t) * (coefficients[0] + t * (coefficients[1] + t * coefficients[2]))
  }

  /**
   * Writes the [width] x [height] page from [source] into [into]: each output pixel's point on the bent canvas,
   * through [inverse] (canvas to source). The exact map is built natively a strip of rows at a time, so no full-size
   * map exists and building it costs little beside the resampling.
   */
  fun remap(source: Mat, inverse: Mat, into: Mat) {
    ReactNativeDocumentScannerOnLoad.initializeNative()
    into.create(height, width, source.type())
    val w = width.toDouble()
    val h = height.toDouble()
    remapNative(
      source.nativeObj, into.nativeObj, DoubleArray(9).also { inverse.get(0, 0, it) },
      FloatArray(height) { (left?.at(it / h) ?: 0.0).toFloat() }, FloatArray(height) { (right?.at(it / h) ?: 0.0).toFloat() },
      FloatArray(width) { (top?.at(it / w) ?: 0.0).toFloat() }, FloatArray(width) { (bottom?.at(it / w) ?: 0.0).toFloat() },
    )
  }

  companion object {
    @JvmStatic @DoNotStrip private external fun remapNative(
      source: Long, into: Long, inverse: DoubleArray, left: FloatArray, right: FloatArray, top: FloatArray, bottom: FloatArray,
    )

    /**
     * Finds the edges of the page that [homography] maps from the RGBA [source] onto a [width] x [height] canvas; null
     * when every side stays straight.
     */
    fun find(source: Mat, homography: Mat, width: Int, height: Int): PageEdges? {
      // A small rectified copy with a band around the page, in which each side is an axis-aligned line.
      val scale = DETECTION_LONG_SIDE / max(width, height)
      val w = max(1, (width * scale).roundToInt())
      val h = max(1, (height * scale).roundToInt())
      val bandX = max(MIN_BAND, (BAND * w).roundToInt())
      val bandY = max(MIN_BAND, (BAND * h).roundToInt())
      val toCanvas = Mat(3, 3, CvType.CV_64F)
      val canvas = Mat()
      try {
        val m = DoubleArray(9).also { homography.get(0, 0, it) }
        // The homography, scaled and shifted by the band. Sampled straight from the full photo: the blur below
        // smooths the aliasing, and page edges are far coarser than it.
        toCanvas.put(0, 0, *DoubleArray(9) { i -> if (i >= 6) m[i] else m[i] * scale + m[6 + i % 3] * (if (i < 3) bandX else bandY) })
        // One more pixel each way, so the right and bottom bands reach a full band past the page.
        val canvasSize = Size((w + 2 * bandX + 1).toDouble(), (h + 2 * bandY + 1).toDouble())
        Imgproc.warpPerspective(source, canvas, toCanvas, canvasSize, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        Imgproc.GaussianBlur(canvas, canvas, Size(0.0, 0.0), BLUR_SIGMA)
        val pixels = ByteArray(canvas.cols() * canvas.rows() * 4).also { canvas.get(0, 0, it) }
        val stride = canvas.cols()

        // Offsets in canvas pixels, outward positive. [at] gives the canvas pixel on the straight side at t, and
        // [outward] the step from one pixel to the next outward of it.
        fun side(alongWidth: Boolean, outward: Int, at: (t: Double) -> Int): Curve? {
          val band = if (alongWidth) bandY else bandX
          val offsets = STATIONS.map { t -> strongestStep(pixels, at(t) * 4, outward * 4, band) }
          return fit(offsets, band)?.let { curve -> Curve(DoubleArray(curve.size) { curve[it] / scale }) }
        }
        fun pixel(x: Int, y: Int) = y * stride + x
        val edges = PageEdges(
          width, height,
          top = side(true, -stride) { t -> pixel((bandX + t * w).roundToInt(), bandY) },
          right = side(false, 1) { t -> pixel(bandX + w, (bandY + t * h).roundToInt()) },
          bottom = side(true, stride) { t -> pixel((bandX + t * w).roundToInt(), bandY + h) },
          left = side(false, -1) { t -> pixel(bandX, (bandY + t * h).roundToInt()) },
        ).insideSource(homography, source.cols(), source.rows())
        return edges.takeIf { it.top != null || it.right != null || it.bottom != null || it.left != null }
      } finally {
        toCanvas.release()
        canvas.release()
      }
    }

    /**
     * The offset in -[band]..[band] from the RGBA byte at [start], [outward] bytes per step, with the largest colour step
     * between the [STEP_WINDOW] pixels either side; null when no step reaches [MIN_STEP] levels.
     */
    private fun strongestStep(pixels: ByteArray, start: Int, outward: Int, band: Int): Int? {
      val length = 2 * band + 1
      val sums = Array(3) { IntArray(length + 1) }
      for (i in 0 until length) {
        val at = start + (i - band) * outward
        for (c in 0 until 3) sums[c][i + 1] = sums[c][i] + (pixels[at + c].toInt() and 255)
      }
      var best: Int? = null
      var bestStep = MIN_STEP * STEP_WINDOW
      for (i in STEP_WINDOW until length - STEP_WINDOW) {
        var squared = 0.0
        for (c in 0 until 3) {
          val inside = sums[c][i] - sums[c][i - STEP_WINDOW]
          val outside = sums[c][i + 1 + STEP_WINDOW] - sums[c][i + 1]
          squared += ((outside - inside) * (outside - inside)).toDouble()
        }
        val step = sqrt(squared)
        if (step >= bestStep) {
          bestStep = step
          best = i - band
        }
      }
      return best
    }

    /**
     * Fits t(1 - t)(a + bt + ct²) to the found offsets: the three stations agreeing with the most others, refined by
     * least squares over them. Null when too few agree, the stations that agree miss either corner, or the side is
     * straight.
     */
    private fun fit(offsets: List<Int?>, band: Int): DoubleArray? {
      val found = STATIONS.indices.filter { offsets[it] != null }
      if (found.size < MIN_AGREEING) return null
      // The edge must pass through both corners, or it belongs to something else near the side.
      val ends = STATIONS.indices.filter { STATIONS[it] < END_ZONE || STATIONS[it] > 1 - END_ZONE }
      val meets = ends.filter { offsets[it] != null && abs(offsets[it]!!) <= END_TOLERANCE }
      if (meets.none { STATIONS[it] < .5 } || meets.none { STATIONS[it] > .5 }) return null
      fun agreeing(curve: DoubleArray) = found.filter { i -> abs(curveAt(curve, i) - offsets[i]!!) <= FIT_TOLERANCE }
      // Each triple's exact curve: offset / t(1 - t) is a quadratic in t, here in Newton's form through the three.
      val t = DoubleArray(found.size) { STATIONS[found[it]] }
      val basis = DoubleArray(found.size) { TERMS[0][found[it]] }
      val y = DoubleArray(found.size) { offsets[found[it]]!!.toDouble() }
      val q = DoubleArray(found.size) { y[it] / basis[it] }
      fun agrees(m: Int, i: Int, j: Int, d1: Double, d2: Double) =
        abs(basis[m] * (q[i] + (t[m] - t[i]) * (d1 + (t[m] - t[j]) * d2)) - y[m]) <= FIT_TOLERANCE
      var bestCount = 0
      var bestI = 0
      var bestJ = 0
      var bestD1 = 0.0
      var bestD2 = 0.0
      // One station from each third of the side: spread out, each solve is well conditioned, and the search is a fifth
      // of every triple.
      val thirds = t.indices.groupBy { (3 * t[it]).toInt().coerceAtMost(2) }
      for (i in thirds[0].orEmpty()) for (j in thirds[1].orEmpty()) {
        val d1 = (q[j] - q[i]) / (t[j] - t[i])
        for (k in thirds[2].orEmpty()) {
          val d2 = ((q[k] - q[j]) / (t[k] - t[j]) - d1) / (t[k] - t[i])
          var count = 0
          for (m in t.indices) if (agrees(m, i, j, d1, d2)) count++
          if (count > bestCount) {
            bestCount = count
            bestI = i; bestJ = j; bestD1 = d1; bestD2 = d2
          }
        }
      }
      if (bestCount < MIN_AGREEING) return null
      val inliers = t.indices.filter { agrees(it, bestI, bestJ, bestD1, bestD2) }.map { found[it] }
      // Refit over what agrees with the refined curve, and require that to reach both corners too, so the middle and
      // the corners are one edge.
      val agree = agreeing(solve(inliers, offsets) ?: return null)
      if (agree.size < MIN_AGREEING || agree.none { STATIONS[it] < END_ZONE } || agree.none { STATIONS[it] > 1 - END_ZONE }) return null
      val curve = solve(agree, offsets) ?: return null
      val largest = (0..20).maxOf { abs(Curve(curve).at(it / 20.0)) }
      return curve.takeIf { largest in MIN_BEND..(band - STEP_WINDOW).toDouble() }
    }

    private fun curveAt(curve: DoubleArray, station: Int) = TERMS.indices.sumOf { TERMS[it][station] * curve[it] }

    /** Least squares of the curve's terms over the [rows] stations' offsets, by the normal equations; null if singular. */
    private fun solve(rows: List<Int>, offsets: List<Int?>): DoubleArray? {
      val n = TERMS.size
      // The normal equations, each row with its right-hand side appended.
      val m = Array(n) { r -> DoubleArray(n + 1) { c -> rows.sumOf { i -> TERMS[r][i] * (if (c < n) TERMS[c][i] else offsets[i]!!.toDouble()) } } }
      for (col in 0 until n) {
        val pivot = (col until n).maxBy { abs(m[it][col]) }
        if (abs(m[pivot][col]) < 1e-12) return null
        m[col] = m[pivot].also { m[pivot] = m[col] }
        for (r in 0 until n) if (r != col) {
          val f = m[r][col] / m[col][col]
          for (c in col..n) m[r][c] -= f * m[col][c]
        }
      }
      return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    private const val DETECTION_LONG_SIDE = 800.0
    /** The band searched either side of each straight side, as a fraction of the page across it. */
    private const val BAND = .06
    private const val MIN_BAND = 8
    private const val BLUR_SIGMA = 1.2
    private const val STEP_WINDOW = 3
    /** Mean colour difference, in levels, below which no edge is found at a station. */
    private const val MIN_STEP = 10.0
    private val STATIONS = List(31) { .05 + .9 * it / 30 }
    /** t(1 - t), t²(1 - t) and t³(1 - t) at each station: the curve's three terms. */
    private val TERMS = List(3) { power -> DoubleArray(STATIONS.size) { STATIONS[it] * (1 - STATIONS[it]) * Math.pow(STATIONS[it], power.toDouble()) } }
    private const val MIN_AGREEING = 16
    private const val FIT_TOLERANCE = 1.5
    private const val END_ZONE = .12
    private const val END_TOLERANCE = 4
    /**
     * Canvas pixels below which a side counts as straight: a bend within the stations' own scatter is a wavy or slightly
     * misplaced straight edge, and bending to it put a sliver of background along a flat page.
     */
    private const val MIN_BEND = FIT_TOLERANCE
  }

  /** Straightens each side whose bent edge would leave the [sourceWidth] x [sourceHeight] photo. */
  private fun insideSource(homography: Mat, sourceWidth: Int, sourceHeight: Int): PageEdges {
    val inverse = Mat()
    try {
      Core.invert(homography, inverse)
      val h = DoubleArray(9).also { inverse.get(0, 0, it) }
      fun inside(curve: Curve?, point: (t: Double, offset: Double) -> Pair<Double, Double>): Curve? = curve?.takeIf {
        (0..40).all { i ->
          val t = i / 40.0
          val (x, y) = point(t, it.at(t))
          val d = h[6] * x + h[7] * y + h[8]
          val sx = (h[0] * x + h[1] * y + h[2]) / d
          val sy = (h[3] * x + h[4] * y + h[5]) / d
          sx in 0.0..sourceWidth - 1.0 && sy in 0.0..sourceHeight - 1.0
        }
      }
      val w = width.toDouble()
      val hh = height.toDouble()
      return PageEdges(
        width, height,
        top = inside(top) { t, o -> t * w to -o },
        right = inside(right) { t, o -> w + o to t * hh },
        bottom = inside(bottom) { t, o -> t * w to hh + o },
        left = inside(left) { t, o -> -o to t * hh },
      )
    } finally {
      inverse.release()
    }
  }
}
