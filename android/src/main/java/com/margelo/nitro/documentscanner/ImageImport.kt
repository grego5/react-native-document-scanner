package com.margelo.nitro.documentscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.margelo.nitro.documentscanner.processing.SourceImageLoader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Page defaults from the public correction and output props, shared by captures and imports. */
internal fun renderOptions(correction: ImageCorrectionOptions?, output: ImageOutputOptions?): DocumentRenderOptions {
  val paper = if (output?.width == null && output?.height == null) null
    else PaperSize(output.width ?: Double.NaN, output.height ?: Double.NaN)
  return DocumentRenderOptions(correction?.removeShadows, output?.jpegQuality, correction?.filter,
    output?.format, paper, output?.dpi, correction?.simpleDewarp)
}

/** An imported original's upright pixel size: the smallest that still covers the export paper, never enlarged. */
internal object ImportSizePolicy {
  /** The renderer's own source and output limit, used when no paper size is configured. */
  const val RENDER_LIMIT = 4096
  private const val CM_PER_INCH = 2.54

  /** [options] must be effective, so a paper size carries its dpi. */
  fun target(width: Int, height: Int, options: DocumentRenderOptions): Size {
    require(width > 0 && height > 0) { "Image dimensions must be positive." }
    val long = max(width, height).toDouble()
    val short = min(width, height).toDouble()
    val paper = options.paperSize
    val scale = if (paper == null) RENDER_LIMIT / long else {
      val dpi = requireNotNull(options.dpi) { "Effective options carry dpi with paperSize." }
      max(max(paper.width, paper.height) / CM_PER_INCH * dpi / long, min(paper.width, paper.height) / CM_PER_INCH * dpi / short)
    }
    if (scale >= 1.0) return Size(width, height)
    return Size(scaled(width, scale), scaled(height, scale))
  }

  // A tiny epsilon keeps exact products such as 1654.0000001 from rounding up a pixel.
  private fun scaled(side: Int, scale: Double) = ceil(side * scale - 1e-6).toInt().coerceIn(1, side)

  data class Size(val width: Int, val height: Int)
}

/**
 * Turns a picked image into a session original on the image worker and admits it as an import: the same crop and
 * render a camera photo gets, without a frame or live corners. The page follows the shared model's status.
 */
internal object ImageImport {
  private const val JPEG_QUALITY = 95
  /** JPEG decoders scale by 1/8 while decoding, so the pixel check reads every scan at a fraction of the memory. */
  private const val PROBE_SAMPLE = 8

  /**
   * Imports one `file://` or `content://` image straight onto the session's image worker, which owns cancellation
   * from then on. [options] must already be effective. Any thread; [onResult] runs once.
   */
  fun fromFile(
    context: Context,
    session: ScanSessionState,
    source: Uri,
    options: DocumentRenderOptions,
    autoCrop: Boolean,
    onResult: (Result<String>) -> Unit,
  ) {
    val open = opener(context, source)
    admit(session, options, autoCrop, { destination, effective -> writeOriginal(open, destination, effective) },
      { onResult(Result.success(it)) }, { onResult(Result.failure(it)) })
  }

  /**
   * Picks library images and admits them as one session-scoped [LibraryImport]: closing the session settles it, ends
   * a picker that is still open and completes only once that picker has released its slot. Any thread; [onResult]
   * runs once.
   */
  fun fromLibrary(
    context: Context,
    session: ScanSessionState,
    maxCount: Int,
    options: DocumentRenderOptions,
    autoCrop: Boolean,
    onResult: (Result<List<String>>) -> Unit,
  ) {
    val main = LibraryPicker.main
    LibraryImport<Uri>(maxCount, { main.post(it) }, { uri, done -> admit(context, session, uri, options, autoCrop, done) }, onResult)
      .start(session::registerCloseCancellation) { onPicked -> LibraryPicker.pick(maxCount, onPicked) }
  }

  /** One picked image; [onDone] runs once, on the image worker or the calling thread. */
  private fun admit(
    context: Context,
    session: ScanSessionState,
    source: Uri,
    options: DocumentRenderOptions,
    autoCrop: Boolean,
    onDone: (Result<String>) -> Unit,
  ) {
    try {
      val open = opener(context, source)
      admit(session, options, autoCrop, { destination, effective -> writeOriginal(open, destination, effective) },
        { onDone(Result.success(it)) }, { onDone(Result.failure(it)) })
    } catch (error: Throwable) {
      onDone(Result.failure(error))
    }
  }

  private fun opener(context: Context, source: Uri): () -> InputStream? {
    requireInput(source.scheme == "file" || source.scheme == "content") { "Expected a file:// or content:// image URI." }
    return {
      if (source.scheme == "file") File(requireNotNull(source.path)).inputStream()
      else context.contentResolver.openInputStream(source)
    }
  }

