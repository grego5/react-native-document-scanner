package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.requireInput
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.margelo.nitro.documentscanner.DocumentFilter
import com.margelo.nitro.documentscanner.DocumentImageFormat
import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad as NitroDocumentQuad
import com.margelo.nitro.documentscanner.DocumentRenderOptions
import com.margelo.nitro.documentscanner.PageThumbnails
import com.margelo.nitro.documentscanner.jpegQualityPercent
import com.margelo.nitro.documentscanner.RenderedDocument
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * Rectifies the upright original so the submitted quad's corners land exactly on the output corners, and bends each
 * side onto the page's own edge where the page curves ([PageEdges]).
 */
internal object DocumentRenderer {
  fun render(
    originalUri: String,
    quad: NitroDocumentQuad,
    options: DocumentRenderOptions?,
  ): RenderedDocument {
    val config = validateOptions(options)
    val uri = Uri.parse(originalUri)
    requireInput(uri.scheme == "file" && !uri.path.isNullOrBlank()) {
      "Unsupported image URI; expected a local file:// JPEG."
    }
    val sourceFile = File(uri.path!!)
    if (!sourceFile.isFile || !sourceFile.canRead()) throw IOException("Captured image file was not found or is not readable.")
    val outputDirectory = outputDirectoryFor(sourceFile)
    val extension = if (config.format == DocumentImageFormat.PNG) "png" else "jpg"
    val destination = File(outputDirectory, "corrected-${System.currentTimeMillis()}-${UUID.randomUUID()}.$extension")
    val temporary = File(outputDirectory, ".${destination.name}.tmp.$extension")
    val thumbnail = File(outputDirectory, "thumbnail-${destination.nameWithoutExtension}.jpg")
    val thumbnailTemporary = File(outputDirectory, ".${thumbnail.name}.tmp.jpg")
    val sourcePoints = normalizedQuad(quad)
    validateQuad(sourcePoints)

    try {
      // The largest decode, so originals up to MAX_SOURCE_DIMENSION keep every pixel the output may use.
      SourceImageLoader.load(originalUri, MAX_SOURCE_DIMENSION).use { upright ->
        OpenCvRuntime.ensureLoaded()
        val sourceBitmap = upright.bitmap
        val sourceMat = Mat()
        val warped = Mat()
        val bgr = Mat()
        val sourceMask = Mat(sourceBitmap.height, sourceBitmap.width, CvType.CV_8UC1, Scalar(255.0))
        val warpedMask = Mat()
        val sourceQuad = MatOfPoint2f()
        val destinationQuad = MatOfPoint2f()
        val homography = Mat()
        val inverse = Mat()
        val parameters = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, config.quality)
        try {
          Utils.bitmapToMat(sourceBitmap, sourceMat)
          val pixelQuad = sourcePoints.points().map { ImagePoint(it.x * sourceBitmap.width, it.y * sourceBitmap.height) }
          // Pixels in the original per pixel of the sampled bitmap, so the output is sized from the original.
          val originalScale = max(upright.encodedWidth, upright.encodedHeight).toDouble() / max(sourceBitmap.width, sourceBitmap.height)
          val target = targetCanvas(pixelQuad, originalScale, config)
          sourceQuad.fromArray(*pixelQuad.map { Point(it.x, it.y) }.toTypedArray())
          destinationQuad.fromArray(*target.destination.map { Point(it.x, it.y) }.toTypedArray())
          val calculatedHomography = Imgproc.getPerspectiveTransform(sourceQuad, destinationQuad)
          try {
            calculatedHomography.copyTo(homography)
          } finally {
            calculatedHomography.release()
          }
          val inverseStatus = Core.invert(homography, inverse)
          check(inverseStatus > 0.0 && Core.determinant(homography).isFinite()) {
            "Document geometry produced an unstable perspective transform."
          }

          val outputSize = Size(target.width.toDouble(), target.height.toDouble())
          // Only with every corner in the photo: coverage below comes from the straight warp, which a bend would
          // move away from where the photo's border lands.
          val cornersInside = pixelQuad.all { it.x in 0.0..sourceBitmap.width.toDouble() && it.y in 0.0..sourceBitmap.height.toDouble() }
          val edges = if (config.followPageEdges && cornersInside) {
            PageEdges.find(sourceMat, homography, target.width, target.height)
          } else null
          if (edges != null) edges.remap(sourceMat, inverse, warped) else Imgproc.warpPerspective(
            sourceMat,
            warped,
            homography,
            outputSize,
            Imgproc.INTER_LINEAR,
            Core.BORDER_CONSTANT,
            Scalar(248.0, 248.0, 248.0, 255.0),
          )
          Imgproc.warpPerspective(
            sourceMask,
            warpedMask,
            homography,
            outputSize,
            Imgproc.INTER_NEAREST,
            Core.BORDER_CONSTANT,
            Scalar(0.0),
          )
          check(Core.countNonZero(warpedMask) > 0) { "The selected document contains no captured image pixels." }
          // Shadows go first, so the filter's own paper estimate sees an evenly lit page.
          if (config.removeShadows) ShadowRemoval.apply(warped)
          val filtered = DocumentFilters.apply(warped, config.filter)
          val thumbnailBitmap: Bitmap
          try {
            if (config.format == DocumentImageFormat.PNG) {
              FileOutputStream(temporary).use { output ->
                GrayscalePng.write4(output, filtered.cols(), filtered.rows()) { row, bytes -> filtered.get(row, 0, bytes) }
              }
            } else {
              // A JPEG stays three-channel, so other PDF writers can also embed it unchanged.
              val encoded = if (filtered.channels() == 1) {
                Imgproc.cvtColor(filtered, bgr, Imgproc.COLOR_GRAY2BGR)
                bgr
              } else filtered
              check(Imgcodecs.imwrite(temporary.absolutePath, encoded, parameters)) { "Corrected image could not be encoded." }
            }
            thumbnailBitmap = writeThumbnail(filtered, PageThumbnails.longSide, thumbnailTemporary)
          } finally {
            filtered.release()
          }
          if (config.format == DocumentImageFormat.JPEG) ExifInterface(temporary.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            saveAttributes()
          }
          FileOutputStream(temporary, true).use { it.fd.sync() }
          moveAtomically(temporary, destination)
          moveAtomically(thumbnailTemporary, thumbnail)

          val clipped = clipImageToQuad(pixelQuad, sourceBitmap.width, sourceBitmap.height)
          check(clipped.size >= 3) { "The selected document does not overlap the captured image." }
          // Captured edges project onto the output edges; clamp floating-point overshoot.
          val validPoints = projectPoints(clipped, homography).map { point ->
            DocumentPoint((point.x / target.width).coerceIn(0.0, 1.0), (point.y / target.height).coerceIn(0.0, 1.0))
          }
          val outputUri = Uri.fromFile(destination).toString()
          // Cached before resolving, so a pager shows the new page and its thumbnail together.
          PageThumbnails.put(outputUri, thumbnailBitmap, thumbnail)
          return RenderedDocument(
            originalUri = originalUri,
            outputUri = outputUri,
            thumbnailUri = Uri.fromFile(thumbnail).toString(),
            width = target.width.toDouble(),
            height = target.height.toDouble(),
            validContentPolygon = validPoints.toTypedArray(),
          )
        } finally {
          sourceMat.release()
          warped.release()
          bgr.release()
          sourceMask.release()
          warpedMask.release()
          sourceQuad.release()
          destinationQuad.release()
          homography.release()
          inverse.release()
          parameters.release()
        }
      }
    } catch (error: Throwable) {
      temporary.delete()
      destination.delete()
      thumbnailTemporary.delete()
      thumbnail.delete()
      throw error
    }
  }

  /**
   * Writes [page], BGR or gray, reduced to at most [longSide] pixels on its longer side, as a JPEG to [file], and
   * returns the same pixels as a bitmap.
   */
  private fun writeThumbnail(page: Mat, longSide: Int, file: File): Bitmap {
    val scale = min(1.0, longSide.toDouble() / max(page.cols(), page.rows()))
    val reduced = Mat()
    val rgba = Mat()
    val parameters = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, THUMBNAIL_QUALITY)
    try {
      Imgproc.resize(page, reduced, Size(), scale, scale, Imgproc.INTER_AREA)
      check(Imgcodecs.imwrite(file.absolutePath, reduced, parameters)) { "Thumbnail could not be encoded." }
      Imgproc.cvtColor(reduced, rgba, if (reduced.channels() == 1) Imgproc.COLOR_GRAY2RGBA else Imgproc.COLOR_BGR2RGBA)
      return Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(rgba, it) }
    } finally {
      reduced.release()
      rgba.release()
      parameters.release()
    }
  }

  private data class RenderConfig(
    val removeShadows: Boolean,
    val quality: Int,
    /** The page's longer side in pixels from paper size and dpi, or null for the page's own size. */
    val paperLongEdge: Double?,
    val filter: DocumentFilter,
    val format: DocumentImageFormat,
    val followPageEdges: Boolean,
  )

  private data class TargetCanvas(val width: Int, val height: Int, val destination: List<ImagePoint>)

  private fun validateOptions(options: DocumentRenderOptions?): RenderConfig {
    val paper = options?.paperSize
    requireInput(paper != null || options?.dpi == null) { "dpi requires paperSize." }
    val paperLongEdge = paper?.let {
      requireInput(listOf(it.width, it.height).all { side -> side.isFinite() && side in MIN_PAPER_CM..MAX_PAPER_CM }) {
        "paperSize sides must be between $MIN_PAPER_CM and $MAX_PAPER_CM cm."
      }
      val dpi = options.dpi ?: DEFAULT_DPI
      requireInput(dpi.isFinite() && dpi in MIN_DPI..MAX_DPI) { "dpi must be between $MIN_DPI and $MAX_DPI." }
      val pixels = max(it.width, it.height) / CM_PER_INCH * dpi
      requireInput(pixels <= MAX_OUTPUT_DIMENSION) { "paperSize at this dpi exceeds $MAX_OUTPUT_DIMENSION px." }
      pixels
    }
    val format = options?.format ?: DocumentImageFormat.JPEG
    val quality = if (format == DocumentImageFormat.PNG) DEFAULT_QUALITY
      else options?.jpegQuality?.let(::jpegQualityPercent) ?: DEFAULT_QUALITY
    val filter = options?.filter ?: DocumentFilter.NONE
    requireInput(format != DocumentImageFormat.PNG || filter == DocumentFilter.GRAYSCALE || filter == DocumentFilter.BLACKWHITE) {
      "The png format writes 4-bit grayscale and requires the grayscale or blackWhite filter."
    }
    return RenderConfig(options?.removeShadows ?: true, quality, paperLongEdge, filter, format, options?.followPageEdges ?: true)
  }

  private fun normalizedQuad(quad: NitroDocumentQuad): ImageQuad = ImageQuad(
    topLeft = ImagePoint(quad.topLeft.x, quad.topLeft.y),
    topRight = ImagePoint(quad.topRight.x, quad.topRight.y),
    bottomRight = ImagePoint(quad.bottomRight.x, quad.bottomRight.y),
    bottomLeft = ImagePoint(quad.bottomLeft.x, quad.bottomLeft.y),
  )

  private fun validateQuad(quad: ImageQuad) {
    val points = quad.points()
    requireInput(points.all { it.x.isFinite() && it.y.isFinite() }) { "Document corners must be finite." }
    requireInput(points.all { it.x in -MAX_EXTRAPOLATION..1.0 + MAX_EXTRAPOLATION && it.y in -MAX_EXTRAPOLATION..1.0 + MAX_EXTRAPOLATION }) {
      "Document corners exceed the supported extrapolation range."
    }
    requireInput(isConvex(points)) { "Document corners must form a convex clockwise quadrilateral." }
    val area = abs(points.indices.sumOf { index ->
      val current = points[index]
      val next = points[(index + 1) % points.size]
      current.x * next.y - next.x * current.y
    }) * 0.5
    requireInput(area in MIN_NORMALIZED_AREA..MAX_NORMALIZED_AREA) { "Document quadrilateral area is outside the supported range." }
  }

  private fun isConvex(points: List<ImagePoint>): Boolean {
    val turns = points.indices.map { index -> cross(points[index], points[(index + 1) % 4], points[(index + 2) % 4]) }
    return turns.all { it > MIN_TURN } && !segmentsIntersect(points[0], points[1], points[2], points[3]) &&
      !segmentsIntersect(points[1], points[2], points[3], points[0])
  }

  private fun targetCanvas(quad: List<ImagePoint>, originalScale: Double, config: RenderConfig): TargetCanvas {
    val contentWidth = (distance(quad[0], quad[1]) + distance(quad[2], quad[3])) / 2.0
    val contentHeight = (distance(quad[1], quad[2]) + distance(quad[3], quad[0])) / 2.0
    requireInput(contentWidth.isFinite() && contentHeight.isFinite() && contentWidth > 1.0 && contentHeight > 1.0) {
      "Document side lengths are unstable."
    }
    val ratio = contentWidth / contentHeight
    requireInput(ratio in MIN_ASPECT_RATIO..MAX_ASPECT_RATIO) { "Document aspect ratio is outside the supported range." }
    // The page keeps its proportions and is drawn at the paper's size, never enlarged past its own pixels.
    val longEdge = min(config.paperLongEdge ?: MAX_OUTPUT_DIMENSION.toDouble(), max(contentWidth, contentHeight) * originalScale)
    val width = (if (ratio >= 1.0) longEdge else longEdge * ratio).roundToInt().coerceAtLeast(1)
    val height = (if (ratio >= 1.0) longEdge / ratio else longEdge).roundToInt().coerceAtLeast(1)
    requireInput(width.toLong() * height <= MAX_OUTPUT_PIXELS) { "Requested output exceeds the maximum pixel count." }
    val w = width.toDouble()
    val h = height.toDouble()
    return TargetCanvas(width, height, listOf(ImagePoint(0.0, 0.0), ImagePoint(w, 0.0), ImagePoint(w, h), ImagePoint(0.0, h)))
  }

  private fun clipImageToQuad(quad: List<ImagePoint>, width: Int, height: Int): List<ImagePoint> {
    var clipped = listOf(
      ImagePoint(0.0, 0.0),
      ImagePoint(width.toDouble(), 0.0),
      ImagePoint(width.toDouble(), height.toDouble()),
      ImagePoint(0.0, height.toDouble()),
    )
    for (index in quad.indices) {
      val edgeStart = quad[index]
      val edgeEnd = quad[(index + 1) % quad.size]
      val input = clipped
      if (input.isEmpty()) break
      clipped = mutableListOf<ImagePoint>().apply {
        var previous = input.last()
        var previousInside = cross(edgeStart, edgeEnd, previous) >= -CLIP_EPSILON
        for (current in input) {
          val currentInside = cross(edgeStart, edgeEnd, current) >= -CLIP_EPSILON
          if (currentInside != previousInside) lineIntersection(previous, current, edgeStart, edgeEnd)?.let(::add)
          if (currentInside) add(current)
          previous = current
          previousInside = currentInside
        }
      }
    }
    return clipped
  }

  private fun projectPoints(points: List<ImagePoint>, homography: Mat): List<ImagePoint> {
    val source = MatOfPoint2f(*points.map { Point(it.x, it.y) }.toTypedArray())
    val destination = MatOfPoint2f()
    try {
      Core.perspectiveTransform(source, destination, homography)
      return destination.toArray().map { ImagePoint(it.x, it.y) }
    } finally {
      source.release()
      destination.release()
    }
  }

  private fun lineIntersection(a: ImagePoint, b: ImagePoint, c: ImagePoint, d: ImagePoint): ImagePoint? {
    val abX = b.x - a.x
    val abY = b.y - a.y
    val cdX = d.x - c.x
    val cdY = d.y - c.y
    val determinant = abX * cdY - abY * cdX
    if (abs(determinant) < 1e-10) return null
    val t = ((c.x - a.x) * cdY - (c.y - a.y) * cdX) / determinant
    return ImagePoint(a.x + t * abX, a.y + t * abY)
  }

  private fun outputDirectoryFor(sourceFile: File): File {
    var current: File? = sourceFile.parentFile
    while (current != null && current.name != "cache") current = current.parentFile
    requireInput(current != null) { "Original image must be stored in the application cache." }
    return File(current, CACHE_DIRECTORY_NAME).apply {
      if (!exists() && !mkdirs()) throw IOException("Document scanner cache directory could not be created.")
      if (!isDirectory || !canWrite()) throw IOException("Document scanner cache directory is not writable.")
    }
  }

  private fun moveAtomically(source: File, destination: File) {
    try {
      Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
      if (!source.renameTo(destination)) throw IOException("Corrected JPEG could not be published atomically.")
    }
  }

  private fun ImageQuad.points() = listOf(topLeft, topRight, bottomRight, bottomLeft)
  private fun cross(a: ImagePoint, b: ImagePoint, c: ImagePoint) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
  private fun distance(a: ImagePoint, b: ImagePoint) = hypot(a.x - b.x, a.y - b.y)
  private fun segmentsIntersect(a: ImagePoint, b: ImagePoint, c: ImagePoint, d: ImagePoint) =
    cross(a, b, c) * cross(a, b, d) < 0.0 && cross(c, d, a) * cross(c, d, b) < 0.0

  private const val CACHE_DIRECTORY_NAME = "react-native-document-scanner"
  private const val DEFAULT_QUALITY = 92
  private const val MAX_OUTPUT_DIMENSION = 4096
  private const val DEFAULT_DPI = 200.0
  private const val MIN_DPI = 50.0
  private const val MAX_DPI = 600.0
  private const val MIN_PAPER_CM = 1.0
  private const val MAX_PAPER_CM = 200.0
  private const val CM_PER_INCH = 2.54
  private const val MAX_OUTPUT_PIXELS = 16_777_216L
  private const val MAX_SOURCE_DIMENSION = 4096
  private const val THUMBNAIL_QUALITY = 85
  private const val MIN_ASPECT_RATIO = 0.20
  private const val MAX_ASPECT_RATIO = 5.0
  private const val MIN_NORMALIZED_AREA = 1e-4
  private const val MAX_NORMALIZED_AREA = 4.0
  private const val MAX_EXTRAPOLATION = 0.8
  private const val MIN_TURN = 1e-8
  private const val CLIP_EPSILON = 1e-7
}
