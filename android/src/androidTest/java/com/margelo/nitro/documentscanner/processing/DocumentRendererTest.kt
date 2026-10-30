package com.margelo.nitro.documentscanner.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import com.margelo.nitro.documentscanner.DocumentRenderOptions
import com.margelo.nitro.documentscanner.PaperSize
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentRendererTest {
  private val outputs = mutableListOf<File>()
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val fixture by lazy { createFixture() }

  @After
  fun cleanup() {
    // Every render also writes its thumbnail beside the output.
    outputs.forEach {
      it.delete()
      File(it.parentFile, "thumbnail-${it.nameWithoutExtension}.jpg").delete()
    }
    fixture.delete()
  }

  @Test
  fun writesUprightIndependentJpegAndPreservesOriginalBytes() {
    val originalHash = fixture.readBytes().sha256()
    val result = DocumentRenderer.render(
      Uri.fromFile(fixture).toString(),
      sourceQuad(),
      DocumentRenderOptions(removeShadows = false, jpegQuality = 0.94, filter = null, format = null, paperSize = PaperSize(10.0, 15.24), dpi = 100.0, followPageEdges = null),
    )
    val output = File(Uri.parse(result.outputUri).path!!)
    outputs += output

    assertNotEquals(fixture.absolutePath, output.absolutePath)
    assertTrue(output.canonicalPath.startsWith(File(context.cacheDir, CACHE_DIRECTORY).canonicalPath))
    assertEquals(originalHash, fixture.readBytes().sha256())
    assertEquals(ExifInterface.ORIENTATION_NORMAL, ExifInterface(output.absolutePath).getAttributeInt(
      ExifInterface.TAG_ORIENTATION,
      ExifInterface.ORIENTATION_NORMAL,
    ))
    assertTrue(result.width <= 600.0 && result.height <= 600.0)
    assertEquals(4, result.validContentPolygon.size)
    assertTrue(result.validContentPolygon.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 })
  }

  @Test
  fun quadCornersMapExactlyToOutputCornersWithoutMargin() {
    // The fixture's white page spans 65..570 x 48..430 on a dark background.
    val result = DocumentRenderer.render(
      Uri.fromFile(fixture).toString(),
      DocumentQuad(
        topLeft = DocumentPoint(65.0 / 640, 48.0 / 480),
        topRight = DocumentPoint(570.0 / 640, 48.0 / 480),
        bottomRight = DocumentPoint(570.0 / 640, 430.0 / 480),
        bottomLeft = DocumentPoint(65.0 / 640, 430.0 / 480),
      ),
      DocumentRenderOptions(removeShadows = false, jpegQuality = 0.95, filter = null, format = null, paperSize = null, dpi = null, followPageEdges = null),
    )
    val output = File(Uri.parse(result.outputUri).path!!)
    outputs += output

    assertEquals(505.0 / 382.0, result.width / result.height, 0.01)
    // Without a paper size the page keeps its own size in the photo.
    assertEquals(505.0, result.width, 1.0)
    // The whole output comes from captured pixels: the valid polygon is the full output rectangle.
    assertEquals(4, result.validContentPolygon.size)
    val corners = listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0)
    assertTrue(corners.all { (x, y) -> result.validContentPolygon.any { abs(it.x - x) < 1e-6 && abs(it.y - y) < 1e-6 } })
    val bitmap = requireNotNull(BitmapFactory.decodeFile(output.absolutePath))
    try {
      // Every pixel along the output border is page, not background.
      val edge = 2
      val border = (edge until bitmap.width - edge).flatMap { x -> listOf(x to edge, x to bitmap.height - 1 - edge) } +
        (edge until bitmap.height - edge).flatMap { y -> listOf(edge to y, bitmap.width - 1 - edge to y) }
      val darkest = border.minOf { (x, y) -> Color.red(bitmap.getPixel(x, y)) }
      assertTrue("Background reached the output border (red=$darkest)", darkest > 200)
    } finally {
      bitmap.recycle()
    }
  }

  @Test
  fun bentPageIsFlattenedBackOntoItsGrid() {
    // A grid page bent by a known curve on every side, the same patch the renderer undoes: its output shows no
    // background at any side and every grid line back where the flat page had it.
    val file = createBentPage()
    val result = DocumentRenderer.render(
      Uri.fromFile(file).toString(),
      BENT_QUAD,
      DocumentRenderOptions(removeShadows = false, jpegQuality = 0.95, filter = null, format = null, paperSize = null, dpi = null, followPageEdges = null),
    )
    val output = File(Uri.parse(result.outputUri).path!!)
    outputs += output
    val rendered = requireNotNull(BitmapFactory.decodeFile(output.absolutePath))
    try {
      assertEquals(BENT_WIDTH, rendered.width)
      assertEquals(BENT_HEIGHT, rendered.height)
      val edge = 3
      val border = (edge until BENT_WIDTH - edge).flatMap { x -> listOf(x to edge, x to BENT_HEIGHT - 1 - edge) } +
        (edge until BENT_HEIGHT - edge).flatMap { y -> listOf(edge to y, BENT_WIDTH - 1 - edge to y) }
      val darkest = border.minOf { (x, y) -> Color.red(rendered.getPixel(x, y)) }
      assertTrue("Background reached a side of the output (red=$darkest)", darkest > 200)
      // Ink-weighted centre of a line across a window either side of where the flat page drew it.
      fun centre(expected: Int, at: (offset: Int) -> Int): Double {
        val window = -6..6
        val weights = window.map { (200 - Color.red(at(it))).coerceAtLeast(0).toDouble() }
        return expected + window.zip(weights).sumOf { (offset, weight) -> offset * weight } / weights.sum()
      }
      for (x in GRID_X) for (y in GRID_Y.map { it - GRID_STEP / 2 } + (BENT_HEIGHT - GRID_STEP / 2)) {
        assertEquals("Vertical line $x at row $y", x.toDouble(), centre(x) { rendered.getPixel(x + it, y) }, 1.5)
      }
      for (y in GRID_Y) for (x in GRID_X.map { it - GRID_STEP / 2 } + (BENT_WIDTH - GRID_STEP / 2)) {
        assertEquals("Horizontal line $y at column $x", y.toDouble(), centre(y) { rendered.getPixel(x, y + it) }, 1.5)
      }
    } finally {
      rendered.recycle()
    }
  }

  @Test
  fun cornerOutsideThePhotoKeepsTheStraightWarp() {
    // The bottom stays bowed between corners in the photo, but with the top-left corner outside it the output must
    // match the straight warp its coverage polygon describes.
    val file = createBentPage()
    val quad = DocumentQuad(DocumentPoint(-8.0 / 640, 60.0 / 480), BENT_QUAD.topRight, BENT_QUAD.bottomRight, BENT_QUAD.bottomLeft)
    fun render(follow: Boolean) = DocumentRenderer.render(
      Uri.fromFile(file).toString(),
      quad,
      DocumentRenderOptions(removeShadows = false, jpegQuality = 0.95, filter = null, format = null, paperSize = null, dpi = null, followPageEdges = follow),
    ).let { File(Uri.parse(it.outputUri).path!!).also { output -> outputs += output } }
    assertEquals(render(false).readBytes().sha256(), render(true).readBytes().sha256())
  }

  @Test
  fun paperSizeAndDpiScaleThePageWithoutStretchingOrEnlargingIt() {
    // The fixture's page is 505 x 382 px. 10 cm at 100 dpi is 394 px; the page keeps its own proportions.
    fun render(paper: PaperSize, dpi: Double?) = DocumentRenderer.render(
      Uri.fromFile(fixture).toString(),
      DocumentQuad(
        topLeft = DocumentPoint(65.0 / 640, 48.0 / 480),
        topRight = DocumentPoint(570.0 / 640, 48.0 / 480),
        bottomRight = DocumentPoint(570.0 / 640, 430.0 / 480),
        bottomLeft = DocumentPoint(65.0 / 640, 430.0 / 480),
      ),
      DocumentRenderOptions(removeShadows = false, jpegQuality = null, filter = null, format = null, paperSize = paper, dpi = dpi, followPageEdges = null),
    ).also { outputs += File(Uri.parse(it.outputUri).path!!) }
    val scaled = render(PaperSize(10.0, 7.0), 100.0)
    assertEquals(394.0, scaled.width, 1.0)
    assertEquals(505.0 / 382.0, scaled.width / scaled.height, 0.01)
    // A4 at the default 200 dpi asks for 2339 px; the page has only 505.
    assertEquals(505.0, render(PaperSize(21.0, 29.7), null).width, 1.0)
  }

  @Test
  fun outputIsSizedFromTheOriginalWhenTheDecodeIsSampled() {
    // 8200 px wide decodes at a quarter under the 4096 limit; the crop is 2050 x 425 px in the original.
    val directory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
    val large = File(directory, "renderer-large-fixture.jpg")
    val bitmap = Bitmap.createBitmap(8200, 1700, Bitmap.Config.RGB_565)
    try {
      bitmap.eraseColor(Color.WHITE)
      large.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    } finally {
      bitmap.recycle()
    }
    outputs += large
    val result = DocumentRenderer.render(
      Uri.fromFile(large).toString(),
      DocumentQuad(
        topLeft = DocumentPoint(0.25, 0.25),
        topRight = DocumentPoint(0.5, 0.25),
        bottomRight = DocumentPoint(0.5, 0.5),
        bottomLeft = DocumentPoint(0.25, 0.5),
      ),
      DocumentRenderOptions(removeShadows = false, jpegQuality = 0.80, filter = null, format = null, paperSize = null, dpi = null, followPageEdges = null),
    )
    outputs += File(Uri.parse(result.outputUri).path!!)

    assertEquals(2050.0, result.width, 4.0)
    assertEquals(425.0, result.height, 4.0)
  }

  @Test
  fun clippedCornerProducesSmallerValidPolygonAndDoesNotInventPixels() {
    val result = DocumentRenderer.render(
      Uri.fromFile(fixture).toString(),
      DocumentQuad(
        topLeft = DocumentPoint(-0.12, -0.08),
        topRight = DocumentPoint(0.87, 0.12),
        bottomRight = DocumentPoint(0.82, 0.90),
        bottomLeft = DocumentPoint(0.08, 0.84),
      ),
      DocumentRenderOptions(removeShadows = false, jpegQuality = null, filter = null, format = null, paperSize = null, dpi = null, followPageEdges = null),
    )
    outputs += File(Uri.parse(result.outputUri).path!!)

    assertTrue(result.validContentPolygon.size >= 5)
    assertTrue(result.validContentPolygon.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 })
  }

  @Test
  fun exifRotatedOriginalProducesUprightOutputWithoutRotatingTwice() {
    val rotatedFixture = createFixture(ExifInterface.ORIENTATION_ROTATE_90)
    val result = DocumentRenderer.render(
      Uri.fromFile(rotatedFixture).toString(),
      DocumentQuad(
        topLeft = DocumentPoint(0.0, 0.0),
        topRight = DocumentPoint(1.0, 0.0),
        bottomRight = DocumentPoint(1.0, 1.0),
        bottomLeft = DocumentPoint(0.0, 1.0),
      ),
      DocumentRenderOptions(removeShadows = false, jpegQuality = 0.92, filter = null, format = null, paperSize = null, dpi = null, followPageEdges = null),
    )
    val output = File(Uri.parse(result.outputUri).path!!)
    outputs += output

    assertTrue(result.width < result.height)
    assertEquals(0.75, result.width / result.height, 0.02)
    assertEquals(ExifInterface.ORIENTATION_NORMAL, ExifInterface(output.absolutePath).getAttributeInt(
      ExifInterface.TAG_ORIENTATION,
      ExifInterface.ORIENTATION_UNDEFINED,
    ))
  }

  @Test
  fun excessiveExtrapolationRejectsWithoutPublishingAnOutput() {
    val original = fixture
    val outputDirectory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
    val before = outputDirectory.list()?.sorted()
    val excessive = DocumentQuad(
      topLeft = DocumentPoint(-0.81, 0.1),
      topRight = DocumentPoint(0.9, 0.1),
      bottomRight = DocumentPoint(0.9, 0.9),
      bottomLeft = DocumentPoint(0.1, 0.9),
    )

    try {
      DocumentRenderer.render(
        Uri.fromFile(original).toString(),
        excessive,
        DocumentRenderOptions(false, null, null, null, null, null, null),
      )
      throw AssertionError("Excessive extrapolation should be rejected.")
    } catch (_: IllegalArgumentException) {
      assertEquals(before, outputDirectory.list()?.sorted())
    }
  }

  private fun sourceQuad() = DocumentQuad(
    topLeft = DocumentPoint(0.125, 0.10),
    topRight = DocumentPoint(0.875, 0.12),
    bottomRight = DocumentPoint(0.84, 0.90),
    bottomLeft = DocumentPoint(0.10, 0.88),
  )

  private fun createFixture(
    orientation: Int? = null,
    directory: File = File(context.cacheDir, CACHE_DIRECTORY),
    name: String = "renderer-fixture.jpg",
  ): File {
    directory.mkdirs()
    val file = File(directory, name)
    val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
    try {
      Canvas(bitmap).apply {
        drawColor(Color.DKGRAY)
        drawRect(RectF(65f, 48f, 570f, 430f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        drawRect(RectF(150f, 130f, 470f, 150f), Paint().apply { color = Color.RED })
      }
      file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    } finally {
      bitmap.recycle()
    }
    if (orientation != null) {
      ExifInterface(file.absolutePath).apply {
        setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        saveAttributes()
      }
    }
    return file
  }

  /**
   * A 640 x 480 photo of a [BENT_WIDTH] x [BENT_HEIGHT] grid page at (80, 60), each side bent outward by
   * t(1 - t)(a + bt) as the renderer's patch has it. Each photo pixel is found on the flat page by inverting the patch.
   */
  private fun createBentPage(): File {
    val file = File(File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }, "renderer-bent.jpg")
    fun bend(a: Double, b: Double, t: Double) = t * (1 - t) * (a + b * t)
    val w = BENT_WIDTH.toDouble()
    val h = BENT_HEIGHT.toDouble()
    // Top and bottom bow in, the right bulges out, the left bows in more towards the bottom.
    fun patch(x: Double, y: Double): Pair<Double, Double> {
      val u = x / w
      val v = y / h
      return x - (1 - u) * bend(-12.0, -20.0, v) + u * bend(16.0, 0.0, v) to
        y - (1 - v) * bend(-40.0, 0.0, u) + v * bend(-28.0, -16.0, u)
    }
    fun shade(sx: Double, sy: Double): Int {
      var x = sx - 80
      var y = sy - 60
      repeat(30) {
        val (px, py) = patch(x, y)
        x += sx - 80 - px
        y += sy - 60 - py
      }
      if (x !in 0.0..w || y !in 0.0..h) return 64
      // Lines stop short of the edges, so the sides show only paper or background.
      val ink = x in GRID_MARGIN..w - GRID_MARGIN && y in GRID_MARGIN..h - GRID_MARGIN &&
        (GRID_X.any { abs(x - it) <= 1.5 } || GRID_Y.any { abs(y - it) <= 1.5 })
      return if (ink) 0 else 255
    }
    // Four samples per pixel, so the page edge and lines are antialiased like a photo's.
    val pixels = IntArray(640 * 480) { index ->
      val level = listOf(-.25, .25).sumOf { dx -> listOf(-.25, .25).sumOf { dy -> shade(index % 640 + dx, index / 640 + dy) } } / 4
      Color.rgb(level, level, level)
    }
    val bitmap = Bitmap.createBitmap(pixels, 640, 480, Bitmap.Config.ARGB_8888)
    try {
      file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    } finally {
      bitmap.recycle()
    }
    outputs += file
    return file
  }

  private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

  private companion object {
    const val CACHE_DIRECTORY = "react-native-document-scanner"
    const val BENT_WIDTH = 480
    const val BENT_HEIGHT = 360
    const val GRID_STEP = 60
    const val GRID_MARGIN = 20.0
    val GRID_X = List(BENT_WIDTH / GRID_STEP - 1) { (it + 1) * GRID_STEP }
    val GRID_Y = List(BENT_HEIGHT / GRID_STEP - 1) { (it + 1) * GRID_STEP }
    val BENT_QUAD = DocumentQuad(
      DocumentPoint(80.0 / 640, 60.0 / 480),
      DocumentPoint(560.0 / 640, 60.0 / 480),
      DocumentPoint(560.0 / 640, 420.0 / 480),
      DocumentPoint(80.0 / 640, 420.0 / 480),
    )
  }
}
