package com.margelo.nitro.documentscanner

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import com.margelo.nitro.documentscanner.processing.SampleCollection

/** Stateless session factory and independent sample-folder controls. */
@DoNotStrip
@Keep
class HybridDocumentScannerProcessor(
  private val contextOverride: Context? = null,
) : HybridDocumentScannerProcessorSpec() {
  override fun createSessionResult(): Variant_HybridScanSessionSpec_ScannerFailure = try {
    Variant_HybridScanSessionSpec_ScannerFailure.create(HybridScanSession(context("a scan session")))
  } catch (error: Throwable) {
    Variant_HybridScanSessionSpec_ScannerFailure.create(scannerFailure(error, ScannerErrorCode.PROCESSOR_UNAVAILABLE))
  }

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

  private fun context(purpose: String): Context = contextOverride ?: NitroModules.applicationContext?.applicationContext
    ?: throw ScannerException(ScannerErrorCode.PROCESSOR_UNAVAILABLE,
      IllegalStateException("React Native application context is unavailable for $purpose."))

}
