package com.margelo.nitro.documentscanner.processing

import android.graphics.Bitmap
import com.margelo.nitro.documentscanner.DocumentFilter
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** Android bitmap/Mat ownership adapter; algorithms live in the shared core. */
internal object DocumentFilters {
  /** New caller-owned BGR or gray Mat; input is borrowed and unchanged. */
  fun apply(rgba: Mat, filter: DocumentFilter, removeShadows: Boolean = false): Mat {
    val result = Mat()
    try {
      NativeDocumentFilters.apply(rgba, result, filter, removeShadows)
      return result
    } catch (error: Throwable) {
      result.release()
      throw error
    }
  }

  /** Returns [source] filtered as a new ARGB_8888 bitmap of the same size; [source] is only read. */
  fun apply(source: Bitmap, filter: DocumentFilter): Bitmap {
    OpenCvRuntime.ensureLoaded()
    val rgba = Mat()
    var filtered: Mat? = null
    try {
      Utils.bitmapToMat(source, rgba)
      filtered = apply(rgba, filter)
      val code = if (filtered.channels() == 1) Imgproc.COLOR_GRAY2RGBA else Imgproc.COLOR_BGR2RGBA
      Imgproc.cvtColor(filtered, rgba, code)
      return Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(rgba, it) }
    } finally {
      rgba.release()
      filtered?.release()
    }
  }

}
