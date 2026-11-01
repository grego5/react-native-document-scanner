package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.ScannerFiles
import com.margelo.nitro.documentscanner.requireInput
import com.margelo.nitro.documentscanner.NativeQuadGeometry
import com.margelo.nitro.documentscanner.SessionResources
import com.margelo.nitro.documentscanner.LocalSessionFiles
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.margelo.nitro.documentscanner.DocumentFilter
import com.margelo.nitro.documentscanner.DocumentImageFormat
import com.margelo.nitro.documentscanner.DocumentQuad as NitroDocumentQuad
import com.margelo.nitro.documentscanner.DocumentRenderOptions
import com.margelo.nitro.documentscanner.PageThumbnails
import com.margelo.nitro.documentscanner.jpegQualityPercent
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * Rectifies the upright original so the submitted quad's corners land exactly on the output corners, and bends each
 * side onto the page's own edge where the page curves in the shared geometric stage.
 */
internal object DocumentRenderer {
  /** Standalone algorithm entry for native pixel fixtures; session work uses leased destinations. */
  fun render(originalUri: String, quad: NitroDocumentQuad, options: DocumentRenderOptions?): RenderResult =
    renderTo(originalUri, quad, options, null)

  /** Pins the original while consuming the destination transactions, including on failure/stale close. */
  fun render(
    original: SessionResources.Resource,
    quad: NitroDocumentQuad,
    options: DocumentRenderOptions?,
    output: SessionResources.RenderDestination,
  ): RenderResult = output.use {
    try {
      requireInput(original.owner === output.owner && original.kind == SessionResources.Kind.ORIGINAL) {
        "Session renders require an original and destinations from the same session."
      }
      output.owner.lease(original).use { source ->
        renderTo(Uri.fromFile(source.file).toString(), quad, options, output)
      }
    } catch (error: Throwable) {
      output.discard()
      throw error
    }
  }

  /** Export already owns a source pin, even when its page has since been retired. */
  fun render(source: SessionResources.Lease, quad: NitroDocumentQuad, options: DocumentRenderOptions?,
    output: SessionResources.RenderDestination): RenderResult = output.use {
    try {
      requireInput(source.resource.owner === output.owner && source.resource.kind == SessionResources.Kind.ORIGINAL) {
        "Expected a leased session original."
      }
      renderTo(Uri.fromFile(source.file).toString(), quad, options, output)
    } catch (error: Throwable) { output.discard(); throw error }
  }

