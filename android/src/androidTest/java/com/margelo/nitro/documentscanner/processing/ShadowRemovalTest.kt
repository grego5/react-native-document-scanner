package com.margelo.nitro.documentscanner.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.margelo.nitro.documentscanner.DocumentFilter
import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import com.margelo.nitro.documentscanner.DocumentRenderOptions
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

@RunWith(AndroidJUnit4::class)
class ShadowRemovalTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Test fun hardShadowIsLiftedAndInkStaysDark() {
    // Text lines over the whole page; the lower half lies in a shadow at half the light.
    val page = textPage { _, y -> if (y >= HEIGHT / 2) .5 else 1.0 }
    try {
      ShadowRemoval.apply(page)
      assertEquals("lit paper", 240.0, page.get(100, 30)[1], 3.0)
      assertTrue("shaded paper ${page.get(500, 30)[1]}", page.get(500, 30)[1] >= 225)
      assertTrue("shaded ink ${page.get(INK_Y + 400, 100)[1]}", page.get(INK_Y + 400, 100)[1] <= 80)
      assertEquals("alpha", 255.0, page.get(500, 30)[3], 0.0)
    } finally {
      page.release()
    }
  }

  @Test fun denseTextWithoutShadowIsUnchanged() {
    val page = textPage { _, _ -> 1.0 }
    val before = Mat().also { page.copyTo(it) }
    try {
      ShadowRemoval.apply(page)
      assertEquals("paper", before.get(100, 30)[1], page.get(100, 30)[1], 1.0)
      assertEquals("ink", before.get(INK_Y, 100)[1], page.get(INK_Y, 100)[1], 1.0)
    } finally {
      before.release()
      page.release()
    }
  }

  @Test fun uniformPaperAndTinyImagesAreUnchanged() {
    OpenCvRuntime.ensureLoaded()
    for ((rows, cols) in listOf(96 to 128, 1 to 1)) {
      val page = Mat(rows, cols, CvType.CV_8UC4, Scalar(170.0, 180.0, 190.0, 255.0))
      try {
        ShadowRemoval.apply(page)
        assertEquals(rows, page.rows())
        assertEquals(listOf(170.0, 180.0, 190.0, 255.0), page.get(rows / 2, cols / 2).toList())
      } finally {
        page.release()
      }
    }
  }

  @Test fun everyFilterRendersWithAndWithoutShadowRemoval() {
    val bitmap = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.rgb(160, 170, 180))
    val source = File(context.cacheDir, "shadow-removal-test.jpg")
    try {
      source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
      val quad = DocumentQuad(DocumentPoint(0.0, 0.0), DocumentPoint(1.0, 0.0), DocumentPoint(1.0, 1.0), DocumentPoint(0.0, 1.0))
      for (removeShadows in listOf(true, false)) for (filter in DocumentFilter.entries) {
        val options = DocumentRenderOptions(removeShadows, null, filter, null, null, null, null)
        val rendered = DocumentRenderer.render(Uri.fromFile(source).toString(), quad, options)
        val file = File(Uri.parse(rendered.outputUri).path!!)
        try {
          val decoded = BitmapFactory.decodeFile(file.absolutePath)
          assertTrue("$filter removeShadows=$removeShadows", decoded != null)
          decoded?.recycle()
        } finally {
          file.delete()
        }
      }
    } finally {
      source.delete()
      bitmap.recycle()
    }
  }

  /** 600 x 800 RGBA paper at 240 with 4 px dark text lines every 20 rows, multiplied by [light]. */
  private fun textPage(light: (x: Int, y: Int) -> Double): Mat {
    OpenCvRuntime.ensureLoaded()
    val pixels = ByteArray(WIDTH * HEIGHT * 4)
    for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
      val ink = x in 60 until WIDTH - 60 && (y - INK_Y) % 20 in 0 until 4 && (x / 6) % 2 == 0
      val value = ((if (ink) 40.0 else 240.0) * light(x, y)).toInt().toByte()
      val index = (y * WIDTH + x) * 4
      pixels[index] = value; pixels[index + 1] = value; pixels[index + 2] = value; pixels[index + 3] = 255.toByte()
    }
    return Mat(HEIGHT, WIDTH, CvType.CV_8UC4).apply { put(0, 0, pixels) }
  }

  private companion object {
    const val WIDTH = 600
    const val HEIGHT = 800
    /** A text row, at x 100 inside an ink run. */
    const val INK_Y = 40
  }
}
