package com.margelo.nitro.documentscanner

import android.view.View
import com.facebook.react.uimanager.ThemedReactContext

/** Native paging between zoomable images; swipes and zoom never cross into JavaScript. */
class HybridImagePagerView(
  themedContext: ThemedReactContext,
) : HybridImagePagerViewSpec() {
  private val pager = ImagePagerView(
    themedContext,
    onPageSettled = { onIndexChange?.invoke(it.toDouble()) },
    onImageLoaded = { onLoad?.invoke(it) },
    onImageFailed = { onError?.invoke(scannerFailure(it, it.code)) },
  )

  override val view: View
    get() = pager

  override var uris: Array<String> = emptyArray()
  override var index: Double = 0.0
  override var swipeEnabled: Boolean? = null
  override var onIndexChange: ((index: Double) -> Unit)? = null
  override var onLoad: ((uri: String) -> Unit)? = null
  override var onError: ((failure: ScannerFailure) -> Unit)? = null

  override fun afterUpdate() {
    pager.swipeEnabled = swipeEnabled ?: true
    pager.setPages(uris.asList())
    pager.show(index.toInt())
  }

  override fun onDropView() = pager.release()
}
