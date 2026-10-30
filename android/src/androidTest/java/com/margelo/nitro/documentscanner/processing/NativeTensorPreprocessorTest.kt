package com.margelo.nitro.documentscanner.processing

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.soloader.SoLoader
import com.margelo.nitro.JNIOnLoad
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class NativeTensorPreprocessorTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  @Before
  fun initializeRuntime() {
    SoLoader.init(instrumentation.targetContext, false)
    JNIOnLoad.initializeNativeNitro()
  }

  @Test
  fun nativeTensorsMatchKotlinAcrossSizesDimensionChangesAndAllChannelValues() {
    val resampler = BilinearRgbResampler()
    NativeTensorPreprocessor().use { native ->
      val input = buffer()
      // A/B/A validates kernel invalidation; narrow widths exercise bitmap row padding.
      for ((width, height) in listOf(7 to 5, 3 to 2, 15 to 9, 224 to 224, 641 to 479, 7 to 5)) {
        val bitmap = patternedBitmap(width, height)
        try {
          val expected = kotlinTensor(bitmap, resampler)
          repeat(2) {
            native.prepare(bitmap, input)
            assertTensorEquals(expected, input, "${width}x$height")
          }
          assertFalse(bitmap.isRecycled)
        } finally { bitmap.recycle() }
      }
      // Constant colors produce every possible 8-bit channel value after both passes.
      val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
      try {
        for (value in 0..255) {
          bitmap.eraseColor(Color.rgb(value, value, value))
          native.prepare(bitmap, input)
          assertTensorEquals(kotlinTensor(bitmap, resampler), input, "channel=$value")
        }
      } finally { bitmap.recycle() }
    }
  }

  @Test
  fun dynamicSquareAndRectangularTensorsMatchKotlinAcrossSourceChanges() {
    for ((width, height) in listOf(1 to 1, 320 to 320, 320 to 192, 192 to 320)) {
      NativeTensorPreprocessor(width, height).use { native ->
        val bytes = width * height * 3 * 4
        val actual = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        val expected = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        val pixels = IntArray(width * height)
        val resampler = BilinearRgbResampler()
        for ((sourceWidth, sourceHeight) in listOf(7 to 5, 641 to 479, 7 to 5)) {
          val bitmap = patternedBitmap(sourceWidth, sourceHeight)
          try {
            fillKotlin(bitmap, resampler, pixels, expected, width, height)
            native.prepare(bitmap, actual)
            assertTensorEquals(expected, actual, "${sourceWidth}x$sourceHeight -> ${width}x$height")
          } finally { bitmap.recycle() }
        }
      }
    }
  }

  @Test
  fun rejectedInputsUnlockPixelsAndCloseIsIdempotent() {
    val native = NativeTensorPreprocessor()
    val bitmap = patternedBitmap(7, 5)
    try {
      assertThrows(IllegalArgumentException::class.java) { native.prepare(bitmap, ByteBuffer.allocate(NativeTensorPreprocessor.INPUT_BYTES)) }
      assertThrows(IllegalArgumentException::class.java) { native.prepare(bitmap, buffer().asReadOnlyBuffer()) }
      assertThrows(IllegalArgumentException::class.java) { native.prepare(bitmap, buffer().apply { limit(4) }) }
      assertThrows(IllegalArgumentException::class.java) { native.prepare(bitmap, buffer().apply { position(4) }) }
      val unaligned = ByteBuffer.allocateDirect(NativeTensorPreprocessor.INPUT_BYTES + 1)
        .apply { position(1) }.slice().order(ByteOrder.nativeOrder())
      assertThrows(RuntimeException::class.java) { native.prepare(bitmap, unaligned) }
      val unsupported = Bitmap.createBitmap(7, 5, Bitmap.Config.RGB_565)
      try { assertThrows(RuntimeException::class.java) { native.prepare(unsupported, buffer()) } }
      finally { unsupported.recycle() }
      bitmap.eraseColor(Color.TRANSPARENT)
      assertThrows(RuntimeException::class.java) { native.prepare(bitmap, buffer()) }
      bitmap.eraseColor(Color.WHITE) // Filtering exception must have released the pixel lock.
      native.prepare(bitmap, buffer())
      native.close()
      native.close()
      assertThrows(IllegalStateException::class.java) { native.prepare(bitmap, buffer()) }
      assertFalse(bitmap.isRecycled)
    } finally { native.close(); bitmap.recycle() }
  }

  private fun buffer() = ByteBuffer.allocateDirect(NativeTensorPreprocessor.INPUT_BYTES).order(ByteOrder.nativeOrder())
  private fun patternedBitmap(width: Int, height: Int): Bitmap {
    val pixels = IntArray(width * height) { i ->
      val x = i % width; val y = i / width
      Color.rgb((x * 17 + y * 29) and 255, (x * 41 + y * 7) and 255, (x * 3 + y * 61) and 255)
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
      setPixels(pixels, 0, width, 0, 0, width, height)
    }
  }
  private fun kotlinTensor(bitmap: Bitmap, resampler: BilinearRgbResampler) = buffer().also {
    fillKotlin(bitmap, resampler, IntArray(NativeTensorPreprocessor.INPUT_SIZE * NativeTensorPreprocessor.INPUT_SIZE), it)
  }
  private fun fillKotlin(bitmap: Bitmap, resampler: BilinearRgbResampler, pixels: IntArray, input: ByteBuffer,
    width: Int = NativeTensorPreprocessor.INPUT_SIZE, height: Int = NativeTensorPreprocessor.INPUT_SIZE) {
    resampler.resize(bitmap.width, bitmap.height, width, height, pixels) { y, row -> bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1) }
    input.clear()
    for (pixel in pixels) {
      input.putFloat((((pixel shr 16) and 255) / 255f - .485f) / .229f)
      input.putFloat((((pixel shr 8) and 255) / 255f - .456f) / .224f)
      input.putFloat(((pixel and 255) / 255f - .406f) / .225f)
    }
    input.rewind()
  }
  private fun assertTensorEquals(expected: ByteBuffer, actual: ByteBuffer, message: String) {
    assertEquals(expected.capacity(), actual.capacity())
    for (offset in 0 until expected.capacity() step 4) {
      val expectedBits = expected.getInt(offset)
      val actualBits = actual.getInt(offset)
      if (expectedBits != actualBits) assertEquals("$message float ${offset / 4}", expectedBits, actualBits)
    }
  }
}
