package com.margelo.nitro.documentscanner.processing

import android.graphics.Bitmap
import android.net.Uri
import com.margelo.nitro.documentscanner.DocumentQuad
import com.margelo.nitro.documentscanner.PageThumbnails
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * Writes a live analysis frame as an upright JPEG at its own resolution, the page straightened when one is given. The
 * capture is its page's thumbnail until a render, so it is also cached as one.
 */
internal object LatestFrameCapture {
  /** [quad] is normalized in the upright frame; null keeps the whole frame. */
  fun write(frame: LiveFrame, quad: DocumentQuad?, file: File) {
    OpenCvRuntime.ensureLoaded()
    val mats = mutableListOf<Mat>()
    fun mat() = Mat().also(mats::add)
    try {
      val rgba = Mat(frame.height, frame.width, CvType.CV_8UC4).also(mats::add)
      rgba.put(0, 0, frame.pixels)
      val upright = when (frame.rotation) {
        90 -> mat().also { Core.rotate(rgba, it, Core.ROTATE_90_CLOCKWISE) }
        180 -> mat().also { Core.rotate(rgba, it, Core.ROTATE_180) }
        270 -> mat().also { Core.rotate(rgba, it, Core.ROTATE_90_COUNTERCLOCKWISE) }
        else -> rgba
      }
      val page = if (quad == null) upright else straightened(upright, quad, ::mat)
      val bgr = mat()
      Imgproc.cvtColor(page, bgr, Imgproc.COLOR_RGBA2BGR)
      val parameters = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, QUALITY).also(mats::add)
      check(Imgcodecs.imwrite(file.absolutePath, bgr, parameters)) { "The latest frame could not be encoded." }
      val bitmap = Bitmap.createBitmap(page.cols(), page.rows(), Bitmap.Config.ARGB_8888)
      Utils.matToBitmap(page, bitmap)
      PageThumbnails.put(Uri.fromFile(file).toString(), bitmap)
    } finally {
      mats.forEach(Mat::release)
    }
  }

  private fun straightened(upright: Mat, quad: DocumentQuad, mat: () -> Mat): Mat {
    val corners = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
      .map { Point(it.x * upright.cols(), it.y * upright.rows()) }
    fun side(a: Int, b: Int) = hypot(corners[a].x - corners[b].x, corners[a].y - corners[b].y)
    val width = max(1, ((side(0, 1) + side(3, 2)) / 2).roundToInt())
    val height = max(1, ((side(0, 3) + side(1, 2)) / 2).roundToInt())
    val source = MatOfPoint2f(*corners.toTypedArray())
    val target = MatOfPoint2f(
      Point(0.0, 0.0), Point(width.toDouble(), 0.0), Point(width.toDouble(), height.toDouble()), Point(0.0, height.toDouble()),
    )
    val homography = Imgproc.getPerspectiveTransform(source, target)
    try {
      return mat().also { Imgproc.warpPerspective(upright, it, homography, Size(width.toDouble(), height.toDouble())) }
    } finally {
      source.release()
      target.release()
      homography.release()
    }
  }

  private const val QUALITY = 85
}
