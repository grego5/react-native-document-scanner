package com.margelo.nitro.documentscanner

import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.core.Promise

/** JS access to [CameraPermissions]; calls arrive on the JS thread and run on the main thread. */
@DoNotStrip
@Keep
class HybridCameraPermission : HybridCameraPermissionSpec() {
  private val mainHandler = Handler(Looper.getMainLooper())

  override fun getStatus(): CameraPermissionStatus = CameraPermissions.status()

  override fun requestResult(): Promise<Variant_CameraPermissionValue_ScannerFailure> {
    val promise = Promise<Variant_CameraPermissionValue_ScannerFailure>()
    mainHandler.post {
      fun failure(error: Throwable) = promise.resolve(Variant_CameraPermissionValue_ScannerFailure.create(
        scannerFailure(error, ScannerErrorCode.PERMISSION_REQUEST_FAILED)))
      try {
        CameraPermissions.request { result ->
          result.fold(
            { promise.resolve(Variant_CameraPermissionValue_ScannerFailure.create(CameraPermissionValue(it))) },
            ::failure,
          )
        }
      } catch (error: Throwable) {
        failure(error)
      }
    }
    return promise
  }

  override fun addStatusListener(listener: (status: CameraPermissionStatus) -> Unit): () -> Unit {
    var remove: (() -> Unit)? = null
    // Removal is posted after the addition, so it always finds it.
    mainHandler.post { remove = CameraPermissions.addListener(listener) }
    return { mainHandler.post { remove?.invoke() } }
  }
}
