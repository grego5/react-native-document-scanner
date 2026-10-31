package com.margelo.nitro.documentscanner.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.documentscanner.CreatePdfOptions
import com.margelo.nitro.documentscanner.InvalidInputException
import com.margelo.nitro.documentscanner.requireInput
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Android resource and publication adapter for the shared PDF serializer. */
internal object PdfWriter {
  fun write(options: CreatePdfOptions, decorateOutput: (OutputStream) -> OutputStream = { it }): String {
    requireInput(options.pages.isNotEmpty()) { "No pages provided." }
    val output = localFile(options.outputPath, "Output path")
    val parent = output.parentFile ?: throw InvalidInputException("Output path '${output.path}' has no parent directory.")
    requireInput(parent.isDirectory && parent.canWrite()) { "Output directory '${parent.path}' does not exist or is not writable." }
    requireInput(!output.isDirectory) { "Output path '${output.path}' is a directory." }
    val temporary = File(parent, ".${output.name}.tmp")
    try {
      FileOutputStream(temporary).use { file ->
        val out = decorateOutput(BufferedOutputStream(file))
        AndroidPdfImages(options.pages).use { images -> NativePdfWriter.write(out, images, options) }
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

  internal fun localFile(value: String, label: String): File {
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

}

/** Synchronous platform capabilities; native code decides layout, reuse and target pixels. */
@DoNotStrip
internal interface PdfImageAdapter {
  fun open(index: Int): Long
  fun readAt(offset: Long, into: ByteArray, count: Int)
  fun dimensions(): IntArray
  fun orientation(): Int
  fun encode(width: Int, height: Int, gray4: Boolean, jpegQuality: Int): ByteArray
}

internal class AndroidPdfImages(private val pages: Array<String>) : PdfImageAdapter, AutoCloseable {
  private var file: File? = null
  private var input: RandomAccessFile? = null
  override fun open(index: Int): Long {
    close()
    val next = PdfWriter.localFile(pages[index], "Image path")
    requireInput(next.isFile && next.canRead()) { "Image path '${next.path}' is not readable." }
    val opened = RandomAccessFile(next, "r")
    file = next; input = opened
    return opened.length()
  }
  override fun readAt(offset: Long, into: ByteArray, count: Int) {
    val source = checkNotNull(input)
    source.seek(offset)
    source.readFully(into, 0, count)
  }
  override fun dimensions(): IntArray {
    val source = checkNotNull(file)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    requireInput(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image path '${source.path}' cannot be decoded into a bitmap." }
    return intArrayOf(bounds.outWidth, bounds.outHeight)
  }
  override fun orientation(): Int = try {
    ExifInterface(checkNotNull(file).absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
  } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }

  override fun encode(width: Int, height: Int, gray4: Boolean, jpegQuality: Int): ByteArray {
    val source = checkNotNull(file)
    val bounds = dimensions()
    var sample = 1
    while (bounds[0] / (sample * 2) >= width && bounds[1] / (sample * 2) >= height) sample *= 2
    val decoded = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply {
      inSampleSize = sample
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }) ?: throw InvalidInputException("Image path '${source.path}' cannot be decoded into a bitmap.")
    val bytes = ByteArrayOutputStream()
    try {
      val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      try {
        Canvas(page).apply {
          drawColor(Color.WHITE)
          drawBitmap(decoded, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        if (gray4) {
          val pixels = IntArray(width)
          GrayscalePng.writeCompressed4(bytes, width, height) { y, row ->
            page.getPixels(pixels, 0, width, 0, y, width, 1)
            for (x in row.indices) row[x] = Color.red(pixels[x]).toByte()
          }
        } else {
          check(page.compress(Bitmap.CompressFormat.JPEG, jpegQuality, bytes)) { "Image path '${source.path}' could not be JPEG encoded." }
        }
      } finally { page.recycle() }
    } finally { decoded.recycle() }
    return bytes.toByteArray()
  }
  override fun close() {
    val previous = input
    input = null; file = null
    previous?.close()
  }
}
