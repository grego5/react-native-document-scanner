package com.margelo.nitro.documentscanner

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Main thread: where focus is held on the preview, shown with `showFocusIndicator` as a small translucent dot. It marks
 * continuous autofocus's aim on a page, or a tap's, capture's or lock's scan region; continuous autofocus aiming at the
 * centre without a page shows nothing. Green focused or locked, yellow scanning, red unfocused, white otherwise. It
 * glides between places and fades in and out over [ANIMATION_MS], and ripples at each step of the scanner's own focus
 * search.
 */
internal class FocusBoxOverlay(context: Context) : View(context) {
  private val density = resources.displayMetrics.density
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
  private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 1.5f * density
  }
  private var state = AutofocusState.INACTIVE
  // Drawn position and opacity, and where the animation goes from and to.
  private var dotX = .5f
  private var dotY = .5f
  private var opacity = 0f
  private var fromX = dotX
  private var fromY = dotY
  private var fromAlpha = opacity
  private var toX = dotX
  private var toY = dotY
  private var toAlpha = opacity
  private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
    duration = ANIMATION_MS
    interpolator = DecelerateInterpolator()
    addUpdateListener {
      val t = it.animatedValue as Float
      dotX = fromX + (toX - fromX) * t
      dotY = fromY + (toY - fromY) * t
      opacity = fromAlpha + (toAlpha - fromAlpha) * t
      invalidate()
    }
  }

  /** The focus region as preview fractions (x, y, width, height), or null to hide it; [centred] hides it too. */
  fun show(box: FloatArray?, centred: Boolean) {
    val visible = box != null && !centred
    // A dot fading in appears where it is going, rather than gliding from its last place.
    if (visible && opacity == 0f) {
      dotX = box[0]
      dotY = box[1]
    }
    animateTo(if (visible) box[0] else dotX, if (visible) box[1] else dotY, if (visible) 1f else 0f)
  }

  fun state(state: AutofocusState) {
    if (state == this.state) return
    this.state = state
    invalidate()
  }

  /** A ripple from the dot, as when the scanner moves the lens one step of its own focus search. */
  fun pulse() {
    pulse.cancel()
    pulse.start()
  }

  private fun animateTo(nextX: Float, nextY: Float, nextAlpha: Float) {
    if (nextX == toX && nextY == toY && nextAlpha == toAlpha) return
    animator.cancel()
    fromX = dotX
    fromY = dotY
    fromAlpha = opacity
    toX = nextX
    toY = nextY
    toAlpha = nextAlpha
    animator.start()
  }

  /** The running ripple's progress, 0 to 1; 1 draws nothing. */
  private var ripple = 1f
  private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
    duration = PULSE_MS
    interpolator = DecelerateInterpolator()
    addUpdateListener {
      ripple = it.animatedValue as Float
      invalidate()
    }
  }

  override fun onDetachedFromWindow() {
    animator.cancel()
    pulse.cancel()
    super.onDetachedFromWindow()
  }

  override fun onDraw(canvas: Canvas) {
    if (opacity <= 0f) return
    val color = when (state) {
      AutofocusState.FOCUSED, AutofocusState.LOCKED -> 0xFF4CD964.toInt()
      AutofocusState.SCAN -> 0xFFFFCC00.toInt()
      AutofocusState.UNFOCUSED -> 0xFFFF3B30.toInt()
      AutofocusState.INACTIVE -> Color.WHITE
    }
    fill.color = color
    fill.alpha = (FILL_ALPHA * opacity).toInt()
    ring.color = color
    ring.alpha = (255 * opacity).toInt()
    val radius = RADIUS_DP * density
    canvas.drawCircle(dotX * width, dotY * height, radius, fill)
    canvas.drawCircle(dotX * width, dotY * height, radius, ring)
    if (ripple < 1f) {
      ring.alpha = (255 * opacity * (1 - ripple)).toInt()
      canvas.drawCircle(dotX * width, dotY * height, radius * (1 + ripple * (PULSE_SCALE - 1)), ring)
    }
  }

  companion object {
    const val ANIMATION_MS = 160L
    const val RADIUS_DP = 12f
    const val FILL_ALPHA = 90
    /** A ripple's duration and how far it spreads, as a multiple of the dot's radius. */
    const val PULSE_MS = 400L
    const val PULSE_SCALE = 2f
  }
}
