package com.margelo.nitro.documentscanner

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Display coordinates only, owned by the overlay's main thread. Eases the drawn guide toward each new detection with a
 * distance-adaptive exponential, as the One Euro filter adapts to speed: a small gap, mostly detection jitter, closes
 * with the [durationNs] time constant, so the guide holds still; a large one closes with [FAST_NS], so it keeps up with
 * the page. A corner farther than the snap distance is likely another page and snaps.
 */
internal class QuadTransition {
  val points = FloatArray(8)
  private val target = FloatArray(8)
  private var lastNs = 0L
  private var snapDistance = 0f
  var hasPoints = false
    private set
  var isAnimating = false
    private set
  /** Time constant for small gaps; zero or less snaps. */
  var durationNs = DEFAULT_DURATION_NS

  fun retarget(next: FloatArray, nowNs: Long, snapDistance: Float) {
    // Start from the last drawn position, including when another result arrives mid-transition.
    next.copyInto(target)
    if (!hasPoints || gap() > snapDistance || durationNs <= 0L) {
      next.copyInto(points)
      hasPoints = true
      isAnimating = false
      return
    }
    this.snapDistance = snapDistance
    if (!isAnimating) lastNs = nowNs
    isAnimating = !points.contentEquals(target)
  }

  fun advance(nowNs: Long) {
    if (!isAnimating) return
    val elapsedNs = nowNs - lastNs
    if (elapsedNs <= 0L) return
    lastNs = nowNs
    val gap = gap()
    // Small gaps (to a twentieth of the snap distance) keep the full time constant; it falls to FAST_NS by half of it.
    val near = snapDistance * NEAR
    val far = snapDistance * FAR
    val reach = if (far <= near) 1f else ((gap - near) / (far - near)).coerceIn(0f, 1f)
    val fastNs = min(durationNs, FAST_NS)
    val timeConstantNs = durationNs + (fastNs - durationNs) * reach
    val step = (1.0 - exp(-elapsedNs.toDouble() / timeConstantNs)).toFloat()
    for (index in points.indices) points[index] += (target[index] - points[index]) * step
    if (gap() <= SETTLED_PX) {
      target.copyInto(points)
      isAnimating = false
    }
  }

  fun clear() {
    hasPoints = false
    isAnimating = false
  }

  /** The farthest any corner is from its target. */
  private fun gap(): Float {
    var farthest = 0f
    for (corner in 0..3) {
      val offset = corner * 2
      farthest = max(farthest, hypot(target[offset] - points[offset], target[offset + 1] - points[offset + 1]))
    }
    return farthest
  }

  companion object {
    const val DEFAULT_DURATION_NS = 80_000_000L
    const val FAST_NS = 20_000_000L
    private const val NEAR = 0.05f
    private const val FAR = 0.5f
    /** Under half a display pixel the guide lands on its target and stops animating. */
    private const val SETTLED_PX = 0.5f
  }
}
