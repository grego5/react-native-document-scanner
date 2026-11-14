package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.processing.CornerModelStatus
import com.margelo.nitro.documentscanner.processing.CornerModelStore

/** The app-wide model status, set by hand. An observer gets the current status at once, then each change. */
internal class ModelStatusStub {
  var status: CornerModelStatus = CornerModelStatus.Absent
    private set
  private val listeners = mutableListOf<(CornerModelStatus) -> Unit>()
  val observers get() = listeners.size

  fun observe(listener: (CornerModelStatus) -> Unit): () -> Unit {
    listeners += listener
    listener(status)
    return { listeners -= listener }
  }

  fun loading() = set(CornerModelStatus.Loading(SOURCE))
  fun ready(selection: CornerModelStore.Selection) = set(CornerModelStatus.Ready(SOURCE, selection))
  fun failed() = set(CornerModelStatus.Failed(SOURCE, ScannerException(ScannerErrorCode.MODEL_LOAD_FAILED)))

  private fun set(next: CornerModelStatus) {
    status = next
    listeners.toList().forEach { it(next) }
  }

  private companion object {
    const val SOURCE = "asset:///corner-detection-model.tflite"
  }
}
