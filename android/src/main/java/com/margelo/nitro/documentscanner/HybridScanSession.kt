package com.margelo.nitro.documentscanner

import android.content.Context
import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import com.margelo.nitro.documentscanner.processing.SampleCollection
import com.margelo.nitro.documentscanner.processing.CachedImageFiles
import java.util.concurrent.CompletionException

/** Thin typed boundary. Registry/state, rather than this JS wrapper's GC lifetime, own all pages. */
@Keep
@DoNotStrip
class HybridScanSession internal constructor(internal val state: ScanSessionState) : HybridScanSessionSpec() {
  constructor() : this(createState(null))
  internal constructor(context: Context) : this(createState(context))

  private val exports = SessionExports(state)

  override fun createPdfResult(pageIds: Array<String>?, options: SessionPdfOptions): Promise<Variant_String_ScannerFailure> =
    exported(ScannerErrorCode.PDF_WRITE_FAILED) { exports.createPdf(pageIds?.toList(), options) }

  override fun exportImagesResult(pageIds: Array<String>?, outputDirectory: String): Promise<Variant_Array_String__ScannerFailure> {
    val promise = Promise<Variant_Array_String__ScannerFailure>()
    try {
      exports.exportImages(pageIds?.toList(), outputDirectory).whenComplete { uris, error ->
        if (error == null) promise.resolve(Variant_Array_String__ScannerFailure.create(uris.toTypedArray()))
        else promise.resolve(Variant_Array_String__ScannerFailure.create(scannerFailure(cause(error), ScannerErrorCode.STORAGE_WRITE_FAILED)))
      }
    } catch (error: Throwable) {
      promise.resolve(Variant_Array_String__ScannerFailure.create(scannerFailure(error, ScannerErrorCode.STORAGE_WRITE_FAILED)))
    }
    return promise
  }

  override fun saveCaptureSampleResult(pageId: String): Promise<Variant_String_ScannerFailure> {
    val promise = Promise<String>()
    try {
      val context = NitroModules.applicationContext ?: throw ScannerException(ScannerErrorCode.PROCESSOR_UNAVAILABLE)
      val source = state.sampleSource(pageId)
      SampleCollection.saveCapture(context, source.original, analysis = source.analysis, model = source.model, liveCorners = source.liveCorners).then { name ->
        try { promise.resolve(state.publishExport { name }) } catch (error: Throwable) { promise.reject(error) }
      }.catch { promise.reject(it) }
    } catch (error: Throwable) { promise.reject(error) }
    return promise.scannerResult(ScannerErrorCode.STORAGE_WRITE_FAILED,
      Variant_String_ScannerFailure::create, Variant_String_ScannerFailure::create)
  }

  private fun exported(fallback: ScannerErrorCode, operation: () -> java.util.concurrent.CompletableFuture<String>): Promise<Variant_String_ScannerFailure> {
    val promise = Promise<Variant_String_ScannerFailure>()
    try {
      operation().whenComplete { value, error ->
        if (error == null) promise.resolve(Variant_String_ScannerFailure.create(value))
        else promise.resolve(Variant_String_ScannerFailure.create(scannerFailure(cause(error), fallback)))
      }
    } catch (error: Throwable) { promise.resolve(Variant_String_ScannerFailure.create(scannerFailure(error, fallback))) }
    return promise
  }

  private fun cause(error: Throwable): Throwable = if (error is CompletionException) error.cause ?: error else error

  override fun setDocumentCropResult(pageId: String, corners: DocumentCorners) = admit { state.setDocumentCrop(pageId, corners) }
  override fun setFilterResult(pageId: String, filter: DocumentFilter) = admit { state.setFilter(pageId, filter) }
  override fun setShadowRemovalResult(pageId: String, enabled: Boolean) =
    admit { state.setShadowRemoval(pageId, enabled) }
  override fun setPageOrderResult(pageIds: Array<String>) = admit { state.setPageOrder(pageIds.toList()) }
  override fun removePageResult(pageId: String) = admit { state.removePage(pageId) }

  private fun admit(action: () -> Unit): Promise<Variant_Boolean_ScannerFailure> {
    val result = try {
      action()
      Variant_Boolean_ScannerFailure.create(true)
    } catch (error: Throwable) {
      Variant_Boolean_ScannerFailure.create(scannerFailure(error, ScannerErrorCode.PROCESSOR_UNAVAILABLE))
    }
    return Promise<Variant_Boolean_ScannerFailure>().also { it.resolve(result) }
  }

  override fun getSession() = state.snapshot()

  override fun observeResult(onChange: (ScanSessionChange) -> Unit): Variant_ScanSessionObservation_ScannerFailure =
    try {
      val registration = state.observe(onChange)
      Variant_ScanSessionObservation_ScannerFailure.create(
        ScanSessionObservation(registration.snapshot, registration.unsubscribe))
    } catch (error: Throwable) {
      Variant_ScanSessionObservation_ScannerFailure.create(scannerFailure(error, ScannerErrorCode.PROCESSOR_UNAVAILABLE))
    }

  override fun closeResult(): Promise<Variant_Boolean_ScannerFailure> {
    val promise = Promise<Variant_Boolean_ScannerFailure>()
    val completion = try { state.close() } catch (error: Throwable) {
      promise.resolve(Variant_Boolean_ScannerFailure.create(scannerFailure(error, ScannerErrorCode.CACHE_DELETE_FAILED)))
      return promise
    }
    completion.whenComplete { _, error ->
      val result = if (error == null) Variant_Boolean_ScannerFailure.create(true) else {
        val cause = if (error is CompletionException) error.cause ?: error else error
        Variant_Boolean_ScannerFailure.create(scannerFailure(cause, ScannerErrorCode.CACHE_DELETE_FAILED))
      }
      promise.resolve(result)
    }
    return promise
  }

  companion object {
    private fun createState(contextOverride: Context?): ScanSessionState {
      val context = contextOverride?.applicationContext ?: NitroModules.applicationContext?.applicationContext
        ?: throw ScannerException(ScannerErrorCode.PROCESSOR_UNAVAILABLE)
      return ScanSessionRegistry.shared.createState(CachedImageFiles.directory(context)) {
        SessionPageProcessing(context, it)
      }
    }
  }
}
