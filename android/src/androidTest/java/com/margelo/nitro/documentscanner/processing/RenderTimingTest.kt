package com.margelo.nitro.documentscanner.processing

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.margelo.nitro.documentscanner.DocumentFilter
import com.margelo.nitro.documentscanner.DocumentImageFormat
import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import com.margelo.nitro.documentscanner.DocumentRenderOptions
import com.margelo.nitro.documentscanner.assumeMeasuring
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File

/** Times each render stage on a private photo in the test app's external files folder; skipped without one. */
@RunWith(AndroidJUnit4::class)
class RenderTimingTest {
  @Test fun timeRenderStages() {
    assumeMeasuring()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val photo = File(context.getExternalFilesDir(null), "render-timing.jpg")
    assumeTrue("Push a photo to ${photo.absolutePath}", photo.isFile)
    OpenCvRuntime.ensureLoaded()
    val bitmap = BitmapFactory.decodeFile(photo.absolutePath)
    val source = Mat()
    Utils.bitmapToMat(bitmap, source)
    bitmap.recycle()
    // The example renders at up to 4096 px.
    val scale = 4096.0 / maxOf(source.cols(), source.rows())
    val page = Mat().also { Imgproc.resize(source, it, Size(), scale, scale, Imgproc.INTER_LINEAR) }
    source.release()
    log("page ${page.cols()}x${page.rows()}")

    fun time(label: String, repeat: Int = 3, block: () -> Unit) {
      block()
      val times = List(repeat) { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1e6 }
      log("%-40s %7.1f ms (min of %d)".format(label, times.min(), repeat))
    }

    time("shadow removal") { Mat().also { page.copyTo(it) }.let { copy -> ShadowRemoval.apply(copy); copy.release() } }
    for (filter in DocumentFilter.entries) time("filter $filter") { DocumentFilters.apply(page, filter).release() }
    val gray = Mat().also { Imgproc.cvtColor(page, it, Imgproc.COLOR_RGBA2GRAY) }
    time("ink detail measure") { InkDetail.measure(gray, .6).close() }
    InkDetail.measure(gray, .6).use { detail ->
      val blurred = Mat().also { Imgproc.GaussianBlur(gray, it, Size(0.0, 0.0), 1.0) }
      time("ink detail apply, one channel") { Mat().also { gray.copyTo(it) }.let { c -> detail.apply(listOf(c), listOf(blurred)); c.release() } }
      blurred.release()
    }
    val small = Mat(64, 48, org.opencv.core.CvType.CV_32F, org.opencv.core.Scalar(1.5))
    time("gain kernel") { Mat().also { page.copyTo(it) }.let { c -> FieldKernels.gain(c, small); c.release() } }
    small.release()
    time("gaussian blur, one channel") { Mat().also { Imgproc.GaussianBlur(gray, it, Size(0.0, 0.0), 1.0) }.release() }
    // A real black-and-white page rather than raw luminance: what the example writes.
    val document = DocumentFilters.apply(page, DocumentFilter.BLACKWHITE)
    val png = File(context.cacheDir, "timing.png")
    time("4-bit PNG write", repeat = 2) {
      png.outputStream().buffered().use { out -> GrayscalePng.write4(out, document.cols(), document.rows()) { row, bytes -> document.get(row, 0, bytes) } }
    }
    log("  size ${png.length() / 1024} KB")
    // The black-and-white page as the renderer writes a JPEG: three channels, quality 92.
    val jpeg = File(context.cacheDir, "timing-bw.jpg")
    val params = org.opencv.core.MatOfInt(org.opencv.imgcodecs.Imgcodecs.IMWRITE_JPEG_QUALITY, 92)
    time("black-and-white JPEG write, q92", repeat = 2) {
      val bgr = Mat().also { Imgproc.cvtColor(document, it, Imgproc.COLOR_GRAY2BGR) }
      org.opencv.imgcodecs.Imgcodecs.imwrite(jpeg.absolutePath, bgr, params)
      bgr.release()
    }
    log("  size ${jpeg.length() / 1024} KB")
    jpeg.delete(); params.release()
    // The same page at the example's former 2400 px and at A4 300 dpi.
    for (side in listOf(3508, 2400)) {
      val reduced = Mat().also { Imgproc.resize(document, it, Size(), side.toDouble() / document.rows(), side.toDouble() / document.rows(), Imgproc.INTER_AREA) }
      time("4-bit PNG write at ${reduced.cols()}x${reduced.rows()}", repeat = 2) {
        png.outputStream().buffered().use { out -> GrayscalePng.write4(out, reduced.cols(), reduced.rows()) { row, bytes -> reduced.get(row, 0, bytes) } }
      }
      log("  size ${png.length() / 1024} KB")
      val small = Mat().also { Imgproc.resize(page, it, Size(), side.toDouble() / page.rows(), side.toDouble() / page.rows(), Imgproc.INTER_AREA) }
      time("filter BLACKWHITE at ${small.cols()}x${small.rows()}") { DocumentFilters.apply(small, DocumentFilter.BLACKWHITE).release() }
      small.release(); reduced.release()
    }
    png.delete()
    document.release()
    time("JPEG write, three channels", repeat = 2) {
      val bgr = Mat().also { Imgproc.cvtColor(page, it, Imgproc.COLOR_RGBA2BGR) }
      val file = File(context.cacheDir, "timing.jpg")
      org.opencv.imgcodecs.Imgcodecs.imwrite(file.absolutePath, bgr)
      bgr.release(); file.delete()
    }
    gray.release()
    page.release()

    // The whole render as the example calls it, from a copy in the cache folder.
    val cached = File(context.cacheDir, "render-timing.jpg").also { photo.copyTo(it, overwrite = true) }
    val quad = DocumentQuad(DocumentPoint(0.0, 0.0), DocumentPoint(1.0, 0.0), DocumentPoint(1.0, 1.0), DocumentPoint(0.0, 1.0))
    for (filter in DocumentFilter.entries) {
      val format = if (filter == DocumentFilter.BLACKWHITE) DocumentImageFormat.PNG else DocumentImageFormat.JPEG
      time("render $filter as $format, A4 200 dpi", repeat = 2) {
        val rendered = DocumentRenderer.render(Uri.fromFile(cached).toString(), quad, DocumentRenderOptions(true, null, filter, format, com.margelo.nitro.documentscanner.PaperSize(21.0, 29.7), 200.0, null))
        File(Uri.parse(rendered.outputUri).path!!).delete()
      }
    }
    cached.delete()
  }

  private fun log(message: String) = Log.i("RenderTiming", message)
}
