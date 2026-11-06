package com.margelo.nitro.documentscanner.processing

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GrayscalePngRuntimeTest {
  @Test fun quantizedPixelsDecodeThroughAndroidAndStreamsRemainCallerOwned() {
    for ((width, height) in listOf(33 to 3, 1 to 1, 16 to 2, 257 to 5)) {
      val output = TrackingStream()
      GrayscalePng.write4(output, width, height) { y, row ->
        for (x in row.indices) row[x] = ((13 * x + 7 * y) % 256).toByte()
      }
      assertFalse(output.closed)
      val bytes = output.bytes.toByteArray()
      assertEquals(4, bytes[24].toInt())
      assertEquals(0, bytes[25].toInt())
      val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
      try {
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        for (y in 0 until height) for (x in 0 until width) {
          assertEquals((((13 * x + 7 * y) % 256 + 8) / 17) * 17, Color.red(decoded.getPixel(x, y)))
        }
      } finally { decoded.recycle() }
    }
  }

  @Test fun sinkAndRowExceptionsStopNativeWriterAndPreserveTheirIdentity() {
    val error = IOException("test sink failure")
    var calls = 0
    var rows = 0
    val sink = object : OutputStream() {
      override fun write(value: Int) { throw AssertionError("Expected bulk writes") }
      override fun write(bytes: ByteArray, offset: Int, length: Int) { calls++; throw error }
    }
    for (compressed in listOf(false, true)) {
      calls = 0; rows = 0
      try {
        if (compressed) GrayscalePng.writeCompressed4(sink, 33, 1000) { _, row -> rows++; row.fill(127) }
        else GrayscalePng.write4(sink, 33, 1000) { _, row -> rows++; row.fill(127) }
        fail("Sink exception was swallowed")
      } catch (actual: IOException) { assertSame(error, actual) }
      assertEquals(1, calls)
      assertTrue(rows < 1000)
    }
    val rowError = IllegalStateException("test row failure")
    val output = TrackingStream()
    rows = 0
    try {
      GrayscalePng.write4(output, 33, 10) { y, row ->
        rows++
        if (y == 2) throw rowError
        row.fill(127)
      }
      fail("Row exception was swallowed")
    } catch (actual: IllegalStateException) { assertSame(rowError, actual) }
    assertEquals(3, rows)
    assertFalse(output.closed)
    // A failed stream is partial; caller decides whether to delete/publish it.
    assertTrue(output.bytes.size() > 0)
    val recovered = TrackingStream()
    GrayscalePng.write4(recovered, 1, 1) { _, row -> row[0] = 0 }
    assertNotNull(BitmapFactory.decodeByteArray(recovered.bytes.toByteArray(), 0, recovered.bytes.size()).also { it?.recycle() })
  }

  @Test fun invalidDimensionsDoNotWriteOrRead() {
    for ((width, height) in listOf(0 to 1, 1 to 0, -1 to 2)) {
      val output = TrackingStream()
      try {
        GrayscalePng.write4(output, width, height) { _, _ -> fail("Read for invalid dimensions") }
        fail("Invalid dimensions accepted")
      } catch (_: IllegalArgumentException) { }
      assertEquals(0, output.bytes.size())
      assertFalse(output.closed)
    }
  }

  private class TrackingStream : OutputStream() {
    val bytes = ByteArrayOutputStream()
    var closed = false
    override fun write(value: Int) = bytes.write(value)
    override fun write(value: ByteArray, offset: Int, length: Int) = bytes.write(value, offset, length)
    override fun close() { closed = true }
  }
}
