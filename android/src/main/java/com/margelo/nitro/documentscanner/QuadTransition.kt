package com.margelo.nitro.documentscanner

/** Display coordinates only, owned by the overlay's main thread. */
internal class QuadTransition {
  val points = FloatArray(8)
  private val from = FloatArray(8)
  private val target = FloatArray(8)
  private var startedNs = 0L
  var hasPoints = false
    private set
  var isAnimating = false
    private set
  /** Easing length for later retargets; zero or less snaps. */
  var durationNs = DEFAULT_DURATION_NS

  fun retarget(next: FloatArray, nowNs: Long, snapDistance: Float) {
    // Start from the last drawn position, including when another result arrives mid-transition.
    val snapDistanceSquared = snapDistance * snapDistance
    val jump = (0..3).any { corner ->
      val offset = corner * 2
      val dx = next[offset] - points[offset]
      val dy = next[offset + 1] - points[offset + 1]
      dx * dx + dy * dy > snapDistanceSquared
    }
    next.copyInto(target)
    if (!hasPoints || jump || durationNs <= 0L) {
      next.copyInto(points)
      hasPoints = true
      isAnimating = false
      return
    }
    points.copyInto(from)
    startedNs = nowNs
    isAnimating = !points.contentEquals(target)
  }

  fun advance(nowNs: Long) {
    if (!isAnimating) return
    val progress = if (durationNs <= 0L) 1f else ((nowNs - startedNs).toDouble() / durationNs).coerceIn(0.0, 1.0).toFloat()
    if (progress >= 1f) {
      target.copyInto(points)
      isAnimating = false
    } else {
      for (index in points.indices) points[index] = from[index] + (target[index] - from[index]) * progress
    }
  }

  fun clear() {
    hasPoints = false
    isAnimating = false
  }

  companion object {
    const val DEFAULT_DURATION_NS = 50_000_000L
  }
}
