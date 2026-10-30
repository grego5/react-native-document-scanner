package com.margelo.nitro.documentscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

/**
 * Draws a contain-fitted upright photo with its crop, and edits the crop by touch: corners move alone, edges move both
 * of their corners. While a corner is dragged, a magnifier floats above the finger, or beside it on the side away from
 * the nearer left or right edge when there is no room above, gliding between the two. Two fingers away from the
 * handles pinch and pan the photo, up to [MAX_ZOOM], and a double tap fits it again; handles keep their size on
 * screen. Main thread only.
 */
internal class CropEditorView(context: Context) : View(context) {
  /** Called with the edited quad when a drag that changed it ends. */
  var onQuadEdited: ((DoubleArray) -> Unit)? = null

  /**
   * The upright photo; the caller owns it and must clear it before recycling. A replacement keeps the zoom, so a
   * filtered copy of the same photo can take its place; call [resetZoom] for a different photo.
   */
  var bitmap: Bitmap? = null
    set(value) {
      field = value
      invalidate()
    }

  /** Fits the whole photo in the view again. */
  fun resetZoom() {
    zoom = 1f
    panX = 0f
    panY = 0f
    invalidate()
  }

  /** The crop in normalized upright coordinates. */
  var quad: DoubleArray = CropQuadEditing.FULL_FRAME
    private set

  /** Replaces the crop, clamped inside the photo, and cancels a drag in progress. */
  fun replaceQuad(value: DoubleArray) {
    quad = CropQuadEditing.clampToFrame(value)
    drag = null
    invalidate()
  }

  /** [start] is the quad before the touch, [base] the quad after snapping the handle under the finger. */
  private class Drag(
    val corner: Boolean,
    val index: Int,
    val pointerId: Int,
    val downX: Float,
    val downY: Float,
    val start: DoubleArray,
    val base: DoubleArray,
  )

  /** What the current touch sequence does, chosen by where its first finger lands. */
  private enum class Gesture { HANDLE, VIEW }

  private var gesture = Gesture.HANDLE
  private var drag: Drag? = null
  // The photo's scale over its contain fit, and its center's offset from the view's center, in pixels.
  private var zoom = 1f
  private var panX = 0f
  private var panY = 0f
  // The last multi-touch centroid, independent of whether a pinch has started. NaN with fewer than two fingers.
  private var focusX = Float.NaN
  private var focusY = Float.NaN
  private val scaleDetector = ScaleGestureDetector(
    context,
    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
      override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
      }

