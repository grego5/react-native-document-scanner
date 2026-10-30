package com.margelo.nitro.documentscanner.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.margelo.nitro.documentscanner.CreatePdfOptions
import com.margelo.nitro.documentscanner.ImageFit
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs

@RunWith(AndroidJUnit4::class)
class PdfWriterTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val directory get() = File(context.cacheDir, "pdf-writer-test").apply { mkdirs() }

  @After
  fun cleanup() {
    directory.deleteRecursively()
  }

  @Test
  fun embedsSmallJpegUnchangedAndEncodesPngUpright() {
    // Stored 400 x 300 with its left half dark, shown rotated 90 degrees clockwise: upright, the dark half is on top.
    val jpeg = jpeg("upright.jpg", 400, 300, ExifInterface.ORIENTATION_ROTATE_90)
    val png = bilevelPng("page.png")
    val pdf = createPdf(listOf(jpeg, png), imageFit = ImageFit.CONTAIN, pageWidth = 595.28, pageHeight = 841.89)
    val bytes = pdf.readBytes()
    assertTrue("JPEG copied unchanged", bytes.indexOf(jpeg.readBytes()) >= 0)
    assertTrue(String(bytes, Charsets.ISO_8859_1).contains("/Width 200 /Height 100 /ColorSpace /DeviceRGB /BitsPerComponent 8"))
    assertFalse("No binary PDF image streams", String(bytes, Charsets.ISO_8859_1).contains("/BitsPerComponent 1"))

    PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
      assertEquals(2, renderer.pageCount)
      renderer.render(0) { width, height, pixel ->
        // PdfRenderer truncates the page size to whole points.
        assertEquals(595 to 841, width to height)
        assertTrue("top ${pixel(width / 2, height / 4)}", pixel(width / 2, height / 4) < 60)
        assertTrue("bottom ${pixel(width / 2, height * 3 / 4)}", pixel(width / 2, height * 3 / 4) > 200)
      }
      renderer.render(1) { width, height, pixel ->
        assertTrue("left ${pixel(width / 4, height / 2)}", pixel(width / 4, height / 2) < 60)
        assertTrue("right ${pixel(width * 3 / 4, height / 2)}", pixel(width * 3 / 4, height / 2) > 200)
      }
    }
  }

  @Test
  fun oneBitPngInputIsAlsoDownscaledAndEncodedAsJpeg() {
    val pdf = createPdf(listOf(bilevelPng("page.png")), imageFit = ImageFit.CONTAIN,
      targetDpi = 72.0, pageWidth = 72.0, pageHeight = 72.0)
    val text = String(pdf.readBytes(), Charsets.ISO_8859_1)
    assertTrue(text.contains("/Width 72 /Height 36 /ColorSpace /DeviceRGB /BitsPerComponent 8"))
    assertFalse(text.contains("/FlateDecode"))
  }

  @Test
  fun fourBitPngEmbedsUnchangedAndDisplaysAllSixteenGrayLevels() {
    val png = fourBitPng("gray4.png")
    val compressed = ByteArrayOutputStream().also { GrayscalePng.read4(png)!!.copyCompressedTo(it) }.toByteArray()
    val pdf = createPdf(listOf(png), jpegQuality = 0.1)
    val bytes = pdf.readBytes()
    assertTrue(String(bytes, Charsets.ISO_8859_1).contains("/ColorSpace /DeviceGray /BitsPerComponent 4 /Filter /FlateDecode"))
    assertTrue("Reuse compressed PNG data despite JPEG quality override", bytes.indexOf(compressed) >= 0)
    PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
      renderer.render(0) { _, height, pixel ->
        assertEquals(16, (0 until 16).map { pixel(it * 10 + 5, height / 2) }.toSet().size)
      }
    }
  }

  @Test
  fun fourBitPngRespectsTargetDpiWithoutBecomingJpeg() {
    val png = fourBitPng("gray4-large.png", width = 320, height = 160)
    val pdf = createPdf(listOf(png), imageFit = ImageFit.CONTAIN, targetDpi = 72.0,
      pageWidth = 72.0, pageHeight = 72.0, jpegQuality = 0.1)
    val text = String(pdf.readBytes(), Charsets.ISO_8859_1)
    assertTrue(text.contains("/Width 72 /Height 36 /ColorSpace /DeviceGray /BitsPerComponent 4"))
    assertFalse(text.contains("/DCTDecode"))
    PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
      renderer.render(0) { width, height, pixel ->
        assertTrue(pixel(width / 4, height / 2) < 150)
        assertTrue(pixel(width * 3 / 4, height / 2) > 150)
      }
    }
  }

  @Test
  fun fourBitPngTransparencyIsPreservedByThePdfMask() {
    val png = fourBitPng("transparent4.png", transparent = 7)
    val pdf = createPdf(listOf(png))
    assertTrue(String(pdf.readBytes(), Charsets.ISO_8859_1).contains("/Mask [7 7]"))
    PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
      renderer.render(0) { _, height, pixel ->
        assertTrue("Transparent level is white", pixel(75, height / 2) > 240)
        assertTrue("Adjacent opaque gray remains", pixel(65, height / 2) < 200)
      }
    }
  }

  @Test
  fun withoutPageSizeEachPageIsItsImage() {
    val pdf = createPdf(listOf(bilevelPng("page.png")))
    PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
      renderer.openPage(0).use { assertEquals(200 to 100, it.width to it.height) }
    }
  }

  @Test
  fun jpegAboveTargetDpiIsDownscaledAndReencoded() {
    val jpeg = jpeg("large.jpg", 3000, 2000)
    val pdf = createPdf(listOf(jpeg), imageFit = ImageFit.CONTAIN, targetDpi = 72.0, pageWidth = 595.0, pageHeight = 842.0)
    val bytes = pdf.readBytes()
    assertTrue(bytes.indexOf(jpeg.readBytes()) < 0)
    // The landscape image fits 595 x 397 points, so 72 dpi keeps one pixel per point.
    assertTrue(String(bytes, Charsets.ISO_8859_1).contains("/Width 595 /Height 397 /ColorSpace /DeviceRGB"))
  }

  @Test
  fun pngWithAlphaIsEncodedOnWhite() {
    val png = File(directory, "alpha.png")
    val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
    try {
      png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } finally {
      bitmap.recycle()
    }
    val pdf = createPdf(listOf(png))
    PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
      renderer.render(0) { width, height, pixel -> assertTrue(pixel(width / 2, height / 2) > 240) }
    }
    assertFalse(String(pdf.readBytes(), Charsets.ISO_8859_1).contains("/FlateDecode"))
  }

  private fun createPdf(
    pages: List<File>,
    imageFit: ImageFit? = null,
    targetDpi: Double? = null,
    pageWidth: Double? = null,
    pageHeight: Double? = null,
    jpegQuality: Double? = null,
  ): File {
    val output = File(directory, "out.pdf")
    val uri = PdfWriter.write(CreatePdfOptions(
      outputPath = output.absolutePath,
      pages = pages.map { Uri.fromFile(it).toString() }.toTypedArray(),
      imageFit = imageFit,
      autoRotateExif = null,
      jpegQuality = jpegQuality,
      targetDpi = targetDpi,
      pageWidth = pageWidth,
      pageHeight = pageHeight,
      pageAspectRatio = null,
    ))
    assertEquals(Uri.fromFile(output).toString(), uri)
    return output
  }

  /** A [width] x [height] JPEG with its left half black. */
  private fun jpeg(name: String, width: Int, height: Int, orientation: Int? = null): File {
    val file = File(directory, name)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
      Canvas(bitmap).apply {
        drawColor(Color.WHITE)
        drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = Color.BLACK })
      }
      file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    } finally {
      bitmap.recycle()
    }
    if (orientation != null) ExifInterface(file.absolutePath).apply {
      setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
      saveAttributes()
    }
    return file
  }

  private fun fourBitPng(name: String, width: Int = 160, height: Int = 80, transparent: Int? = null): File {
    val file = File(directory, name)
    file.outputStream().use { output ->
      GrayscalePng.write4(output, width, height) { _, row ->
        for (x in row.indices) row[x] = (x * 16 / width * 17).toByte()
      }
    }
    if (transparent != null) {
      val before = file.readBytes()
      val type = "tRNS".toByteArray(Charsets.US_ASCII)
      val value = byteArrayOf(0, transparent.toByte())
      val crc = CRC32().apply { update(type); update(value) }
      file.outputStream().use { raw ->
        DataOutputStream(raw).apply {
          write(before, 0, 33)
          writeInt(2)
          write(type)
          write(value)
          writeInt(crc.value.toInt())
          write(before, 33, before.size - 33)
        }
      }
    }
    return file
  }

  /** A binary input fixture: it must still use ordinary decoding, not four-bit passthrough. */
  private fun bilevelPng(name: String): File {
    val file = File(directory, name)
    OpenCvRuntime.ensureLoaded()
    val page = Mat(100, 200, CvType.CV_8UC1, Scalar(255.0))
    try {
      page.submat(Rect(0, 0, 100, 100)).setTo(Scalar(0.0))
      assertTrue(Imgcodecs.imwrite(file.absolutePath, page, MatOfInt(Imgcodecs.IMWRITE_PNG_BILEVEL, 1)))
    } finally {
      page.release()
    }
    return file
  }

  /** Renders page [index] on white at one pixel per point and passes its size and a red-channel reader. */
  private fun PdfRenderer.render(index: Int, check: (Int, Int, (Int, Int) -> Int) -> Unit) = openPage(index).use { page ->
    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
    try {
      bitmap.eraseColor(Color.WHITE)
      page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
      check(page.width, page.height) { x, y -> Color.red(bitmap.getPixel(x, y)) }
    } finally {
      bitmap.recycle()
    }
  }

  private fun ByteArray.indexOf(part: ByteArray): Int {
    outer@ for (start in 0..size - part.size) {
      for (offset in part.indices) if (this[start + offset] != part[offset]) continue@outer
      return start
    }
    return -1
  }
}
