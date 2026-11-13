package com.margelo.nitro.documentscanner

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.margelo.nitro.NitroModules

/**
 * Presents the system photo picker, which needs no library permission and falls back to the document picker where it
 * is unavailable. Picked `content://` grants last while the activity lives, so imports read them straight away.
 * One picker is open at a time across sessions. The launching activity owns the request: if it is destroyed before
 * a result, for example by recreation, the pick settles as superseded and releases the busy slot. Main thread.
 * The picker only presents and reports picks; [LibraryImport] owns the call from there.
 */
internal object LibraryPicker {
  private class Request(val onResult: (Result<List<Uri>>) -> Unit) {
    var launcher: ActivityResultLauncher<PickVisualMediaRequest>? = null
    var release: () -> Unit = {}
  }

  private var current: Request? = null
  private var keys = 0L

  /** The thread every picker call and handle runs on. */
  val main = Handler(Looper.getMainLooper())

  /**
   * Presents the picker; [onResult] runs once. The returned handle ends the request early: the result is dropped,
   * settling as disposed, and the busy slot is released even while the system picker is still on screen.
   */
  fun pick(maxCount: Int, onResult: (Result<List<Uri>>) -> Unit): () -> Unit {
    check(Looper.myLooper() == Looper.getMainLooper()) { "The library picker opens on the main thread." }
    if (current != null) throw ScannerException(ScannerErrorCode.CAPTURE_BUSY)
    val activity = NitroModules.applicationContext?.currentActivity as? ComponentActivity
    if (activity == null || activity.isFinishing || activity.isDestroyed ||
      !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
      throw ScannerException(ScannerErrorCode.PROCESSOR_UNAVAILABLE)
    }
    val request = Request(onResult)
    current = request
    try {
      val key = "react-native-document-scanner.library-images.${++keys}"
      val registry = activity.activityResultRegistry
      request.launcher = if (maxCount == 1) {
        registry.register(key, ActivityResultContracts.PickVisualMedia()) { settle(request, Result.success(listOfNotNull(it))) }
      } else {
        val contract = if (maxCount > 1) ActivityResultContracts.PickMultipleVisualMedia(maxCount)
          else ActivityResultContracts.PickMultipleVisualMedia()
        registry.register(key, contract) { settle(request, Result.success(it)) }
      }
      val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) settle(request, Result.failure(ScannerException(ScannerErrorCode.PROCESSOR_SUPERSEDED)))
      }
      activity.lifecycle.addObserver(observer)
      request.release = { activity.lifecycle.removeObserver(observer) }
      // The photo picker returns picks in the order the user tapped them; the document-picker fallback keeps its own.
      request.launcher?.launch(PickVisualMediaRequest.Builder()
        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
        .setOrderedSelection(true)
        .build())
    } catch (error: Throwable) {
      settle(request, Result.failure(error))
    }
    return { settle(request, Result.failure(ScannerException(ScannerErrorCode.PROCESSOR_DISPOSED))) }
  }

  private fun settle(request: Request, result: Result<List<Uri>>) {
    if (current !== request) return
    current = null
    request.launcher?.unregister()
    request.release()
    request.onResult(result)
  }
}
