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
import com.margelo.nitro.core.Promise
import com.margelo.nitro.documentscanner.processing.CornerModelStore
import com.margelo.nitro.documentscanner.processing.SourceImageLoader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
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
 * Turns a picked image into a session original and admits it through the capture path: the same reservation,
 * analysis, crop and render a camera photo gets, without a frame or live corners.
 */
internal object ImageImport {
  private const val JPEG_QUALITY = 95

  private const val BUNDLED_MODEL = "asset:///doccornernet/model.tflite"
  private val modelWorker = Executors.newSingleThreadExecutor { Thread(it, "ScannerImportModel").apply { isDaemon = true } }
  @Volatile private var bundled: CornerModelStore.Selection? = null

  /**
   * The model an import call freezes: the camera's active one, else the bundled model prepared once without
   * activating it, so the camera's own model choice is never replaced. [onResult] may run on a worker.
   */
  fun model(context: Context, onResult: (Result<CornerModelStore.Selection>) -> Unit) {
    val active = CornerModelStore.selected ?: bundled
    if (active != null) return onResult(Result.success(active))
    modelWorker.execute {
      onResult(runCatching {
        bundled ?: CornerModelStore.prepare(context.applicationContext, BUNDLED_MODEL).also { bundled = it }
      }.recoverCatching { throw if (it is ScannerException) it else ScannerException(ScannerErrorCode.MODEL_LOAD_FAILED, it) })
    }
  }

  /** Admits one `file://` or `content://` image; [onDone] runs once, on the image worker or the calling thread. */
  fun admit(
    context: Context,
    session: ScanSessionState,
    source: Uri,
    options: DocumentRenderOptions,
    autoCrop: Boolean,
    selection: CornerModelStore.Selection,
    onDone: (Result<String>) -> Unit,
  ) {
    try {
      requireInput(source.scheme == "file" || source.scheme == "content") { "Expected a file:// or content:// image URI." }
      val open = {
        if (source.scheme == "file") File(requireNotNull(source.path)).inputStream()
        else context.contentResolver.openInputStream(source)
      }
      admit(session, options, autoCrop, selection, { destination, effective -> writeOriginal(open, destination, effective) },
        { onDone(Result.success(it)) }, { onDone(Result.failure(it)) })
    } catch (error: Throwable) {
      onDone(Result.failure(error))
    }
  }

  /**
   * Picks library images and admits them as one session-scoped [LibraryImport]: closing the session settles it and
   * ends a picker that is still open. Any thread; [onResult] runs once.
   */
  fun fromLibrary(
    context: Context,
    session: ScanSessionState,
    maxCount: Int,
    options: DocumentRenderOptions,
    autoCrop: Boolean,
    selection: CornerModelStore.Selection,
    onResult: (Result<LibraryImportResult>) -> Unit,
  ) {
    val main = LibraryPicker.main
    val operation = LibraryImport<Uri>(maxCount, { main.post(it) },
      { uri, done -> admit(context, session, uri, options, autoCrop, selection, done) }, onResult)
    main.post { operation.start(session::onClose) { onPicked -> LibraryPicker.pick(maxCount, onPicked) } }
  }

  /** Writes the original on the session's image worker, then publishes it as a capture with no frame or live corners. */
  internal fun admit(
    session: ScanSessionState,
    options: DocumentRenderOptions,
    autoCrop: Boolean,
    selection: CornerModelStore.Selection,
    write: (File, DocumentRenderOptions) -> Unit,
    onSuccess: (String) -> Unit,
    onFailure: (Throwable) -> Unit,
  ) {
    val capture = SessionCapture.reserve(session, options, selection, frame = false)
    val owner = session.resources.workOwner
    owner.queue.submit(owner, ScannerWorkQueue.Kind.CAPTURE, capture.pageId,
      operation = {
        capture.use {
          write(capture.original.file, capture.options)
          capture.original.commit()
          capture.publish(null, null, autoCrop)
        }
      },
      onSuccess = onSuccess,
      onFailure = { error ->
        capture.close()
        onFailure(if (error is ScannerException || error is InvalidInputException) error
          else ScannerException(ScannerErrorCode.IMAGE_LOAD_FAILED, error))
      })
  }

  /** Copies the encoded bytes to [destination], then replaces them with an upright JPEG unless they already fit. */
  internal fun writeOriginal(open: () -> InputStream?, destination: File, options: DocumentRenderOptions) {
    (open() ?: throw IOException("Image could not be opened.")).use { input ->
      destination.outputStream().use { input.copyTo(it) }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(destination.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Image is not decodable.")
    val orientation = runCatching {
      ExifInterface(destination.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
      .takeIf { it in ExifInterface.ORIENTATION_NORMAL..ExifInterface.ORIENTATION_ROTATE_270 } ?: ExifInterface.ORIENTATION_NORMAL
    val transposed = orientation >= ExifInterface.ORIENTATION_TRANSPOSE
    val uprightWidth = if (transposed) bounds.outHeight else bounds.outWidth
    val uprightHeight = if (transposed) bounds.outWidth else bounds.outHeight
    val target = ImportSizePolicy.target(uprightWidth, uprightHeight, options)
    val fits = target.width == uprightWidth && target.height == uprightHeight
    // The pipeline reads a JPEG's EXIF orientation itself, so a JPEG that already fits keeps its original bytes.
    if (fits && bounds.outMimeType == "image/jpeg") return
    val upright = decodeUpright(destination, orientation, target)
    try {
      destination.outputStream().use { stream ->
        if (!upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) throw IOException("Imported JPEG could not be encoded.")
      }
    } finally {
      upright.recycle()
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