  /**
   * Reserves, writes and commits the original on the session's image worker, then admits it through
   * [ScanSessionState.admitImport]. [options] must already be effective. Unreadable or undecodable sources fail as
   * `image/load-failed`; reserving, writing or committing the destination as `storage/write-failed`. Every outcome
   * reaches [onFailure] or [onSuccess] once.
   */
  internal fun admit(
    session: ScanSessionState,
    options: DocumentRenderOptions,
    autoCrop: Boolean,
    write: (File, DocumentRenderOptions) -> Unit,
    onSuccess: (String) -> Unit,
    onFailure: (Throwable) -> Unit,
  ) {
    val owner = session.resources.workOwner
    val pageId = UUID.randomUUID().toString()
    owner.queue.submit(owner, ScannerWorkQueue.Kind.CAPTURE, pageId,
      operation = {
        writing { session.resources.output(SessionResources.Kind.ORIGINAL) }.use { original ->
          try {
            write(original.file, options)
            writing { original.commit() }
            session.admitImport(pageId, original.resource, options, autoCrop)
          } catch (error: Throwable) {
            // A committed original that no page took is retired with the output.
            original.discard()
            throw error
          }
        }
        pageId
      },
      onSuccess = onSuccess,
      onFailure = { error -> onFailure(if (typed(error)) error else ScannerException(ScannerErrorCode.IMAGE_LOAD_FAILED, error)) })
  }

  private fun typed(error: Throwable) = error is ScannerException || error is InvalidInputException

  /** Reading the picked image: any untyped failure is the image's. */
  private inline fun <T> reading(action: () -> T): T = try { action() } catch (error: Exception) {
    throw if (typed(error)) error else ScannerException(ScannerErrorCode.IMAGE_LOAD_FAILED, error)
  }

  /** Writing the session original: any untyped failure is storage's. */
  private inline fun <T> writing(action: () -> T): T = try { action() } catch (error: Exception) {
    throw if (typed(error)) error else ScannerException(ScannerErrorCode.STORAGE_WRITE_FAILED, error)
  }

  /** Copies the encoded bytes to [destination], then replaces them with an upright JPEG unless they already fit. */
  internal fun writeOriginal(open: () -> InputStream?, destination: File, options: DocumentRenderOptions) {
    copyOriginal(open) { destination.outputStream() }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(destination.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
      throw ScannerException(ScannerErrorCode.IMAGE_LOAD_FAILED, IOException("Image is not decodable."))
    }
    val orientation = runCatching {
      ExifInterface(destination.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
      .takeIf { it in ExifInterface.ORIENTATION_NORMAL..ExifInterface.ORIENTATION_ROTATE_270 } ?: ExifInterface.ORIENTATION_NORMAL
    val transposed = orientation >= ExifInterface.ORIENTATION_TRANSPOSE
    val uprightWidth = if (transposed) bounds.outHeight else bounds.outWidth
    val uprightHeight = if (transposed) bounds.outWidth else bounds.outHeight
    val target = ImportSizePolicy.target(uprightWidth, uprightHeight, options)
    val fits = target.width == uprightWidth && target.height == uprightHeight
    // The pipeline reads a JPEG's EXIF orientation itself, so a JPEG that already fits keeps its original bytes. Its
    // header alone proves nothing about its pixels, so a reduced decode rejects pixel data the decoder cannot read. A
    // truncated scan still decodes partially and passes; the renderer's region decoder is not consulted.
    if (fits && bounds.outMimeType == "image/jpeg") {
      val probe = BitmapFactory.decodeFile(destination.absolutePath, BitmapFactory.Options().apply { inSampleSize = PROBE_SAMPLE })
        ?: throw ScannerException(ScannerErrorCode.IMAGE_LOAD_FAILED, IOException("Image pixels are not decodable."))
      probe.recycle()
      return
    }
    val upright = reading { decodeUpright(destination, orientation, target) }
    try {
      writing {
        destination.outputStream().use { stream ->
          if (!upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) throw IOException("Imported JPEG could not be encoded.")
        }
      }
    } finally {
      upright.recycle()
    }
  }

  /** Copies the picked bytes into [output]: opening or reading [open] fails as the image, the rest as storage. */
  internal fun copyOriginal(open: () -> InputStream?, output: () -> OutputStream) {
    reading { open() ?: throw IOException("Image could not be opened.") }.use { input ->
      val stream = writing(output)
      try {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
          val count = reading { input.read(buffer) }
          if (count < 0) break
          writing { stream.write(buffer, 0, count) }
        }
      } catch (error: Throwable) {
        runCatching { stream.close() }
        throw error
      }
      writing { stream.close() }
    }
  }

  /** An opaque upright bitmap of exactly [target], decoded no larger than needed; transparency becomes white paper. */
  private fun decodeUpright(file: File, orientation: Int, target: ImportSizePolicy.Size): Bitmap {
    val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      // ImageDecoder applies EXIF orientation and reports the upright size; it samples and scales while decoding.
      ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetSize(target.width, target.height)
      }
    } else {
      val transposed = orientation >= ExifInterface.ORIENTATION_TRANSPOSE
      val encodedTarget = if (transposed) Pair(target.height, target.width) else Pair(target.width, target.height)
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.absolutePath, bounds)
      var sample = 1
      while (bounds.outWidth / (sample * 2) >= encodedTarget.first && bounds.outHeight / (sample * 2) >= encodedTarget.second) sample *= 2
      val sampled = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw IOException("Image could not be decoded.")
      SourceImageLoader.orient(sampled, orientation).also { if (it !== sampled) sampled.recycle() }
    }
    try {
      val result = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
      Canvas(result).apply {
        drawColor(Color.WHITE)
        drawBitmap(decoded, null, Rect(0, 0, target.width, target.height), Paint(Paint.FILTER_BITMAP_FLAG))
      }
      return result
    } finally {
      decoded.recycle()
    }
  }
}
