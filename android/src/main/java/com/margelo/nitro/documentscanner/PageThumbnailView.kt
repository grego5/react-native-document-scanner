package com.margelo.nitro.documentscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import java.util.concurrent.Executor

/** Image observation and display only; no touch handling, ordering, selection or JS callbacks. Main owner only. */
internal class PageThumbnailView(
  context: Context,
  private val reads: Executor = ScannerImageReads,
  private val decode: (SessionPageSource, Int) -> PageThumbnails.TilePixels = PageThumbnails::loadTile,
) : View(context) {
  private val main = Handler(Looper.getMainLooper())
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  private val destination = RectF()
  private var state: ScanSessionState? = null
  private var pageId: String? = null
  private var binding: SessionPageBinding? = null
  private var source: SessionPageSource? = null
  private var displayedSource: SessionPageSource? = null
  internal var bitmap: Bitmap? = null
    private set
  @Volatile private var generation = 0
  private var disposed = false
  private var resizeMode = PageThumbnailResizeMode.COVER

  fun bind(next: ScanSessionState?, id: String, mode: PageThumbnailResizeMode) {
    if (disposed) return
    if (mode != resizeMode) { resizeMode = mode; invalidate() }
    if (state === next && pageId == id) return
    binding?.close()
    source?.close(); source = null
    clearImage()
    state = next; pageId = id
    binding = next?.let { owner ->
      SessionPageBinding(owner, id, thumbnailOnly = true, changed = { value ->
        source?.close(); source = value
        if (value == null) clearImage() else load()
      }, failed = {})
    }
    if (isAttachedToWindow) binding?.start()
  }

  private fun load() {
    val current = ++generation
    val value = source ?: return
    val limit = maxOf(width, height)
    if (!isAttachedToWindow || limit <= 0) return
    val pin = value.retain()
    reads.executeRead(pin) {
      val result = try {
        runCatching { if (generation == current) decode(pin, limit) else null }
      } finally { pin.close() }
      main.post {
        val pixels = result.getOrNull() ?: return@post
        if (disposed || generation != current || binding?.valid() != true) {
          pixels.discard()
          return@post
        }
        displayedSource?.close()
        displayedSource = value.retain()
        // Previously installed bitmaps may still be drawn by RenderThread. Never recycle them.
        bitmap = pixels.bitmap
        invalidate()
      }
    }
  }

  private fun clearImage() {
    generation++
    displayedSource?.close(); displayedSource = null
    bitmap = null
    invalidate()
  }

  fun dispose() {
    if (disposed) return
    disposed = true
    binding?.close(); binding = null
    source?.close(); source = null
    state = null; pageId = null
    clearImage()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (!disposed) binding?.start()
  }

  override fun onDetachedFromWindow() {
    binding?.close()
    source?.close(); source = null
    clearImage()
    super.onDetachedFromWindow()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    load()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val image = bitmap ?: return
    val sx = width.toFloat() / image.width
    val sy = height.toFloat() / image.height
    val scale = if (resizeMode == PageThumbnailResizeMode.COVER) maxOf(sx, sy) else minOf(sx, sy)
    val w = image.width * scale
    val h = image.height * scale
    destination.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    val saved = canvas.save()
    canvas.clipRect(0, 0, width, height)
    canvas.drawBitmap(image, null, destination, paint)
    canvas.restoreToCount(saved)
  }
}
