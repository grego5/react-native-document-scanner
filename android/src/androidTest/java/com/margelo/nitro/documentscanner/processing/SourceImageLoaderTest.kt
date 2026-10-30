package com.margelo.nitro.documentscanner.processing

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceImageLoaderTest {
  private val fixtures = mutableListOf<File>()
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  @After
  fun removeFixtures() {
    fixtures.forEach { it.delete() }
  }

  @Test
  fun allExifOrientationsProduceExpectedUprightDimensionsAndMarkerPositions() {
    val expectedCorners = mapOf(
      ExifInterface.ORIENTATION_NORMAL to listOf(Color.RED, Color.GREEN, Color.YELLOW, Color.BLUE),
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL to listOf(Color.GREEN, Color.RED, Color.BLUE, Color.YELLOW),
      ExifInterface.ORIENTATION_ROTATE_180 to listOf(Color.YELLOW, Color.BLUE, Color.RED, Color.GREEN),
      ExifInterface.ORIENTATION_FLIP_VERTICAL to listOf(Color.BLUE, Color.YELLOW, Color.GREEN, Color.RED),
      ExifInterface.ORIENTATION_TRANSPOSE to listOf(Color.RED, Color.BLUE, Color.YELLOW, Color.GREEN),
      ExifInterface.ORIENTATION_ROTATE_90 to listOf(Color.BLUE, Color.RED, Color.GREEN, Color.YELLOW),
      ExifInterface.ORIENTATION_TRANSVERSE to listOf(Color.YELLOW, Color.GREEN, Color.RED, Color.BLUE),
      ExifInterface.ORIENTATION_ROTATE_270 to listOf(Color.GREEN, Color.YELLOW, Color.BLUE, Color.RED),
    )

    expectedCorners.forEach { (orientation, expected) ->
      val file = createFixture(orientation)
      SourceImageLoader.load(Uri.fromFile(file).toString(), maxDimension = 128).use { image ->
        val rotated = orientation >= ExifInterface.ORIENTATION_TRANSPOSE
        assertEquals(if (rotated) 60 else 80, image.uprightWidth)
        assertEquals(if (rotated) 80 else 60, image.uprightHeight)
        assertEquals(orientation, image.exifOrientation)
        assertCorners(image.bitmap, expected, orientation)

        val encodedPoint = ImagePoint(20.0, 15.0)
        val uprightPoint = image.encodedToUpright.encodedToUpright(encodedPoint)
        val roundTrip = image.encodedToUpright.uprightToEncoded(uprightPoint)
        assertEquals(encodedPoint.x, roundTrip.x, 1e-8)
        assertEquals(encodedPoint.y, roundTrip.y, 1e-8)
      }
    }
  }

  @Test
  fun sampledDecodeHonorsRequestedMaximumDimensionAndPreservesAspect() {
    val file = createFixture(ExifInterface.ORIENTATION_NORMAL, width = 640, height = 480)
    SourceImageLoader.load(Uri.fromFile(file).toString(), maxDimension = 160).use { image ->
      assertTrue(image.uprightWidth <= 160)
      assertTrue(image.uprightHeight <= 160)
      assertEquals(image.encodedWidth.toDouble() / image.encodedHeight, image.uprightWidth.toDouble() / image.uprightHeight, 0.01)
    }
  }

  private fun createFixture(
    orientation: Int,
    width: Int = 80,
    height: Int = 60,
  ): File {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val color = when {
          x < width / 2 && y < height / 2 -> Color.RED
          x >= width / 2 && y < height / 2 -> Color.GREEN
          x < width / 2 -> Color.BLUE
          else -> Color.YELLOW
        }
        bitmap.setPixel(x, y, color)
      }
    }
    val file = newFixtureFile()
    file.outputStream().use { output ->
      assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
    }
    bitmap.recycle()

    ExifInterface(file.absolutePath).apply {
      setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
      saveAttributes()
    }
    return file
  }

  private fun newFixtureFile(): File = File.createTempFile("scanner-fixture-", ".jpg", context.cacheDir)
    .also(fixtures::add)

  private fun assertCorners(bitmap: Bitmap, expected: List<Int>, orientation: Int) {
    val samples = listOf(
      bitmap.getPixel(bitmap.width / 4, bitmap.height / 4),
      bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 4),
      bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height * 3 / 4),
      bitmap.getPixel(bitmap.width / 4, bitmap.height * 3 / 4),
    )
    expected.zip(samples).forEachIndexed { index, (expectedColor, actualColor) ->
      assertTrue(
        "Orientation $orientation corner $index expected $expectedColor near $actualColor",
        colorDistance(expectedColor, actualColor) < 140,
      )
    }
  }

  private fun colorDistance(left: Int, right: Int): Int =
    kotlin.math.abs(Color.red(left) - Color.red(right)) +
      kotlin.math.abs(Color.green(left) - Color.green(right)) +
      kotlin.math.abs(Color.blue(left) - Color.blue(right))
}
