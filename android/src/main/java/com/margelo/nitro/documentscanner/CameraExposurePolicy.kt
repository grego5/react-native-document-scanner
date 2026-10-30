package com.margelo.nitro.documentscanner

import kotlin.math.abs
import kotlin.math.roundToInt

/** Maps the exposure props onto what the bound camera advertises. */
internal object CameraExposurePolicy {
  /** The compensation index nearest [ev] stops of [step] EV each, within [lower, upper]; 0 without compensation. */
  fun compensationIndex(ev: Double, step: Double, lower: Int, upper: Int): Int {
    if (step <= 0.0 || lower >= upper || !ev.isFinite()) return 0
    return (ev / step).roundToInt().coerceIn(lower, upper)
  }

  /**
   * Of the advertised AE target ranges, the one with the lowest minimum at or above [minFps], or the highest minimum
   * when none reaches it, then the maximum nearest [PREFERRED_MAX_FPS]; null when [minFps] is not positive.
   */
  fun <T> targetFpsRange(ranges: List<T>, minFps: Int, lower: (T) -> Int, upper: (T) -> Int): T? {
    if (minFps <= 0) return null
    val reaching = ranges.filter { lower(it) >= minFps }
    val nearestMax = compareBy<T> { abs(upper(it) - PREFERRED_MAX_FPS) }
    return if (reaching.isNotEmpty()) {
      reaching.minWithOrNull(compareBy<T> { lower(it) }.then(nearestMax))
    } else {
      ranges.minWithOrNull(compareByDescending<T> { lower(it) }.then(nearestMax))
    }
  }

  private const val PREFERRED_MAX_FPS = 30
}
