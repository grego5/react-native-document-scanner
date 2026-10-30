package com.margelo.nitro.documentscanner.processing

import android.graphics.Bitmap
import com.margelo.nitro.documentscanner.DocumentFilter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** The render filters described on `DocumentFilter`; OpenCV must be loaded. */
internal object DocumentFilters {
  /**
   * Returns [rgba] filtered as a new Mat the caller releases: three-channel BGR for `none` and `enhance`, one channel
   * for `grayscale` and `blackWhite`. Both retain gray edge pixels; `blackWhite` darkens the text more strongly.
   */
  fun apply(rgba: Mat, filter: DocumentFilter): Mat = when (filter) {
    DocumentFilter.NONE -> bgr(rgba)
    DocumentFilter.ENHANCE -> {
      val page = bgr(rgba)
      val channels = List(3) { Mat() }
      try {
        val (level, gains) = evenLighting(page)
        try {
          // One illumination for all channels keeps each colour's ratios, so coloured areas keep their colour. The
          // white balance rides in the divide's scale, saving a pass over the page.
          FieldKernels.divide(page, channels, level, DoubleArray(3) { 255.0 * gains[it] })
        } finally {
          level.release()
        }
        Core.merge(channels, page)
        // One measurement from the evened luminance serves every channel, as the lighting estimate does.
        val gray = Mat()
        val detail = try {
          Imgproc.cvtColor(page, gray, Imgproc.COLOR_BGR2GRAY)
          InkDetail.measure(gray, SHARPEN_AMOUNT)
        } finally {
          gray.release()
        }
        detail.use { finish(channels, it) }
        Core.merge(channels, page)
        page
      } catch (error: Throwable) {
        page.release()
        throw error
      } finally {
        channels.forEach(Mat::release)
      }
    }
    DocumentFilter.GRAYSCALE -> evenGray(rgba).also { gray -> InkDetail.measure(gray, SHARPEN_AMOUNT).use { finish(listOf(gray), it) } }
    DocumentFilter.BLACKWHITE -> evenGray(rgba).also { gray ->
      InkDetail.measure(gray, SHARPEN_AMOUNT).use { finish(listOf(gray), it, document = true) }
    }
  }

