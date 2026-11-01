package com.margelo.nitro.documentscanner.processing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.soloader.SoLoader
import com.margelo.nitro.JNIOnLoad
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

@RunWith(AndroidJUnit4::class)
class NativeGrayMatTest {
  @Before fun initialize() {
    SoLoader.init(InstrumentationRegistry.getInstrumentation().targetContext, false)
    JNIOnLoad.initializeNativeNitro()
    OpenCvRuntime.ensureLoaded()
  }

  @Test fun paddedOddWidthAndCompressedStreamsMatchRowReaderExactly() {
    val parent = Mat(43, 79, CvType.CV_8UC1)
    parent.put(0, 0, ByteArray(43*79) { (it*37).toByte() })
    val source = parent.submat(Rect(3, 2, 71, 37))
    val before = source.clone()
    try {
      for (compressed in listOf(false, true)) {
        val expected = ByteArrayOutputStream()
        NativeGrayscalePng.write(expected, source.cols(), source.rows(), compressed) { y, row -> source.get(y, 0, row) }
        val actual = ByteArrayOutputStream()
        NativeGrayscalePng.writeMatNative(actual, source.nativeObj, compressed)
        assertArrayEquals(expected.toByteArray(), actual.toByteArray())
      }
      assertEquals(0.0, Core.norm(source, before, Core.NORM_INF), 0.0)
    } finally { source.release(); parent.release(); before.release() }
  }

  @Test fun sinkFailureUnwindsWithoutClosingAndNextWriteSucceeds() {
    val source = Mat.ones(16, 17, CvType.CV_8UC1)
    var closed = false
    val sink = object : OutputStream() {
      override fun write(value: Int) { throw IOException("expected sink failure") }
      override fun write(bytes: ByteArray, offset: Int, count: Int) { throw IOException("expected sink failure") }
      override fun close() { closed = true }
    }
    try {
      assertThrows(IOException::class.java) { GrayscalePng.write4(sink, source) }
      assertFalse(closed)
      val output = ByteArrayOutputStream()
      GrayscalePng.write4(output, source)
      assertTrue(output.size() > 0)
    } finally { source.release() }
  }

  @Test fun invalidMatricesFailBeforeWriting() {
    for (source in listOf(Mat(), Mat.ones(4, 4, CvType.CV_8UC4))) {
      val output = ByteArrayOutputStream()
      try {
        assertThrows(IllegalArgumentException::class.java) { GrayscalePng.write4(output, source) }
        assertEquals(0, output.size())
      } finally { source.release() }
    }
  }
}
