package com.margelo.nitro.documentscanner.processing

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** The native kernels against the same arithmetic on whole images, with maps enlarged by Imgproc.resize. */
@RunWith(AndroidJUnit4::class)
class FieldKernelsTest {
  @Test fun inkMatchesWholeImageArithmetic() {
    OpenCvRuntime.ensureLoaded()
    val deepen = small(0.0, 2.5)
    val amount = small(0.0, 2.0)
    // 301 columns leave a tail past the 16-pixel vectors; 1 x 1 has no vector at all.
    for ((width, height) in SIZES) for (withInk in listOf(true, false)) {
      val channels = List(3) { noise(height, width, CvType.CV_8U) }
      val blurred = channels.map { c -> Mat().also { Imgproc.blur(c, it, Size(3.0, 3.0)) } }
      val ink = if (withInk) noise(height, width, CvType.CV_8U) else null
      val expected = channels.zip(blurred).map { (c, b) ->
        val x = Mat().also { c.convertTo(it, CvType.CV_32F) }
        val d = Mat().also { b.convertTo(it, CvType.CV_32F) }
        Core.subtract(x, d, d)
        Core.multiply(d, enlarged(amount, width, height), d)
        Core.add(x, d, x)
        if (ink != null) {
          val k = Mat().also { ink.convertTo(it, CvType.CV_32F) }
          Core.multiply(k, enlarged(deepen, width, height), k)
          Core.subtract(x, k, x)
        }
        Mat().also { x.convertTo(it, CvType.CV_8U) }
      }
      FieldKernels.ink(channels, blurred, ink, deepen, amount)
      channels.zip(expected).forEach { (actual, wanted) -> assertClose("ink ${width}x$height ink=$withInk", wanted, actual) }
    }
  }

  @Test fun gainMatchesWholeImageArithmeticAndKeepsAlpha() {
    OpenCvRuntime.ensureLoaded()
    val gain = small(1.0, 2.0)
    for ((width, height) in SIZES) {
      val rgba = noise(height, width, CvType.CV_8UC4)
      val pixels = Mat().also { rgba.convertTo(it, CvType.CV_32FC4) }
      val g = enlarged(gain, width, height)
      val gains = Mat().also { Core.merge(listOf(g, g, g, Mat(height, width, CvType.CV_32F, Scalar(1.0))), it) }
      Core.multiply(pixels, gains, pixels)
      val expected = Mat().also { pixels.convertTo(it, CvType.CV_8UC4) }
      val alpha = Mat().also { Core.extractChannel(rgba, it, 3) }
      FieldKernels.gain(rgba, gain)
      assertClose("gain ${width}x$height", expected, rgba)
      val after = Mat().also { Core.extractChannel(rgba, it, 3) }
      assertTrue("alpha ${width}x$height", Core.countNonZero(Mat().also { Core.absdiff(alpha, after, it) }) == 0)
    }
  }

  @Test fun dividesMatchResizeAndCoreDivide() {
    OpenCvRuntime.ensureLoaded()
    // From zero, so some paper rounds to zero, which divides to zero.
    val level = small(0.0, 255.0)
    for ((width, height) in SIZES) {
      val paper = Mat().also { enlarged(level, width, height).convertTo(it, CvType.CV_8U) }
      val bgr = noise(height, width, CvType.CV_8UC3)
      val scales = doubleArrayOf(255.0 * 1.1, 255.0, 255.0 * .9)
      val channels = ArrayList<Mat>().also { Core.split(bgr, it) }
      val expected = channels.mapIndexed { c, channel -> Mat().also { Core.divide(channel, paper, it, scales[c]) } }
      val actual = List(3) { Mat() }
      FieldKernels.divide(bgr, actual, level, scales)
      expected.zip(actual).forEach { (wanted, got) -> assertClose("divide ${width}x$height", wanted, got) }

      val gray = channels[1].clone()
      val wantedGray = Mat().also { Core.divide(gray, paper, it, 255.0) }
      FieldKernels.divide(gray, listOf(gray), level, doubleArrayOf(255.0))
      assertClose("divide in place ${width}x$height", wantedGray, gray)

      // Gains high enough that bright channels clip, as saturated colours do on tinted paper.
      val gains = doubleArrayOf(1.67, 1.0, .9)
      val mixed = Mat().also { m ->
        val balanced = Mat().also { Core.multiply(bgr, Scalar(gains[0], gains[1], gains[2]), it) }
        Imgproc.cvtColor(balanced, m, Imgproc.COLOR_BGR2GRAY)
      }
      // Paper as bright as a page's, so a gray one level apart stays within two after the divide.
      val brightLevel = small(130.0, 255.0)
      val brightPaper = Mat().also { enlarged(brightLevel, width, height).convertTo(it, CvType.CV_8U) }
      val wantedMixed = Mat().also { Core.divide(mixed, brightPaper, it, 255.0) }
      val gotMixed = Mat()
      FieldKernels.grayDivide(bgr, gotMixed, brightLevel, doubleArrayOf(.114, .587, .299), gains, 255.0)
      assertClose("gray divide ${width}x$height", wantedMixed, gotMixed, tolerance = 2.0)
    }
  }

  /**
   * At most [tolerance] apart: the kernels blend the two map rows directly, where Imgproc.resize may round differently,
   * and the gray's weighted sum may round differently from Core.transform's, which the divide can enlarge by one more.
   */
  private fun assertClose(label: String, expected: Mat, actual: Mat, tolerance: Double = 1.0) {
    val difference = Mat().also { Core.absdiff(expected, actual, it) }
    val error = (0 until difference.channels()).maxOf { c -> Core.minMaxLoc(Mat().also { Core.extractChannel(difference, it, c) }).maxVal }
    assertTrue("$label: $error", error <= tolerance)
  }

  /** An 11 x 8 map of values between [from] and [to]. */
  private fun small(from: Double, to: Double) = Mat(11, 8, CvType.CV_32F).apply {
    put(0, 0, FloatArray(88) { (from + (to - from) * (it * 37 % 23) / 22).toFloat() })
  }

  private fun enlarged(map: Mat, width: Int, height: Int) =
    Mat().also { Imgproc.resize(map, it, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR) }

  private fun noise(rows: Int, cols: Int, type: Int) = Mat(rows, cols, type).also { Core.randu(it, 0.0, 256.0) }

  private companion object {
    val SIZES = listOf(301 to 517, 64 to 40, 1 to 1)
  }
}