  private fun renderTo(
    originalUri: String,
    quad: NitroDocumentQuad,
    options: DocumentRenderOptions?,
    output: SessionResources.RenderDestination?,
  ): RenderResult {
    val issue = NativeQuadGeometry.renderIssue(NativeQuadGeometry.coordinates(quad))
    requireInput(issue == null) { requireNotNull(issue) }
    val config = validateOptions(options)
    val uri = Uri.parse(originalUri)
    requireInput(uri.scheme == "file" && !uri.path.isNullOrBlank()) {
      "Unsupported image URI; expected a local file:// JPEG."
    }
    val sourceFile = File(uri.path!!)
    if (!sourceFile.isFile || !sourceFile.canRead()) throw IOException("Captured image file was not found or is not readable.")
    requireInput(output != null || !LocalSessionFiles.isSessionFile(sourceFile)) {
      "Session originals require a leased native render destination."
    }
    val outputDirectory = if (output == null) outputDirectoryFor(sourceFile) else requireNotNull(output.image.destination.parentFile)
    val extension = if (config.format == DocumentImageFormat.PNG) "png" else "jpg"
    val destination = output?.image?.destination ?: File(outputDirectory, "corrected-${System.currentTimeMillis()}-${UUID.randomUUID()}.$extension")
    val temporary = output?.image?.file ?: File(outputDirectory, ".${destination.name}.tmp.$extension")
    val thumbnail = output?.thumbnail?.destination ?: File(outputDirectory, "thumbnail-${destination.nameWithoutExtension}.jpg")
    val thumbnailTemporary = output?.thumbnail?.file ?: File(outputDirectory, ".${thumbnail.name}.tmp.jpg")
    requireInput(destination.extension == extension && thumbnail.extension == "jpg") {
      "Session output format does not match render options."
    }
    val sourcePoints = normalizedQuad(quad)
    NativeGeometry.validateRender(sourcePoints)

    try {
      SourceImageLoader.inspect(originalUri).let { header ->
        // Only the page and PageEdges' search band around it, unsampled up to MAX_SOURCE_DIMENSION so the output
        // keeps every pixel it may use.
        val sampleSize = SourceImageLoader.sampleSize(header, MAX_SOURCE_DIMENSION)
        val region = pageRegion(sourcePoints, header.uprightWidth, header.uprightHeight)
        check(region != null) { "The selected document does not overlap the captured image." }
        OpenCvRuntime.ensureLoaded()
        val sourceBitmap = SourceImageLoader.loadRegion(header, region, sampleSize)
        val sourceMat = Mat()
        val filtered = Mat()
        val bgr = Mat()
        val parameters = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, config.quality)
        try {
          Utils.bitmapToMat(sourceBitmap, sourceMat)
          // Pixels in the original per pixel of the sampled bitmap, so output sizing preserves the original.
          val originalScale = sampleSize.toDouble()
          // Shared warp and filtering stay together; only the final BGR/gray image returns to Android.
          val target = NativeRenderGeometry.render(sourceMat, filtered, sourcePoints.coordinates(), originalScale,
            config.paperLongEdge, config.followPageEdges, config.filter, config.removeShadows,
            doubleArrayOf(header.uprightWidth.toDouble(), header.uprightHeight.toDouble(), region.left.toDouble(), region.top.toDouble(), sampleSize.toDouble()))
          if (config.format == DocumentImageFormat.PNG) {
            FileOutputStream(temporary).use { output ->
              GrayscalePng.write4(output, filtered)
            }
          } else {
            // A JPEG stays three-channel, so other PDF writers can also embed it unchanged.
            val encoded = if (filtered.channels() == 1) {
              Imgproc.cvtColor(filtered, bgr, Imgproc.COLOR_GRAY2BGR)
              bgr
            } else filtered
            check(Imgcodecs.imwrite(temporary.absolutePath, encoded, parameters)) { "Corrected image could not be encoded." }
          }
          val thumbnailBitmap = writeThumbnail(filtered, PageThumbnails.longSide, thumbnailTemporary)
          if (config.format == DocumentImageFormat.JPEG) ExifInterface(temporary.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            saveAttributes()
          }
          FileOutputStream(temporary, true).use { it.fd.sync() }
          if (output == null) {
            moveAtomically(temporary, destination)
            moveAtomically(thumbnailTemporary, thumbnail)
          } else {
            output.image.commit()
            output.thumbnail.commit()
          }

          val outputUri = Uri.fromFile(destination).toString()
          // Cached before resolving, so a pager shows the new page and its thumbnail together.
          PageThumbnails.put(outputUri, thumbnailBitmap, thumbnail)
          return RenderResult(
            originalUri = originalUri,
            outputUri = outputUri,
            thumbnailUri = Uri.fromFile(thumbnail).toString(),
            width = target.width.toDouble(),
            height = target.height.toDouble(),
            validContentPolygon = target.validContentPolygon.toTypedArray(),
          )
        } finally {
          sourceBitmap.recycle()
          sourceMat.release()
          filtered.release()
          bgr.release()
          parameters.release()
        }
      }
    } catch (error: Throwable) {
      if (output == null) {
        temporary.delete()
        destination.delete()
        thumbnailTemporary.delete()
        thumbnail.delete()
      }
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

  /** Validate at command admission, and make omitted defaults compare equal to explicit defaults. */
  internal fun effectiveOptions(options: DocumentRenderOptions?): DocumentRenderOptions {
    val config = validateOptions(options)
    return DocumentRenderOptions(config.removeShadows, config.quality / 100.0, config.filter, options?.format ?: DocumentImageFormat.JPEG,
      options?.paperSize, options?.paperSize?.let { options.dpi ?: DEFAULT_DPI }, config.followPageEdges)
  }

  internal fun outputFormat(options: DocumentRenderOptions?): DocumentImageFormat = when (options?.format) {
    DocumentImageFormat.AUTO -> if (options.filter == DocumentFilter.BLACKWHITE) DocumentImageFormat.PNG else DocumentImageFormat.JPEG
    else -> options?.format ?: DocumentImageFormat.JPEG
  }

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
    val format = outputFormat(options)
    val quality = if (format == DocumentImageFormat.PNG && options?.format != DocumentImageFormat.AUTO) DEFAULT_QUALITY
      else options?.jpegQuality?.let(::jpegQualityPercent) ?: DEFAULT_QUALITY
    val filter = options?.filter ?: DocumentFilter.NONE
    requireInput(format != DocumentImageFormat.PNG || filter == DocumentFilter.GRAYSCALE || filter == DocumentFilter.BLACKWHITE) {
      "The png format writes 4-bit grayscale and requires the grayscale or blackWhite filter."
    }
    return RenderConfig(options?.removeShadows ?: true, quality, paperLongEdge, filter, format, options?.followPageEdges ?: true)
  }

