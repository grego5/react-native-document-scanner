package com.margelo.nitro.documentscanner

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import com.facebook.react.uimanager.ThemedReactContext
import com.margelo.nitro.documentscanner.processing.DocumentFilters
import com.margelo.nitro.documentscanner.processing.FastCores
import com.margelo.nitro.documentscanner.processing.SourceImageLoader
import kotlin.math.max
import kotlin.math.roundToInt

/** Native crop editing for a captured photo; the unsubmitted draft stays in the editor. */
open class HybridDocumentCropEditorView(
  themedContext: ThemedReactContext,
) : HybridDocumentCropEditorViewSpec() {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val editor = CropEditorView(themedContext)

  init {
    editor.onQuadEdited = { publishPreset() }
    editor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
      override fun onViewAttachedToWindow(view: View) { if (!dropped) binding?.start() }
      override fun onViewDetachedFromWindow(view: View) {
        binding?.close()
        source?.close(); source = null
        loadGeneration++; filterGeneration++
        loadedUri = null; photo = null; editor.bitmap = null
        editor.resetZoom()
      }
    })
  }

  override val view: View
    get() = editor

  override lateinit var session: HybridScanSessionSpec
  override var pageId: String = ""
  private var boundState: ScanSessionState? = null
  private var boundId: String? = null
  private var binding: SessionPageBinding? = null
  private var source: SessionPageSource? = null
  private var dropped = false
  private var reportedPreset: CropDraftPreset? = null
  override var onPresetChange: ((preset: CropDraftPreset) -> Unit)? = null
    set(value) { if (field !== value) reportedPreset = null; field = value }
  override var filter: DocumentFilter? = null
  override var onLoad: ((width: Double, height: Double) -> Unit)? = null
  override var onError: ((failure: ScannerFailure) -> Unit)? = null

  // Main thread. A load or filter result is applied only while its generation is current.
  private var loadedUri: String? = null
  @Volatile private var loadGeneration = 0
  /** The decoded photo, unfiltered; null while loading or after a failure. */
  private var photo: Bitmap? = null
  /** The filter of the last preview requested. */
  private var previewFilter = DocumentFilter.NONE
  // Written on the main thread, read by the worker to skip a filter request superseded before it started.
  @Volatile private var filterGeneration = 0
  /** Bind a fresh native draft only when the configured page changes. */
  override fun afterUpdate() {
    if (dropped) return
    val state = (session as? HybridScanSession)?.state
    if (state !== boundState || pageId != boundId) {
      binding?.close()
      source?.close(); source = null
      boundState = state; boundId = pageId
      reportedPreset = null
      state?.snapshot()?.pages?.get(pageId)?.quad?.let { editor.replaceQuad(NativeQuadGeometry.coordinates(it)) }
      loadGeneration++; filterGeneration++
      loadedUri = null; photo = null; editor.bitmap = null
      binding = state?.let { owner ->
        SessionPageBinding(owner, pageId, original = true, changed = { next ->
          source?.close(); source = next
          if (next == null) {
            loadGeneration++; filterGeneration++
            loadedUri = null; photo = null; editor.bitmap = null
          } else load(next.uri)
        }, failed = { onError?.invoke(scannerFailure(it, it.code)) })
      }
      if (editor.isAttachedToWindow) binding?.start()
      if (state == null) fail(ScannerErrorCode.INPUT_INVALID, InvalidInputException("Expected a native scan session."))
    }
    publishPreset()
    val wanted = filter ?: DocumentFilter.NONE
    if (wanted != previewFilter) {
      previewFilter = wanted
      photo?.let(::preview)
    }
  }

  private fun publishPreset() {
    if (dropped || binding?.valid() != true) return
    val quad = editor.quad
    val automatic = boundState?.snapshot()?.pages?.get(boundId)?.analysis?.candidate?.quad
    val preset = when {
      quad.contentEquals(CropQuadEditing.FULL_FRAME) -> CropDraftPreset.NONE
      automatic != null && quad.contentEquals(NativeQuadGeometry.coordinates(automatic)) -> CropDraftPreset.AUTO
      else -> CropDraftPreset.CUSTOM
    }
    if (preset != reportedPreset && onPresetChange != null) {
      reportedPreset = preset
      onPresetChange?.invoke(preset)
    }
  }

  override fun submitResult(): com.margelo.nitro.core.Promise<Variant_Boolean_ScannerFailure> {
    val promise = com.margelo.nitro.core.Promise<Variant_Boolean_ScannerFailure>()
    mainHandler.post {
      val result = try {
        requireInput(!dropped && binding?.valid() == true) { "Crop editor page is unavailable." }
        boundState!!.setDocumentCrop(boundId!!, editor.quad.toDocumentQuad())
        Variant_Boolean_ScannerFailure.create(true)
      } catch (error: Throwable) { Variant_Boolean_ScannerFailure.create(scannerFailure(error, ScannerErrorCode.INPUT_INVALID)) }
      promise.resolve(result)
    }
    return promise
  }

  override fun getQuadResult(): com.margelo.nitro.core.Promise<Variant_ScannerFailure_DocumentQuad> {
    val promise = com.margelo.nitro.core.Promise<Variant_ScannerFailure_DocumentQuad>()
    mainHandler.post {
      val result = try {
        requireInput(!dropped && binding?.valid() == true) { "Crop editor page is unavailable." }
        Variant_ScannerFailure_DocumentQuad.create(editor.quad.toDocumentQuad())
      } catch (error: Throwable) { Variant_ScannerFailure_DocumentQuad.create(scannerFailure(error, ScannerErrorCode.INPUT_INVALID)) }
      promise.resolve(result)
    }
    return promise
  }

  override fun setPresetResult(preset: CropPreset): com.margelo.nitro.core.Promise<Variant_Boolean_ScannerFailure> {
    val promise = com.margelo.nitro.core.Promise<Variant_Boolean_ScannerFailure>()
    mainHandler.post {
      val result = try {
        requireInput(!dropped && binding?.valid() == true) { "Crop editor page is unavailable." }
        val coordinates = when (preset) {
          CropPreset.NONE -> CropQuadEditing.FULL_FRAME
          CropPreset.AUTO -> NativeQuadGeometry.coordinates(boundState?.snapshot()?.pages?.get(boundId)?.analysis?.candidate?.quad
            ?: throw InvalidInputException("No automatic crop is available."))
        }
        editor.replaceQuad(coordinates)
        publishPreset()
        Variant_Boolean_ScannerFailure.create(true)
      } catch (error: Throwable) { Variant_Boolean_ScannerFailure.create(scannerFailure(error, ScannerErrorCode.INPUT_INVALID)) }
      promise.resolve(result)
    }
    return promise
  }

  override fun onDropView() {
    dropped = true
    binding?.close(); binding = null
    source?.close(); source = null
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
    val pin = source?.retain()
    worker.executeRead(pin) {
      val result = try {
        runCatching { if (generation != loadGeneration) null else SourceImageLoader.load(uri, DECODE_LIMIT).bitmap }
      } finally { pin?.close() }
      mainHandler.post {
        if (generation != loadGeneration || binding?.valid() == false) { result.getOrNull()?.recycle(); return@post }
        result.fold(
          onSuccess = { bitmap ->
            if (bitmap == null) return@fold
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
    if (dropped || binding?.valid() != true) return
    val generation = ++filterGeneration
    val filter = previewFilter
    if (filter == DocumentFilter.NONE) {
      editor.bitmap = source
      return
    }
    val pin = this.source?.retain() ?: return
    filterWorker.executeRead(pin) {
      try {
        if (generation != filterGeneration) return@executeRead
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
          if (generation != filterGeneration || binding?.valid() == false) { result.getOrNull()?.recycle(); return@post }
          result.fold(
            onSuccess = { editor.bitmap = it },
            onFailure = { error -> fail(ScannerErrorCode.EDITOR_FILTER_FAILED, error) },
          )
        }
      } finally { pin.close() }
    }
  }

  private fun fail(code: ScannerErrorCode, error: Throwable) {
    onError?.invoke(scannerFailure(ScannerException(code, error), code))
  }

  private companion object {
    /** Full resolution for typical captures, so the 3x magnifier stays sharp. */
    const val DECODE_LIMIT = 4096
    val worker = ScannerImageReads
    /** The longer side the crop editor filters its photo at; detail beyond this is not visible while editing. */
    private const val FILTER_PREVIEW_DIMENSION = 2400f
    val filterWorker = ScannerImageReads
  }
}

private fun DoubleArray.toDocumentQuad() = DocumentQuad(
  DocumentPoint(this[0], this[1]),
  DocumentPoint(this[2], this[3]),
  DocumentPoint(this[4], this[5]),
  DocumentPoint(this[6], this[7]),
)
