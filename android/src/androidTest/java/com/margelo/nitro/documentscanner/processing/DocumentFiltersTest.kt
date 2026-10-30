package com.margelo.nitro.documentscanner.processing

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.margelo.nitro.documentscanner.DocumentFilter
import com.margelo.nitro.documentscanner.DocumentImageFormat
import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import com.margelo.nitro.documentscanner.DocumentRenderOptions
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar

@RunWith(AndroidJUnit4::class)
class DocumentFiltersTest {
  private val outputs = mutableListOf<File>()
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  @After
  fun cleanup() = outputs.forEach { it.delete() }

  @Test
  fun grayscaleEvensUnevenLightAndKeepsInk() {
    val page = litPage()
    val filtered = DocumentFilters.apply(page, DocumentFilter.GRAYSCALE)
    try {
      assertEquals(1, filtered.channels())
      // Paper is white from the bright edge to the shaded one, and ink stays dark under both.
      assertTrue("bright paper ${filtered.at(40, 300)}", filtered.at(40, 300) >= 245)
      assertTrue("shaded paper ${filtered.at(760, 300)}", filtered.at(760, 300) >= 245)
      assertTrue("bright ink ${filtered.at(BRIGHT_INK, 150)}", filtered.at(BRIGHT_INK, 150) <= 80)
      assertTrue("shaded ink ${filtered.at(SHADED_INK, 150)}", filtered.at(SHADED_INK, 150) <= 80)
      // A dark area wider than the stroke filter is content, not shadow.
      assertTrue("photo ${filtered.at(450, 450)}", filtered.at(450, 450) <= 100)
    } finally {
      page.release()
      filtered.release()
    }
  }

  @Test
  fun enhanceKeepsBroadColourAndMidTones() {
    val page = litPage()
    val filtered = DocumentFilters.apply(page, DocumentFilter.ENHANCE)
    try {
      fun bgr(x: Int, y: Int) = filtered.get(y, x).map { it.toInt() }
      for ((x, y) in listOf(40 to 50, 760 to 50)) assertTrue("paper ${bgr(x, y)}", bgr(x, y).all { it >= 240 })
      // A pastel patch wider than the paper filter keeps its colour instead of turning white.
      val (blue, green, red) = bgr(PASTEL_X.first + 100, 400)
      assertTrue("pastel ${bgr(PASTEL_X.first + 100, 400)}", red <= 215 && blue <= 215 && green - red >= 30)
      // So does a broad mid-gray photo.
      val photo = bgr(MID_TONE_X.first + 100, 400)
      assertTrue("mid-tone $photo", photo.all { it in 60..210 })
    } finally {
      page.release()
      filtered.release()
    }
  }

  @Test
  fun blackWhiteKeepsGrayscaleDetail() {
    val page = litPage()
    val filtered = DocumentFilters.apply(page, DocumentFilter.BLACKWHITE)
    try {
      val values = ByteArray(filtered.cols() * filtered.rows()).also { filtered.get(0, 0, it) }
      assertTrue("Gray detail must survive", values.any { (it.toInt() and 0xFF) in 1..254 })
      assertEquals(255, filtered.at(760, 300))
      assertTrue("Ink stays dark", filtered.at(SHADED_INK, 150) < 64)
    } finally {
      page.release()
      filtered.release()
    }
  }

  @Test
  fun blackWhitePreservesContinuousFaintOnePixelStrokes() {
    OpenCvRuntime.ensureLoaded()
    val page = Mat(300, 400, CvType.CV_8UC4, Scalar(255.0, 255.0, 255.0, 255.0))
    try {
      for ((x, value) in listOf(100 to 180.0, 150 to 225.0, 200 to 235.0)) {
        val stroke = page.submat(Rect(x, 80, 1, 140))
        try { stroke.setTo(Scalar(value, value, value, 255.0)) } finally { stroke.release() }
      }
      val filtered = DocumentFilters.apply(page, DocumentFilter.BLACKWHITE)
      try {
        assertEquals(255, filtered.at(30, 150))
        for (x in listOf(100, 150, 200)) for (y in 90 until 210) {
          assertTrue("Faint stroke at $x,$y became ${filtered.at(x, y)}", filtered.at(x, y) in 1..254)
        }
      } finally { filtered.release() }
    } finally { page.release() }
  }

  @Test
  fun fadedInkIsDeepenedTowardTheCrispInk() {
    OpenCvRuntime.ensureLoaded()
    // Text strokes, crisp on the left and faded by glare on the right.
    val page = Mat(600, 800, CvType.CV_8UC4, Scalar(255.0, 255.0, 255.0, 255.0))
    try {
      for (x in 40 until 760 step 12) for (top in 40 until 560 step 30) {
        val value = if (x < 400) 40.0 else 170.0
        val stroke = page.submat(Rect(x, top, 4, 16))
        try { stroke.setTo(Scalar(value, value, value, 255.0)) } finally { stroke.release() }
      }
      val filtered = DocumentFilters.apply(page, DocumentFilter.GRAYSCALE)
      try {
        val crisp = filtered.at(100, 290)
        val faded = filtered.at(700, 290)
        // Without the stretch the faded stroke stays near 170.
        assertTrue("faded $faded against crisp $crisp", faded <= 110)
        assertTrue("paper ${filtered.at(706, 290)}", filtered.at(706, 290) >= 245)
      } finally { filtered.release() }
    } finally { page.release() }
  }