  /**
   * The quad's bounds widened by [PAGE_MARGIN] of their size each side, within the photo; null when they miss it.
   * PageEdges searches at most its band, a smaller fraction of the page, past each side.
   */
  private fun ImageQuad.points() = listOf(topLeft, topRight, bottomRight, bottomLeft)

  private fun pageRegion(quad: ImageQuad, photoWidth: Int, photoHeight: Int): PhotoInferenceCrop? {
    val points = quad.points()
    val x0 = points.minOf { it.x } * photoWidth
    val x1 = points.maxOf { it.x } * photoWidth
    val y0 = points.minOf { it.y } * photoHeight
    val y1 = points.maxOf { it.y } * photoHeight
    val left = floor(x0 - (x1 - x0) * PAGE_MARGIN).toInt().coerceAtLeast(0)
    val top = floor(y0 - (y1 - y0) * PAGE_MARGIN).toInt().coerceAtLeast(0)
    val right = ceil(x1 + (x1 - x0) * PAGE_MARGIN).toInt().coerceAtMost(photoWidth)
    val bottom = ceil(y1 + (y1 - y0) * PAGE_MARGIN).toInt().coerceAtMost(photoHeight)
    return if (right > left && bottom > top) PhotoInferenceCrop(left, top, right - left, bottom - top) else null
  }

  private fun normalizedQuad(quad: NitroDocumentQuad): ImageQuad = ImageQuad(
    topLeft = ImagePoint(quad.topLeft.x, quad.topLeft.y),
    topRight = ImagePoint(quad.topRight.x, quad.topRight.y),
    bottomRight = ImagePoint(quad.bottomRight.x, quad.bottomRight.y),
    bottomLeft = ImagePoint(quad.bottomLeft.x, quad.bottomLeft.y),
  )

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
    ScannerFiles.move(source, destination)
  }

  private const val CACHE_DIRECTORY_NAME = "react-native-document-scanner"
  private const val DEFAULT_QUALITY = 92
  private const val MAX_OUTPUT_DIMENSION = 4096
  private const val DEFAULT_DPI = 200.0
  private const val MIN_DPI = 50.0
  private const val MAX_DPI = 600.0
  private const val MIN_PAPER_CM = 1.0
  private const val MAX_PAPER_CM = 200.0
  private const val CM_PER_INCH = 2.54
  private const val MAX_SOURCE_DIMENSION = 4096
  private const val PAGE_MARGIN = .10
  private const val THUMBNAIL_QUALITY = 85
}
