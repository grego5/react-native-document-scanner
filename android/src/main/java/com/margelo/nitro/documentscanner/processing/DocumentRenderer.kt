package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.requireInput
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.margelo.nitro.documentscanner.DocumentFilter
import com.margelo.nitro.documentscanner.DocumentImageFormat
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
    NativeGeometry.validateRender(sourcePoints)

    try {
      // The largest decode, so originals up to MAX_SOURCE_DIMENSION keep every pixel the output may use.
      SourceImageLoader.load(originalUri, MAX_SOURCE_DIMENSION).use { upright ->
        OpenCvRuntime.ensureLoaded()
        val sourceBitmap = upright.bitmap
        val sourceMat = Mat()
        val filtered = Mat()
        val bgr = Mat()
        val parameters = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, config.quality)
        try {
          Utils.bitmapToMat(sourceBitmap, sourceMat)
          // Pixels in the original per pixel of the sampled bitmap, so output sizing preserves the original.
          val originalScale = max(upright.encodedWidth, upright.encodedHeight).toDouble() / max(sourceBitmap.width, sourceBitmap.height)
          // Shared warp and filtering stay together; only the final BGR/gray image returns to Android.
          val target = NativeRenderGeometry.render(sourceMat, filtered, sourcePoints.coordinates(), originalScale,
            config.paperLongEdge, config.followPageEdges, config.filter, config.removeShadows)
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
          moveAtomically(temporary, destination)
          moveAtomically(thumbnailTemporary, thumbnail)

          val outputUri = Uri.fromFile(destination).toString()
          // Cached before resolving, so a pager shows the new page and its thumbnail together.
          PageThumbnails.put(outputUri, thumbnailBitmap, thumbnail)
          return RenderedDocument(
            originalUri = originalUri,
            outputUri = outputUri,
            thumbnailUri = Uri.fromFile(thumbnail).toString(),
            width = target.width.toDouble(),
            height = target.height.toDouble(),
            validContentPolygon = target.validContentPolygon.toTypedArray(),
          )
        } finally {
          sourceMat.release()
          filtered.release()
          bgr.release()
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
  private const val THUMBNAIL_QUALITY = 85
}