      override fun onScale(detector: ScaleGestureDetector): Boolean {
        val next = (zoom * detector.scaleFactor).coerceIn(1f, MAX_ZOOM)
        // Translation is already applied by pan(); scale around the current focus without counting it twice.
        val ratio = next / zoom
        val centerX = width / 2f + panX
        val centerY = height / 2f + panY
        panX = detector.focusX - (detector.focusX - centerX) * ratio - width / 2f
        panY = detector.focusY - (detector.focusY - centerY) * ratio - height / 2f
        zoom = next
        invalidate()
        return true
      }
    },
  ).apply { isQuickScaleEnabled = false }
  private val tapDetector = GestureDetector(
    context,
    object : GestureDetector.SimpleOnGestureListener() {
      override fun onDoubleTap(event: MotionEvent): Boolean {
        resetZoom()
        return true
      }
    },
  )
  private var fingerX = 0f
  private var fingerY = 0f
  // The magnifier's drawn center, eased toward its target each frame; NaN until the first frame of a drag.
  private var magnifierX = Float.NaN
  private var magnifierY = Float.NaN
  private var lastFrameMs = 0L

  private val density = resources.displayMetrics.density
  private val content = RectF()
  private val path = Path()
  private val circle = Path()
  private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
  private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = COLOR
    style = Paint.Style.STROKE
    strokeJoin = Paint.Join.ROUND
  }
  private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = COLOR
    style = Paint.Style.STROKE
    strokeWidth = 2 * density
  }
  private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR }
  private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    style = Paint.Style.STROKE
    strokeWidth = 2 * density
  }
  private val crosshairPaint = Paint().apply {
    color = CROSSHAIR_COLOR
    strokeWidth = density
  }

  override fun onDraw(canvas: Canvas) {
    val source = bitmap ?: return
    if (!fitContent(source)) return
    canvas.drawBitmap(source, null, content, bitmapPaint)
    buildPath()
    linePaint.strokeWidth = 2 * density
    canvas.drawPath(path, linePaint)
    for (index in 0 until 4) drawEdgeGrip(canvas, index)
    for (index in 0 until 4) canvas.drawCircle(viewX(index), viewY(index), CORNER_RADIUS * density, handlePaint)
    drag?.takeIf { it.corner }?.let { drawMagnifier(canvas, source, it.index) }
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
      val source = bitmap ?: return false
      if (!fitContent(source)) return false
      gesture = if (handleAt(event.x, event.y) == null) Gesture.VIEW else Gesture.HANDLE
    }
    if (gesture == Gesture.VIEW) {
      pan(event)
      scaleDetector.onTouchEvent(event)
      tapDetector.onTouchEvent(event)
      return true
    }
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        val handle = handleAt(event.x, event.y) ?: return false
        // A handle press ends any tap sequence, so it never completes a double tap.
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        tapDetector.onTouchEvent(cancel)
        cancel.recycle()
        val start = quad.copyOf()
        // The grabbed corner, or the middle of the grabbed edge, jumps under the finger at once.
        val (corner, index) = handle
        val snapped = if (corner) {
          CropQuadEditing.moveCorner(start, index, normalizedX(event.x), normalizedY(event.y))
        } else {
          CropQuadEditing.moveEdge(
            start, index,
            (event.x - midX(index)) / content.width().toDouble(),
            (event.y - midY(index)) / content.height().toDouble(),
          )
        } ?: start
        quad = snapped
        drag = Drag(corner, index, event.getPointerId(0), event.x, event.y, start, snapped)
        fingerX = event.x
        fingerY = event.y
        magnifierX = Float.NaN
        parent?.requestDisallowInterceptTouchEvent(true)
        invalidate()
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        val current = drag ?: return false
        val pointer = event.findPointerIndex(current.pointerId)
        if (pointer < 0) return true
        fingerX = event.getX(pointer)
        fingerY = event.getY(pointer)
        val dx = (fingerX - current.downX) / content.width().toDouble()
        val dy = (fingerY - current.downY) / content.height().toDouble()
        val start = current.base
        val next = if (current.corner) {
          CropQuadEditing.moveCorner(start, current.index, start[current.index * 2] + dx, start[current.index * 2 + 1] + dy)
        } else {
          CropQuadEditing.moveEdge(start, current.index, dx, dy)
        }
        // An invalid move keeps the last valid quad.
        if (next != null) quad = next
        invalidate()
        return true
      }
      MotionEvent.ACTION_POINTER_UP -> {
        val current = drag ?: return false
        if (event.getPointerId(event.actionIndex) == current.pointerId) endDrag(current)
        return true
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        drag?.let(::endDrag)
        return true
      }
    }
    return drag != null
  }

  /** Pans on two-finger movement even when finger spacing stays constant; pointer changes only rebase the focus. */
  private fun pan(event: MotionEvent) {
    val action = event.actionMasked
    val leaving = if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
    val count = event.pointerCount - if (leaving >= 0) 1 else 0
    if (count < 2 || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
      focusX = Float.NaN
      focusY = Float.NaN
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
        parent?.requestDisallowInterceptTouchEvent(false)
      }
      return
    }
    var x = 0f
    var y = 0f
    for (index in 0 until event.pointerCount) {
      if (index == leaving) continue
      x += event.getX(index)
      y += event.getY(index)
    }
    x /= count
    y /= count
    if (action == MotionEvent.ACTION_MOVE && !focusX.isNaN()) {
      panX += x - focusX
      panY += y - focusY
      invalidate()
    }
    focusX = x
    focusY = y
    parent?.requestDisallowInterceptTouchEvent(true)
  }

  private fun endDrag(current: Drag) {
    drag = null
    invalidate()
    if (!current.start.contentEquals(quad)) onQuadEdited?.invoke(quad.copyOf())
  }

  /** The corner within reach of the touch, nearest first, otherwise the nearest edge midpoint within reach. */
  private fun handleAt(x: Float, y: Float): Pair<Boolean, Int>? {
    val reach = TOUCH_RADIUS * density
    val corner = (0 until 4).minBy { hypot(viewX(it) - x, viewY(it) - y) }
    if (hypot(viewX(corner) - x, viewY(corner) - y) <= reach) return true to corner
    val edge = (0 until 4).minBy { hypot(midX(it) - x, midY(it) - y) }
    if (hypot(midX(edge) - x, midY(edge) - y) <= reach) return false to edge
    return null
  }

  private fun drawEdgeGrip(canvas: Canvas, index: Int) {
    val next = (index + 1) % 4
    val angle = Math.toDegrees(atan2((viewY(next) - viewY(index)).toDouble(), (viewX(next) - viewX(index)).toDouble()))
    canvas.save()
    canvas.translate(midX(index), midY(index))
    canvas.rotate(angle.toFloat())
    val halfWidth = GRIP_LENGTH / 2 * density
    val halfHeight = GRIP_THICKNESS / 2 * density
    canvas.drawRoundRect(-halfWidth, -halfHeight, halfWidth, halfHeight, halfHeight, halfHeight, gripPaint)
    canvas.restore()
  }

  private fun drawMagnifier(canvas: Canvas, source: Bitmap, index: Int) {
    val radius = MAGNIFIER_SIZE / 2 * density
    val margin = MAGNIFIER_MARGIN * density
    val offset = (FINGER_CLEARANCE + MAGNIFIER_SIZE / 2) * density
    // Above the finger; without room there, level with it on the side away from the nearer left or right edge.
    val above = fingerY - offset >= radius + margin
    val side = if (fingerX < width / 2f) 1 else -1
    val targetX = (if (above) fingerX else fingerX + side * offset).coerceIn(radius + margin, width - radius - margin)
    val targetY = (if (above) fingerY - offset else fingerY).coerceIn(radius + margin, height - radius - margin)
    val now = SystemClock.uptimeMillis()
    if (magnifierX.isNaN()) {
      magnifierX = targetX
      magnifierY = targetY
    } else {
      val step = 1f - exp(-(now - lastFrameMs).coerceIn(0L, 100L) / GLIDE_MS).toFloat()
      magnifierX += (targetX - magnifierX) * step
      magnifierY += (targetY - magnifierY) * step
    }
    lastFrameMs = now
    if (hypot(targetX - magnifierX, targetY - magnifierY) > 0.5f) postInvalidateOnAnimation()

    val pointX = viewX(index)
    val pointY = viewY(index)
    circle.rewind()
    circle.addCircle(magnifierX, magnifierY, radius, Path.Direction.CW)
    canvas.save()
    canvas.clipPath(circle)
    canvas.drawColor(Color.BLACK)
    canvas.translate(magnifierX, magnifierY)
    // The zoom already enlarges the photo, so the magnifier adds only what keeps its total near MAGNIFICATION.
    val magnification = (MAGNIFICATION / zoom).coerceAtLeast(MIN_MAGNIFICATION)
    canvas.scale(magnification, magnification)
    canvas.translate(-pointX, -pointY)
    canvas.drawBitmap(source, null, content, bitmapPaint)
    linePaint.strokeWidth = 2 * density / magnification
    canvas.drawPath(path, linePaint)
    canvas.restore()
    canvas.drawCircle(magnifierX, magnifierY, radius, borderPaint)
    val arm = CROSSHAIR / 2 * density
    canvas.drawLine(magnifierX - arm, magnifierY, magnifierX + arm, magnifierY, crosshairPaint)
    canvas.drawLine(magnifierX, magnifierY - arm, magnifierX, magnifierY + arm, crosshairPaint)
  }

  /**
   * Places the photo: contain-fitted, scaled by [zoom], and panned no further than keeps the view covered along each
   * axis the zoomed photo fills, centered along the others. False until the view and photo have a size.
   */
  private fun fitContent(source: Bitmap): Boolean {
    if (width <= 0 || height <= 0 || source.width <= 0 || source.height <= 0) return false
    val scale = min(width / source.width.toFloat(), height / source.height.toFloat()) * zoom
    val contentWidth = source.width * scale
    val contentHeight = source.height * scale
    val reachX = maxOf(0f, (contentWidth - width) / 2)
    val reachY = maxOf(0f, (contentHeight - height) / 2)
    panX = panX.coerceIn(-reachX, reachX)
    panY = panY.coerceIn(-reachY, reachY)
    val centerX = width / 2f + panX
    val centerY = height / 2f + panY
    content.set(centerX - contentWidth / 2, centerY - contentHeight / 2, centerX + contentWidth / 2, centerY + contentHeight / 2)
    return true
  }

  private fun buildPath() {
    path.rewind()
    path.moveTo(viewX(0), viewY(0))
    for (index in 1 until 4) path.lineTo(viewX(index), viewY(index))
    path.close()
  }

  private fun normalizedX(x: Float) = ((x - content.left) / content.width()).toDouble()
  private fun normalizedY(y: Float) = ((y - content.top) / content.height()).toDouble()
  private fun viewX(index: Int) = content.left + quad[index * 2].toFloat() * content.width()
  private fun viewY(index: Int) = content.top + quad[index * 2 + 1].toFloat() * content.height()
  private fun midX(index: Int) = (viewX(index) + viewX((index + 1) % 4)) / 2
  private fun midY(index: Int) = (viewY(index) + viewY((index + 1) % 4)) / 2

  private companion object {
    val COLOR = Color.parseColor("#38D6A8")
    val CROSSHAIR_COLOR = Color.parseColor("#FF3B30")
    // Sizes in dp. Touch reach gives 44 dp targets; the visible handle stays on the actual coordinate.
    const val TOUCH_RADIUS = 22f
    const val CORNER_RADIUS = 12f
    const val GRIP_LENGTH = 26f
    const val GRIP_THICKNESS = 6f
    const val MAGNIFIER_SIZE = 120f
    const val MAGNIFIER_MARGIN = 12f
    /** Gap between the finger and the magnifier's edge. */
    const val FINGER_CLEARANCE = 40f
    const val CROSSHAIR = 24f
    /** The magnifier's scale over the contain fit. */
    const val MAGNIFICATION = 3f
    /** The least the magnifier enlarges the zoomed photo, so it still reads as a magnifier. */
    const val MIN_MAGNIFICATION = 1.5f
    /** Time constant of the magnifier's glide toward its target, in milliseconds. */
    const val GLIDE_MS = 70.0
    /** Largest scale over the contain fit. */
    const val MAX_ZOOM = 5f
  }
}
