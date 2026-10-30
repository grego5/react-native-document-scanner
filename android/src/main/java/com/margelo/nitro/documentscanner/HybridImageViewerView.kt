package com.margelo.nitro.documentscanner

import android.os.Handler
import android.os.Looper
import android.view.View
import com.facebook.react.uimanager.ThemedReactContext

/** A native zoomable image; its gestures never cross into JavaScript. */
class HybridImageViewerView(
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

  override var uri: String = ""
  override var onLoad: ((uri: String) -> Unit)? = null
  override var onError: ((failure: ScannerFailure) -> Unit)? = null
  override var onZoomChange: ((zoomed: Boolean) -> Unit)? = null

  override fun isZoomed() = zoomed

  override fun resetZoom() {
    mainHandler.post(image::resetZoom)
  }

  override fun afterUpdate() = loader.show(uri, fade = true)

  // A dropped view may stay on screen while its screen animates away, so it keeps its image until detached, where the
  // loader lets go of it.
  override fun onDropView() = Unit
}
