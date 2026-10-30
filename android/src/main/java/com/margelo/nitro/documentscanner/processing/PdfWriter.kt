package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.InvalidInputException
import com.margelo.nitro.documentscanner.requireInput
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.margelo.nitro.documentscanner.CreatePdfOptions
import com.margelo.nitro.documentscanner.ImageFit
import com.margelo.nitro.documentscanner.jpegQualityPercent
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Writes images into a PDF with the options of `react-native-images-to-pdf`. A JPEG that needs no scaling is copied as
 * a DCTDecode stream. Four-bit grayscale PNGs keep their depth, including when resized; other images use JPEG.
 */
internal object PdfWriter {
  fun write(options: CreatePdfOptions): String {
    requireInput(options.pages.isNotEmpty()) { "No pages provided." }
    val output = localFile(options.outputPath, "Output path")
    val parent = output.parentFile ?: throw InvalidInputException("Output path '${output.path}' has no parent directory.")
    requireInput(parent.isDirectory && parent.canWrite()) { "Output directory '${parent.path}' does not exist or is not writable." }
    requireInput(!output.isDirectory) { "Output path '${output.path}' is a directory." }
    val pageSize = pageSize(options.pageWidth, options.pageHeight, options.pageAspectRatio)
    val targetDpi = if (pageSize == null) {
      requireInput(options.targetDpi == null) { "targetDpi requires explicit PDF page dimensions." }
      null
    } else {
      (options.targetDpi ?: DEFAULT_TARGET_DPI).also {
        requireInput(it.isFinite() && it > 0.0) { "targetDpi must be a finite positive number." }
      }
    }
    val jpegQuality = options.jpegQuality?.let(::jpegQualityPercent)
    val layout = Layout(pageSize, options.imageFit ?: ImageFit.NONE, options.autoRotateExif ?: true, targetDpi, jpegQuality)

    val temporary = File(parent, ".${output.name}.tmp")
    try {
      FileOutputStream(temporary).use { file ->
        val out = CountingStream(BufferedOutputStream(file))
        val offsets = mutableListOf<Long>()
        fun startObject() {
          offsets += out.count
          out.ascii("${offsets.size} 0 obj\n")
        }
        out.ascii("%PDF-1.4\n")
        // A comment of high bytes marks the file as binary for transfer tools.
        out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        startObject()
        out.ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        startObject()
        val kids = options.pages.indices.joinToString(" ") { "${3 + it * 3} 0 R" }
        out.ascii("<< /Type /Pages /Kids [$kids] /Count ${options.pages.size} >>\nendobj\n")
        // One page's image is held at a time.
        options.pages.forEachIndexed { index, path ->
          val page = layout.page(path)
          val width = number(page.width)
          val height = number(page.height)
          startObject()
          out.ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] " +
            "/Resources << /XObject << /Im0 ${5 + index * 3} 0 R >> >> /Contents ${4 + index * 3} 0 R >>\nendobj\n")
          // Clipped to the page, so `none` and `cover` overflow is cut.
          val content = "q 0 0 $width $height re W n ${page.matrix.joinToString(" ") { number(it) }} cm /Im0 Do Q\n"
          startObject()
          out.ascii("<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")
          val image = page.image
          startObject()
          out.ascii("<< /Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} " +
            "${image.dictionary} /Length ${image.length} >>\nstream\n")
          image.copy(out)
          out.ascii("\nendstream\nendobj\n")
        }
        val xref = out.count
        out.ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.ascii(String.format(Locale.US, "%010d 00000 n \n", it)) }
        out.ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.flush()
        file.fd.sync()
      }
      Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      return Uri.fromFile(output).toString()
    } catch (error: Throwable) {
      temporary.delete()
      throw error
    }
  }

  /** An image stream: its stored pixel size, dictionary entries and length, and a copier of its data. */
  private class EmbeddedImage(
    val width: Int,
    val height: Int,
    val dictionary: String,
    val length: Long,
    val copy: (OutputStream) -> Unit,
  )

  /** A page's size in points, its image, and the matrix that draws the image's unit square upright in place. */
  private class Page(val width: Double, val height: Double, val image: EmbeddedImage, val matrix: List<Double>)

  private class Layout(
    val pageSize: Pair<Double, Double>?,
    val fit: ImageFit,
    val autoRotateExif: Boolean,
    val targetDpi: Double?,
    val jpegQuality: Int?,
  ) {
    fun page(path: String): Page {
      val file = localFile(path, "Image path")
      if (!file.isFile || !file.canRead()) throw InvalidInputException("Image path '${file.path}' is not readable.")
      val source = probe(file)
      val orientation = if (autoRotateExif) {
        try {
          ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
          ExifInterface.ORIENTATION_NORMAL
        }
      } else {
        ExifInterface.ORIENTATION_NORMAL
      }
      val swaps = orientation in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270
      val uprightWidth = (if (swaps) source.height else source.width).toDouble()
      val uprightHeight = (if (swaps) source.width else source.height).toDouble()
      // Without a page size, a page is its image at one point per pixel.
      val (pageWidth, pageHeight) = pageSize ?: (uprightWidth to uprightHeight)
      val fit = if (pageSize == null) ImageFit.NONE else fit
      val destination = destination(uprightWidth, uprightHeight, pageWidth, pageHeight, fit)
      val (targetWidth, targetHeight) = targetSize(uprightWidth, uprightHeight, pageWidth, pageHeight, destination, fit)
      val unscaled = targetWidth == uprightWidth.toInt() && targetHeight == uprightHeight.toInt()
      val image = when {
        source.gray4 != null && unscaled -> source.gray4.let { png ->
          EmbeddedImage(png.width, png.height, gray4Dictionary(png.width, png.transparentSample),
            png.compressedLength, png::copyCompressedTo)
        }
        source.jpeg != null && jpegQuality == null && unscaled -> source.jpeg
        else -> encode(file, if (swaps) targetHeight else targetWidth, if (swaps) targetWidth else targetHeight,
          fourBit = source.gray4 != null)
      }
      return Page(pageWidth, pageHeight, image, matrix(orientation, destination))
    }

    /** The upright image's box on the page, in PDF coordinates. */
    private fun destination(width: Double, height: Double, pageWidth: Double, pageHeight: Double, fit: ImageFit): RectF {
      val (w, h) = when (fit) {
        ImageFit.NONE -> width to height
        ImageFit.FILL -> pageWidth to pageHeight
        ImageFit.CONTAIN, ImageFit.COVER -> {
          val scale = if (fit == ImageFit.CONTAIN) min(pageWidth / width, pageHeight / height) else max(pageWidth / width, pageHeight / height)
          width * scale to height * scale
        }
      }
      val left = (pageWidth - w) / 2
      val bottom = (pageHeight - h) / 2
      return RectF(left.toFloat(), bottom.toFloat(), (left + w).toFloat(), (bottom + h).toFloat())
    }

    /** The upright pixel size an image is drawn from: capped at [targetDpi] over its box, never enlarged. */
    private fun targetSize(
      width: Double,
      height: Double,
      pageWidth: Double,
      pageHeight: Double,
      destination: RectF,
      fit: ImageFit,
    ): Pair<Int, Int> {
      val dpi = targetDpi ?: return width.toInt() to height.toInt()
      val pixelsPerPoint = dpi / 72.0
      if (fit == ImageFit.FILL) {
        return max(1, min(width.toInt(), (pageWidth * pixelsPerPoint).roundToInt())) to
          max(1, min(height.toInt(), (pageHeight * pixelsPerPoint).roundToInt()))
      }
      val scale = min(1.0, max(abs(destination.width()) * pixelsPerPoint / width, abs(destination.height()) * pixelsPerPoint / height))
      return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** Decodes and scales on white; a four-bit source stays four-bit, other images are JPEG encoded. */
    private fun encode(file: File, width: Int, height: Int, fourBit: Boolean): EmbeddedImage {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.absolutePath, bounds)
      var sample = 1
      while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
      val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
      }) ?: throw InvalidInputException("Image path '${file.path}' cannot be decoded into a bitmap.")
      val bytes = ByteArrayOutputStream()
      try {
        val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
          Canvas(page).apply {
            drawColor(Color.WHITE)
            drawBitmap(decoded, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
          }
          if (fourBit) {
            val pixels = IntArray(width)
            GrayscalePng.writeCompressed4(bytes, width, height) { y, row ->
              page.getPixels(pixels, 0, width, 0, y, width, 1)
              for (x in row.indices) row[x] = Color.red(pixels[x]).toByte()
            }
          } else {
            check(page.compress(Bitmap.CompressFormat.JPEG, jpegQuality ?: DEFAULT_JPEG_QUALITY, bytes)) {
              "Image path '${file.path}' could not be JPEG encoded."
            }
          }
        } finally {
          page.recycle()
        }
      } finally {
        decoded.recycle()
      }
      val dictionary = if (fourBit) gray4Dictionary(width)
        else "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode"
      return EmbeddedImage(width, height, dictionary,
        bytes.size().toLong()) { bytes.writeTo(it) }
    }
  }

  /** What [probe] found: the stored size, and the image as embedded unchanged when its format allows. */
  private class Source(val width: Int, val height: Int, val jpeg: EmbeddedImage? = null, val gray4: GrayscalePng.Image? = null)

  private fun probe(file: File): Source {
    RandomAccessFile(file, "r").use { input ->
      val signature = ByteArray(8)
      if (input.length() >= 8) {
        input.readFully(signature)
        if (signature[0] == 0xFF.toByte() && signature[1] == 0xD8.toByte()) jpeg(file, input)?.let { return it }
      }
    }
    GrayscalePng.read4(file)?.let { return Source(it.width, it.height, gray4 = it) }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    requireInput(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image path '${file.path}' cannot be decoded into a bitmap." }
    return Source(bounds.outWidth, bounds.outHeight)
  }

  /** A baseline or progressive 8-bit gray or RGB JPEG, which PDF embeds as it is; null for any other. */
  private fun jpeg(file: File, input: RandomAccessFile): Source? {
    input.seek(2)
    while (input.filePointer + 4 <= input.length()) {
      var marker = input.readUnsignedByte()
      if (marker != 0xFF) return null
      while (marker == 0xFF) marker = input.readUnsignedByte()
      if (marker == 0xD9 || marker == 0xDA) return null
      if (marker in 0xD0..0xD7 || marker == 0x01) continue
      val length = input.readUnsignedShort()
      if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
        if (marker !in 0xC0..0xC2 || input.readUnsignedByte() != 8) return null
        val height = input.readUnsignedShort()
        val width = input.readUnsignedShort()
        val colorSpace = when (input.readUnsignedByte()) {
          1 -> "/DeviceGray"
          3 -> "/DeviceRGB"
          else -> return null
        }
        if (width <= 0 || height <= 0) return null
        return Source(width, height, jpeg = EmbeddedImage(width, height,
          "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode", file.length()) { out ->
          file.inputStream().use { it.copyTo(out) }
        })
      }
      input.seek(input.filePointer + length - 2)
    }
    return null
  }

  /**
   * The image matrix that maps an image's unit square, first stored row on top, onto [box] upright by [orientation].
   */
  private fun matrix(orientation: Int, box: RectF): List<Double> {
    val w = box.width().toDouble()
    val h = box.height().toDouble()
    val (a, b, c, d, e, f) = when (orientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix(-w, 0.0, 0.0, h, w, 0.0)
      ExifInterface.ORIENTATION_ROTATE_180 -> Matrix(-w, 0.0, 0.0, -h, w, h)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix(w, 0.0, 0.0, -h, 0.0, h)
      ExifInterface.ORIENTATION_TRANSPOSE -> Matrix(0.0, -h, -w, 0.0, w, h)
      ExifInterface.ORIENTATION_ROTATE_90 -> Matrix(0.0, -h, w, 0.0, 0.0, h)
      ExifInterface.ORIENTATION_TRANSVERSE -> Matrix(0.0, h, w, 0.0, 0.0, 0.0)
      ExifInterface.ORIENTATION_ROTATE_270 -> Matrix(0.0, h, -w, 0.0, w, 0.0)
      else -> Matrix(w, 0.0, 0.0, h, 0.0, 0.0)
    }
    return listOf(a, b, c, d, e + box.left, f + box.top)
  }

  private data class Matrix(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double)

  private fun gray4Dictionary(width: Int, transparent: Int? = null): String =
    "/ColorSpace /DeviceGray /BitsPerComponent 4 /Filter /FlateDecode " +
      "/DecodeParms << /Predictor 15 /Colors 1 /BitsPerComponent 4 /Columns $width >>" +
      (transparent?.let { " /Mask [$it $it]" } ?: "")

  private fun pageSize(width: Double?, height: Double?, ratio: Double?): Pair<Double, Double>? {
    fun points(value: Double, label: String) = value.also {
      requireInput(it.isFinite() && it in MIN_PAGE_SIDE..MAX_PAGE_SIDE) { "$label must be between $MIN_PAGE_SIDE and $MAX_PAGE_SIDE PDF points." }
    }
    if (width == null && height == null) {
      requireInput(ratio == null) { "pageAspectRatio requires exactly one of pageWidth or pageHeight." }
      return null
    }
    if (width != null && height != null) {
      requireInput(ratio == null) { "pageAspectRatio cannot be combined with both pageWidth and pageHeight." }
      return points(width, "pageWidth") to points(height, "pageHeight")
    }
    requireInput(ratio != null && ratio.isFinite() && ratio > 0.0) {
      "pageAspectRatio must be a finite positive width-to-height ratio when only one page dimension is provided."
    }
    return if (width != null) {
      points(width, "pageWidth") to points(width / ratio, "derived pageHeight")
    } else {
      points(height!! * ratio, "derived pageWidth") to points(height, "pageHeight")
    }
  }

  private fun localFile(value: String, label: String): File {
    requireInput(value.isNotBlank()) { "$label cannot be empty." }
    val uri = Uri.parse(value)
    val path = when {
      uri.scheme == null -> value
      uri.scheme.equals("file", ignoreCase = true) -> uri.path ?: throw InvalidInputException("$label is not a valid file URI.")
      else -> throw InvalidInputException("$label must be an absolute local path or file:// URI.")
    }
    requireInput(path.startsWith(File.separator)) { "$label must be an absolute local path or file:// URI." }
    return File(path).normalize()
  }

  private class CountingStream(out: OutputStream) : FilterOutputStream(out) {
    var count = 0L
      private set

    override fun write(b: Int) {
      out.write(b)
      count += 1
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
      out.write(b, off, len)
      count += len
    }

    fun ascii(text: String) = write(text.toByteArray(Charsets.US_ASCII))
  }

  private fun number(value: Double) = String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')

  private const val DEFAULT_TARGET_DPI = 200.0
  private const val DEFAULT_JPEG_QUALITY = 72
  private const val MIN_PAGE_SIDE = 1.0
  private const val MAX_PAGE_SIDE = 14_400.0
}
