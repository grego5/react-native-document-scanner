package com.margelo.nitro.documentscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import com.margelo.nitro.documentscanner.processing.CornerTensorModelLoader
import com.margelo.nitro.documentscanner.processing.SampleCollection

/** Stateless session factory, independent sample-folder controls and app-wide diagnostics. */
@DoNotStrip
@Keep
class HybridDocumentScannerProcessor(
  private val contextOverride: Context? = null,
) : HybridDocumentScannerProcessorSpec() {
  private val models = CornerTensorModelLoader.shared

  override fun createSessionResult(): Variant_HybridScanSessionSpec_ScannerFailure = try {
    val context = context("a scan session")
    // The shared default request outlives any session.
    Variant_HybridScanSessionSpec_ScannerFailure.create(HybridScanSession(context)).also { models.startDefault(context) }
  } catch (error: Throwable) {
    Variant_HybridScanSessionSpec_ScannerFailure.create(scannerFailure(error, ScannerErrorCode.PROCESSOR_UNAVAILABLE))
  }

  override fun createTextSessionResult(): Variant_HybridTextSessionSpec_ScannerFailure =
    Variant_HybridTextSessionSpec_ScannerFailure.create(HybridTextSession())

  override fun waitForCornerDetectionModelResult(): Promise<Variant_Boolean_ScannerFailure> {
    val promise = Promise<Variant_Boolean_ScannerFailure>()
    try {
      models.startDefault(context("the default model"))
      models.await { result ->
        result.fold({ promise.resolve(Variant_Boolean_ScannerFailure.create(it)) }, { error ->
          promise.resolve(Variant_Boolean_ScannerFailure.create(scannerFailure(error, ScannerErrorCode.MODEL_LOAD_FAILED)))
        })
      }
    } catch (error: Throwable) {
      promise.resolve(Variant_Boolean_ScannerFailure.create(scannerFailure(error, ScannerErrorCode.PROCESSOR_UNAVAILABLE)))
    }
    return promise
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

  override fun setDiagnosticsEnabled(on: Boolean) {
    ScannerDiagnostics.enabled = on
  }

  override fun exportDiagnosticsResult(): Promise<Variant_String_ScannerFailure> {
    val promise = Promise<Variant_String_ScannerFailure>()
    val context = try { context("diagnostics") } catch (error: Throwable) {
      promise.resolve(Variant_String_ScannerFailure.create(scannerFailure(error, ScannerErrorCode.PROCESSOR_UNAVAILABLE)))
      return promise
    }
    // Writing a few megabytes is disk work, kept off the JS and main threads.
    kotlin.concurrent.thread(name = "scanner-diagnostics-export") {
      try {
        promise.resolve(Variant_String_ScannerFailure.create(android.net.Uri.fromFile(ScannerDiagnostics.export(context)).toString()))
      } catch (error: Throwable) {
        promise.resolve(Variant_String_ScannerFailure.create(scannerFailure(error, ScannerErrorCode.PROCESSOR_UNAVAILABLE)))
      }
    }
    return promise
  }

  override fun copyText(text: String) {
    val clipboard = context("clipboard").getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
  }

  private fun context(purpose: String): Context = contextOverride ?: NitroModules.applicationContext?.applicationContext
    ?: throw ScannerException(ScannerErrorCode.PROCESSOR_UNAVAILABLE,
      IllegalStateException("React Native application context is unavailable for $purpose."))

}
