package com.margelo.nitro.documentscanner.processing

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

@RunWith(AndroidJUnit4::class)
class InkDetailTest {
  @Test
  fun blankPageHasNoEdgesOrExtraSharpening() = checkPage(blank = true, blurred = false)

  @Test
  fun sharpPageMatchesSeparateBufferReference() = checkPage(blank = false, blurred = false)

  @Test
  fun partlyBlurredPageMatchesSeparateBufferReference() = checkPage(blank = false, blurred = true)

  private fun checkPage(blank: Boolean, blurred: Boolean) {
    OpenCvRuntime.ensureLoaded()
    val page = fixture(blank, blurred)
    val original = page.clone()
    try {
      val expected = reference(page)
      if (blank) assertTrue(expected.sharpness.all { it == 0f })
      else {
        assertTrue("Fixture must exercise edge measurements", expected.sharpness.any { it > 0f })
        assertTrue("Fixture must exercise adaptive sharpening", expected.amount.any { it > BASE_AMOUNT.toFloat() })
      }
      repeat(6) { iteration ->
        // Keep competing allocations alive through measurement, then release before the next call.
        val allocations = listOf(
          Mat(SIDE / 2, SIDE / 2, CvType.CV_16S, Scalar(iteration.toDouble())),
          Mat(SIDE / 2, SIDE / 2, CvType.CV_8U, Scalar(255.0 - iteration)),
        )
        try {
          InkDetail.measure(page, BASE_AMOUNT).use { detail ->
            assertArrayEquals("amount, call $iteration", expected.amount, values(detail, "amount"), 1e-5f)
            assertArrayEquals("deepen, call $iteration", expected.deepen, values(detail, "deepen"), 1e-5f)
            if (blank) assertNull(field(detail, "ink"))
          }
          assertEquals("Measurement must preserve input", 0.0, Core.norm(original, page, Core.NORM_INF), 0.0)
        } finally {
          allocations.forEach(Mat::release)
        }
      }
    } finally {
      original.release()
      page.release()
    }
  }

  private data class Reference(val sharpness: FloatArray, val amount: FloatArray, val deepen: FloatArray)

  /** Independent tile quantiles from sorted pixels, using separate buffers at every image stage. */
  private fun reference(page: Mat): Reference {
    val ink = Mat()
    val half = Mat()
    val blurred = Mat()
    val signed = Mat()
    val magnitude = Mat()
    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
    try {
      Imgproc.morphologyEx(page, ink, Imgproc.MORPH_BLACKHAT, kernel)
      Imgproc.resize(page, half, Size(SIDE / 2.0, SIDE / 2.0), 0.0, 0.0, Imgproc.INTER_AREA)
      Imgproc.GaussianBlur(half, blurred, Size(0.0, 0.0), 1.0)
      Imgproc.Laplacian(blurred, signed, CvType.CV_16S)
      Core.convertScaleAbs(signed, magnitude)
      val depth = FloatArray(64)
      val sharpness = FloatArray(64)
      for (r in 0 until 8) for (c in 0 until 8) {
        val i = r * 8 + c
        depth[i] = quantile(ink, r * 96, c * 96, 96)
        sharpness[i] = quantile(magnitude, r * 48, c * 48, 48)
      }
      if (depth.all { it == 0f }) {
        return Reference(sharpness, floatArrayOf(BASE_AMOUNT.toFloat()), floatArrayOf(0f))
      }
      // These text-filled fixtures need no empty-tile neighbour interpolation.
      assertTrue("Every fixture tile must contain ink", depth.all { it >= 24f })
      val relative = FloatArray(64) { sharpness[it] / max(depth[it], 1f) }
      val target = percentile(depth, .9)
      val best = percentile(relative, .9)
      val amount = FloatArray(64) {
        BASE_AMOUNT.toFloat() + if (best <= 0f) 0f
        else (1f - relative[it] / best).coerceIn(0f, .6f) / .6f * 1.2f
      }
      val deepen = FloatArray(64) { (target / depth[it]).coerceIn(1f, 2.5f) - 1f }
      return Reference(sharpness, amount, deepen)
    } finally {
      listOf(ink, half, blurred, signed, magnitude, kernel).forEach(Mat::release)
    }
  }

  private fun quantile(image: Mat, top: Int, left: Int, side: Int): Float {
    val region = image.submat(top, top + side, left, left + side)
    try {
      val bytes = ByteArray(side * side).also { region.get(0, 0, it) }
      val sorted = bytes.map { it.toInt() and 255 }.sorted()
      return sorted[ceil(sorted.size * .99).toInt() - 1].toFloat()
    } finally {
      region.release()
    }
  }

  private fun percentile(values: FloatArray, fraction: Double): Float =
    values.sorted()[((values.size - 1) * fraction).roundToInt()]

  private fun fixture(blank: Boolean, blurred: Boolean): Mat {
    val pixels = ByteArray(SIDE * SIDE) { 255.toByte() }
    if (!blank) for (y in 0 until SIDE) for (x in 0 until SIDE) {
      val width = 2 + 2 * (x / 96 % 3)
      if (x % 16 < width && y % 24 < 18) pixels[y * SIDE + x] = 40
    }
    val page = Mat(SIDE, SIDE, CvType.CV_8UC1).apply { put(0, 0, pixels) }
    if (blurred) {
      val soft = Mat()
      try {
        Imgproc.GaussianBlur(page, soft, Size(0.0, 0.0), 1.2)
        val source = soft.submat(0, SIDE, SIDE / 2, SIDE)
        val destination = page.submat(0, SIDE, SIDE / 2, SIDE)
        try { source.copyTo(destination) } finally { source.release(); destination.release() }
      } finally {
        soft.release()
      }
    }
    return page
  }

  // Inspect measurement maps without adding a production API solely for instrumentation.
  private fun field(detail: InkDetail, name: String): Mat? =
    InkDetail::class.java.getDeclaredField(name).apply { isAccessible = true }.get(detail) as Mat?

  private fun values(detail: InkDetail, name: String): FloatArray {
    val map = requireNotNull(field(detail, name))
    return FloatArray(map.rows() * map.cols()).also { map.get(0, 0, it) }
  }

  private companion object {
    const val SIDE = 768
    const val BASE_AMOUNT = .4
  }
}
