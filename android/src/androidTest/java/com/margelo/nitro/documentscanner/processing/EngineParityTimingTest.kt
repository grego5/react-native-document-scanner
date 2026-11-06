package com.margelo.nitro.documentscanner.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.soloader.SoLoader
import com.margelo.nitro.JNIOnLoad
import com.margelo.nitro.documentscanner.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import org.opencv.android.Utils
import org.opencv.core.Mat

/** This same source also runs in the isolated pre-migration checkout. No camera or model is opened. */
@RunWith(AndroidJUnit4::class)
class EngineParityTimingTest {
  @Test fun exportUnencodedFilters() {
    assumeMeasuring()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    SoLoader.init(context, false)
    JNIOnLoad.initializeNativeNitro()
    OpenCvRuntime.ensureLoaded()
    val folder = File(context.getExternalFilesDir(null), "engine-parity").apply { mkdirs() }
    val bitmap = fixture(2048)
    val source = Mat()
    try {
      Utils.bitmapToMat(bitmap, source)
      bitmap.recycle()
      for (filter in DocumentFilter.entries) {
        val working = source.clone()
        try {
          FastCores.run {
            ShadowRemoval.apply(working)
            val output = DocumentFilters.apply(working, filter)
            try {
              GZIPOutputStream(File(folder, "unencoded-$filter.raw.gz").outputStream()).use { stream ->
                val row = ByteArray(output.cols()*output.channels())
                for (y in 0 until output.rows()) { output.get(y, 0, row); stream.write(row) }
              }
            } finally { output.release() }
          }
        } finally { working.release() }
      }
    } finally { bitmap.recycle(); source.release() }
  }

  @Test fun measureProductionRenderAndPng() {
    assumeMeasuring()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    SoLoader.init(context, false)
    JNIOnLoad.initializeNativeNitro()
    OpenCvRuntime.ensureLoaded()
    val folder = File(context.getExternalFilesDir(null), "engine-parity").apply { mkdirs() }
    val report = StringBuilder("# device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}\n")
      .append("stage\tside\tfilter\tshadows\titeration\tms\twidth\theight\tbytes\tpixelCrc\n")
    val quad = DocumentQuad(DocumentPoint(.05,.05), DocumentPoint(.95,.05), DocumentPoint(.95,.95), DocumentPoint(.05,.95))
    for (side in listOf(2048, 4096)) {
      val source = fixture(side)
      val file = File(context.cacheDir, "engine-parity-$side.jpg")
      try {
        file.outputStream().use { check(source.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        source.recycle()
        for ((filter, shadows) in listOf(DocumentFilter.NONE to false, DocumentFilter.NONE to true,
          DocumentFilter.ENHANCE to true, DocumentFilter.GRAYSCALE to true, DocumentFilter.BLACKWHITE to true)) {
          val format = if (filter == DocumentFilter.GRAYSCALE || filter == DocumentFilter.BLACKWHITE) DocumentImageFormat.PNG
            else DocumentImageFormat.JPEG
          val options = DocumentRenderOptions(shadows, null, filter, format, null, null, true)
          FastCores.run {
            // Sustained work, not a fixed number of cold calls, warms workers and frequency scaling.
            val warmUntil = System.nanoTime() + 2_000_000_000L
            do {
              val warm = DocumentRenderer.render(Uri.fromFile(file).toString(), quad, options)
              PageThumbnails.forget(listOf(warm.outputUri))
              File(Uri.parse(warm.outputUri).path!!).delete()
              File(Uri.parse(warm.thumbnailUri).path!!).delete()
            } while (System.nanoTime() < warmUntil)
            repeat(11) { iteration ->
              val start = System.nanoTime()
              val rendered = DocumentRenderer.render(Uri.fromFile(file).toString(), quad, options)
              val ms = (System.nanoTime() - start) / 1e6
              val output = File(Uri.parse(rendered.outputUri).path!!)
              try {
                report.append("render\t$side\t$filter\t$shadows\t$iteration\t$ms\t${rendered.width}\t${rendered.height}\t${output.length()}\t\n")
                if (iteration == 10) {
                  output.copyTo(File(folder, "$side-$filter-$shadows.${output.extension}"), overwrite = true)
                  val bitmap = BitmapFactory.decodeFile(output.absolutePath)
                  try { report.append("pixels\t$side\t$filter\t$shadows\t0\t0\t${bitmap.width}\t${bitmap.height}\t${output.length()}\t${pixelCrc(bitmap)}\n") }
                  finally { bitmap.recycle() }
                }
              } finally {
                PageThumbnails.forget(listOf(rendered.outputUri))
                output.delete()
                File(Uri.parse(rendered.thumbnailUri).path!!).delete()
              }
            }
          }
        }
      } finally { source.recycle(); file.delete() }

      // Exact same caller/row contract in both worktrees, including native Mat.get on Android.
      val gray = org.opencv.core.Mat(side, side*3/4, org.opencv.core.CvType.CV_8UC1)
      val row = ByteArray(gray.cols())
      try {
        for (y in 0 until side) {
          for (x in row.indices) row[x] = (if (y % 40 < 3 && x in 30 until row.size-30) 40 else 245-75*x/row.size).toByte()
          gray.put(y, 0, row)
        }
        FastCores.run {
          repeat(9) { iteration ->
            var bytes = 0L
            val sink = object : OutputStream() {
              override fun write(value: Int) { bytes++ }
              override fun write(buffer: ByteArray, offset: Int, count: Int) { bytes += count }
            }
            val start = System.nanoTime()
            GrayscalePng.write4(sink, gray.cols(), gray.rows()) { y, into -> gray.get(y, 0, into) }
            val ms = (System.nanoTime()-start)/1e6
            if (iteration >= 2) report.append("pngRows\t$side\tGRAYSCALE\tfalse\t${iteration-2}\t$ms\t${gray.cols()}\t${gray.rows()}\t$bytes\t\n")
          }
        }
      } finally { gray.release() }
    }
    File(folder, "timings.tsv").writeText(report.toString())
  }

  private fun fixture(side: Int): Bitmap {
    val width = side*3/4
    val bitmap = Bitmap.createBitmap(width, side, Bitmap.Config.ARGB_8888)
    val row = IntArray(width)
    for (y in 0 until side) {
      for (x in row.indices) {
        val light = 245-75*x/width
        val ink = y % maxOf(16, side/35) < maxOf(2, side/400) && x in width/12..width*11/12
        val level = if (ink) light/4 else light
        val green = if (x in width/4..width/2 && y in side/3..side/2) level*4/5 else level
        row[x] = android.graphics.Color.rgb(level, green, level)
      }
      bitmap.setPixels(row, 0, width, 0, y, width, 1)
    }
    return bitmap
  }

  private fun pixelCrc(bitmap: Bitmap): Long {
    val crc = CRC32()
    val row = IntArray(bitmap.width)
    val bytes = ByteArray(bitmap.width*4)
    for (y in 0 until bitmap.height) {
      bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
      for (x in row.indices) for (c in 0..3) bytes[x*4+c] = (row[x] ushr (c*8)).toByte()
      crc.update(bytes)
    }
    return crc.value
  }
}
