package com.margelo.nitro.documentscanner

import android.os.Handler
import android.os.Looper
import android.view.View
import com.facebook.react.uimanager.ThemedReactContext

/** A native zoomable image; its gestures never cross into JavaScript. */
open class HybridImageViewerView(
  themedContext: ThemedReactContext,
) : HybridImageViewerViewSpec() {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val image = ImageViewerView(themedContext)
  /** Written on the main thread, read from JavaScript. */
  @Volatile private var zoomed = false
  private val loader = ImageLoader(
    image,
    onLoaded = { onLoad?.invoke(it) },
    onFailed = { onError?.invoke(scannerFailure(it, it.code)) },
    onZoomChanged = {
      zoomed = it
      onZoomChange?.invoke(it)
    },
  )

  override val view: View
    get() = image

  override lateinit var session: HybridScanSessionSpec
  override var pageId: String = ""
  override var onLoad: ((uri: String) -> Unit)? = null
  override var onError: ((failure: ScannerFailure) -> Unit)? = null
  override var onZoomChange: ((zoomed: Boolean) -> Unit)? = null

  override fun isZoomed() = zoomed

  override fun resetZoom() {
    mainHandler.post(image::resetZoom)
  }

  override fun afterUpdate() {
    val state = (session as? HybridScanSession)?.state
    if (state == null) {
      loader.clear()
      onError?.invoke(scannerFailure(InvalidInputException("Expected a native scan session."), ScannerErrorCode.INPUT_INVALID))
    } else loader.bind(state, pageId)
  }

  // Explicit unmount detaches subscriptions and invalidates even already queued decodes.
  override fun onDropView() {
    onLoad = null; onError = null; onZoomChange = null
    loader.clear()
  }
}
