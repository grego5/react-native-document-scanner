package com.margelo.nitro.documentscanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Main-thread-only capture progress drawn over the frozen preview, around [guide]'s outline or, without one, its
 * viewfinder area. While waiting it follows the live outline; from shooting on it keeps the outline drawn at the
 * shutter, which is the one the frozen frame shows.
 */
internal class CaptureFeedbackView(context: Context, private val guide: LiveQuadOverlay) : View(context) {
  /** ARGB color for every stage but failure; its alpha scales each stage's opacity. */
  var color = DEFAULT_COLOR
    set(value) {
      if (field == value) return
      field = value
      invalidate()
    }

  private var stage: CaptureStage? = null
  private var stageStartNs = 0L
  /** The shooting line's progress when the shot was exposed, where the captured stage closes it from. */
  private var releasedSweep = 0f
  /** The outline held from the shutter on; null while waiting, which reads the live outline. */
  private var heldPoints: FloatArray? = null
  private val shape = Path()
  private val line = Path()
  private val measure = PathMeasure()
  private var framePending = false
  private val animationFrame = Runnable {
    framePending = false
    invalidate()
  }
  private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 2.5f * resources.displayMetrics.density
    strokeJoin = Paint.Join.ROUND
    strokeCap = Paint.Cap.ROUND
  }
  private val linePaint = Paint(strokePaint).apply { strokeWidth = 4f * resources.displayMetrics.density }
  private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

  init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false }

  internal val shownStage: CaptureStage? get() = stage

  /** Starts [next]'s animation. Captured adds a haptic tick and failure a rejection one. */
  fun show(next: CaptureStage) {
    val now = System.nanoTime()
    if (stage == CaptureStage.SHOOTING) {
      releasedSweep = CaptureFeedbackTimeline.shootingSweep(elapsedMs(now))
    } else if (next == CaptureStage.CAPTURED) {
      releasedSweep = 0f
    }
    if (next != CaptureStage.WAITING && heldPoints == null && guide.hasOutline) heldPoints = guide.drawnPoints
    if (next == CaptureStage.WAITING) heldPoints = null
    stage = next
    stageStartNs = now
    when (next) {
      CaptureStage.CAPTURED -> performHapticFeedback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY,
      )
      CaptureStage.FAILED -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        performHapticFeedback(HapticFeedbackConstants.REJECT)
      }
      else -> Unit
    }
    invalidate()
  }

  fun clear() {
    removeCallbacks(animationFrame)
    framePending = false
    stage = null
    heldPoints = null
    invalidate()
  }

  override fun onDetachedFromWindow() {
    clear()
    super.onDetachedFromWindow()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val current = stage ?: return
    val elapsed = elapsedMs(System.nanoTime())
    val frame = when (current) {
      CaptureStage.WAITING -> CaptureFeedbackTimeline.waiting(elapsed)
      CaptureStage.SHOOTING -> CaptureFeedbackTimeline.shooting(elapsed)
      CaptureStage.CAPTURED -> CaptureFeedbackTimeline.captured(elapsed, releasedSweep)
      CaptureStage.FAILED -> CaptureFeedbackTimeline.failed(elapsed)
    }
    if (frame.finished) {
      clear()
      return
    }
    if (updateShape()) {
      val base = if (current == CaptureStage.FAILED) FAILURE_COLOR else color
      draw(canvas, shape, fillPaint, base, frame.fillAlpha)
      draw(canvas, shape, strokePaint, base, frame.outlineAlpha)
      if (frame.sweep > 0f) {
        measure.setPath(shape, true)
        line.reset()
        measure.getSegment(0f, measure.length * frame.sweep.coerceAtMost(1f), line, true)
        draw(canvas, line, linePaint, base, frame.lineAlpha)
      }
    }
    if (!framePending) {
      framePending = true
      postOnAnimation(animationFrame)
    }
  }

  /** The outline to draw around, clockwise from its top-left corner; false when there is nothing to draw around. */
  private fun updateShape(): Boolean {
    shape.reset()
    val points = heldPoints ?: if (guide.hasOutline) guide.drawnPoints else null
    if (points != null) {
      shape.moveTo(points[0], points[1])
      for (index in 1..3) shape.lineTo(points[index * 2], points[index * 2 + 1])
      shape.close()
      return true
    }
    if (guide.viewfinderBounds.isEmpty) return false
    shape.addRect(guide.viewfinderBounds, Path.Direction.CW)
    return true
  }

  private fun draw(canvas: Canvas, path: Path, paint: Paint, base: Int, alpha: Float) {
    if (alpha <= 0f) return
    paint.color = base
    paint.alpha = (paint.alpha * alpha.coerceIn(0f, 1f)).toInt()
    if (paint.alpha > 0) canvas.drawPath(path, paint)
  }

  private fun elapsedMs(nowNs: Long): Double = (nowNs - stageStartNs) / 1_000_000.0

  companion object {
    internal const val DEFAULT_COLOR = 0xFFFFFFFF.toInt()
    private const val FAILURE_COLOR = 0xFFFF453A.toInt()
  }
}
