package com.margelo.nitro.documentscanner

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * Pages of [ImageViewerView] side by side, snapping one view width at a time, each loaded by a [ImageLoader]. Paging
 * is blocked while the shown page is zoomed, so its drags pan it; a page that leaves, by a swipe or a jump, fits again.
 * Only the shown page reports a failure; a neighbour that failed loads again once shown. Main thread only.
 */
@SuppressLint("ViewConstructor")
internal class ImagePagerView(
  context: Context,
  /** A swipe settled on another page. */
  private val onPageSettled: (index: Int) -> Unit,
  private val onImageLoaded: (uri: String) -> Unit,
  /** The current page could not be loaded. */
  private val onImageFailed: (error: ScannerException) -> Unit,
) : RecyclerView(context) {
  var swipeEnabled = true

  private var session: ScanSessionState? = null
  private var pageIds: List<String> = emptyList()
  private var direction = ImagePagerDirection.AUTO
  internal var current = 0
    private set
  /** The last index asked for; only a new one moves the pager, so other prop updates never interrupt a swipe. */
  private var requested = -1
  /** Set once React has dropped the pager, which may stay on screen while its screen animates away. */
  private var dropped = false
  private val snapHelper = PagerSnapHelper()

  init {
    layoutManager = object : LinearLayoutManager(context, HORIZONTAL, false) {
      override fun canScrollHorizontally() =
        // The snap helper asks this before finding its snap view, so the shown page is looked up by position.
        swipeEnabled && (findViewByPosition(current) as? ImageViewerView)?.isZoomed != true && super.canScrollHorizontally()

      // Lays out one page beyond each side, so a neighbour has decoded before a swipe reveals it.
      override fun calculateExtraLayoutSpace(state: State, extraLayoutSpace: IntArray) {
        extraLayoutSpace[0] = width
        extraLayoutSpace[1] = width
      }
    }
    // The page last swiped away stays cached with its image, so swiping back is instant; the rest release theirs.
    setItemViewCacheSize(1)
    adapter = PageAdapter()
    snapHelper.attachToRecyclerView(this)
    addOnScrollListener(object : OnScrollListener() {
      override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        if (newState != SCROLL_STATE_IDLE) return
        val snapped = snapHelper.findSnapView(layoutManager)?.let(::getChildAdapterPosition) ?: return
        if (snapped == NO_POSITION || snapped == current) return
        settle(snapped)
        onPageSettled(snapped)
      }
    })
  }

  /** Retains selection by ID across input order changes; a replaced owner releases all old bindings. */
  @SuppressLint("NotifyDataSetChanged")
  fun setPages(next: List<String>, state: ScanSessionState? = session) {
    if (next == pageIds && state === session) return
    val selected = pageIds.getOrNull(current).takeIf { state === session }
    for (index in 0 until childCount) {
      val holder = getChildViewHolder(getChildAt(index)) as PageHolder
      if (state !== session || holder.boundId !in next) holder.loader.clear()
    }
    session = state
    pageIds = next
    adapter?.notifyDataSetChanged()
    val retained = selected?.let(next::indexOf)?.takeIf { it >= 0 }
    if (retained != null) {
      settle(retained)
      scrollToPosition(retained)
    } else requested = -1
  }

  fun setDirection(direction: ImagePagerDirection) {
    if (this.direction == direction) return
    this.direction = direction
    val wanted = when (direction) {
      ImagePagerDirection.AUTO -> LAYOUT_DIRECTION_INHERIT
      ImagePagerDirection.LTR -> LAYOUT_DIRECTION_LTR
      ImagePagerDirection.RTL -> LAYOUT_DIRECTION_RTL
    }
    stopScroll()
    layoutDirection = wanted
    scrollToPosition(current)
    requestLayout()
  }

  /** Jumps to [index], clamped to the pages, when it differs from the index asked for before. */
  fun show(index: Int) {
    val target = index.coerceIn(0, (pageIds.size - 1).coerceAtLeast(0))
    if (target == requested) return
    requested = target
    if (target == current && childCount > 0) return
    settle(target)
    scrollToPosition(target)
  }

  /** Makes [position] the shown page: the others fit again, and it loads again if it had failed off screen. */
  private fun settle(position: Int) {
    current = position
    for (index in 0 until childCount) {
      val holder = getChildViewHolder(getChildAt(index)) as PageHolder
      if (holder.bindingAdapterPosition == position) holder.loader.retry() else holder.image.resetZoom()
    }
  }

  /**
   * Detaches bindings immediately without recycling transitioning child views. React screens can keep their parents
   * during exit animations, so adapter notifications and relayout stop until physical removal.
   */
  fun release() {
    dropped = true
    // Parent traversals can still arrive while react-native-screens retains transitioning children.
    suppressLayout(true)
    removeCallbacks(relayout)
    stopScroll()
    for (index in 0 until childCount) (getChildViewHolder(getChildAt(index)) as PageHolder).loader.clear()
    session = null
    pageIds = emptyList()
  }

  // React Native lays out its views itself and drops native layout requests, so the pages are laid out here.
  private val relayout = Runnable {
    measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    layout(left, top, right, bottom)
  }

  override fun requestLayout() {
    super.requestLayout()
    @Suppress("SENSELESS_COMPARISON") // Called by the superclass constructor, before the field is set.
    if (relayout != null && !dropped) post(relayout)
  }

  private inner class PageHolder(val image: ImageViewerView) : ViewHolder(image) {
    /** Identity whose callbacks and leases belong to this holder. */
    var boundId: String? = null
    val loader = ImageLoader(
      image,
      onLoaded = onImageLoaded,
      onFailed = { if (bindingAdapterPosition == current) onImageFailed(it) },
    )
  }

  private inner class PageAdapter : Adapter<PageHolder>() {
    override fun getItemCount() = pageIds.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PageHolder(
      ImageViewerView(parent.context).apply {
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
      },
    )

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
      val state = session
      if (state != null) holder.loader.bind(state, pageIds[position]) else holder.loader.clear()
      holder.boundId = pageIds[position]
    }

    override fun onViewRecycled(holder: PageHolder) {
      holder.loader.clear()
      holder.boundId = null
    }
  }
}
