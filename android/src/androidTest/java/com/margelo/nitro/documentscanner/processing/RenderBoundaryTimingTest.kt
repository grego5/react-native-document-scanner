package com.margelo.nitro.documentscanner.processing

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.soloader.SoLoader
import com.margelo.nitro.JNIOnLoad
import com.margelo.nitro.documentscanner.DocumentFilter
import com.margelo.nitro.documentscanner.assumeMeasuring
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.OutputStream

/** Opt-in, camera-free release measurements. Includes JNI, allocation and disposal, excludes fixture setup. */
@RunWith(AndroidJUnit4::class)
class RenderBoundaryTimingTest {
  @Test fun measureStages() {
    assumeMeasuring()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    SoLoader.init(context, false)
    JNIOnLoad.initializeNativeNitro()
    OpenCvRuntime.ensureLoaded()
    val report = StringBuilder("# device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}\n")
      .append("side\tfilter\tshadows\titeration\tpath\twarpMs\tfilterMs\ttotalMs\n")
    val quad = doubleArrayOf(.05, .05, .95, .05, .95, .95, .05, .95)
    for (side in listOf(768, 2048, 4096)) {
      val source = fixture(side)
      try {
        for ((filter, shadows) in listOf(DocumentFilter.NONE to false, DocumentFilter.NONE to true,
          DocumentFilter.ENHANCE to true, DocumentFilter.BLACKWHITE to true)) {
          FastCores.run {
            repeat(9) { iteration ->
              // Alternate order within each pair to reduce order/thermal bias.
              for (combined in if (iteration % 2 == 0) listOf(false, true) else listOf(true, false)) {
                val into = Mat()
                val path = if (combined) "combined" else "split"
                val start = System.nanoTime()
                var geometryEnd = start
                try {
                  if (combined) {
                    NativeRenderGeometry.render(source, into, quad, 1.0, null, true, filter, shadows)
                  } else {
                    NativeRenderGeometry.render(source, into, quad, 1.0, null, true)
                    geometryEnd = System.nanoTime()
                    val filtered = DocumentFilters.apply(into, filter, shadows)
                    filtered.release()
                  }
                } finally { into.release() }
                val end = System.nanoTime()
                if (iteration >= 2) report.append("$side\t$filter\t$shadows\t${iteration - 2}\t$path\t${(geometryEnd-start)/1e6}\t${(end-geometryEnd)/1e6}\t${(end-start)/1e6}\n")
              }
            }
          }
        }
      } finally { source.release() }
    }
    val file = File(context.getExternalFilesDir(null), "render-boundary.tsv")
    file.writeText(report.toString())
    Log.i("RenderBoundaryTiming", "Report: ${file.absolutePath}")
  }

  @Test fun measurePngBoundary() {
    assumeMeasuring()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    SoLoader.init(context, false)
    JNIOnLoad.initializeNativeNitro()
    OpenCvRuntime.ensureLoaded()
    val report = StringBuilder("# device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}\nside\titeration\tpath\tms\tbytes\n")
    for (side in listOf(768, 2048, 4096)) {
      val rgba = fixture(side)
      val gray = Mat()
      try {
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        FastCores.run {
          repeat(11) { iteration ->
            for (direct in if (iteration % 2 == 0) listOf(false, true) else listOf(true, false)) {
              var count = 0L
              val output = object : OutputStream() {
                override fun write(value: Int) { count++ }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { count += length }
              }
              val start = System.nanoTime()
              if (direct) GrayscalePng.write4(output, gray)
              else GrayscalePng.write4(output, gray.cols(), gray.rows()) { y, row -> gray.get(y, 0, row) }
              val elapsed = (System.nanoTime() - start) / 1e6
              if (iteration >= 2) report.append("$side\t${iteration-2}\t${if(direct) "direct" else "rows"}\t$elapsed\t$count\n")
            }
          }
        }
      } finally { rgba.release(); gray.release() }
    }
    val file = File(context.getExternalFilesDir(null), "png-boundary.tsv")
    file.writeText(report.toString())
    Log.i("RenderBoundaryTiming", "Report: ${file.absolutePath}")
  }

  private fun fixture(side: Int): Mat {
    val width = side * 3 / 4
    val source = Mat(side, width, CvType.CV_8UC4)
    val row = ByteArray(width * 4)
    for (y in 0 until side) {
      for (x in 0 until width) {
        val light = 245 - 75 * x / width
        val ink = y % maxOf(16, side / 35) < maxOf(2, side / 400) && x > width / 12 && x < width * 11 / 12
        val level = if (ink) light / 4 else light
        row[x*4] = level.toByte()
        row[x*4+1] = (if (x in width/4..width/2 && y in side/3..side/2) level*4/5 else level).toByte()
        row[x*4+2] = level.toByte()
        row[x*4+3] = 255.toByte()
      }
      source.put(y, 0, row)
    }
    return source
  }
}
