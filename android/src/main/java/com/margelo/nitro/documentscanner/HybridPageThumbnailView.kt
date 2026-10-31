package com.margelo.nitro.documentscanner

import android.view.View
import com.facebook.react.uimanager.ThemedReactContext

open class HybridPageThumbnailView(context: ThemedReactContext) : HybridPageThumbnailViewSpec() {
  private val thumbnail = PageThumbnailView(context)
  override val view: View get() = thumbnail
  override lateinit var session: HybridScanSessionSpec
  override var pageId: String = ""
  override var resizeMode: PageThumbnailResizeMode? = null

  override fun afterUpdate() {
    thumbnail.bind((session as? HybridScanSession)?.state, pageId, resizeMode ?: PageThumbnailResizeMode.COVER)
  }

  override fun onDropView() = thumbnail.dispose()
}