  /** Returns [source] filtered as a new ARGB_8888 bitmap of the same size; [source] is only read. */
  fun apply(source: Bitmap, filter: DocumentFilter): Bitmap {
    OpenCvRuntime.ensureLoaded()
    val rgba = Mat()
    var filtered: Mat? = null
    try {
      Utils.bitmapToMat(source, rgba)
      filtered = apply(rgba, filter)
      val code = if (filtered.channels() == 1) Imgproc.COLOR_GRAY2RGBA else Imgproc.COLOR_BGR2RGBA
      Imgproc.cvtColor(filtered, rgba, code)
      return Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(rgba, it) }
    } finally {
      rgba.release()
      filtered?.release()
    }
  }

  private fun bgr(rgba: Mat) = Mat().also { Imgproc.cvtColor(rgba, it, Imgproc.COLOR_RGBA2BGR) }

  /** [rgba] in gray, divided by its paper illumination. */
  private fun evenGray(rgba: Mat): Mat {
    val page = bgr(rgba)
    try {
      val (level, gains) = evenLighting(page)
      try {
        // Gray of the white-balanced page and its division in one pass, with COLOR_BGR2GRAY's weights.
        return Mat().also { gray -> FieldKernels.grayDivide(page, gray, level, GRAY_WEIGHTS, gains, 255.0) }
      } finally {
        level.release()
      }
    } finally {
      page.release()
    }
  }

  /** Sharpens [channels], deepens faded ink by [detail] and darkens their mid-tones, in place. */
  private fun finish(channels: List<Mat>, detail: InkDetail, document: Boolean = false) {
    val blurred = channels.map { Mat() }
    try {
      channels.zip(blurred).forEach { (channel, soft) -> Imgproc.GaussianBlur(channel, soft, Size(0.0, 0.0), SHARPEN_SIGMA) }
      detail.apply(channels, blurred)
    } finally {
      blurred.forEach(Mat::release)
    }
    channels.forEach { Core.LUT(it, if (document) documentToneCurve else toneCurve, it) }
  }

  /**
   * Returns the per-channel gains that white-balance [bgr] to its brightest pixels, mostly paper, and the balanced
   * paper's brightness on a reduced copy, as a float map the caller enlarges and releases. [bgr] is only read: the
   * caller applies both within its own pass over the page. The paper is estimated from paper pixels only:
   * neutral ones at least [PAPER_RATIO] of the brightest within the page's short side. Ink, coloured areas and mid-tone
   * photos are not paper, and are filled from the paper around them, the nearest scale with enough paper winning. The
   * estimate never falls below [MIN_PAPER] of its brightest point.
   */
  private fun evenLighting(bgr: Mat): Pair<Mat, DoubleArray> {
    val mats = mutableListOf<Mat>()
    fun mat() = Mat().also(mats::add)
    try {
      val scale = min(1.0, BACKGROUND_SHORT_SIDE / min(bgr.cols(), bgr.rows()))
      val small = mat()
      Imgproc.resize(bgr, small, Size(), scale, scale, Imgproc.INTER_AREA)
      small.convertTo(small, CvType.CV_32FC3)
      val luma = mat()
      Imgproc.cvtColor(small, luma, Imgproc.COLOR_BGR2GRAY)

      val bright = mat()
      Imgproc.threshold(luma, bright, Core.minMaxLoc(luma).maxVal * BRIGHT_FRACTION, 255.0, Imgproc.THRESH_BINARY)
      bright.convertTo(bright, CvType.CV_8U)
      val means = Core.mean(small, bright).`val`
      val neutral = (means[0] + means[1] + means[2]) / 3.0
      val gains = Scalar(neutral / max(means[0], 1.0), neutral / max(means[1], 1.0), neutral / max(means[2], 1.0))
      Core.multiply(small, gains, small)
      Imgproc.cvtColor(small, luma, Imgproc.COLOR_BGR2GRAY)

      val channels = ArrayList<Mat>(3)
      Core.split(small, channels)
      mats += channels
      val high = mat()
      val low = mat()
      Core.max(channels[0], channels[1], high)
      Core.max(high, channels[2], high)
      Core.min(channels[0], channels[1], low)
      Core.min(low, channels[2], low)
      val chroma = mat()
      Core.subtract(high, low, chroma)
      Core.multiply(high, Scalar(NEUTRAL_CHROMA), high)
      val neutralMask = mat()
      Core.compare(chroma, high, neutralMask, Core.CMP_LE)

      val shortSide = min(small.cols(), small.rows())
      val wideSide = odd(shortSide * WIDE_FRACTION)
      val strokeSide = odd(shortSide * STROKE_REMOVAL_FRACTION)
      // The brightest nearby, within the page's short side, taken on a quarter-size copy to keep the filter cheap. A
      // uniform area narrower than that window cannot be its own reference.
      val nearby = mat()
      Imgproc.resize(luma, nearby, Size(), 0.25, 0.25, Imgproc.INTER_AREA)
      val nearbySide = odd(min(nearby.cols(), nearby.rows()).toDouble())
      Imgproc.dilate(nearby, nearby, mat().also { it.create(nearbySide, nearbySide, CvType.CV_8U); it.setTo(Scalar(1.0)) })
      Imgproc.resize(nearby, nearby, luma.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
      Core.multiply(nearby, Scalar(PAPER_RATIO), nearby)
      val brightMask = mat()
      Core.compare(luma, nearby, brightMask, Core.CMP_GE)
      val paperMask = mat()
      Core.bitwise_and(neutralMask, brightMask, paperMask)

      val weight = mat()
      paperMask.convertTo(weight, CvType.CV_32F, 1.0 / 255.0)
      val weighted = mat()
      Core.multiply(luma, weight, weighted)
      val global = Core.mean(luma, paperMask).`val`[0].takeIf { it > 0.0 } ?: Core.minMaxLoc(luma).maxVal
      val level = Mat(luma.rows(), luma.cols(), CvType.CV_32F, Scalar(global)).also(mats::add)
      val sum = mat()
      val coverage = mat()
      val covered = mat()
      // Coarse to fine: each finer scale replaces the estimate wherever it sees enough paper.
      for (side in listOf(shortSide or 1, wideSide, strokeSide)) {
        val size = Size(side.toDouble(), side.toDouble())
        Imgproc.blur(weighted, sum, size)
        Imgproc.blur(weight, coverage, size)
        Imgproc.threshold(coverage, covered, MIN_COVERAGE, 255.0, Imgproc.THRESH_BINARY)
        covered.convertTo(covered, CvType.CV_8U)
        Core.divide(sum, coverage, sum)
        sum.copyTo(level, covered)
      }
      Imgproc.blur(level, level, Size(strokeSide.toDouble(), strokeSide.toDouble()))
      Core.max(level, Scalar(Core.minMaxLoc(level).maxVal * MIN_PAPER), level)

      return level.clone() to gains.`val`.copyOf(3)
    } finally {
      mats.forEach(Mat::release)
    }
  }

  private fun odd(size: Double) = max(3, size.roundToInt()) or 1

  /** Clips near-black and near-white, then darkens mid-tones with a gamma above 1. */
  private val toneCurve: Mat by lazy { toneCurve(BLACK_POINT, WHITE_POINT, TONE_GAMMA) }

  // Keep a wider range of light grays than Enhance/Gray, rather than cutting faint stroke edges to white.
  private val documentToneCurve: Mat by lazy { toneCurve(0.12, 0.96, 1.6) }

  private fun toneCurve(black: Double, white: Double, gamma: Double): Mat =
    Mat(1, 256, CvType.CV_8U).apply {
      put(0, 0, ByteArray(256) { value ->
        val x = ((value / 255.0 - black) / (white - black)).coerceIn(0.0, 1.0)
        (x.pow(gamma) * 255.0).roundToInt().toByte()
      })
    }

  /** COLOR_BGR2GRAY's weights, in BGR order. */
  private val GRAY_WEIGHTS = doubleArrayOf(.114, .587, .299)

  // Unmeasured starting points, to tune on recorded pages.
  /** The paper estimate is taken on a copy this many pixels on its short side. */
  private const val BACKGROUND_SHORT_SIDE = 400.0
  /** Pixels this bright relative to the brightest set the white balance. */
  private const val BRIGHT_FRACTION = 0.8
  /** Paper's channels differ by at most this fraction of its brightest one. */
  private const val NEUTRAL_CHROMA = 0.12
  /** Paper is at least this bright relative to the brightest nearby; shading deeper than this is left uncorrected. */
  private const val PAPER_RATIO = 0.6
  /** The middle scale of the paper fill, as a fraction of the page's short side. */
  private const val WIDE_FRACTION = 0.25
  /** The finest scale, as a fraction of the page's short side: wider than any ink stroke. */
  private const val STROKE_REMOVAL_FRACTION = 0.05
  /** A scale's estimate is used where at least this fraction of its window is paper. */
  private const val MIN_COVERAGE = 0.05
  private const val MIN_PAPER = 0.5
  private const val SHARPEN_SIGMA = 1.0
  private const val SHARPEN_AMOUNT = 0.6
  private const val BLACK_POINT = 0.08
  /** Evened paper at or above this fraction of white becomes pure white, which is what keeps the encoding small. */
  private const val WHITE_POINT = 0.88
  private const val TONE_GAMMA = 1.3
}
