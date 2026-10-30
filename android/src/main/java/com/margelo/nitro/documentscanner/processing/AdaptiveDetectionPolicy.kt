package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Analysis-executor policy. Once a trusted outline is locked, flow tracking alone carries it; inference returns
 * when tracking is lost or stays degraded, or when the tracked outline is in doubt. Movement, focus scans and blur do
 * not wake it while flow holds.
 */
internal class AdaptiveDetectionPolicy {
  private var revalidate = false
  var status: String = "acquiring"
    private set
  var lastReset: String = "none"
    private set
  val paused: Boolean get() = status == "paused"
  fun reset() { revalidate = false; status = "acquiring" }
  fun confirmed() { revalidate = false }

  fun interval(now: Long, trusted: Boolean, tracked: Boolean, degraded: Boolean, doubt: String? = null): Long {
    if (!trusted || !tracked || degraded || doubt != null) {
      revalidate = trusted
      status = doubt ?: when {
        !trusted -> "unconfirmed"
        !tracked -> "tracking lost"
        else -> "tracking degraded"
      }
      lastReset = status
      return if (trusted) LiveDetectionState.TRACKING_GAP_NS else 0L
    }
    if (revalidate) { status = "revalidating"; return LiveDetectionState.TRACKING_GAP_NS }
    status = "paused"
    return Long.MAX_VALUE
  }

  companion object {
    /** Accumulated image change since the last model confirmation: translation, scale, rotation or perspective. */
    fun changed(reference: DocumentQuad, current: DocumentQuad, width: Int, height: Int): Boolean {
      val a = arrayOf(reference.topLeft, reference.topRight, reference.bottomRight, reference.bottomLeft)
      val b = arrayOf(current.topLeft, current.topRight, current.bottomRight, current.bottomLeft)
      return a.indices.any { hypot((a[it].x-b[it].x)*width,(a[it].y-b[it].y)*height) > width*.05 }
    }
  }
}
