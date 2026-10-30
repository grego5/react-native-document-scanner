package com.margelo.nitro.documentscanner.processing

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Lifts shaded paper toward the page's bright paper. The paper's brightness comes from a reduced copy with its ink
 * closed away, and one gain serves all channels, so colours keep their ratios. Deterministic; no model.
 */
internal object ShadowRemoval {
  /** Corrects [rgba] in place; alpha is unchanged. */
  fun apply(rgba: Mat) {
    if (rgba.cols() < MIN_SIDE || rgba.rows() < MIN_SIDE) return
    val mats = mutableListOf<Mat>()
    fun mat() = Mat().also(mats::add)
    try {
      FieldKernels.gain(rgba, gain(rgba, ::mat) ?: return)
    } finally {
      mats.forEach(Mat::release)
    }
  }

  /** The gain at reduced size, or null when no pixel needs one. */
  private fun gain(rgba: Mat, mat: () -> Mat): Mat? {
    val scale = min(1.0, ANALYSIS_LONG_SIDE / max(rgba.cols(), rgba.rows()))
    val small = mat()
    Imgproc.resize(rgba, small, Size(), scale, scale, Imgproc.INTER_AREA)
    // Ink keeps at least one bright channel; a shadow darkens all three.
    val channels = ArrayList<Mat>(4)
    Core.split(small, channels)
    val paper = mat()
    try {
      Core.max(channels[0], channels[1], paper)
      Core.max(paper, channels[2], paper)
    } finally {
      channels.forEach(Mat::release)
    }
    val side = max(3, (min(small.cols(), small.rows()) * INK_FRACTION).roundToInt()) or 1
    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(side.toDouble(), side.toDouble()))
    try {
      Imgproc.morphologyEx(paper, paper, Imgproc.MORPH_CLOSE, kernel)
    } finally {
      kernel.release()
    }
    paper.convertTo(paper, CvType.CV_32F)
    Imgproc.GaussianBlur(paper, paper, Size(0.0, 0.0), PAPER_SIGMA)

    val reference = innerPercentile(paper, REFERENCE_PERCENTILE)
    Core.max(paper, Scalar(reference * MIN_PAPER_FRACTION), paper)
    val gain = mat()
    Core.divide(reference, paper, gain)
    Core.min(gain, Scalar(MAX_GAIN), gain)
    Core.max(gain, Scalar(1.0), gain)
    return gain.takeIf { Core.minMaxLoc(it).maxVal > 1.0 + 1.0 / 255 }
  }

  /** The [fraction] quantile of [field] inside a 5% border, where the page edge and background stay out. */
  private fun innerPercentile(field: Mat, fraction: Double): Double {
    val left = (field.cols() * .05).toInt()
    val top = (field.rows() * .05).toInt()
    val inner = field.submat(top, max(top + 1, field.rows() - top), left, max(left + 1, field.cols() - left))
    val copy = Mat()
    try {
      inner.copyTo(copy)
      val values = FloatArray(copy.cols() * copy.rows())
      copy.get(0, 0, values)
      values.sort()
      return values[((values.size - 1) * fraction).roundToInt()].toDouble()
    } finally {
      copy.release()
      inner.release()
    }
  }

  /** The paper estimate's long side; shadows are broad, so this keeps their shape. */
  private const val ANALYSIS_LONG_SIDE = 512.0
  /** Closing kernel as a fraction of the reduced short side: wider than a text line, so ink is closed away. */
  private const val INK_FRACTION = .025
  private const val PAPER_SIGMA = 1.5
  /** The bright paper the rest is lifted toward. */
  private const val REFERENCE_PERCENTILE = .9
  /** Never lifts more than this; deeper shadows stay darker and keep their noise in check. */
  private const val MAX_GAIN = 2.0
  private const val MIN_PAPER_FRACTION = .05
  private const val MIN_SIDE = 8
}
