package com.margelo.nitro.documentscanner.processing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.soloader.SoLoader
import com.margelo.nitro.JNIOnLoad
import com.margelo.nitro.documentscanner.DocumentFilter
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar

@RunWith(AndroidJUnit4::class)
class RenderPipelineTest {
  @Before fun initialize() {
    SoLoader.init(InstrumentationRegistry.getInstrumentation().targetContext, false)
    JNIOnLoad.initializeNativeNitro()
    OpenCvRuntime.ensureLoaded()
  }

  @Test fun combinedStagesPreservePixelsMetadataAndBorrowedSource() {
    for (kind in listOf("flat", "bent", "outside", "scaled", "color")) {
      val source = GeometricRenderTest().fixture(kind)
      if (kind == "color") source.submat(Rect(180, 160, 180, 100)).let {
        try { it.setTo(Scalar(70.0, 150.0, 210.0, 255.0)) } finally { it.release() }
      }
      val before = source.clone()
      val quad = if (kind == "outside") doubleArrayOf(-.02,.125,.875,.125,.875,.875,.125,.875)
        else doubleArrayOf(.125,.125,.875,.125,.875,.875,.125,.875)
      val scale = if (kind == "scaled") 2.0 else 1.0
      val paper = if (kind == "scaled") 720.0 else null
      val warped = Mat()
      try {
        val expectedMetadata = NativeRenderGeometry.render(source, warped, quad, scale, paper, true)
        for (shadows in listOf(false, true)) for (filter in DocumentFilter.entries) {
          val expected = DocumentFilters.apply(warped, filter, shadows)
          val actual = Mat()
          try {
            val actualMetadata = NativeRenderGeometry.render(source, actual, quad, scale, paper, true, filter, shadows)
            assertEquals("$kind metadata", expectedMetadata, actualMetadata)
            assertEquals(expected.type(), actual.type())
            assertEquals(expected.size(), actual.size())
            assertEquals("$kind $filter shadows=$shadows", 0.0, Core.norm(expected, actual, Core.NORM_INF), 0.0)
          } finally { expected.release(); actual.release() }
        }
        assertEquals(0.0, Core.norm(before, source, Core.NORM_INF), 0.0)
      } finally { source.release(); before.release(); warped.release() }
    }
  }
}
