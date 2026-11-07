package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentCorners
import kotlin.math.hypot

/**
 * Adaptive inference pacing; analysis-executor confined. Once a trusted model result is confirmed, inference pauses
 * while flow reliably carries it. Losing or degrading the track, or a corner straying from the confirmed outline, asks
 * the model to confirm again. Swift implements the same policy; both pass `tests/fixtures/parity/adaptive.tsv`.
 */
internal class AdaptiveDetectionPolicy {
  private var reference: DocumentCorners? = null
  private var revalidate = false
  /** Why the latest decision was made: acquiring, paused, revalidating or a wake reason. */
  var status = "acquiring"
    private set
  /** The latest wake reason, kept across resets for diagnostics. */
  var lastReset = "none"
    private set
  /** The wake reason the latest [next] found against the reference, if any. */
  var doubt: String? = null
    private set
  val hasReference: Boolean get() = reference != null
  val paused: Boolean get() = status == "paused"

  fun reset() {
    revalidate = false
    reference = null
    status = "acquiring"
  }

  /** The model's latest result, or null when it found no tracked page. */
  fun reference(corners: DocumentCorners?) { reference = corners }

  /** The model confirmed the reference; a pending revalidation is satisfied. */
  fun confirmed() { revalidate = false }

  /**
   * The gap before the next inference, in nanoseconds; [Long.MAX_VALUE] pauses it. [tracking] means the guide still holds
   * a page, [reliable] that flow carried it to this frame as [current], [degraded] that some page region lost its
   * features. [width] and [height] are the upright frame in pixels.
   */
  fun next(tracking: Boolean, reliable: Boolean, degraded: Boolean, current: DocumentCorners?, width: Int, height: Int): Long {
    val trusted = tracking && reference != null
    val wake = reference?.takeIf { reliable && current != null && changed(it, current, width, height) }
      ?.let { "scene changed" }
    doubt = wake
    if (!trusted || !reliable || degraded || wake != null) {
      revalidate = trusted
      status = wake ?: if (!trusted) "unconfirmed" else if (!reliable) "tracking lost" else "tracking degraded"
      lastReset = status
      return if (trusted) REVALIDATE_GAP_NS else 0
    }
    if (revalidate) {
      status = "revalidating"
      return REVALIDATE_GAP_NS
    }
    status = "paused"
    return Long.MAX_VALUE
  }

  companion object {
    const val REVALIDATE_GAP_NS = 60_000_000L
    /** Any corner moved more than 5% of the upright frame width; translation counts. */
    fun changed(a: DocumentCorners, b: DocumentCorners, width: Int, height: Int): Boolean {
      fun moved(p: DocumentPoint, q: DocumentPoint) = hypot((p.x - q.x) * width, (p.y - q.y) * height) > width * .05
      return moved(a.topLeft, b.topLeft) || moved(a.topRight, b.topRight) ||
        moved(a.bottomRight, b.bottomRight) || moved(a.bottomLeft, b.bottomLeft)
    }
  }
}

/** Times model attempts independently of the policy that carries a confirmed outline. */
internal class AdaptiveInferenceCadence {
  private var searching = false
  private var checked = false
  private var movingSince: Long? = null
  private var steadyFrames = 0
  private var waitingSince: Long? = null
  private var lastAttempt: Long? = null

  fun gap(now: Long, searching: Boolean, moving: Boolean, measured: Boolean, trackingGap: Long,
          blurFraction: Double? = null): Long? {
    if (searching && !this.searching) checked = false
    this.searching = searching
    // A search or revalidation must make progress even if motion never settles.
    waitingSince = if (searching || trackingGap != Long.MAX_VALUE) waitingSince ?: now else null
    val earlyOrDue = waitingSince?.let { since ->
      val previous = lastAttempt
      val lowBlur = measured && blurFraction != null && blurFraction <= LOW_BLUR_FRACTION
      now - maxOf(since, previous ?: since) >= RETRY_NS ||
        lowBlur && (previous == null || previous < since)
    }
    val fallbackGap = if (earlyOrDue == true) 0L else null
    if (moving) {
      steadyFrames = 0
      val since = movingSince ?: now
      movingSince = since
      if (now - since >= 100_000_000L) checked = false
      return fallbackGap
    }
    movingSince = null
    if (!measured) {
      steadyFrames = 0
      return fallbackGap
    }
    if (steadyFrames < 4) steadyFrames++
    if (steadyFrames < 4) return fallbackGap
    if (searching) return if (checked) fallbackGap else 0L
    return trackingGap.takeUnless { it == Long.MAX_VALUE }
  }

  fun didAttempt(now: Long) {
    lastAttempt = now
    if (searching) checked = true
  }

  private companion object {
    const val RETRY_NS = 1_000_000_000L
    const val LOW_BLUR_FRACTION = 0.002
  }
}