  @Test
  fun aGrayBoxAmongFadedTextKeepsItsTone() {
    OpenCvRuntime.ensureLoaded()
    // Faded text on the left with a broad gray box among it, crisp text on the right setting the page's best ink.
    val page = Mat(600, 800, CvType.CV_8UC4, Scalar(255.0, 255.0, 255.0, 255.0))
    try {
      for (x in 40 until 760 step 12) for (top in 40 until 560 step 30) {
        val value = if (x < 400) 170.0 else 40.0
        val stroke = page.submat(Rect(x, top, 4, 16))
        try { stroke.setTo(Scalar(value, value, value, 255.0)) } finally { stroke.release() }
      }
      val box = page.submat(Rect(150, 150, 120, 120))
      try { box.setTo(Scalar(128.0, 128.0, 128.0, 255.0)) } finally { box.release() }
      val filtered = DocumentFilters.apply(page, DocumentFilter.GRAYSCALE)
      try {
        // The tone curve alone takes 128 to about 110; a stretch of the whole tile would take it to black.
        assertTrue("box ${filtered.at(210, 210)}", filtered.at(210, 210) in 90..130)
        assertTrue("faded stroke ${filtered.at(100, 520)}", filtered.at(100, 520) <= 110)
      } finally { filtered.release() }
    } finally { page.release() }
  }

  @Test
  fun monochromePngOutputIsFourBitGrayscale() {
    for (filter in listOf(DocumentFilter.GRAYSCALE, DocumentFilter.BLACKWHITE)) {
      val bytes = renderFixture(DocumentRenderOptions(false, null, filter, DocumentImageFormat.PNG, null, null, null))
      assertEquals(0x89, bytes[0].toInt() and 255)
      assertEquals("PNG", String(bytes, 1, 3))
      assertEquals("Sixteen levels, not binary", 4, bytes[24].toInt())
      assertEquals("Grayscale, no palette or alpha", 0, bytes[25].toInt())
    }
  }

  /**
   * 800 x 600 RGBA paper lit from 255 at the left down to 120 at the right, with ink strokes, a dark photo and, below
   * them, a broad pastel green patch and a broad mid-gray photo.
   */
  private fun litPage(): Mat {
    val width = 800
    val height = 600
    val pixels = ByteArray(width * height * 4)
    for (y in 0 until height) for (x in 0 until width) {
      val light = 255.0 - 135.0 * x / (width - 1)
      val ink = y in 100 until 200 && x in INK_X && (x - INK_X.first) % 12 < 4
      val photo = x in 400 until 500 && y in 400 until 500
      val broad = y in 300 until 500
      val (red, green, blue) = when {
        ink || photo -> Triple(0.25, 0.25, 0.25)
        broad && x in PASTEL_X -> Triple(160 / 255.0, 220 / 255.0, 160 / 255.0)
        broad && x in MID_TONE_X -> Triple(0.6, 0.6, 0.6)
        else -> Triple(1.0, 1.0, 1.0)
      }
      val index = (y * width + x) * 4
      pixels[index] = (light * red).toInt().toByte()
      pixels[index + 1] = (light * green).toInt().toByte()
      pixels[index + 2] = (light * blue).toInt().toByte()
      pixels[index + 3] = 255.toByte()
    }
    OpenCvRuntime.ensureLoaded()
    return Mat(height, width, CvType.CV_8UC4).apply { put(0, 0, pixels) }
  }

  private fun Mat.at(x: Int, y: Int) = get(y, x)[0].toInt()

  private fun renderFixture(options: DocumentRenderOptions): ByteArray {
    val directory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
    val fixture = File(directory, "filters-fixture.jpg")
    outputs += fixture
    val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
    try {
      bitmap.eraseColor(Color.LTGRAY)
      fixture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    } finally {
      bitmap.recycle()
    }
    val result = DocumentRenderer.render(
      Uri.fromFile(fixture).toString(),
      DocumentQuad(DocumentPoint(0.1, 0.1), DocumentPoint(0.9, 0.1), DocumentPoint(0.9, 0.9), DocumentPoint(0.1, 0.9)),
      options,
    )
    val output = File(Uri.parse(result.outputUri).path!!)
    outputs += output
    return output.readBytes()
  }

  private companion object {
    const val CACHE_DIRECTORY = "react-native-document-scanner"
    val INK_X = 60 until 740
    // Inside a stroke at each end of the strokes, which are 4 px wide every 12 px.
    const val BRIGHT_INK = 61
    const val SHADED_INK = 721
    val PASTEL_X = 100 until 300
    val MID_TONE_X = 520 until 720
  }
}
