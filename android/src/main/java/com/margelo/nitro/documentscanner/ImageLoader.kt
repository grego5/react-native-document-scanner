package com.margelo.nitro.documentscanner

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.margelo.nitro.documentscanner.processing.SourceImageLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Loads one page into [image] off the main thread, only while the view is attached to a window: a detached view, such as
 * a page a pager has moved out of its window, holds no image, and shows its thumbnail again from the shared cache once
 * attached. The page shows its [PageThumbnails] thumbnail. Zooming past
 * [PRELOAD_ZOOM] starts decoding the full image, which replaces the thumbnail in place if the page is still zoomed when
 * it arrives and is discarded otherwise; back at the fit, the thumbnail returns. A shown thumbnail is reported to
 * [onLoaded]; a failure goes to [onFailed] and leaves the shown image, and is retried when the view becomes visible
 * again. [onZoomChanged] reports whether the image is zoomed past the fit. The full image is also dropped when the
 * app's UI is hidden, and decoded again when the view is visible and still zoomed. Main thread only.
 */
internal class ImageLoader(
  private val image: ImageViewerView,
  private val onLoaded: (uri: String) -> Unit,
  private val onFailed: (error: ScannerException) -> Unit,
  private val onZoomChanged: (zoomed: Boolean) -> Unit = {},
) {
  private val mainHandler = Handler(Looper.getMainLooper())

  /** The requested uri, loaded or not; null after [clear]. */
  var uri: String? = null
    private set

  // The thumbnail is shared through the cache; the full image is this page's own while zoomed.
  private var thumbnail: Bitmap? = null
  private var fullImage: Bitmap? = null

  // A result is applied only while its generation is current, and a queued decode that is already superseded is
  // skipped. The page generation changes with the uri; the full generation also when the full image is dropped.
  @Volatile private var pageGeneration = 0
  @Volatile private var fullGeneration = 0
  private var fullPending = false
  /** Whether the current uri's thumbnail failed to load. */
  private var failed = false

  private var zoomed = false

  init {
    PageThumbnails.watchMemory(image.context)
    PageThumbnails.onUiHidden(this)
    image.onZoom = { scale ->
      if (scale >= PRELOAD_ZOOM) loadFull() else if (scale <= 1f) release()
      if ((scale > 1f) != zoomed) {
        zoomed = scale > 1f
        onZoomChanged(zoomed)
      }
    }
    image.onVisible = {
      restore()
      retry()
    }
    image.onAttach = { uri?.let { load(it, fade = false) } }
    image.onDetach = ::drop
  }

  /**
   * Shows the page at [uri]; an empty uri clears the view. The shown image stays until the new thumbnail is ready, then
   * fades it in when [fade], or is replaced at once for a view reused for another page. A detached view loads it once
   * attached.
   */
  fun show(uri: String, fade: Boolean) {
    if (uri == this.uri) return
    this.uri = uri
    if (image.isAttachedToWindow) load(uri, fade) else drop()
  }

  private fun load(uri: String, fade: Boolean) {
    val page = ++pageGeneration
    release()
    thumbnail = null
    failed = false
    // Bitmaps are left to the garbage collector, since the render thread may still be drawing them.
    if (uri.isEmpty()) {
      image.clearImage()
      return
    }
    val cached = PageThumbnails.cached(uri)
    if (cached != null) {
      applyThumbnail(uri, cached, fade)
      return
    }
    if (!fade) image.clearImage()
    thumbnailWorker.execute {
      if (page != pageGeneration) return@execute
      val result = runCatching { PageThumbnails.load(uri) }
      mainHandler.post {
        if (page != pageGeneration) return@post
        result.fold(
          onSuccess = { applyThumbnail(uri, it, fade) },
          onFailure = { error ->
            failed = true
            onFailed(ScannerException(ScannerErrorCode.IMAGE_LOAD_FAILED, error))
          },
        )
      }
    }
  }

  /** Loads the page again if its thumbnail failed, reporting a new failure; the shown image stays meanwhile. */
  fun retry() {
    val uri = uri?.takeIf { failed && image.isAttachedToWindow } ?: return
    load(uri, fade = true)
  }

  /** Loads the full image again if the page is zoomed without it, as after [release]. */
  fun restore() {
    if (image.zoomScale >= PRELOAD_ZOOM) loadFull()
  }

  /** Drops the full image, or its pending decode, and shows the thumbnail. */
  fun release() {
    fullGeneration++
    fullPending = false
    if (fullImage == null) return
    fullImage = null
    // The same picture, so the thumbnail replaces it in place.
    thumbnail?.let { image.bitmap = it }
  }

  /** Drops the images and any pending decode. */
  fun clear() {
    drop()
    uri = null
  }

  /** Drops the images and any pending decode, keeping [uri] to load once attached; the thumbnail stays cached. */
  private fun drop() {
    pageGeneration++
    release()
    thumbnail = null
    failed = false
    image.clearImage()
  }

  private fun applyThumbnail(uri: String, bitmap: Bitmap, fade: Boolean) {
    thumbnail = bitmap
    if (fade) image.fadeIn(bitmap, fit = true) else {
      image.bitmap = bitmap
      image.resetZoom()
    }
    onLoaded(uri)
  }

  private fun loadFull() {
    val uri = uri?.takeIf(String::isNotEmpty) ?: return
    // Zooming over the previous page's image waits for this page's thumbnail.
    if (thumbnail == null || fullImage != null || fullPending) return
    fullPending = true
    val full = fullGeneration
    fullWorker.execute {
      if (full != fullGeneration) return@execute
      val result = runCatching { SourceImageLoader.loadReduced(uri, FULL_LIMIT) }
      mainHandler.post {
        if (full != fullGeneration) {
          // Never shown, so nothing else can be drawing it.
          result.getOrNull()?.recycle()
          return@post
        }
        fullPending = false
        // A failed full decode keeps the thumbnail; zooming again retries it.
        val bitmap = result.getOrNull() ?: return@post
        // The same picture, so it replaces the thumbnail in place, keeping the zoom.
        fullImage = bitmap
        image.bitmap = bitmap
      }
    }
  }

  private companion object {
    /** Past this scale over the fit the thumbnail's pixels show, so the full image is worth decoding. */
    const val PRELOAD_ZOOM = 1.1f
    /** A full-resolution rendered page, so zooming in stays sharp. */
    const val FULL_LIMIT = 4096
    /** Makes thumbnails, one at a time across views. */
    val thumbnailWorker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "DocumentThumbnailLoader").apply { isDaemon = true }
    }
    /** Decodes full images, one at a time across views, apart from thumbnails. */
    val fullWorker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "ImageViewerLoader").apply { isDaemon = true }
    }
  }
}
