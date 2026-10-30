package com.margelo.nitro.documentscanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.OutputTransform
import com.margelo.nitro.documentscanner.processing.LiveDetectionResult
import com.margelo.nitro.documentscanner.processing.LiveGuideStatus
import kotlin.math.hypot
import kotlin.math.min

/** Main-thread-only visual guidance. Does not modify geometry or trigger capture. */
internal class LiveQuadOverlay(context: Context) : View(context) {
  private val outline = Path()
  private val viewfinderPath = Path()
  private val transition = QuadTransition()
  private var framePending = false
  private val animationFrame = object : Runnable {
    override fun run() {
      framePending = false
      if (!isAttachedToWindow || !transition.hasPoints) return
      transition.advance(System.nanoTime())
      updateOutline()
      scheduleFrame()
    }
  }
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = DEFAULT_COLOR
    style = Paint.Style.STROKE
    strokeWidth = 2.5f * resources.displayMetrics.density
    strokeJoin = Paint.Join.ROUND
    strokeCap = Paint.Cap.ROUND
  }
  /** Opacity of the current guide state, before the color's own alpha. */
  private var stateAlpha = FULL_ALPHA

  init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false }
  internal val hasOutline: Boolean get() = !outline.isEmpty
  internal var drawnAcceptanceId = 0L
    private set
  internal val strokeAlpha: Int get() = stateAlpha
  internal val drawnPoints: FloatArray get() = transition.points.copyOf()
  /** Draws fixed corner brackets around a centered page-shaped area while no guide is drawn. */
  var viewfinder = false
    set(value) {
      if (field == value) return
      field = value
      if (outline.isEmpty) invalidate()
    }
  internal val showsViewfinder: Boolean get() = viewfinder && outline.isEmpty && !viewfinderPath.isEmpty
  /** The page-shaped area the viewfinder brackets mark, whether or not they are drawn; empty before layout. */
  internal val viewfinderBounds = RectF()
  /** The preview shows the frame's centered square, letterboxed to the view's centered square. */
  var squareFrame = false
    set(value) {
      if (field == value) return
      field = value
      updateViewfinder(width, height)
      invalidate()
    }
  var transitionNs: Long
    get() = transition.durationNs
    set(value) { transition.durationNs = value }
  /** ARGB guide color for every state; its alpha scales each state's opacity. */
  var color = DEFAULT_COLOR
    set(value) {
      if (field == value) return
      field = value
      if (!outline.isEmpty) invalidate()
    }

  fun clear() {
    removeCallbacks(animationFrame)
    framePending = false
    transition.clear()
    outline.reset()
    drawnAcceptanceId = 0L
    stateAlpha = FULL_ALPHA
    invalidate()
  }

  fun show(result: LiveDetectionResult, target: OutputTransform?) {
    when (result.status) {
      LiveGuideStatus.SEARCHING, LiveGuideStatus.UNAVAILABLE -> clear()
      LiveGuideStatus.DETECTED -> draw(result, target, HELD_ALPHA)
      LiveGuideStatus.TRACKING -> draw(result, target, FULL_ALPHA)
      LiveGuideStatus.HELD -> if (transition.hasPoints && result.acceptanceId == drawnAcceptanceId) {
        // Dim in place; an in-flight transition may finish.
        stateAlpha = HELD_ALPHA
        invalidate()
      } else {
        draw(result, target, HELD_ALPHA)
      }
    }
  }

  private fun draw(result: LiveDetectionResult, target: OutputTransform?, alpha: Int) {
    val points = mappedPoints(result, target)
    if (points == null || !isAttachedToWindow) { clear(); return }
    // Large jumps may be a new document; avoid sweeping the guide across the preview.
    transition.retarget(points, System.nanoTime(), hypot(width.toFloat(), height.toFloat()) * 0.2f)
    drawnAcceptanceId = result.acceptanceId
    stateAlpha = alpha
    updateOutline()
    scheduleFrame()
  }

  private fun updateOutline() {
    val points = transition.points
    outline.reset()
    outline.moveTo(points[0], points[1])
    for (index in 1..3) outline.lineTo(points[index * 2], points[index * 2 + 1])
    outline.close()
    invalidate()
  }

  /** Corner brackets around the largest A-series portrait page that fits 80% of the view, or of its centered square,
   * centered.
   */
  private fun updateViewfinder(viewWidth: Int, viewHeight: Int) {
    viewfinderPath.reset()
    viewfinderBounds.setEmpty()
    if (viewWidth <= 0 || viewHeight <= 0) return
    val w = if (squareFrame) min(viewWidth, viewHeight) else viewWidth
    val h = if (squareFrame) w else viewHeight
    val pageWidth = min(w * VIEWFINDER_FILL, h * VIEWFINDER_FILL / PAGE_ASPECT)
    val pageHeight = pageWidth * PAGE_ASPECT
    val left = (viewWidth - pageWidth) / 2
    val top = (viewHeight - pageHeight) / 2
    val right = left + pageWidth
    val bottom = top + pageHeight
    viewfinderBounds.set(left, top, right, bottom)
    val arm = pageWidth * VIEWFINDER_ARM
    for ((x, y) in listOf(left to top, right to top, right to bottom, left to bottom)) {
      val dx = if (x == left) arm else -arm
      val dy = if (y == top) arm else -arm
      viewfinderPath.moveTo(x, y + dy)
      viewfinderPath.lineTo(x, y)
      viewfinderPath.lineTo(x + dx, y)
    }
  }

  private fun scheduleFrame() {
    if (!transition.isAnimating || framePending) return
    framePending = true
    postOnAnimation(animationFrame)
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    updateViewfinder(w, h)
    clear()
  }

  override fun onDetachedFromWindow() {
    clear()
    super.onDetachedFromWindow()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val path = when {
      !outline.isEmpty -> outline
      viewfinder -> viewfinderPath
      else -> return
    }
    paint.color = color
    paint.alpha = paint.alpha * (if (path === outline) stateAlpha else VIEWFINDER_ALPHA) / FULL_ALPHA
    canvas.drawPath(path, paint)
  }

  companion object {
    private const val FULL_ALPHA = 255
    private const val HELD_ALPHA = 115 // 45% opacity.
    private const val VIEWFINDER_ALPHA = 153 // 60% opacity.
    private const val VIEWFINDER_FILL = 0.8f
    private const val VIEWFINDER_ARM = 0.12f // Of the page width.
    private const val PAGE_ASPECT = 1.4142f // A-series height over width.
    internal const val DEFAULT_COLOR = 0xFF61E1CF.toInt()

    internal fun mappedPoints(result: LiveDetectionResult, target: OutputTransform?): FloatArray? {
      val quad = result.quad ?: return null
      val source = result.source ?: return null
      if (target == null) return null
      val points = floatArrayOf(
        (quad.topLeft.x * result.width).toFloat(), (quad.topLeft.y * result.height).toFloat(),
        (quad.topRight.x * result.width).toFloat(), (quad.topRight.y * result.height).toFloat(),
        (quad.bottomRight.x * result.width).toFloat(), (quad.bottomRight.y * result.height).toFloat(),
        (quad.bottomLeft.x * result.width).toFloat(), (quad.bottomLeft.y * result.height).toFloat(),
      )
      CoordinateTransform(source, target).mapPoints(points)
      return points.takeIf { values -> values.all { it.isFinite() } }
    }
  }
}
