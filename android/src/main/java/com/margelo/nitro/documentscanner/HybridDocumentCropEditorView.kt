package com.margelo.nitro.documentscanner

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import com.facebook.react.uimanager.ThemedReactContext
import com.margelo.nitro.documentscanner.processing.DocumentFilters
import com.margelo.nitro.documentscanner.processing.DocumentRenderer
import com.margelo.nitro.documentscanner.processing.FastCores
import com.margelo.nitro.documentscanner.processing.SourceImageLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/** Native crop editing for a captured photo; drags never cross into JavaScript until they end. */
class HybridDocumentCropEditorView(
  themedContext: ThemedReactContext,
) : HybridDocumentCropEditorViewSpec() {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val editor = CropEditorView(themedContext).apply {
    onQuadEdited = { edited ->
      val quad = edited.toDocumentQuad()
      // An edit back to the quad React last set brings no new prop to acknowledge it.
      if (quad != appliedQuad) unacknowledgedEdits.addLast(quad)
      onQuadChange?.invoke(quad)
    }
  }

  override val view: View
    get() = editor

  override var originalUri: String = ""
  override var quad: DocumentQuad = CropQuadEditing.FULL_FRAME.toDocumentQuad()
  override var onQuadChange: ((quad: DocumentQuad) -> Unit)? = null
  override var filter: DocumentFilter? = null
  override var onLoad: ((width: Double, height: Double) -> Unit)? = null
  override var onError: ((failure: ScannerFailure) -> Unit)? = null

  // Main thread. A load or filter result is applied only while its generation is current.
  private var loadedUri: String? = null
  private var loadGeneration = 0
  /** The decoded photo, unfiltered; null while loading or after a failure. */
  private var photo: Bitmap? = null
  /** The filter of the last preview requested. */
  private var previewFilter = DocumentFilter.NONE
  // Written on the main thread, read by the worker to skip a filter request superseded before it started.
  @Volatile private var filterGeneration = 0
  private var appliedQuad: DocumentQuad? = null
  // Edits reported to React, oldest first, until a quad prop sends one back.
  private val unacknowledgedEdits = ArrayDeque<DocumentQuad>()

  /**
   * Main thread, after each React prop batch. A quad that sends back a reported edit only acknowledges it, and the
   * editor keeps any newer edit or drag; any other quad different from the last one set replaces the crop.
   */
  override fun afterUpdate() {
    val wanted = filter ?: DocumentFilter.NONE
    if (originalUri != loadedUri) {
      previewFilter = wanted
      load(originalUri)
    } else if (wanted != previewFilter) {
      previewFilter = wanted
      photo?.let(::preview)
    }
    if (quad == appliedQuad) return
    appliedQuad = quad
    val acknowledged = unacknowledgedEdits.indexOf(quad)
    if (acknowledged >= 0) {
      repeat(acknowledged + 1) { unacknowledgedEdits.removeFirst() }
      return
    }
    unacknowledgedEdits.clear()
    editor.replaceQuad(quad.toArray())
  }

  override fun onDropView() {
    loadGeneration++
    filterGeneration++
    loadedUri = null
    photo = null
    editor.bitmap = null
  }

  /** Shows the photo at [uri] once decoded, then its preview under [previewFilter]. */
  private fun load(uri: String) {
    loadedUri = uri
    val generation = ++loadGeneration
    filterGeneration++
    // Previous bitmaps are left to the garbage collector, since the render thread may still be drawing them.
    photo = null
    editor.bitmap = null
    editor.resetZoom()
    if (uri.isEmpty()) return
    worker.execute {
      val result = runCatching { SourceImageLoader.load(uri, DECODE_LIMIT).bitmap }
      mainHandler.post {
        if (generation != loadGeneration) return@post
        result.fold(
          onSuccess = { bitmap ->
            photo = bitmap
            editor.bitmap = bitmap
            onLoad?.invoke(bitmap.width.toDouble(), bitmap.height.toDouble())
            preview(bitmap)
          },
          onFailure = { error -> fail(ScannerErrorCode.IMAGE_LOAD_FAILED, error) },
        )
      }
    }
  }

  /**
   * Shows [source] under [previewFilter]: at once for `none`, otherwise once a copy no larger than a default render is
   * filtered off the main thread, the editor keeping its current image until then. A newer request discards this one's
   * result, or skips it if not yet started.
   */
  private fun preview(source: Bitmap) {
    val generation = ++filterGeneration
    val filter = previewFilter
    if (filter == DocumentFilter.NONE) {
      editor.bitmap = source
      return
    }
    filterWorker.execute {
      if (generation != filterGeneration) return@execute
      val result = FastCores.run {
        runCatching {
          val scale = FILTER_PREVIEW_DIMENSION / max(source.width, source.height).toFloat()
          if (scale >= 1f) return@runCatching DocumentFilters.apply(source, filter)
          val width = (source.width * scale).roundToInt().coerceAtLeast(1)
          val height = (source.height * scale).roundToInt().coerceAtLeast(1)
          val reduced = Bitmap.createScaledBitmap(source, width, height, true)
          try {
            DocumentFilters.apply(reduced, filter)
          } finally {
            reduced.recycle()
          }
        }
      }
      mainHandler.post {
        if (generation != filterGeneration) return@post
        result.fold(
          onSuccess = { editor.bitmap = it },
          onFailure = { error -> fail(ScannerErrorCode.EDITOR_FILTER_FAILED, error) },
        )
      }
    }
  }

  private fun fail(code: ScannerErrorCode, error: Throwable) {
    onError?.invoke(scannerFailure(ScannerException(code, error), code))
  }

  private companion object {
    /** Full resolution for typical captures, so the 3x magnifier stays sharp. */
    const val DECODE_LIMIT = 4096
    /** Decodes photos, one at a time across editors. */
    val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "DocumentCropEditorLoader").apply { isDaemon = true }
    }
    /** The longer side the crop editor filters its photo at; detail beyond this is not visible while editing. */
    private const val FILTER_PREVIEW_DIMENSION = 2400f
    /** Filters previews, one at a time across editors, so a running filter never delays the next photo. */
    val filterWorker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "DocumentCropEditorFilter").apply { isDaemon = true }
    }
  }
}

private fun DocumentQuad.toArray() = doubleArrayOf(
  topLeft.x, topLeft.y, topRight.x, topRight.y, bottomRight.x, bottomRight.y, bottomLeft.x, bottomLeft.y,
)

private fun DoubleArray.toDocumentQuad() = DocumentQuad(
  DocumentPoint(this[0], this[1]),
  DocumentPoint(this[2], this[3]),
  DocumentPoint(this[4], this[5]),
  DocumentPoint(this[6], this[7]),
)
