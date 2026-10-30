package com.margelo.nitro.documentscanner

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Draws a contain-fitted upright image that pinches and pans up to [MAX_ZOOM]; a double tap zooms to
 * [DOUBLE_TAP_ZOOM] around the tapped point, or back to the fit. The pan stops at the image's edges. A new image can
 * fade in over the shown one, each drawn in its own layout. Main thread only.
 */
internal class ImageViewerView(context: Context) : View(context) {
  /** The upright image; the caller owns it and must clear it before recycling. */
  var bitmap: Bitmap? = null
    set(value) {
      field = value
      relayout()
      invalidate()
    }

  /** Whether the image is scaled past the fit. */
  val isZoomed: Boolean
    get() = zoom.state.zoom > 1f

  /** The scale over the fit, from 1. */
  val zoomScale: Float
    get() = zoom.state.zoom

  /** Called when [zoomScale] changes, by a gesture, an animation or a reset. */
  var onZoom: ((scale: Float) -> Unit)? = null

  /** Called when the view becomes visible: attached, shown, and in a visible window. */
  var onVisible: (() -> Unit)? = null

  /** Called when the view joins a window, as when a pager or list brings its page into its window. */
  var onAttach: (() -> Unit)? = null

  /** Called when the view leaves its window, as when a pager or list moves its page out of its window. */
  var onDetach: (() -> Unit)? = null

  /** Fits the whole image in the view again. */
  fun resetZoom() {
    animator?.cancel()
    setZoom(ZoomState.FIT)
    invalidate()
  }

  /** Clears the image, with any fade under it and any zoom animation, back at the fit. */
  fun clearImage() {
    fade?.end()
    underneath = null
    bitmap = null
    resetZoom()
  }

  /**
   * Shows [next], fading it in over the shown image, which stays opaque where it is drawn now and goes once the fade
   * ends, so the page never shows through. [fit] returns the zoom to the fit. A hidden view swaps at once.
   */
  fun fadeIn(next: Bitmap, fit: Boolean) {
    val previous = bitmap
    fade?.end()
    if (previous != null && previous !== next && laidOut && isShown) {
      underneath = previous
      underneathRect.set(zoom.left, zoom.top, zoom.left + zoom.width, zoom.top + zoom.height)
      fadeProgress = 0f
      fade = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = FADE_MS
        addUpdateListener {
          fadeProgress = it.animatedValue as Float
          invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
          override fun onAnimationEnd(animation: Animator) {
            underneath = null
            fade = null
            invalidate()
          }
        })
        start()
      }
    }
    bitmap = next
    if (fit) resetZoom()
  }

  private val zoom = ImageZoom(MAX_ZOOM)
  private var animator: ValueAnimator? = null
  private var fade: ValueAnimator? = null
  /** The previous image under a fade, drawn in [underneathRect]; it is no longer the caller's to recycle. */
  private var underneath: Bitmap? = null
  private val underneathRect = RectF()
  private var fadeProgress = 1f
  private var reportedScale = 1f
  private val content = RectF()
  /** The fit is computed when the bitmap or the view size changes, not per frame; false while either is empty. */
  private var laidOut = false
  private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

  private val scaleDetector = ScaleGestureDetector(
    context,
    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
      override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
      }

      override fun onScale(detector: ScaleGestureDetector): Boolean {
        zoom.scaleAround(detector.scaleFactor, detector.focusX, detector.focusY)
        zoomUpdated()
        invalidate()
        return true
      }
    },
  ).apply { isQuickScaleEnabled = false }

  private val gestureDetector = GestureDetector(
    context,
    object : GestureDetector.SimpleOnGestureListener() {
      override fun onDown(event: MotionEvent): Boolean = true

      // Follows the focus of all fingers, so two fingers also pan while they pinch.
      override fun onScroll(first: MotionEvent?, event: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        zoom.panBy(-distanceX, -distanceY)
        invalidate()
        return true
      }

      override fun onDoubleTap(event: MotionEvent): Boolean {
        animateTo(zoom.doubleTapTarget(event.x, event.y, DOUBLE_TAP_ZOOM))
        return true
      }
    },
  )

  override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
    relayout()
  }

  override fun onDraw(canvas: Canvas) {
    val previous = underneath
    if (previous != null) {
      canvas.drawBitmap(previous, null, underneathRect, bitmapPaint)
      bitmapPaint.alpha = (fadeProgress * 255).toInt()
    }
    val source = bitmap
    if (source != null && laidOut) {
      content.set(zoom.left, zoom.top, zoom.left + zoom.width, zoom.top + zoom.height)
      canvas.drawBitmap(source, null, content, bitmapPaint)
    }
    bitmapPaint.alpha = 255
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    // A new touch stops a running zoom animation. This must come before the detectors: on a second tap, GestureDetector
    // calls onDoubleTap before onDown, so cancelling in onDown would stop the animation the double tap just started.
    if (event.actionMasked == MotionEvent.ACTION_DOWN) animator?.cancel()
    scaleDetector.onTouchEvent(event)
    gestureDetector.onTouchEvent(event)
    when (event.actionMasked) {
      // A zoomed image keeps one-finger drags from a scrolling parent.
      MotionEvent.ACTION_DOWN -> if (isZoomed) parent?.requestDisallowInterceptTouchEvent(true)
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
    }
    return true
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    onAttach?.invoke()
  }

  override fun onDetachedFromWindow() {
    animator?.cancel()
    fade?.end()
    super.onDetachedFromWindow()
    onDetach?.invoke()
  }

  override fun onVisibilityAggregated(isVisible: Boolean) {
    super.onVisibilityAggregated(isVisible)
    if (isVisible) onVisible?.invoke()
  }

  private fun relayout() {
    val source = bitmap
    laidOut = source != null &&
      zoom.layout(width.toFloat(), height.toFloat(), source.width.toFloat(), source.height.toFloat())
  }

  private fun setZoom(state: ZoomState) {
    zoom.set(state)
    zoomUpdated()
  }

  private fun zoomUpdated() {
    if (zoomScale == reportedScale) return
    reportedScale = zoomScale
    onZoom?.invoke(reportedScale)
  }

  private fun animateTo(target: ZoomState) {
    animator?.cancel()
    val start = zoom.state
    animator = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = ANIMATION_MS
      interpolator = DecelerateInterpolator()
      addUpdateListener {
        val t = it.animatedValue as Float
        setZoom(
          ZoomState(
            start.zoom + (target.zoom - start.zoom) * t,
            start.panX + (target.panX - start.panX) * t,
            start.panY + (target.panY - start.panY) * t,
          ),
        )
        invalidate()
      }
      start()
    }
  }

  private companion object {
    /** Largest scale over the contain fit. */
    const val MAX_ZOOM = 5f
    const val DOUBLE_TAP_ZOOM = 2.5f
    const val ANIMATION_MS = 220L
    const val FADE_MS = 180L
  }
}
