package com.margelo.nitro.documentscanner.processing

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.margelo.nitro.documentscanner.DocumentFilter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.android.Utils
import org.opencv.imgproc.Imgproc

@RunWith(AndroidJUnit4::class)
class FilterParityTest {
  @Test fun bitmapPreviewMatchesMatStageAndBoundaryFailureRecovers() {
    OpenCvRuntime.ensureLoaded()
    val invalid = Mat(16, 16, CvType.CV_8UC1)
    try {
      try {
        DocumentFilters.apply(invalid, DocumentFilter.ENHANCE).release()
        fail("Non-RGBA input accepted")
      } catch (_: IllegalArgumentException) { }
    } finally { invalid.release() }
    val source = fixture("page")
    val opaque = Mat()
    val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    try {
      // Bitmap alpha conversion is platform-owned; compare the same decoded pixels.
      Utils.matToBitmap(source, bitmap)
      Utils.bitmapToMat(bitmap, opaque)
      for (filter in DocumentFilter.entries) {
        val preview = DocumentFilters.apply(bitmap, filter)
        val expected = DocumentFilters.apply(opaque, filter)
        val expectedRgba = Mat()
        val actual = Mat()
        try {
          Imgproc.cvtColor(expected, expectedRgba, if (expected.channels() == 1) Imgproc.COLOR_GRAY2RGBA else Imgproc.COLOR_BGR2RGBA)
          Utils.bitmapToMat(preview, actual)
          val a = ByteArray(W * H * 4).also { actual.get(0, 0, it) }
          val b = ByteArray(W * H * 4).also { expectedRgba.get(0, 0, it) }
          assertArrayEquals(b, a)
          assertEquals(W, preview.width)
          assertEquals(H, preview.height)
        } finally { preview.recycle(); expected.release(); expectedRgba.release(); actual.release() }
      }
      assertFalse(bitmap.isRecycled)
    } finally { bitmap.recycle(); source.release(); opaque.release() }
  }

  @Test fun preEncodingPixelsMatchCorrectedReference() {
    OpenCvRuntime.ensureLoaded()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    for (kind in listOf("page", "empty")) for (shadow in listOf(false, true)) for (filter in DocumentFilter.entries) {
      val source = fixture(kind)
      val before = ByteArray(W * H * 4).also { source.get(0, 0, it) }
      val referenceSource = source.clone()
      val reference = try {
        if (shadow) ReferenceShadowRemoval.apply(referenceSource)
        ReferenceDocumentFilters.apply(referenceSource, filter)
      } finally { referenceSource.release() }
      val referenceBytes = ByteArray(W * H * reference.channels()).also { reference.get(0, 0, it) }
      reference.release()
      val combined = DocumentFilters.apply(source, filter, shadow)
      assertArrayEquals(before, ByteArray(W * H * 4).also { source.get(0, 0, it) })
      val name = "$kind-${filter.name}-$shadow.raw"
      var filtered: Mat? = null
      try {
        if (shadow) ShadowRemoval.apply(source)
        filtered = DocumentFilters.apply(source, filter)
        val bytes = ByteArray(W * H * filtered.channels()).also { filtered.get(0, 0, it) }
        // Android asset packaging expands .gz and exposes the path without that suffix.
        val expected = instrumentation.context.assets.open("parity/filters/$name").use { it.readBytes() }
        assertArrayEquals("Corrected Kotlin reference changed", expected, referenceBytes)
        assertArrayEquals(bytes, ByteArray(W * H * combined.channels()).also { combined.get(0, 0, it) })
        assertEquals(name, expected.size, bytes.size)
        var worst = 0
        for (i in bytes.indices) worst = maxOf(worst, kotlin.math.abs((bytes[i].toInt() and 255) - (expected[i].toInt() and 255)))
        assertTrue("$name maximum difference $worst", worst <= 1)
        if (!shadow) assertArrayEquals(before, ByteArray(W * H * 4).also { source.get(0, 0, it) })
      } finally { filtered?.release(); combined.release(); source.release() }
    }
  }

  private fun fixture(kind: String): Mat {
    val bytes = ByteArray(W * H * 4)
    for (y in 0 until H) for (x in 0 until W) {
      val light = if (kind == "empty") 235 else 250 - 100 * x / (W - 1)
      val ink = y in 35..90 && x in 20..235 && x % 12 < 3
      val faint = y in 100..135 && x in 20..235 && x % 12 < 2
      val color = y in 150..205 && x in 20..95
      val gray = y in 150..205 && x in 125..230
      val channels = if (kind == "empty") listOf(light, light, light) else when {
        ink -> listOf(light / 4, light / 4, light / 4)
        faint -> listOf(light * 3 / 4, light * 3 / 4, light * 3 / 4)
        color -> listOf(light * 160 / 255, light * 220 / 255, light * 160 / 255)
        gray -> listOf(light * 3 / 5, light * 3 / 5, light * 3 / 5)
        else -> listOf(light, light, light)
      }
      val i = (y * W + x) * 4
      for (c in 0..2) bytes[i + c] = channels[c].toByte()
      bytes[i + 3] = (128 + y % 128).toByte()
    }
    return Mat(H, W, CvType.CV_8UC4).apply { put(0, 0, bytes) }
  }
  companion object { const val W = 256; const val H = 224 }
}
