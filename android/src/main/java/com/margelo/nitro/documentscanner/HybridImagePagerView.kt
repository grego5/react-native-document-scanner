package com.margelo.nitro.documentscanner

import android.view.View
import com.facebook.react.uimanager.ThemedReactContext

/** Native paging between zoomable images; swipes and zoom never cross into JavaScript. */
open class HybridImagePagerView(
  themedContext: ThemedReactContext,
) : HybridImagePagerViewSpec() {
  private var boundState: ScanSessionState? = null
  private var previousSelection: String? = null
  private var previousInput: List<String>? = null
  private var unsubscribe: (() -> Unit)? = null
  private var displayedPageIds: List<String> = emptyList()
  private val pager = ImagePagerView(
    themedContext,
    onPageSettled = { index -> displayedPageIds.getOrNull(index)?.let { onPageChange?.invoke(it) } },
    onImageLoaded = { onLoad?.invoke(it) },
    onImageFailed = { onError?.invoke(scannerFailure(it, it.code)) },
  )

  override val view: View
    get() = pager

  override lateinit var session: HybridScanSessionSpec
  override var pageIds: Array<String>? = null
  override var direction: ImagePagerDirection? = null
  override var selectedPageId: String = ""
  override var swipeEnabled: Boolean? = null
  override var onPageChange: ((pageId: String) -> Unit)? = null
  override var onLoad: ((uri: String) -> Unit)? = null
  override var onError: ((failure: ScannerFailure) -> Unit)? = null

  override fun afterUpdate() {
    pager.swipeEnabled = swipeEnabled ?: true
    pager.setDirection(direction ?: ImagePagerDirection.AUTO)
    val state = (session as? HybridScanSession)?.state
    if (state !== boundState) {
      unsubscribe?.invoke()
      boundState = state
      previousInput = null
      previousSelection = null
      unsubscribe = state?.takeUnless { it.snapshot().closed }?.observe { change ->
        if (change.closed || change.pageIds != null || change.removedPageIds.isNotEmpty() ||
          change.updatedPages.keys.any { it !in displayedPageIds }) refreshPages()
      }?.unsubscribe
    }
    val input = pageIds?.toList()
    try {
      if (input != null && input != previousInput && state?.snapshot()?.closed == false) {
        state.setPageOrder(input)
      }
      previousInput = input
      if (selectedPageId != previousSelection && selectedPageId.isNotEmpty() && state?.snapshot()?.closed == false) {
        requireInput(state.hasPage(selectedPageId)) { "Selected page must belong to the session." }
      }
      previousSelection = selectedPageId
      refreshPages()
    } catch (error: Throwable) {
      displayedPageIds = emptyList()
      pager.setPages(emptyList(), null)
      onError?.invoke(scannerFailure(error, ScannerErrorCode.INPUT_INVALID))
    }
  }

  private fun refreshPages() {
    val state = boundState
    val snapshot = state?.snapshot()
    if (snapshot == null || snapshot.closed) {
      displayedPageIds = emptyList()
      pager.setPages(emptyList(), null)
      return
    }
    displayedPageIds = snapshot.pageIds.asList()
    pager.setPages(displayedPageIds, state)
    val selected = displayedPageIds.indexOf(selectedPageId)
    if (selected >= 0) pager.show(selected)

  }

  override fun onDropView() {
    unsubscribe?.invoke(); unsubscribe = null
    pager.release()
  }
}
