package com.margelo.nitro.documentscanner.processing

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Local ink contrast and sharpness of an evened gray page. Applying it deepens each tile's thin ink toward the page's
 * best ink and sharpens softer tiles more: glare and distance from the lens leave some areas faded and soft while
 * others are crisp.
 *
 * Ink is a black-hat about a text line wide, kept per pixel: only that depth below the surrounding paper is deepened,
 * so a broad mid-tone area, such as a gray box or a printed photo beside faded text, keeps its tone.
 */
internal class InkDetail private constructor(
  /** Per-pixel ink depth below the local paper; null when no tile holds text. */
  private val ink: Mat?,
  /** Per tile, how much more of [ink] to remove: stretch − 1. */
  private val deepen: Mat,
  private val amount: Mat,
) : AutoCloseable {
  /**
   * Sharpens each of [channels] in place against its blur in [blurred] and deepens its ink:
   * x + a × (x − blur) − k × ink.
   */
  fun apply(channels: List<Mat>, blurred: List<Mat>) = FieldKernels.ink(channels, blurred, ink, deepen, amount)

  override fun close() {
    ink?.release()
    deepen.release()
    amount.release()
  }

  companion object {
    /** Measures [gray], an evened 8-bit page; [baseAmount] is the sharpening every tile gets. */
    fun measure(gray: Mat, baseAmount: Double): InkDetail {
      if (min(gray.cols(), gray.rows()) < MIN_SIDE) return uniform(baseAmount)
      val ink = Mat(); val half = Mat(); val edges = Mat()
      var kept = false
      try {
        val side = max(3, (min(gray.cols(), gray.rows()) * INK_FRACTION).roundToInt()) or 1
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(side.toDouble(), side.toDouble()))
        try {
          Imgproc.morphologyEx(gray, ink, Imgproc.MORPH_BLACKHAT, kernel)
        } finally {
          kernel.release()
        }
        // Sharpness only needs edges per tile; half size is enough and costs a quarter.
        Imgproc.resize(gray, half, Size(), .5, .5, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(half, edges, Size(0.0, 0.0), 1.0)
        Imgproc.Laplacian(edges, edges, CvType.CV_16S)
        Core.convertScaleAbs(edges, edges)

        val tileSide = min(gray.cols(), gray.rows()).toDouble() / TILES
        val rows = max(1, (gray.rows() / tileSide).roundToInt())
        val cols = max(1, (gray.cols() / tileSide).roundToInt())
        val depth = FloatArray(rows * cols)
        val sharpness = FloatArray(rows * cols)
        for (r in 0 until rows) for (c in 0 until cols) {
          val y0 = r * gray.rows() / rows; val y1 = (r + 1) * gray.rows() / rows
          val x0 = c * gray.cols() / cols; val x1 = (c + 1) * gray.cols() / cols
          val i = r * cols + c
          depth[i] = quantile(ink.submat(y0, y1, x0, x1), .99)
          sharpness[i] = quantile(edges.submat(y0 / 2, max(y0 / 2 + 1, y1 / 2), x0 / 2, max(x0 / 2 + 1, x1 / 2)), .99)
        }
        return fromTiles(rows, cols, depth, sharpness, baseAmount, ink).also { kept = it.ink != null }
      } finally {
        if (!kept) ink.release()
        half.release(); edges.release()
      }
    }

    private fun fromTiles(
      rows: Int, cols: Int, depth: FloatArray, sharpness: FloatArray, baseAmount: Double, ink: Mat,
    ): InkDetail {
      val valid = BooleanArray(depth.size) { depth[it] >= MIN_INK_DEPTH }
      if (valid.none { it }) return uniform(baseAmount)
      val target = percentile(depth.filterIndexed { i, _ -> valid[i] }, .9)
      // Sharpness relative to the tile's ink contrast measures blur alone; the stretch handles contrast.
      val relative = FloatArray(depth.size) { sharpness[it] / max(depth[it], 1f) }
      val best = percentile(relative.filterIndexed { i, _ -> valid[i] }, .9)
      val s = FloatArray(depth.size) { if (valid[it]) (target / depth[it]).coerceIn(1f, MAX_STRETCH) else 1f }
      val extra = FloatArray(depth.size) {
        if (!valid[it] || best <= 0f) 0f
        else (1f - relative[it] / best).coerceIn(0f, FULL_EXTRA_SOFTNESS) / FULL_EXTRA_SOFTNESS * MAX_EXTRA_AMOUNT
      }
      // Text-free tiles borrow their valid neighbours' mean, so text beside a margin keeps its stretch.
      val filledS = s.copyOf(); val filledExtra = extra.copyOf()
      for (r in 0 until rows) for (c in 0 until cols) {
        val i = r * cols + c
        if (valid[i]) continue
        var count = 0; var sumS = 0f; var sumExtra = 0f
        for (nr in max(0, r - 1)..min(rows - 1, r + 1)) for (nc in max(0, c - 1)..min(cols - 1, c + 1)) {
          val n = nr * cols + nc
          if (valid[n]) { count++; sumS += s[n]; sumExtra += extra[n] }
        }
        if (count > 0) { filledS[i] = sumS / count; filledExtra[i] = sumExtra / count }
      }
      val deepen = FloatArray(depth.size) { filledS[it] - 1f }
      val amount = FloatArray(depth.size) { baseAmount.toFloat() + filledExtra[it] }
      return InkDetail(ink, map(rows, cols, deepen), map(rows, cols, amount))
    }

    private fun uniform(baseAmount: Double) =
      InkDetail(null, map(1, 1, floatArrayOf(0f)), map(1, 1, floatArrayOf(baseAmount.toFloat())))

    private fun map(rows: Int, cols: Int, values: FloatArray) = Mat(rows, cols, CvType.CV_32F).apply { put(0, 0, values) }

    /** The [fraction] quantile of an 8-bit [region], from its histogram; releases [region]. */
    private fun quantile(region: Mat, fraction: Double): Float {
      val histogram = Mat(); val channel = MatOfInt(0); val noMask = Mat(); val bins = MatOfInt(256)
      val range = MatOfFloat(0f, 256f)
      try {
        Imgproc.calcHist(listOf(region), channel, noMask, histogram, bins, range)
        val counts = FloatArray(256).also { histogram.get(0, 0, it) }
        val limit = counts.sum() * fraction
        var total = 0f
        for (level in counts.indices) {
          total += counts[level]
          if (total >= limit) return level.toFloat()
        }
        return 255f
      } finally {
        listOf(histogram, channel, noMask, bins, range, region).forEach(Mat::release)
      }
    }

    private fun percentile(values: List<Float>, fraction: Double): Float =
      values.sorted()[((values.size - 1) * fraction).roundToInt()]

    /** Tiles across the page's short side. */
    private const val TILES = 8
    /** Black-hat kernel as a fraction of the page's short side: wider than strokes, about a text line. */
    private const val INK_FRACTION = .01
    /** Tiles whose thin ink is shallower than this hold no text; noise and paper grain stay below it. */
    private const val MIN_INK_DEPTH = 24f
    /** Faded ink is deepened at most this much, so noise in blank-looking tiles is not amplified further. */
    private const val MAX_STRETCH = 2.5f
    /** Extra sharpening for the softest tiles, on top of the base amount. */
    private const val MAX_EXTRA_AMOUNT = 1.2f
    /** Tiles this much less sharp than the page's sharpest get all of [MAX_EXTRA_AMOUNT]. */
    private const val FULL_EXTRA_SOFTNESS = .6f
    private const val MIN_SIDE = 64
  }
}
