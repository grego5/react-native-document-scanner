package com.margelo.nitro.documentscanner

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import com.margelo.nitro.documentscanner.processing.CachedImageFiles
import com.margelo.nitro.documentscanner.processing.DocumentRenderer
import com.margelo.nitro.documentscanner.processing.FastCores
import com.margelo.nitro.documentscanner.processing.CornerInference
import com.margelo.nitro.documentscanner.processing.QuadModelStore
import com.margelo.nitro.documentscanner.processing.DocCornerSession
import com.margelo.nitro.documentscanner.processing.PhotoInferenceCrop
import com.margelo.nitro.documentscanner.processing.SourceImageLoader
import com.margelo.nitro.documentscanner.processing.PdfWriter
import com.margelo.nitro.documentscanner.processing.SampleCollection
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Image analysis is independent of the camera view and requires no Activity or camera permission. */
@DoNotStrip
@Keep
class HybridDocumentScannerProcessor(
  private val contextOverride: Context? = null,
) : HybridDocumentScannerProcessorSpec() {
  private val disposed = AtomicBoolean(false)
  // This executor owns a lease; the model worker owns the single shared interpreter and buffers.
  private var modelSession: CornerInference.Lease? = null
  private val pendingPromises = Collections.newSetFromMap(
    java.util.IdentityHashMap<Promise<*>, Boolean>(),
  )
  private val executor = ThreadPoolExecutor(
    1,
    1,
    0L,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue(1),
    { task -> Thread(task, "DocumentScannerAnalysis").apply { isDaemon = true } },
    ThreadPoolExecutor.AbortPolicy(),
  )
  // Deletions are small and never wait behind, or count against, the bounded image queue.
  private val cleanupExecutor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "DocumentScannerCleanup").apply { isDaemon = true }
  }

  override fun analyzePhotoResult(originalUri: String, liveQuad: DocumentQuad?): Promise<Variant_ScannerFailure_DocumentAnalysis> =
    analyzePhoto(originalUri, liveQuad)
      .scannerResult(ScannerErrorCode.ANALYSIS_FAILED,
      { Variant_ScannerFailure_DocumentAnalysis.create(it) }, { Variant_ScannerFailure_DocumentAnalysis.create(it) })

  override fun renderDocumentResult(originalUri: String, quad: DocumentQuad, options: DocumentRenderOptions?): Promise<Variant_ScannerFailure_RenderedDocument> =
    renderDocument(originalUri, quad, options).scannerResult(ScannerErrorCode.RENDER_FAILED,
      { Variant_ScannerFailure_RenderedDocument.create(it) }, { Variant_ScannerFailure_RenderedDocument.create(it) })

  override fun createPdfResult(options: CreatePdfOptions): Promise<Variant_String_ScannerFailure> =
    createPdf(options).scannerResult(ScannerErrorCode.PDF_WRITE_FAILED,
      { Variant_String_ScannerFailure.create(it) }, { Variant_String_ScannerFailure.create(it) })

  override fun removeCachedImagesResult(uris: Array<String>): Promise<Variant_Boolean_ScannerFailure> =
    removeCachedImages(uris).scannerResult(ScannerErrorCode.CACHE_DELETE_FAILED,
      { Variant_Boolean_ScannerFailure.create(true) }, { Variant_Boolean_ScannerFailure.create(it) })

  override fun pickSampleFolderResult(): Promise<Variant_ScannerFailure_SampleFolder> {
    val promise = Promise<Variant_ScannerFailure_SampleFolder>()
    Handler(Looper.getMainLooper()).post {
      fun failure(error: Throwable) = promise.resolve(Variant_ScannerFailure_SampleFolder.create(
        scannerFailure(error, ScannerErrorCode.PERMISSION_REQUEST_FAILED)))
      try {
        SampleCollection.pick(context("sample collection")) { result ->
          result.fold({ promise.resolve(Variant_ScannerFailure_SampleFolder.create(it)) }, ::failure)
        }
      } catch (error: Throwable) {
        failure(error)
      }
    }
    return promise
  }

  override fun getSampleFolder(): SampleFolder = SampleCollection.current(context("sample collection"))

  override fun clearSampleFolder() = SampleCollection.clear(context("sample collection"))

  override fun setSampleFolder(uri: String) = SampleCollection.configure(context("sample collection"), uri, null)

  override fun setSampleCollection(on: Boolean) = SampleCollection.configure(context("sample collection"), null, on)

  override fun saveCaptureSampleResult(originalUri: String, quad: DocumentQuad?): Promise<Variant_String_ScannerFailure> =
    SampleCollection.saveCapture(context("sample collection"), originalUri, quad).scannerResult(ScannerErrorCode.STORAGE_WRITE_FAILED,
      { Variant_String_ScannerFailure.create(it) }, { Variant_String_ScannerFailure.create(it) })

  /** One inference on the live-guided region, or the whole photo when no valid seed was supplied. */
  internal fun analyzePhoto(originalUri: String, liveQuad: DocumentQuad? = null): Promise<DocumentAnalysis> = submit {
    val startedAt = System.nanoTime()
    val session = openSession()
    SourceImageLoader.load(originalUri, DocCornerSession.MAX_PHOTO_DIMENSION).use { source ->
      if (disposed.get()) throw ScannerException(ScannerErrorCode.PROCESSOR_DISPOSED)
      val bitmap = source.bitmap
      val crop = PhotoInferenceCrop.from(liveQuad, bitmap.width, bitmap.height)
      val region = crop?.let { Bitmap.createBitmap(bitmap, it.left, it.top, it.width, it.height) } ?: bitmap
      val candidate = try {
        session.analyze(region)?.let { result ->
          if (crop == null) result else result.copy(quad = crop.toPhoto(result.quad, bitmap.width, bitmap.height))
        }
      } finally {
        if (region !== bitmap) region.recycle()
      }
      val turned = source.exifOrientation in 5..8
      DocumentAnalysis(
        sourceWidth = (if (turned) source.encodedHeight else source.encodedWidth).toDouble(),
        sourceHeight = (if (turned) source.encodedWidth else source.encodedHeight).toDouble(),
        duration = (System.nanoTime() - startedAt) / 1_000_000.0,
        candidate = candidate,
      ).also { analysis ->
        Uri.parse(originalUri).lastPathSegment?.let { name ->
          SampleCollection.noteAnalysis(name, analysis.sourceWidth, analysis.sourceHeight, analysis.duration, candidate,
            session.lastSelection ?: QuadModelStore.current)
        }
      }
    }
  }

  private fun openSession(): CornerInference.Lease {
    if (disposed.get()) throw ScannerException(ScannerErrorCode.PROCESSOR_DISPOSED)
    return modelSession ?: CornerInference.acquire(context("the corner model")).also { modelSession = it }
  }

  internal fun renderDocument(
    originalUri: String,
    quad: DocumentQuad,
    options: DocumentRenderOptions?,
  ): Promise<RenderedDocument> = submit { FastCores.run { DocumentRenderer.render(originalUri, quad, options) } }

  internal fun createPdf(options: CreatePdfOptions): Promise<String> = submit { PdfWriter.write(options) }

  internal fun removeCachedImages(uris: Array<String>): Promise<Unit> = submit(cleanupExecutor) {
    try {
      SampleCollection.forget(uris.mapNotNull { Uri.parse(it).lastPathSegment })
      CachedImageFiles.remove(CachedImageFiles.directory(context("the cache folder")), uris.toList())
    } finally {
      // Also after a partial failure; a forgotten thumbnail of a kept page is made again from the page.
      PageThumbnails.forget(uris.asList())
    }
  }

  private fun context(purpose: String): Context = contextOverride ?: NitroModules.applicationContext?.applicationContext
    ?: throw ScannerException(ScannerErrorCode.PROCESSOR_UNAVAILABLE,
      IllegalStateException("React Native application context is unavailable for $purpose."))

  private fun <T> submit(target: ExecutorService = executor, operation: () -> T): Promise<T> {
    val promise = Promise<T>()
    synchronized(pendingPromises) {
      if (disposed.get()) {
        promise.reject(ScannerException(ScannerErrorCode.PROCESSOR_DISPOSED))
        return promise
      }
      pendingPromises.add(promise)
      try {
        target.execute {
          if (!disposed.get()) {
            try {
              resolveOnce(promise, operation())
            } catch (error: Throwable) {
              rejectOnce(promise, error)
            }
          }
        }
      } catch (error: RejectedExecutionException) {
        rejectOnce(promise, ScannerException(if (disposed.get()) ScannerErrorCode.PROCESSOR_DISPOSED else ScannerErrorCode.PROCESSOR_BUSY, error))
      }
    }
    return promise
  }

  override fun dispose() {
    val pending = synchronized(pendingPromises) {
      if (disposed.compareAndSet(false, true)) {
        val rejected = pendingPromises.toList()
        pendingPromises.clear()
        executor.queue.clear()
        executor.execute {
          modelSession?.close()
          modelSession = null
        }
        executor.shutdown()
        cleanupExecutor.shutdown()
        rejected
      } else {
        emptyList()
      }
    }
    pending.forEach { it.reject(ScannerException(ScannerErrorCode.PROCESSOR_DISPOSED)) }
    super.dispose()
  }

  private fun <T> resolveOnce(promise: Promise<T>, result: T) {
    val accepted = synchronized(pendingPromises) { pendingPromises.remove(promise) }
    if (accepted) promise.resolve(result)
  }

  private fun <T> rejectOnce(promise: Promise<T>, error: Throwable) {
    val accepted = synchronized(pendingPromises) { pendingPromises.remove(promise) }
    if (accepted) promise.reject(error)
  }

}
